package app.getknit.knit.data

import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The decoded link-preview cards this process holds, keyed by the blob hash the message row names — the
 * bridge between "the bytes are here" (the `blobs` table) and "the bubble can draw it" (a value-equal
 * [LinkCard] on the row). A card is decoded once, on request, from the stored blob: decrypted when the row
 * carries a key (a DM or group card is sealed like any attachment), then opened through
 * [LinkPreviewBlob.decodeOrNull], which is also where a peer-supplied container is normalized and refused.
 *
 * Bounded and in memory only: the newest [MAX_CARDS] stay decoded, a container that would not decode is
 * remembered so it is never tried twice, and nothing is written anywhere — the blob table already holds the
 * bytes, encrypted, for as long as a message references them. The chat ViewModel folds [cards] into its rows;
 * the Coil fetcher for the card's picture reads [imageBytes] through the same decode.
 */
class LinkCardStore(
    private val blobs: BlobRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _cards = MutableStateFlow<Map<String, LinkCard>>(emptyMap())

    /** Every card decoded so far, by blob hash. */
    val cards: StateFlow<Map<String, LinkCard>> = _cards.asStateFlow()

    private val lock = Mutex()
    private val recent = ArrayDeque<String>()
    private val undecodable = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()

    /**
     * Decodes the card under [hash] (with the row's base64 [key] when sealed) into [cards] unless it is already
     * there, already known not to decode, or being decoded right now. Never throws; a blob that is not stored
     * yet is simply left for a later call.
     */
    suspend fun ensure(
        hash: String,
        key: String?,
    ) {
        lock.withLock {
            if (hash in _cards.value || hash in undecodable || !inFlight.add(hash)) return
        }
        val opened = runCatching { withContext(io) { open(hash, key) } }.getOrDefault(Opened.Absent)
        lock.withLock {
            inFlight.remove(hash)
            when (opened) {
                Opened.Absent -> {
                    Unit
                }

                Opened.Refused -> {
                    remember(undecodable, hash, MAX_UNDECODABLE)
                }

                is Opened.Card -> {
                    recent.remove(hash)
                    recent.addLast(hash)
                    val next = _cards.value + (hash to opened.blob.toCard())
                    _cards.value = if (recent.size > MAX_CARDS) next - recent.removeFirst() else next
                }
            }
        }
    }

    /** The card's picture bytes and their type, for the image loader, or null when the card has none. */
    suspend fun imageBytes(
        hash: String,
        key: String?,
    ): Pair<ByteArray, String>? =
        withContext(io) {
            val blob = (open(hash, key) as? Opened.Card)?.blob ?: return@withContext null
            val image = blob.image ?: return@withContext null
            image to (blob.imageMime ?: DEFAULT_IMAGE_MIME)
        }

    private sealed interface Opened {
        class Card(
            val blob: LinkPreviewBlob,
        ) : Opened

        /** No bytes stored under the hash yet. */
        data object Absent : Opened

        /** Bytes are there but do not open as a card — a bad key, a foreign layout, a refused container. */
        data object Refused : Opened
    }

    private suspend fun open(
        hash: String,
        key: String?,
    ): Opened {
        val stored = blobs.bytes(hash) ?: return Opened.Absent
        val plain =
            if (key != null) {
                AttachmentCrypto.open(stored, b64d(key)) ?: return Opened.Refused
            } else {
                stored
            }
        return LinkPreviewBlob.decodeOrNull(plain)?.let { Opened.Card(it) } ?: Opened.Refused
    }

    private fun remember(
        set: LinkedHashSet<String>,
        hash: String,
        max: Int,
    ) {
        set += hash
        while (set.size > max) set.remove(set.first())
    }

    companion object {
        /** How many decoded cards stay in memory; a chat shows far fewer at once, and re-decoding is cheap. */
        const val MAX_CARDS = 64
        const val MAX_UNDECODABLE = 256
        private const val DEFAULT_IMAGE_MIME = "image/jpeg"
    }
}
