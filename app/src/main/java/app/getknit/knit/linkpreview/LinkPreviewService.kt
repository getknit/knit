package app.getknit.knit.linkpreview

import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a link in the sender's draft into the card that will ride with the message, or into a reason there is
 * none. The whole Internet-facing half of link previews sits here and below; nothing on the receiving side
 * ever fetches.
 *
 * The order of the gates is the point. The connectivity questions come first, before any bytes move: a phone
 * that is only on the mesh, behind a captive portal or under Data Saver never opens a socket. Then the page
 * (bounded by the fetcher), the picture (optional — any failure there yields a text-only card), and finally
 * moderation on the sender's own device: a picture the classifier flags is dropped from the card, and a card
 * whose text the message gate would refuse is dropped whole. Both run on the recipient again (one verdict per
 * card), so a modified client cannot smuggle either past a stock one.
 *
 * A short memo keeps a retyped or re-pasted link from being fetched twice; it holds the *pre-moderation* blob,
 * because the room and a DM classify differently and the same link can be pasted in both. Nothing persists.
 * Pure Kotlin with every collaborator injected, so it is JVM-tested with fakes; the Android pieces
 * (`PreviewImage.shrink`, the screening service) arrive as functions.
 */
class LinkPreviewService(
    private val gate: InternetGate,
    private val fetcher: PreviewFetcher,
    // ImageScreeningService::isImageExplicit — the send-side screen every staged picture already passes.
    private val screenImage: suspend (ByteArray) -> Boolean,
    // The message body's own send gate, scoped like it: (text, isRoom) → flagged.
    private val textFlagged: suspend (String, Boolean) -> Boolean,
    // PreviewImage::shrink — a bounded decode and re-encode to the card's small picture, or null to go without.
    private val shrink: (ByteArray) -> Shrunk?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    // Where the decode/re-encode runs; a test passes its own scheduler's dispatcher so virtual time stays coherent.
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /** A re-encoded card picture: [bytes] in one of [LinkPreviewBlob.IMAGE_MIMES], at most [LinkPreviewBlob.IMAGE_MAX_BYTES]. */
    class Shrunk(
        val bytes: ByteArray,
        val mime: String,
    )

    /** Why a link did or did not become a card. */
    sealed interface CardResult {
        /** The card to attach, already moderated and normalized. */
        class Card(
            val blob: LinkPreviewBlob,
        ) : CardResult

        /** No validated Internet route: not an answer about the link, so a caller may retry when one appears. */
        data object Offline : CardResult

        /** Data Saver is on for this metered network; the same non-answer as [Offline]. */
        data object Restricted : CardResult

        /** The link yields no card — refused by policy, unreachable, not a page, no title, or flagged text. */
        data object NoCard : CardResult
    }

    /** The gate's stream, so the composer can re-arm a link that was skipped offline. */
    val online: StateFlow<Boolean> get() = gate.online

    private val memoLock = Mutex()
    private val memo = LinkedHashMap<String, Pair<Long, LinkPreviewBlob>>()

    /** The card for [url] as it appears in a draft, classified for the Nearby room when [isRoom]. Never throws. */
    suspend fun fetchCard(
        url: String,
        isRoom: Boolean,
    ): CardResult {
        val normalized = LinkPreviewPolicy.normalize(url) ?: return CardResult.NoCard
        if (!gate.isOnline()) return CardResult.Offline
        if (gate.isDataRestricted()) return CardResult.Restricted
        val blob =
            when (val remembered = memoized(normalized)) {
                null -> {
                    when (val fetched = fetchBlob(normalized)) {
                        is Fetched.Blob -> {
                            fetched.blob.also { remember(normalized, it) }
                        }

                        Fetched.Offline -> {
                            return CardResult.Offline
                        }

                        Fetched.None -> {
                            return CardResult.NoCard
                        }
                    }
                }

                else -> {
                    remembered
                }
            }
        if (textFlagged(blob.moderationText(), isRoom)) {
            log("card text flagged for ${LinkPreviewBlob.hostOf(normalized)}, no card")
            return CardResult.NoCard
        }
        return CardResult.Card(blob)
    }

    private sealed interface Fetched {
        class Blob(
            val blob: LinkPreviewBlob,
        ) : Fetched

        data object Offline : Fetched

        data object None : Fetched
    }

    private suspend fun fetchBlob(url: String): Fetched =
        withTimeoutOrNull(FETCH_BUDGET_MS) {
            val host = LinkPreviewBlob.hostOf(url)
            val page = fetcher.fetchPage(url)
            if (page is PageFetch.Offline) return@withTimeoutOrNull Fetched.Offline
            if (page !is PageFetch.Html) {
                log("no page for $host: ${page::class.simpleName}")
                return@withTimeoutOrNull Fetched.None
            }
            val meta = OpenGraphParser.parse(OpenGraphParser.decode(page.bytes, page.contentType), page.finalUrl)
            if (meta == null) {
                log("no title for $host")
                return@withTimeoutOrNull Fetched.None
            }
            val picture = meta.imageUrl?.let { fetchPicture(it) }
            val blob =
                LinkPreviewBlob(
                    v = LinkPreviewBlob.VERSION,
                    url = url,
                    title = meta.title,
                    description = meta.description,
                    image = picture?.bytes,
                    imageMime = picture?.mime,
                ).normalized()
            if (blob == null) Fetched.None else Fetched.Blob(blob)
        } ?: Fetched.None.also { log("gave up on ${LinkPreviewBlob.hostOf(url)} after ${FETCH_BUDGET_MS}ms") }

    /** The card's picture, or null on any failure at all — a card without a picture is still a card. */
    private suspend fun fetchPicture(imageUrl: String): Shrunk? {
        val normalized = LinkPreviewPolicy.normalize(imageUrl) ?: return null
        val fetched = fetcher.fetchImage(normalized) as? ImageFetch.Image ?: return null
        val shrunk = withContext(cpu) { shrink(fetched.bytes) } ?: return null
        if (screenImage(shrunk.bytes)) {
            log("card picture flagged for ${LinkPreviewBlob.hostOf(imageUrl)}, dropped")
            return null
        }
        return shrunk
    }

    private suspend fun memoized(url: String): LinkPreviewBlob? =
        memoLock.withLock {
            val now = clock()
            memo.entries.removeAll { now - it.value.first > MEMO_TTL_MS }
            memo[url]?.second
        }

    private suspend fun remember(
        url: String,
        blob: LinkPreviewBlob,
    ) = memoLock.withLock {
        memo[url] = clock() to blob
        while (memo.size > MEMO_MAX) memo.remove(memo.keys.first())
    }

    companion object {
        /** Wall-clock budget for one link, page and picture together: a slow site must not hold the composer. */
        const val FETCH_BUDGET_MS = 30_000L

        /** How long a fetched card is reused for the same link, and how many are kept. */
        const val MEMO_TTL_MS = 10 * 60_000L
        const val MEMO_MAX = 32
    }
}
