package app.getknit.knit.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ThreadMediaRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.linkpreview.LinkPreviewPolicy
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.ui.chat.AttachmentExport
import app.getknit.knit.ui.chat.AttachmentKind
import app.getknit.knit.ui.chat.ChatWindow
import app.getknit.knit.ui.chat.PendingSave
import app.getknit.knit.ui.chat.SavedFile
import app.getknit.knit.ui.chat.attachmentKindOf
import app.getknit.knit.ui.chat.findUrls
import app.getknit.knit.ui.chat.linkCardFor
import app.getknit.knit.ui.speakerLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo or video in the Media grid. [covered] hides it behind a tap; [held] says its bytes are on this phone. */
data class MediaTile(
    val messageId: String,
    val hash: String,
    val mime: String?,
    val key: String?,
    val video: Boolean,
    /** A video's poster, base64, as the row keeps it; null for a photo. */
    val poster: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val held: Boolean,
    val covered: Boolean,
    val mine: Boolean,
    val senderName: String,
    val sentAt: Long,
)

/** A file or voice message in the Files list. [flagged] is the file's own screening verdict, as the bubble shows it. */
data class FileItem(
    val messageId: String,
    val hash: String,
    val key: String?,
    val name: String?,
    val mime: String?,
    /** The size the sender declared, when it did. */
    val size: Long?,
    val voice: Boolean,
    val voiceDurationMs: Long? = null,
    val held: Boolean,
    val flagged: Boolean,
    val mine: Boolean,
    val senderName: String,
    val sentAt: Long,
) {
    /** What a tap opens: the file through the opener, or null when only the chat can (a voice message, or bytes not here yet). */
    val pending: PendingSave? get() = if (held && !voice) PendingSave(hash, key, name, mime) else null
}

/** A link in the Links list: one per URL in a message, with its preview [card] when one arrived for that URL. */
data class LinkItem(
    /** Unique in the list: a message can carry several links. */
    val key: String,
    val messageId: String,
    val url: String,
    val card: LinkCard? = null,
    /** The card's blob, for its picture; null with no card. */
    val cardHash: String? = null,
    val cardKey: String? = null,
    val mine: Boolean,
    val senderName: String,
    val sentAt: Long,
)

/** The three tabs of "Media, links and files", in order. */
enum class MediaTab(
    @param:StringRes val label: Int,
) {
    Media(R.string.media_tab_media),
    Files(R.string.media_tab_files),
    Links(R.string.media_tab_links),
}

/**
 * "Media, links and files" for one thread (ADR 2026-11.dgvf): the open [tab] and its list, newest first. The other
 * two lists stay empty: only the open tab is read.
 */
data class ChatMediaUiState(
    val tab: MediaTab = MediaTab.Media,
    /** False until the open tab's read answers, so an empty tab is not claimed before it is known. */
    val loaded: Boolean = false,
    val media: List<MediaTile> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    /** The open tab's read filled its window: an older page may exist ([ChatMediaViewModel.loadMore]). */
    val more: Boolean = false,
)

/**
 * The thread's own rows, one tab's read at a time (ADR 2026-11.dgvf): its photos and videos, its files and voice
 * messages, or the messages whose body holds a link. Each tab's read has its own SQL, so its window counts only what
 * it shows, and its own window, growing by [PAGE] up to [ChatWindow.MAX] (the bound ADR 2026-09.fjcw's `IN` list rests
 * on) and kept while another tab is open. Only the open tab's read runs: a message write while the screen is open
 * re-runs one walk of the thread, never three.
 *
 * Everything hides as the chat hides it: a blocked sender's rows are not read at all; a picture or video the
 * on-device screen flagged is covered while content filtering is on (a video by its poster's verdict too), and a
 * file shows its flag; text the moderator flagged lists no links, and a flagged card is dropped for its bare link.
 * The screen's only subscription to the blobs table is [heldSizes], keyed to the open tab's hashes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaViewModel(
    private val conversationId: String,
    media: ThreadMediaRepository,
    blobs: BlobRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    private val identity: IdentitySource,
    /** Every hash the image screen flagged (`ImageScreeningService.observeFlaggedHashes`). */
    flaggedHashes: Flow<List<String>>,
    private val linkCards: LinkCardStore,
    private val export: AttachmentExport,
    @field:SuppressLint("StaticFieldLeak") private val context: Context,
) : ViewModel() {
    private val tab = MutableStateFlow(MediaTab.Media)
    private val limits: Map<MediaTab, MutableStateFlow<Int>> = MediaTab.entries.associateWith { MutableStateFlow(PAGE) }
    private val me = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Toasts to show: a save's result. */
    val events: SharedFlow<Int> = _events.asSharedFlow()

    private val _savedFiles = MutableSharedFlow<SavedFile>(extraBufferCapacity = 1)

    /** A file's copy to open, for the screen's file opener. */
    val savedFiles: SharedFlow<SavedFile> = _savedFiles.asSharedFlow()

    private val _saveNeeded = MutableSharedFlow<PendingSave>(extraBufferCapacity = 1)

    /** A file with no saved copy: the screen's file opener asks where to save it first. */
    val saveNeeded: SharedFlow<PendingSave> = _saveNeeded.asSharedFlow()

    private val blocked = settings.blockedNodeIds.distinctUntilChanged()
    private val hideSensitive = settings.contentFilteringEnabled.distinctUntilChanged()

    /** One read's answer: whose tab, the window it was read with, and the rows. */
    private data class TabRows(
        val tab: MediaTab,
        val limit: Int,
        val rows: List<MessageEntity>,
    )

    /** Held sizes, and the tab whose hashes they answer, so a tab switch never reads the last tab's. */
    private data class Held(
        val tab: MediaTab,
        val sizes: Map<String, Int>,
    )

    /** The open tab's rows; switching tabs cancels the last tab's read, so only one runs. */
    private val tabRows: StateFlow<TabRows?> =
        tab
            .flatMapLatest { open ->
                val window = limits.getValue(open)
                when (open) {
                    MediaTab.Media -> {
                        combine(blocked, window, ::Pair).flatMapLatest { (b, limit) ->
                            media.observeMedia(conversationId, b, limit).map { TabRows(open, limit, it) }
                        }
                    }

                    MediaTab.Files -> {
                        combine(blocked, window, ::Pair).flatMapLatest { (b, limit) ->
                            media.observeFiles(conversationId, b, limit).map { TabRows(open, limit, it) }
                        }
                    }

                    MediaTab.Links -> {
                        combine(blocked, hideSensitive, window, ::Triple).flatMapLatest { (b, hide, limit) ->
                            media.observeLinkRows(conversationId, b, hide, limit).map { TabRows(open, limit, it) }
                        }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Hash → held byte length for the open tab's attachments (the Links tab: its cards): the screen's only read of
     * the blobs table (ADR 2026-09.fjcw), re-subscribed only when that set of hashes changes.
     */
    private val heldSizes: StateFlow<Held?> =
        tabRows
            .filterNotNull()
            .map { read -> read.tab to hashesOf(read) }
            .distinctUntilChanged()
            .flatMapLatest { (open, asked) -> blobs.observeSizes(asked).map { Held(open, it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val screening: Flow<Pair<Set<String>, Boolean>> =
        combine(flaggedHashes.map { it.toSet() }, hideSensitive, ::Pair)

    val state: StateFlow<ChatMediaUiState> =
        combine(
            tab,
            tabRows.filterNotNull(),
            heldSizes.filterNotNull(),
            screening,
            combine(peers.observeDirectory(), me, linkCards.cards, ::Triple),
        ) { open, read, held, (flagged, hide), (directory, myId, cards) ->
            // Until the open tab's own rows and sizes are in, it is loading: never the last tab's list, never a guess.
            if (read.tab != open || held.tab != open) return@combine ChatMediaUiState(tab = open)
            val names = Names(directory, myId, hide)
            val sizes = held.sizes
            val more = read.rows.size >= read.limit && read.limit < ChatWindow.MAX
            when (open) {
                MediaTab.Media -> {
                    val media =
                        read.rows.mapNotNull { m ->
                            val hash = m.attachmentHash ?: return@mapNotNull null
                            val flaggedHere = hide && hash in flagged
                            when (attachmentKindOf(m.attachmentMime, m.attachmentName, video = m.videoDurationMs != null)) {
                                AttachmentKind.Photo -> tile(m, hash, sizes, flaggedHere, names)
                                AttachmentKind.Video -> tile(m, hash, sizes, flaggedHere || (hide && m.videoPosterFlagged), names)
                                else -> null
                            }
                        }
                    ChatMediaUiState(tab = open, loaded = true, media = media, more = more)
                }

                MediaTab.Files -> {
                    val files =
                        read.rows.mapNotNull { m ->
                            val hash = m.attachmentHash ?: return@mapNotNull null
                            val flaggedHere = hide && hash in flagged
                            when (attachmentKindOf(m.attachmentMime, m.attachmentName, video = m.videoDurationMs != null)) {
                                AttachmentKind.File -> file(m, hash, sizes, flaggedHere, names, voice = false)
                                AttachmentKind.Voice -> file(m, hash, sizes, flaggedHere, names, voice = true)
                                else -> null
                            }
                        }
                    ChatMediaUiState(tab = open, loaded = true, files = files, more = more)
                }

                MediaTab.Links -> {
                    val links = read.rows.flatMap { linkItems(it, cards, flagged.takeIf { hide }.orEmpty(), names) }
                    ChatMediaUiState(tab = open, loaded = true, links = links, more = more)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatMediaUiState())

    init {
        viewModelScope.launch { me.value = identity.nodeId() }
        // Decode every card in the Links tab's window whose blob has landed, once; the store dedups, and the rows pick
        // the result up through [LinkCardStore.cards]. Distinct, so a size change elsewhere does not re-walk the window.
        viewModelScope.launch {
            combine(tabRows.filterNotNull(), heldSizes.filterNotNull()) { read, held ->
                if (read.tab != MediaTab.Links || held.tab != MediaTab.Links) return@combine emptyList()
                read.rows.mapNotNull { m ->
                    val hash = m.attachmentHash
                    if (m.attachmentMime == LinkPreviewBlob.MIME && hash != null && hash in held.sizes) hash to m.attachmentKey else null
                }
            }.distinctUntilChanged().collect { found -> found.forEach { (hash, key) -> linkCards.ensure(hash, key) } }
        }
    }

    /** The user opened [open]: its read starts, and the last tab's stops. */
    fun selectTab(open: MediaTab) {
        tab.value = open
    }

    /** The open tab's list reached the end of what is read: read [PAGE] more, up to [ChatWindow.MAX]. */
    fun loadMore() = limits.getValue(tab.value).update { (it + PAGE).coerceAtMost(ChatWindow.MAX) }

    /** What the open tab asks the blobs table about: its attachments, or on the Links tab its cards. */
    private fun hashesOf(read: TabRows): Set<String> =
        read.rows.mapNotNullTo(HashSet()) { m ->
            m.attachmentHash?.takeIf { read.tab != MediaTab.Links || m.attachmentMime == LinkPreviewBlob.MIME }
        }

    /** A held video's plaintext, for the player: [AttachmentExport.plaintext]. */
    suspend fun videoBytes(
        hash: String,
        key: String?,
    ): ByteArray? = export.plaintext(hash, key)

    /** The viewer's Save on a video: `Movies/Knit`. */
    fun saveVideo(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        viewModelScope.launch { _events.tryEmit(export.saveVideo(hash, key, mime)) }
    }

    /** The viewer's Save on a photo: `Pictures/Knit`. */
    fun savePicture(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        viewModelScope.launch { _events.tryEmit(export.savePicture(hash, key, mime)) }
    }

    /** A tap on a held file that is not risky: its saved copy, or the picker first (ADR 2026-09.7ad3). */
    fun openFile(pending: PendingSave) {
        viewModelScope.launch {
            val copy = export.savedCopy(pending)
            if (copy != null) _savedFiles.tryEmit(copy) else _saveNeeded.tryEmit(pending)
        }
    }

    /** The picked document for [pending]: written, then opened unless the file is risky. */
    fun writeFile(
        pending: PendingSave,
        dest: Uri,
    ) {
        viewModelScope.launch {
            val written = export.writeTo(pending, dest)
            _events.tryEmit(written.message)
            written.opened?.let { _savedFiles.tryEmit(it) }
        }
    }

    /** How a list names who sent a row: "You" for our own, a heard post's speaker, anyone else by their label. */
    private inner class Names(
        val directory: PeerDirectory,
        val me: String?,
        val hideFlagged: Boolean,
    ) {
        fun of(m: MessageEntity): String = speakerLabel(context, m, directory, me, isDm = false, hideFlagged = hideFlagged).orEmpty()

        fun mine(m: MessageEntity): Boolean = m.senderId == me && m.originNode == null
    }

    private fun tile(
        m: MessageEntity,
        hash: String,
        sizes: Map<String, Int>,
        covered: Boolean,
        names: Names,
    ): MediaTile {
        val video = m.videoDurationMs != null
        return MediaTile(
            messageId = m.id,
            hash = hash,
            mime = m.attachmentMime,
            key = m.attachmentKey,
            video = video,
            poster = m.videoPoster.takeIf { video },
            durationMs = m.videoDurationMs,
            width = m.videoWidth,
            height = m.videoHeight,
            held = hash in sizes,
            covered = covered,
            mine = names.mine(m),
            senderName = names.of(m),
            sentAt = m.sentAt,
        )
    }

    private fun file(
        m: MessageEntity,
        hash: String,
        sizes: Map<String, Int>,
        flagged: Boolean,
        names: Names,
        voice: Boolean,
    ): FileItem =
        FileItem(
            messageId = m.id,
            hash = hash,
            key = m.attachmentKey,
            name = m.attachmentName,
            mime = m.attachmentMime,
            size = m.attachmentSize,
            voice = voice,
            voiceDurationMs = m.voiceDurationMs?.toLong(),
            held = hash in sizes,
            flagged = flagged,
            mine = names.mine(m),
            senderName = names.of(m),
            sentAt = m.sentAt,
        )

    /** One item per link [m]'s body holds, the matching card on the link it was fetched for unless it is [flagged]. */
    private fun linkItems(
        m: MessageEntity,
        cards: Map<String, LinkCard>,
        flagged: Set<String>,
        names: Names,
    ): List<LinkItem> {
        val urls = findUrls(m.body)
        if (urls.isEmpty()) return emptyList()
        val card = linkCardFor(m, cards)?.takeIf { m.attachmentHash !in flagged }
        var carded = false
        return urls.mapIndexed { i, span ->
            // The card is drawn once, on the first link it matches; a repeat of that link is listed bare.
            val shown = card?.takeIf { !carded && LinkPreviewPolicy.sameUrl(span.url, it.url) }
            if (shown != null) carded = true
            LinkItem(
                key = "${m.id}#$i",
                messageId = m.id,
                url = span.url,
                card = shown,
                cardHash = m.attachmentHash.takeIf { shown != null },
                cardKey = m.attachmentKey.takeIf { shown != null },
                mine = names.mine(m),
                senderName = names.of(m),
                sentAt = m.sentAt,
            )
        }
    }

    companion object {
        /** Rows read per page of either window. */
        const val PAGE = 300
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
