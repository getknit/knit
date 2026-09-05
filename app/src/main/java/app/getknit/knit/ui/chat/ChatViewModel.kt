package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.relay.attachmentReach
import app.getknit.knit.data.relay.noticeFor
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.relay.reachFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.linkpreview.LinkPreviewPolicy
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.voice.VoicePlayer
import app.getknit.knit.ui.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatRow(
    val id: String,
    val body: String,
    val mine: Boolean,
    val senderName: String,
    val senderNodeId: String,
    // The ` (Alias)` suffix already inside [senderName] when another known peer shares the sender's name
    // (ADR 058), which the bubble draws muted — and the plain name for the one sink that must stay plain:
    // the author snapshot a reply puts on the wire ([app.getknit.knit.mesh.protocol.ReplyRef.author]).
    val senderDiscriminator: String? = null,
    val senderPlainName: String = senderName,
    // A non-[MessageEntity.KIND_NORMAL] row is a status notice (e.g. [MessageEntity.KIND_MEMBER_LEFT]),
    // rendered as a centered line using [senderName] instead of a chat bubble.
    val kind: Int = MessageEntity.KIND_NORMAL,
    val avatarHash: String?,
    val sentAt: Long,
    val received: Boolean,
    // The plane the receipt that flipped [received] arrived on; [DeliveryPlane.Internet] paints a globe
    // beside the tick. Only meaningful on our own delivered messages — see [MessageEntity].
    val deliveredVia: DeliveryPlane = DeliveryPlane.Unknown,
    // How many of the group's other members have acked this message, out of how many there are. Both 0
    // outside a group send of ours — and [deliveredCount] is 0 for a message acked before this device
    // recorded ackers, which is what makes the tick fall back to a bare "Delivered" (see [deliveryLabel]).
    val deliveredCount: Int = 0,
    val recipientTotal: Int = 0,
    // True when the on-device text moderator flagged this message's body; the bubble collapses it
    // behind a tap-to-reveal instead of showing the text outright.
    val moderationFlagged: Boolean = false,
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    // Base64 key for an end-to-end-encrypted attachment (null for plaintext/broadcast attachments);
    // passed to the image loader to decrypt the ciphertext blob before decoding.
    val attachmentKey: String? = null,
    // True once the attachment blob is present locally; false while it's still being pulled (the bubble
    // shows a loading placeholder). Only meaningful when [attachmentHash] is non-null.
    val attachmentReady: Boolean = false,
    // True when on-device screening flagged the attachment as explicit; the bubble blurs it behind a
    // tap-to-view. Only meaningful when [attachmentHash] is non-null.
    val attachmentFlagged: Boolean = false,
    // An arbitrary-file attachment's name and the byte count its sender declared (ADR 2026-09.qq2r). Both
    // null for an image or a voice note; a non-null [attachmentName] is what selects the file bubble.
    // [attachmentSize] is what the bubble shows until the bytes land — after that [attachmentBytes] is.
    val attachmentName: String? = null,
    val attachmentSize: Long? = null,
    // The stored blob's own length once it is here, which supersedes the sender's declared size.
    val attachmentBytes: Int? = null,
    // Whether this attachment can cross the Internet-relay plane. Anything but [AttachmentRelay.Silent]
    // or [AttachmentRelay.Relayable] marks the bubble "nearby only" — a statement about *reach*, never
    // about delivery, which the ✓/✓✓ tick keeps to itself. Set only for our own sends; see the mapping
    // in [ChatViewModel].
    val attachmentRelay: AttachmentRelay = AttachmentRelay.Silent,
    // The decoded link-preview card when this attachment is one ([attachmentMime] is the card MIME), its blob
    // is here, it decoded, and — the receiver's guard against a card attached to a link it does not describe —
    // its link is one the body actually contains. Null until then: the bubble draws nothing for a card that
    // has not arrived, never a spinner, since the body's own link is already tappable.
    val linkCard: LinkCard? = null,
    // A voice note's playing time and waveform bars, both derived locally from the audio (never carried on
    // the wire — see [app.getknit.knit.data.VoiceAudio]). Null until the blob has arrived and been
    // described, which is why the bubble can render a length-less placeholder in the meantime.
    //
    // The bars stay in their stored Base64 form here rather than a decoded FloatArray: this is a data class,
    // and an array field would give it reference-identity equality, so every re-emission of the message list
    // would recompose every voice bubble on screen. The bubble decodes once, under `remember`.
    val voiceDurationMs: Int? = null,
    val voicePeaks: String? = null,
    val mentions: List<Mention> = emptyList(),
    val reactions: List<ReactionSummary> = emptyList(),
    // The message this row quotes (Signal-style reply), or null when it isn't a reply. Denormalized so the
    // quote renders even if the quoted original isn't in this thread. See [MessageEntity.replyRef].
    val replyTo: ReplyRef? = null,
    // Set only on a bridged Meshtastic post, and the flag the bubble reads to render one differently: an
    // unverified badge, no tappable avatar, and the gateway named as the radio that carried it. Null on every
    // ordinary row, including our own.
    val origin: MeshOrigin? = null,
)

/**
 * Who said a bridged post on the foreign mesh, and how it reached us — the render-time shape of
 * [MessageEntity]'s `origin*` columns.
 *
 * Everything here is **unauthenticated**. A Meshtastic node number and name are self-asserted on an open,
 * unsigned channel and are trivially spoofable, so [name] is a claim rather than an identity and the UI must
 * never let it look like a Knit peer. [gateway] is the one part anybody vouched for: the Knit peer whose
 * board heard the post and whose signature the frame carries.
 */
data class MeshOrigin(
    /** The speaker's `!hex` id, always shown — it is the only stable handle a bridged author has. */
    val nodeLabel: String,
    /** `User.long_name` if the gateway's board knew one, else null and the id stands alone. */
    val name: String?,
    /** The Knit peer whose radio carried it, already resolved to a display label. */
    val gateway: String,
    val hops: Int?,
    /** Signal-to-noise at the gateway's board, in tenths of a dB. */
    val snrDeci: Int?,
    /** The post entered the foreign mesh over an MQTT uplink, so it may have come from anywhere. */
    val viaMqtt: Boolean,
)

/**
 * One emoji's tally on a message: the [emoji], how many people reacted with it ([count]), and whether
 * the local user is one of them ([mine], to highlight the chip). Distinct emoji become distinct chips;
 * the UI shows the count only when it exceeds 1.
 */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/**
 * A person who can be "@"-mentioned: a thread sender or group member, resolved to a name. [displayName] is
 * the label the picker inserts after the "@" — `Name (Alias)` when another known peer shares the name
 * (ADR 058), in which case [discriminator] is that suffix; [alias] is always shown beside the name in the
 * picker so the right person can be chosen, and matches the typed query too.
 */
data class MentionCandidate(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val alias: String = Alias.aliasFor(nodeId),
    val discriminator: String? = null,
)

/** A peer currently shown as "typing" in this thread, resolved to a display [name] + [avatarHash] for the
 *  animated indicator row. */
data class TypingPeer(
    val nodeId: String,
    val name: String,
    val avatarHash: String?,
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    // Radio health, so the connection header can distinguish "nobody nearby" from radios off/seized.
    val transportHealth: TransportHealth = TransportHealth.Healthy,
    val myNodeId: String = "",
    val mentionCandidates: List<MentionCandidate> = emptyList(),
    // Conversation header: the room ([isRoom] true) or a 1:1 DM with [title]/[avatarHash] of the peer.
    val isRoom: Boolean = true,
    val title: String = "",
    // The ` (Alias)` suffix already inside a DM's [title] when another known peer shares the name (ADR 058).
    val titleDiscriminator: String? = null,
    val avatarHash: String? = null,
    // True when this DM's peer is blocked, so the header offers "Unblock" instead of "Block".
    val isBlocked: Boolean = false,
    // True when this DM's peer has been key-verified (safety number / QR), to show a verified badge.
    val verified: Boolean = false,
    // True when this thread is a group chat; [memberCount] sizes the header subtitle. The header then
    // offers "Rename group" / "Leave group" instead of Block/Unblock.
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    // Peers currently typing in this thread, shown as an animated indicator above the input. Ephemeral
    // (TTL'd in the mesh layer) and best-effort; empty most of the time.
    val typingPeers: List<TypingPeer> = emptyList(),
    // Whether the Internet-relay plane covers this thread. Only [RelayReach.Room] and
    // [RelayReach.Pending] render anything — coverage is the happy path, and an outage is transient and
    // stays quiet. A room whose notice the user has dismissed reads [RelayReach.Silent] here, so this is
    // what to *show*, not what is true of the plane. See [noticeFor].
    val relayReach: RelayReach = RelayReach.Silent,
    // The Internet plane's whole-device state, for the connection header. Coarser than [relayReach] and
    // about a different thing: whether the plane is up at all, not whether it covers this thread.
    val relayPlane: RelayPlane = RelayPlane.Off,
    // The LoRa plane's whole-device state, for the same header (the board glyph beside the cloud).
    val loraPlane: LoraPlane = LoraPlane.Off,
    // Whether the board alone can hear this DM's peer — the pinned notice under the header. See [loraReachFor].
    val loraReach: LoraReach = LoraReach.Silent,
    // Whether (and in which form) a draft here rides LoRa — sizes the composer's length hint. See [loraCarryFor].
    val loraCarry: LoraCarry = LoraCarry.None,
    // Whether the composer shows its attach-a-file button (ADR 2026-09.qq2r) — everywhere except the
    // Nearby room, which
    // takes the refusal voice notes take: nothing on the device can screen a file, and the room floods
    // unencrypted to everyone in range.
    //
    // Deliberately NOT also gated on the recipient advertising [Protocol.CAP_FILES]. That bit only arrives
    // in a profile frame from a peer already running a build that has it, so hiding the item until then
    // made the whole feature invisible with no way to tell why — including on a fresh pair of devices where
    // one side has updated. The capability is enforced where it can explain itself instead, in
    // [ChatViewModel.attachFile].
    val canSendFile: Boolean = false,
    // True when this thread is the **bridged Meshtastic public channel** — posts a paired board overheard on
    // the foreign mesh's primary. It is a public room like Nearby, and shares its glyph and its "no
    // attachments" refusal, but its authors are not Knit peers at all: no avatar, no verified badge, and
    // nothing to tap through to. Distinct from [isRoom] because almost every rule that reads that flag is
    // really asking "is this Nearby", and answering yes here would put a stranger's unauthenticated name
    // wherever Knit shows a person it vouches for.
    val isBridged: Boolean = false,
    // The name that will ride on the front of a post to the foreign public channel — the user's own display
    // name, or null when they have not set one. Shown in the composer hint so the one place ADR 049's rule is
    // suspended says so before the user types, rather than after the words have left.
    val publicPostName: String? = null,
    // Whether a post here still needs the first-use disclosure. Read only by [isBridged] threads.
    val needsPublicConsent: Boolean = false,
)

/**
 * The chat thread's state and every action a bubble or the composer can take.
 *
 * `LargeClass` is suppressed because this class *is* one screen's surface: a single 5-way state combine
 * feeds one `ChatUiState`, and every action below mutates state that combine reads. What could leave has —
 * the row/quote labels, the file gate and the ingest-failure mapping are pure top-level functions in
 * `AttachmentLabels.kt`, and the reply snippet lives in `ReplyFormatting.kt`. What is left needs the same
 * repositories, the same `viewModelScope` and the same one-shot event channel; splitting it would mean two
 * owners of one screen's state, which is the shape `MeshtasticSession` avoids for the same reason.
 */
@Suppress("LargeClass")
class ChatViewModel(
    private val conversationId: String,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val peers: PeerRepository,
    private val reactions: ReactionRepository,
    private val receipts: MessageReceiptRepository,
    private val meshManager: MeshController,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val gallerySaver: GallerySaver,
    // App-scoped on purpose: any number of voice-note bubbles can be on screen and only one may sound, so
    // arbitration can't live in a per-screen ViewModel.
    private val voicePlayer: VoicePlayer,
    // The decoded link-preview cards, app-scoped like the blobs they come from: a card decoded for one thread
    // is the same card in another, and the image loader reads the same store for its picture.
    private val linkCards: LinkCardStore,
    // Fetches the card for a link in this composer's draft — the sender-side half of link previews, the only
    // half that ever touches the Internet, gated on the setting, the validated-Internet route and the audience.
    private val linkPreviews: LinkPreviewService,
    // The facts flow, not the repository that produces it. Narrow on purpose: this ViewModel needs a
    // Flow<RelayFacts> and nothing else, and the production flow is an infinite poller — under a test's
    // virtual clock its `delay` is instant, so a test that drives this VM with `advanceUntilIdle()` could
    // never reach idle. Taking the flow lets a test supply a finite one.
    private val relayFacts: Flow<RelayFacts>,
    // The LoRa plane's facts, the same way (a pushed flow in production, but a test still supplies its own).
    private val loraFacts: Flow<LoraFacts>,
    private val context: Context,
) : ViewModel() {
    /** This thread is the broadcast room (vs a 1:1 DM keyed by the peer's node id). */
    private val isRoom = conversationId == Conversations.NEARBY

    /** This thread is the bridged Meshtastic public channel — read-only, and its authors are not peers. */
    private val isBridged = conversationId == Conversations.MESHTASTIC

    private val myNodeId = MutableStateFlow<String?>(null)

    /** True while the chat is on screen; drives the read watermark below. */
    private val chatForeground = MutableStateFlow(false)

    /** Image staged in the input bar, ready to send with the next message (null when none). */
    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    /**
     * An image flagged as explicit by on-device screening, awaiting the user's "send anyway?"
     * confirmation. Sending such images is allowed but discouraged: it's staged only once confirmed.
     */
    private val _confirmAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val confirmAttachment: StateFlow<AttachmentStore.Ingested?> = _confirmAttachment.asStateFlow()

    /** The composer's text as typed, fed by the screen so a link in it can grow a card. Never persisted. */
    private val draft = MutableStateFlow("")

    /** Bumped when the draft is sent or a card dismissed, so a fetch still in flight cannot stage into the next draft. */
    private val draftEpoch = MutableStateFlow(0)

    /** Per-draft memory, main-thread-confined: the link whose card the user removed, and links that yielded none. */
    private var dismissedUrl: String? = null
    private val failedUrls = HashSet<String>()

    private val _linkPreviewLoading = MutableStateFlow(false)

    /** True while a card is being fetched for the draft; the composer shows a transient "Loading preview…" line. */
    val linkPreviewLoading: StateFlow<Boolean> = _linkPreviewLoading.asStateFlow()

    /**
     * Live state of an in-progress recording, or null when the mic is idle. [elapsedMs] drives the counter,
     * [amplitude] the level meter, and [locked] distinguishes hands-free recording (the user slid up) from
     * hold-to-talk, where letting go ends it.
     */
    data class VoiceRecording(
        val elapsedMs: Long,
        val amplitude: Float,
        val locked: Boolean,
    )

    private val _voiceRecording = MutableStateFlow<VoiceRecording?>(null)
    val voiceRecording: StateFlow<VoiceRecording?> = _voiceRecording.asStateFlow()

    /** Playback state of whichever voice note is loaded app-wide; a bubble matches it against its own hash. */
    val voicePlayback: StateFlow<VoicePlayer.Playback?> = voicePlayer.nowPlaying

    // Built lazily so a chat that never records never opens a recorder, and torn down in onCleared: the
    // microphone is exclusive, and leaking it would block every other app until this process died.
    private val recorder by lazy { VoiceRecorder(context, viewModelScope) }

    // Ticks the recording UI. Cancelled by every path that ends a recording.
    private var recordingTicker: Job? = null

    /** One-shot UI messages (a string res id), surfaced as toasts — e.g. the result of saving an image. */
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    /** Emitted once the DM's peer is blocked, so the screen can close (the thread is now hidden). */
    private val _closeChat = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeChat: SharedFlow<Unit> = _closeChat.asSharedFlow()

    /**
     * Emitted after a message is accepted and sent, so the screen clears its input field/mentions. The
     * screen no longer clears optimistically: if [send] blocks the text for abuse, nothing is emitted and
     * the user keeps their draft to edit.
     */
    private val _clearInput = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearInput: SharedFlow<Unit> = _clearInput.asSharedFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        watchDraftForLinks()
        // Decode every link-preview card in this thread whose blob has landed, once; the store dedups and the
        // rows pick the result up through blobState. The pair list is distinct so a size change elsewhere in
        // the table does not re-walk the thread.
        viewModelScope.launch {
            combine(messages.observeMessages(conversationId), blobs.observeSizes()) { msgs, sizes ->
                msgs
                    .filter { it.attachmentMime == LinkPreviewBlob.MIME && it.attachmentHash != null && it.attachmentHash in sizes }
                    .map { it.attachmentHash!! to it.attachmentKey }
            }.distinctUntilChanged().collect { held ->
                held.forEach { (hash, key) -> linkCards.ensure(hash, key) }
            }
        }
        // Advance this conversation's read watermark while the chat is on screen: on every stream
        // emission (so messages arriving while you read don't reappear as unread), stamp newest sentAt.
        viewModelScope.launch {
            combine(chatForeground, messages.observeMessages(conversationId)) { foreground, msgs ->
                if (foreground) msgs.maxOfOrNull { it.sentAt } else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setLastReadAt(conversationId, watermark)
            }
        }
    }

    // Bundles the four message-related streams so the outer combine below stays at the 5-flow typed
    // overload (a 6th flow falls back to unchecked Array<*> casts). Blocked senders' messages are
    // filtered out here, so they also drop out of rows and mention candidates. Observing the blob
    // sizes here is what flips an attachment from "loading" to shown when its bytes arrive — and, since
    // the same rows carry the byte length, what tells the UI whether those bytes can cross a relay.
    private data class MessagesBundle(
        val messages: List<MessageEntity>,
        val reactions: List<ReactionEntity>,
        val blocked: Set<String>,
        val blobSizes: Map<String, Int>,
        val flaggedHashes: Set<String>,
        val hideSensitiveContent: Boolean,
        // blob hash -> the decoded link-preview card, for every card this process has opened so far.
        val linkCards: Map<String, LinkCard>,
        val group: GroupEntity?,
        // messageId -> how many current roster members have acked it. Empty outside a group.
        val deliveredCounts: Map<String, Int>,
    )

    // What the blob table and the moderation cache say about every attachment, folded into one arm of the
    // message bundle so it stays at the typed 5-flow combine overload.
    private data class BlobState(
        val sizes: Map<String, Int>,
        val flagged: Set<String>,
        val hideSensitive: Boolean,
        val linkCards: Map<String, LinkCard>,
    )

    // Held blob sizes + moderation-flagged hashes plus the content-filtering setting, and the decoded
    // link-preview cards, combined upstream so the main bundle stays at the typed 5-flow combine overload.
    // The setting only gates receive-side *hiding* (the chat blur + toxic-text collapse below), so toggling
    // it reactively reveals/hides already-received content without re-screening; what you can send is
    // enforced elsewhere regardless.
    private val blobState =
        combine(
            blobs.observeSizes(),
            imageScreening.observeFlaggedHashes(),
            settings.contentFilteringEnabled,
            linkCards.cards,
        ) { sizes, flagged, hideSensitive, cards ->
            BlobState(sizes, flagged.toSet(), hideSensitive, cards)
        }

    // The group row paired with "how many members have acked each message", re-subscribed whenever the
    // roster changes (a departure changes the denominator AND which receipts count). Not a group ⇒ no
    // roster ⇒ no counts, and the tick keeps its plain wording.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val groupDelivery: Flow<Pair<GroupEntity?, Map<String, Int>>> =
        groups
            .observeGroup(conversationId)
            .distinctUntilChanged()
            .flatMapLatest { group ->
                val roster = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                if (roster.isEmpty()) {
                    flowOf(group to emptyMap())
                } else {
                    receipts.observeDeliveredCounts(conversationId, roster).map { group to it }
                }
            }

    private val messagesWithReactions =
        combine(
            messages.observeMessages(conversationId),
            reactions.observeReactions(),
            settings.blockedNodeIds,
            blobState,
            groupDelivery,
        ) { msgs, reacts, blocked, blob, (group, delivered) ->
            MessagesBundle(
                msgs.filter { it.senderId !in blocked },
                reacts,
                blocked,
                blob.sizes,
                blob.flagged,
                blob.hideSensitive,
                blob.linkCards,
                group,
                delivered,
            )
        }

    // The LoRa plane's facts + whoever this thread's notice is about, folded first so the LoRa notice costs
    // the mesh-status combine one arm, not two.
    private data class LoraThread(
        val facts: LoraFacts,
        val audience: LoraAudience,
    )

    // Which peers the notice reasons over — one shape per conversation kind, decided by the thread's id at
    // construction, so a thread only ever computes the one it can use. The transport map is reduced to that
    // shape before `distinctUntilChanged`, so another peer's sighting never rebuilds the screen.
    private sealed interface LoraAudience {
        /** A DM: the radios that currently reach this thread's peer; null when none do. */
        data class Peer(
            val kinds: Set<TransportKind>?,
        ) : LoraAudience

        /** The room, addressed to nobody: whether *anyone* at all sits behind the board. */
        data class Room(
            val loraOnly: Boolean,
        ) : LoraAudience

        /** A group: every id behind the board, narrowed to this group's roster where the state is built. */
        data class Group(
            val loraOnly: Set<String>,
        ) : LoraAudience

        /**
         * The bridged Meshtastic room, which has no LoRa audience to speak of — nothing here is ever sent, so
         * no congestion notice about delayed delivery could be true. Its own object rather than reusing
         * [Room]: the room's saturated notice reads the airtime *this* device would spend, and this thread
         * spends none.
         */
        data object Bridged : LoraAudience
    }

    private val loraAudience: Flow<LoraAudience> =
        meshManager.peerTransports
            .map { transports ->
                when (Conversations.kindFor(conversationId)) {
                    ConversationKind.NEARBY -> LoraAudience.Room(transports.values.any(::isLoraOnly))
                    ConversationKind.GROUP -> LoraAudience.Group(transports.filterValues(::isLoraOnly).keys)
                    ConversationKind.DM -> LoraAudience.Peer(transports[conversationId])
                    ConversationKind.MESHTASTIC -> LoraAudience.Bridged
                }
            }.distinctUntilChanged()

    private val loraThread =
        combine(loraFacts, loraAudience) { facts, audience ->
            LoraThread(facts, audience)
        }.distinctUntilChanged()

    // The relay plane's facts + whether the user has dismissed the room's notice, folded the same way and
    // for the same reason as [LoraThread]: the dismissal only ever changes what the notice says, so it
    // belongs beside the facts that notice reads rather than as another arm of the status combine.
    private data class RelayThread(
        val facts: RelayFacts,
        val roomNoticeDismissed: Boolean,
    )

    private val relayThread =
        combine(
            relayFacts,
            settings.relayRoomNoticeDismissed,
        ) { facts, dismissed -> RelayThread(facts, dismissed) }.distinctUntilChanged()

    // Neighbor count + radio health + the "who's typing" map + Internet-relay reach + the LoRa thread folded
    // into one source so the main state combine stays within its five-flow arity.
    private data class MeshStatus(
        val neighborCount: Int,
        val transportHealth: TransportHealth,
        val typing: Map<String, Set<String>>,
        val relay: RelayThread,
        val lora: LoraThread,
    )

    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            meshManager.typing,
            relayThread,
            loraThread,
        ) { count, health, typing, relay, lora -> MeshStatus(count, health, typing, relay, lora) }

    // Paired rather than combined separately because `combine`'s typed arity stops at five, and these two
    // are one question anyway: who this device posts to the foreign public channel as, and whether it may.
    private val publicIdentity =
        combine(settings.displayName, settings.meshtasticPostConsented) { name, consented -> name to consented }

    val state: StateFlow<ChatUiState> =
        combine(
            messagesWithReactions,
            peers.observeDirectory(),
            meshStatus,
            myNodeId,
            publicIdentity,
        ) { bundle, directory, mesh, me, publicId ->
            val (myName, publicConsented) = publicId
            val count = mesh.neighborCount
            val health = mesh.transportHealth
            val typingMap = mesh.typing
            val relay = mesh.relay.facts
            val msgs = bundle.messages
            val reacts = bundle.reactions
            val blocked = bundle.blocked
            val blobSizes = bundle.blobSizes
            val flaggedHashes = bundle.flaggedHashes
            val hideSensitive = bundle.hideSensitiveContent
            val cards = bundle.linkCards
            val group = bundle.group
            val deliveredCounts = bundle.deliveredCounts
            val isGroup = group != null
            val members = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
            val peersByNode = directory.byNode
            // Group once, then tally per emoji within each message's bucket. Orphan reactions (no matching
            // message yet) simply never produce a row until their message arrives.
            val reactionsByMessage = reacts.groupBy { it.messageId }
            val rows =
                msgs.map { m ->
                    // A bridged post's author is the Meshtastic speaker, NOT the frame's signer — the gateway
                    // whose board heard it, which the directory would otherwise name here. Resolve the origin
                    // first so the name, avatar and tap target below all follow from the same answer.
                    val origin = m.originNode?.let { node -> meshOriginFor(m, node, directory.label(m.senderId).text) }
                    val mine = m.senderId == me && origin == null
                    val senderLabel = if (mine || origin != null) null else directory.label(m.senderId)
                    val name =
                        origin?.let { it.name ?: it.nodeLabel }
                            ?: senderLabel?.text
                            ?: myName.ifBlank { context.getString(R.string.chat_self_name) }
                    val tallies =
                        reactionsByMessage[m.id]
                            .orEmpty()
                            .groupBy { it.emoji }
                            .mapNotNull { (emoji, group) ->
                                // emoji is non-null in the stream (tombstones are filtered in the DAO); guard anyway.
                                if (emoji == null) {
                                    null
                                } else {
                                    ReactionSummary(emoji, group.size, group.any { it.reactorNodeId == me })
                                }
                            }
                    val heldBytes = m.attachmentHash?.let { blobSizes[it] }
                    ChatRow(
                        id = m.id,
                        body = m.body,
                        mine = mine,
                        senderName = name,
                        senderNodeId = m.senderId,
                        senderDiscriminator = senderLabel?.discriminator,
                        senderPlainName = senderLabel?.name ?: name,
                        kind = m.kind,
                        // Never the gateway's avatar on a bridged post: it would put a Knit peer's face on
                        // somebody else's words. A bridged author draws the letter avatar instead.
                        avatarHash = if (origin == null) peersByNode[m.senderId]?.avatarHash else null,
                        sentAt = m.sentAt,
                        received = m.received,
                        deliveredVia = m.receivedPlane,
                        // Only our own group sends have a "who has it" answer; the roster excludes us,
                        // since we never ack ourselves (the details screen's rule, kept identical here).
                        deliveredCount = if (mine && isGroup) deliveredCounts[m.id] ?: 0 else 0,
                        recipientTotal = if (mine && isGroup) members.count { it != me } else 0,
                        moderationFlagged = hideSensitive && m.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                        attachmentHash = m.attachmentHash,
                        attachmentMime = m.attachmentMime,
                        attachmentKey = m.attachmentKey,
                        voiceDurationMs = m.voiceDurationMs,
                        voicePeaks = m.voicePeaks,
                        attachmentReady = heldBytes != null,
                        attachmentName = m.attachmentName,
                        attachmentSize = m.attachmentSize,
                        attachmentBytes = heldBytes,
                        attachmentFlagged = hideSensitive && m.attachmentHash != null && m.attachmentHash in flaggedHashes,
                        // Outbound reach only: a received attachment has already arrived, so telling its
                        // reader it is "nearby only" would describe a journey that is over. Unknown size
                        // (bytes reclaimed by retention) falls through to Silent rather than guessing. A
                        // link-preview card stays Silent too: a card that does not make the Internet
                        // shortcut is no loss worth a marker.
                        attachmentRelay =
                            if (mine && heldBytes != null && m.attachmentMime != LinkPreviewBlob.MIME) {
                                attachmentReach(conversationId, heldBytes, relay)
                            } else {
                                AttachmentRelay.Silent
                            },
                        linkCard = linkCardFor(m, cards),
                        mentions = MentionStore.decode(m.mentions),
                        reactions = tallies,
                        replyTo = m.replyRef(),
                        origin = origin,
                    )
                }
            // Autocomplete candidates: everyone we've received a message from, plus a group's roster (so
            // @-mentions work in a fresh group before anyone has spoken), resolved to a display name.
            val candidates =
                (msgs.map { it.senderId } + members)
                    .asSequence()
                    .filter { it != me }
                    .distinct()
                    .map { id ->
                        val label = directory.label(id)
                        MentionCandidate(
                            nodeId = id,
                            displayName = label.text,
                            avatarHash = peersByNode[id]?.avatarHash,
                            alias = label.alias,
                            discriminator = label.discriminator,
                        )
                    }.sortedBy { it.displayName.lowercase() }
                    .toList()
            // Peers typing in THIS thread, resolved for the indicator row. Skip ourselves (defensive — our
            // own cue never lands here) and blocked senders (as their messages are already filtered out).
            val typingPeers =
                typingMap[conversationId]
                    .orEmpty()
                    .asSequence()
                    .filter { it != me && it !in blocked }
                    .map { id -> TypingPeer(id, directory.label(id).text, peersByNode[id]?.avatarHash) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
            ChatUiState(
                rows = rows,
                neighborCount = count,
                transportHealth = health,
                myNodeId = me.orEmpty(),
                mentionCandidates = candidates,
                isRoom = isRoom,
                title =
                    when {
                        group != null -> {
                            groupTitle(
                                storedName = group.name,
                                memberIds = members,
                                selfId = me,
                                fallback = context.getString(R.string.group_unnamed),
                            ) { id -> directory.label(id).text }
                        }

                        isRoom -> {
                            context.getString(R.string.nearby_title)
                        }

                        // The channel's own name where a post has told us one (`LongFast`, `MediumFast` — it
                        // varies with the board's preset), else the generic label. Read off the newest post
                        // rather than the board, because a phone with no board of its own still shows this
                        // room and has only the frames to go on.
                        isBridged -> {
                            msgs.lastOrNull { !it.originChannel.isNullOrBlank() }?.originChannel
                                ?: context.getString(R.string.meshtastic_title)
                        }

                        else -> {
                            directory.label(conversationId).text
                        }
                    },
                titleDiscriminator = if (isRoom || isBridged || isGroup) null else directory.label(conversationId).discriminator,
                // A room uses a glyph; a group shows its photo (or the glyph when unset); a DM the peer avatar.
                avatarHash =
                    when {
                        isRoom || isBridged -> null
                        else -> group?.photoHash ?: peersByNode[conversationId]?.avatarHash
                    },
                canSendFile = !isRoom && !isBridged,
                isBridged = isBridged,
                publicPostName = myName.takeIf { isBridged && it.isNotBlank() },
                needsPublicConsent = isBridged && !publicConsented,
                isBlocked = !isRoom && !isBridged && !isGroup && conversationId in blocked,
                verified = !isRoom && !isBridged && !isGroup && peersByNode[conversationId]?.verified == true,
                isGroup = isGroup,
                memberCount = members.size,
                typingPeers = typingPeers,
                relayReach = noticeFor(conversationId, relay, mesh.relay.roomNoticeDismissed),
                relayPlane = planeFor(relay),
                loraPlane = mesh.lora.facts.plane,
                loraReach =
                    when (val audience = mesh.lora.audience) {
                        is LoraAudience.Room -> {
                            loraRoomReachFor(mesh.lora.facts, audience.loraOnly)
                        }

                        is LoraAudience.Group -> {
                            loraGroupReachFor(
                                mesh.lora.facts,
                                // The roster, never the whole directory: a LoRa-only stranger is not in
                                // this group, and our own id is not somebody we fail to deliver to.
                                loraOnlyMember = members.any { it != me && it in audience.loraOnly },
                                relayReach = reachFor(conversationId, relay),
                            )
                        }

                        is LoraAudience.Peer -> {
                            loraReachFor(
                                conversationId,
                                mesh.lora.facts,
                                audience.kinds,
                                reachFor(conversationId, relay),
                            )
                        }

                        // Nothing leaves this thread, so no congestion notice about it could be true. What
                        // the room does say about itself is a static strip in the screen, not a reach state.
                        LoraAudience.Bridged -> {
                            LoraReach.Silent
                        }
                    },
                loraCarry = loraCarryFor(conversationId, isGroup, mesh.lora.facts),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom))

    /**
     * The render shape of a bridged post's attribution. [gatewayLabel] is the frame signer resolved through
     * the peer directory — the one identity in this row anybody vouched for, which is why the bubble names it
     * ("via Sam's radio") rather than leaving an unauthenticated Meshtastic name standing alone.
     */
    private fun meshOriginFor(
        m: MessageEntity,
        node: Long,
        gatewayLabel: String,
    ) = MeshOrigin(
        nodeLabel = meshNodeLabel(node),
        name = m.originName?.takeIf { it.isNotBlank() },
        gateway = gatewayLabel,
        hops = m.originHops,
        snrDeci = m.originSnrDeci,
        viaMqtt = m.originViaMqtt,
    )

    /** The long-press quick-reaction row: the [RecentReactions.SHOWN] most recent picks, newest first. */
    val recentReactions: StateFlow<List<String>> =
        settings.recentReactions
            .map { it.take(RecentReactions.SHOWN) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentReactions.DEFAULTS)

    /**
     * Reach for the image staged in the composer, so the user learns a photo is nearby-only *before*
     * sending rather than after. Its own flow rather than a [ChatUiState] field: the staged attachment is
     * not part of the main combine (which is already at the typed five-flow limit), and the composer is
     * the only consumer.
     *
     * The size comes from the blob table, not from [AttachmentStore.Ingested] — ingestion has already
     * stored the bytes by the time an image is staged, so the row is there to be read.
     */
    val stagedAttachmentRelay: StateFlow<AttachmentRelay> =
        combine(
            _pendingAttachment,
            blobs.observeSizes(),
            relayFacts,
        ) { staged, sizes, relay ->
            if (staged?.link != null) return@combine AttachmentRelay.Silent // a card's reach is never a marker
            val bytes = staged?.hash?.let { sizes[it] } ?: return@combine AttachmentRelay.Silent
            attachmentReach(conversationId, bytes, relay)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttachmentRelay.Silent)

    /**
     * The card for [m] when it carries one that has been decoded ([cards]) **and** whose link the body
     * actually contains — a sender cannot attach one page's card to another page's link. The compare is on
     * normalized links, so a typed `http://` and the card's `https://` form still match.
     */
    private fun linkCardFor(
        m: MessageEntity,
        cards: Map<String, LinkCard>,
    ): LinkCard? {
        if (m.attachmentMime != LinkPreviewBlob.MIME) return null
        val card = m.attachmentHash?.let { cards[it] } ?: return null
        return card.takeIf { c -> findUrls(m.body).any { LinkPreviewPolicy.sameUrl(it.url, c.url) } }
    }

    /** The screen reports every edit of the draft here; a blank draft resets the per-draft memory. */
    fun onDraftChanged(text: String) {
        draft.value = text
        if (text.isBlank()) {
            dismissedUrl = null
            failedUrls.clear()
        }
    }

    /**
     * Whether every recipient of this thread can render a card, or null when the room is the audience: a DM or
     * group message carries a card only toward pinned profiles carrying [Protocol.CAP_LINK_PREVIEW], since a
     * build without it shows a spinner where the card should be. Silent, unlike [refusalForFile] — an implicit
     * action has no affordance to explain itself through, and the message goes as plain text either way.
     */
    private suspend fun audienceCannotRenderCards(): Boolean {
        if (isRoom) return false
        val members = groups.find(conversationId)?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        val me = identity.nodeId()
        val audience = if (members.isEmpty()) listOf(conversationId) else members.filter { it != me }
        return audience.isEmpty() || audience.any { (peers.find(it)?.capabilities ?: 0L) and Protocol.CAP_LINK_PREVIEW == 0L }
    }

    /**
     * The composer's link-preview loop: the first eligible link in the draft, debounced, becomes a staged card
     * when every gate agrees — the setting is on, a validated route exists, nothing else is staged, the link
     * was not dismissed or found empty in this draft, the thread does not ride LoRa (a card's reference costs
     * body budget there and its bytes never cross), and the audience can render one. `collectLatest` cancels a
     * fetch the moment the link or the epoch changes, so a card can only ever land on the draft it was
     * fetched for.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchDraftForLinks() {
        viewModelScope.launch {
            combine(
                draft.map(LinkPreviewPolicy::firstEligible),
                draftEpoch,
                linkPreviews.online,
                // Whether the one attachment slot is free: clearing a staged photo re-arms the link under it.
                _pendingAttachment.map { it == null },
            ) { url, epoch, online, free -> DraftKey(url, epoch, online, free) }
                .distinctUntilChanged()
                .debounce(PREVIEW_DEBOUNCE_MS)
                .collectLatest { key -> considerCard(key.url, key.online) }
        }
    }

    /** Everything the link loop reacts to; a change in any field re-runs [considerCard] after the debounce. */
    private data class DraftKey(
        val url: String?,
        val epoch: Int,
        val online: Boolean,
        val free: Boolean,
    )

    private suspend fun considerCard(
        url: String?,
        online: Boolean,
    ) {
        val staged = _pendingAttachment.value
        if (url == null) {
            if (staged?.link != null) clearAttachment()
            return
        }
        if (!cardWanted(url, staged, online)) return
        _linkPreviewLoading.value = true
        try {
            stageCard(url)
        } finally {
            _linkPreviewLoading.value = false
        }
    }

    /** Every gate a fetch for [url] has to pass, cheapest first; the audience read comes last because it hits the DB. */
    private suspend fun cardWanted(
        url: String,
        staged: AttachmentStore.Ingested?,
        online: Boolean,
    ): Boolean =
        staged == null &&
            url != dismissedUrl &&
            url !in failedUrls &&
            online &&
            settings.linkPreviewsEnabled.first() &&
            state.value.loraCarry == LoraCarry.None &&
            !audienceCannotRenderCards()

    private suspend fun stageCard(url: String) {
        when (val result = linkPreviews.fetchCard(url, isRoom)) {
            is LinkPreviewService.CardResult.Card -> {
                // Re-check: a photo may have been staged, or the draft edited or sent, while the fetch ran.
                if (_pendingAttachment.value == null && LinkPreviewPolicy.firstEligible(draft.value) == url) {
                    stage(attachments.ingestLinkPreview(result.blob), notifyFailure = false)
                }
            }

            LinkPreviewService.CardResult.NoCard -> {
                failedUrls += url
            }

            LinkPreviewService.CardResult.Offline, LinkPreviewService.CardResult.Restricted -> {
                // Not an answer about the link: retried when the route returns.
            }
        }
    }

    /**
     * Double-submit guard: true from the moment a send is accepted until its input is cleared (success)
     * or it's rejected (blocked). [send] is a suspending round-trip (seal-to-recipients + DB write +
     * enqueue), and the input isn't cleared until it returns, so without this a rapid burst of taps on
     * the always-enabled send button would each read the same still-present draft and flood duplicates.
     * Main-thread-confined: touched only from [send] and [onInputCleared], both on the main dispatcher.
     *
     * Exposed as [isSending] so the chat screen can show a "working…" spinner in the send button while a
     * send is in flight — on a cold start the first send blocks on the one-time toxicity-model load
     * (~16 MB TFLite + tokenizer + Interpreter), which otherwise looks like a frozen app. Backing the
     * guard and the UI signal with the same value keeps them from ever diverging.
     */
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _showPublicConsent = MutableStateFlow(false)

    /** Whether the bridged room's first-use disclosure is on screen. See [acceptPublicConsent]. */
    val showPublicConsent: StateFlow<Boolean> = _showPublicConsent.asStateFlow()

    /**
     * Records the disclosure as accepted and lowers it. Deliberately does **not** send: the user pressed a
     * button that said "Post" on a sheet, not on their message, and making one tap do both would mean the
     * words went out in the same motion as the decision to allow them to.
     */
    fun acceptPublicConsent() {
        viewModelScope.launch {
            settings.acceptMeshtasticPostConsent()
            _showPublicConsent.value = false
        }
    }

    /** Lowers the disclosure without recording anything, so the next attempt asks again. */
    fun dismissPublicConsent() {
        _showPublicConsent.value = false
    }

    fun send(
        text: String,
        mentions: List<Mention> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
        val trimmed = text.trim().take(TextLimits.MESSAGE)
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        // The bridged room's first post raises the disclosure instead of sending. Raised here rather than on
        // opening the room, because a person who only ever reads it should never be asked to decide anything;
        // the draft is kept, so accepting sends what they already wrote.
        if (isBridged && state.value.needsPublicConsent) {
            _showPublicConsent.value = true
            return
        }
        // Ignore re-entrant taps while a send is in flight, and — on success — until the field is
        // actually cleared, so a tap landing in the gap between sendChat returning and clearText running
        // can't re-send the same draft. Released in the blocked branch and in onInputCleared().
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch {
            // Deferred release: an accepted send keeps the guard held until the field is actually
            // cleared (onInputCleared); the finally frees it on a block or an unexpected send-path throw
            // so the guard can never stick and freeze the input.
            var accepted = false
            try {
                // Normalize a self-quote's snapshotted author before it goes on the wire (see the helper).
                val outgoingReply = normalizeSelfAuthor(replyTo)
                // Re-read the group at send time so it's never misrouted as a DM in a startup race, and so
                // a pending rename rides this message (its GroupInfo.name converges last-writer-wins).
                val group = if (isRoom || isBridged) null else groups.find(conversationId)
                val sent =
                    if (isBridged) {
                        // The bridged room is not addressed to anybody: no recipient, no group, no
                        // attachment, no reply. `sendChat` would read that shape as the Nearby room and put
                        // the post there, so this room has its own origination path (see MeshController).
                        meshManager.sendPublicPost(trimmed)
                    } else if (group != null) {
                        meshManager.sendChat(
                            trimmed,
                            attachment,
                            mentions,
                            recipientId = null,
                            group = group.toGroupInfo(),
                            replyTo = outgoingReply,
                        )
                    } else {
                        // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
                        val recipientId = if (isRoom) null else conversationId
                        meshManager.sendChat(trimmed, attachment, mentions, recipientId, replyTo = outgoingReply)
                    }
                // MeshManager applies block-on-send. Clear the input/attachment only once a message is
                // accepted; a blocked message keeps the draft and surfaces a toast so the user can edit.
                if (sent) {
                    accepted = true
                    // The voice description is deliberately NOT written here. It rides on the staged
                    // [AttachmentStore.Ingested] and is written by `MeshManager.sendChat` against the hash
                    // the row actually holds — for a DM or group that is the attachment's *ciphertext*
                    // hash, so writing it here against the plaintext hash staged above would silently
                    // update no rows at all.
                    _pendingAttachment.value = null
                    // The next draft starts clean: no dismissed link, no failed ones, and a card fetch still in
                    // flight for this one can no longer stage into it.
                    dismissedUrl = null
                    failedUrls.clear()
                    draftEpoch.value++
                    // Guard stays held until the screen reports the field cleared (onInputCleared), so no
                    // duplicate can slip through the tryEmit -> collect -> clearText hop.
                    _clearInput.tryEmit(Unit)
                } else {
                    _events.tryEmit(R.string.moderation_text_blocked)
                }
            } finally {
                if (!accepted) _isSending.value = false
            }
        }
    }

    /**
     * Normalizes a quoted-reply's author snapshot before it goes on the wire: a reply quoting *our own*
     * message must carry the display name a peer resolves for us — never the local "You" self-label — so
     * every recipient shows our real name and only swaps in "You" when they are themselves the quoted
     * author. A reply to anyone else is returned unchanged (its snapshot is already a peer-resolved name).
     */
    private suspend fun normalizeSelfAuthor(replyTo: ReplyRef?): ReplyRef? {
        val me = identity.nodeId()
        return replyTo
            ?.takeIf { it.authorId == me }
            ?.copy(author = displayNameFor(settings.displayName.first(), me))
            ?: replyTo
    }

    /**
     * The screen finished clearing the input after an accepted send; release the double-submit guard.
     * Deferred to here (rather than the success branch above) so the guard covers the window between
     * [send] returning and the field visually clearing — see [isSending].
     */
    fun onInputCleared() {
        _isSending.value = false
    }

    /**
     * Toggles the local user's [emoji] reaction on [messageId] (add / replace / remove) and floods it.
     * Passes the thread context along, resolved the same way [send] does (re-read at send time), so a
     * DM/group reaction rides sealed where the conversation supports it — the manager never re-derives
     * the context from the message row.
     */
    fun react(
        messageId: String,
        emoji: String,
    ) {
        // Tapping the chip you already own retracts — undoing a choice, not making one — so only an add or a
        // replace fronts the recents. Judged from the highlighted chip the user is looking at, after the send.
        val retracting =
            state.value.rows
                .firstOrNull { it.id == messageId }
                ?.reactions
                ?.any { it.mine && it.emoji == emoji } == true
        viewModelScope.launch {
            val group = if (isRoom) null else groups.find(conversationId)?.toGroupInfo()
            val recipientId = if (isRoom || group != null) null else conversationId
            meshManager.sendReaction(messageId, emoji, recipientId, group)
            if (!retracting) settings.recordReaction(emoji)
        }
    }

    /**
     * Removes [messageId] from this device only — its row, its reactions, its per-recipient delivery
     * rows, and (if no other message still references it) its content-addressed attachment blob. Sends
     * nothing over the mesh.
     */
    fun deleteMessage(messageId: String) {
        val hash =
            state.value.rows
                .firstOrNull { it.id == messageId }
                ?.attachmentHash
        viewModelScope.launch {
            messages.delete(messageId)
            reactions.deleteForMessage(messageId)
            receipts.deleteForMessage(messageId)
            blobs.deleteIfUnreferenced(hash)
            _events.tryEmit(R.string.chat_message_deleted)
        }
    }

    /** Blocks [nodeId] locally: their messages/reactions stop being stored, shown, and notified. */
    fun block(nodeId: String) {
        viewModelScope.launch {
            settings.block(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_blocked)
            // Blocking the peer of a DM empties this thread (and hides it from the list), so close the
            // now-confusing screen. Emitted only after the block persists, so navigating away can't
            // cancel the write. Blocking from the Nearby room leaves the room open.
            if (!isRoom) _closeChat.tryEmit(Unit)
        }
    }

    /**
     * Dismisses the room's "never sent over the Internet" notice, for good. Reachable only from that notice's
     * close button — the room is the one thread whose notice offers one (see `dismissable`) — and it stays
     * dismissed across restarts, because the fact it stated is permanent and repeating it is a nag.
     */
    fun dismissRelayNotice() {
        viewModelScope.launch { settings.dismissRelayRoomNotice() }
    }

    /** Unblocks [nodeId], restoring their (never-deleted) message history. */
    fun unblock(nodeId: String) {
        viewModelScope.launch {
            settings.unblock(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_unblocked)
        }
    }

    /**
     * Ingests a picked or keyboard-inserted image and stages it in the input bar. A decode failure is
     * silently ignored, as before — the picture is still sitting in the picker, so there is nothing to
     * explain.
     */
    fun attach(uri: Uri) {
        viewModelScope.launch { stage(attachments.ingest(uri), notifyFailure = false) }
    }

    /**
     * Ingests a picked **file** of any type and stages it. Unlike [attach] every failure speaks up: a file
     * refused for its size cannot be shrunk the way a photo is, and one refused for being an app package is
     * a decision rather than an accident, so silence would read as the app doing nothing.
     */
    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            refusalForFile()?.let {
                _events.tryEmit(it)
                return@launch
            }
            stage(attachments.ingestFile(uri), notifyFailure = true)
        }
    }

    /**
     * Why this thread cannot take a file, or null when it can.
     *
     * The composer already hides the "File" item where [ChatUiState.canSendFile] is false, so for a picked
     * file this only re-states a decision the UI made. The share sheet is why it exists: a file arriving
     * from another app is drained on the chat's first composition, before the state combine has read a
     * single peer row, so a check against the rendered state would refuse every capable peer exactly once —
     * and refuse it with the wrong reason. Reading the repositories directly has no such window, and
     * [isRoom] is settled at construction.
     *
     * Returns a string resource, or null. Not `@StringRes`-annotated: a nullable `Int?` boxes, and the
     * annotation only applies to a primitive.
     */
    private suspend fun refusalForFile(): Int? {
        if (isRoom) return R.string.chat_share_needs_chat
        val members = groups.find(conversationId)?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        val me = identity.nodeId()
        val audience = if (members.isEmpty()) listOf(conversationId) else members.filter { it != me }
        val capable =
            audience.isNotEmpty() &&
                audience.all { (peers.find(it)?.capabilities ?: 0L) and Protocol.CAP_FILES != 0L }
        return if (capable) null else R.string.chat_file_peer_too_old
    }

    /**
     * Ingests a photo just taken with the in-app camera ([app.getknit.knit.ui.camera.PhotoCapture]) and
     * stages it exactly like a picked one. The bytes arrive in memory and are never written to disk.
     *
     * Unlike [attach] this **does** surface a failure: the shot exists nowhere else, so silently
     * dropping it would look like the camera simply did nothing.
     */
    fun attachCaptured(jpeg: ByteArray) {
        viewModelScope.launch { stage(attachments.ingest(jpeg, "image/jpeg"), notifyFailure = true) }
    }

    /**
     * Stages an ingested image, or handles its verdict. A picture flagged as explicit by on-device
     * screening is handled by context: the public Nearby room **blocks** it outright (no confirmation
     * bypass), while DMs/groups route it to [confirmAttachment] for a "send anyway?" confirmation.
     */
    private suspend fun stage(
        result: AttachmentStore.IngestResult,
        notifyFailure: Boolean,
    ) {
        when (result) {
            is AttachmentStore.IngestResult.Success -> {
                when {
                    !result.flagged -> {
                        // A staged card gives way to whatever the user attached on purpose (one slot).
                        _pendingAttachment.value?.takeIf { it.link != null && result.ingested.link == null }?.let { card ->
                            blobs.deleteIfUnreferenced(card.hash)
                        }
                        _pendingAttachment.value = result.ingested
                    }

                    isRoom -> {
                        // Hard block in the broadcast room; drop the ingested-but-unsent blob.
                        blobs.deleteIfUnreferenced(result.ingested.hash)
                        _events.tryEmit(R.string.moderation_image_blocked)
                    }

                    else -> {
                        _confirmAttachment.value = result.ingested
                    }
                }
            }

            is AttachmentStore.IngestResult.Failed -> {
                if (notifyFailure) _events.tryEmit(ingestFailureMessage(result.reason))
            }
        }
    }

    /**
     * Starts recording a voice note. Returns false when the microphone couldn't be opened — held by a call
     * or another app — so the composer can say so rather than showing a recorder that captures silence. The
     * caller has already cleared the `RECORD_AUDIO` gate.
     *
     * [locked] starts a hands-free recording directly (the accessibility tap path); hold-to-talk starts
     * unlocked and flips via [lockVoiceRecording] when the user slides up.
     */
    fun startVoiceRecording(locked: Boolean = false): Boolean {
        if (_voiceRecording.value != null) return false
        if (!recorder.start()) {
            _events.tryEmit(R.string.chat_voice_record_failed)
            return false
        }
        _voiceRecording.value = VoiceRecording(elapsedMs = 0L, amplitude = 0f, locked = locked)
        recordingTicker?.cancel()
        recordingTicker =
            viewModelScope.launch {
                while (true) {
                    delay(VOICE_TICK_MS)
                    val elapsed = recorder.elapsedMs()
                    // Stop cleanly at the cap rather than letting the recorder run on: the note is still
                    // staged, so a user who talks past five minutes keeps what they said instead of losing it.
                    if (elapsed >= VoiceRecorder.MAX_DURATION_MS) {
                        stopVoiceRecordingAndStage()
                        return@launch
                    }
                    _voiceRecording.value =
                        _voiceRecording.value?.copy(elapsedMs = elapsed, amplitude = recorder.amplitude())
                }
            }
        return true
    }

    /** Switches an in-progress hold-to-talk recording to hands-free; the user slid up off the button. */
    fun lockVoiceRecording() {
        _voiceRecording.value = _voiceRecording.value?.copy(locked = true)
    }

    /**
     * Ends the recording and stages it for review, exactly as a picked photo is staged — so the user hears
     * it back before sending, and can still add text or a reply quote to it.
     *
     * A recording too short to have said anything is discarded rather than staged: releasing the button by
     * accident is common, and an unsendable 0.2 s blip in the composer is worse than nothing happening.
     */
    fun stopVoiceRecordingAndStage() {
        if (_voiceRecording.value == null) return
        recordingTicker?.cancel()
        recordingTicker = null
        _voiceRecording.value = null
        // Decide on the *elapsed time* before touching the recorder. A press too short to have encoded a
        // frame is the common fumble, and taking it through stop() is what made it look like a hardware
        // failure: MediaRecorder.stop() throws a bare RuntimeException when the encoder produced nothing,
        // so a tap logged a scary warning and toasted "couldn't record". Cancelling instead resets the
        // recorder cleanly and says the one useful thing — hold the button.
        val tooShort = recorder.elapsedMs() < MIN_VOICE_MS
        if (tooShort) {
            recorder.cancel()
            _events.tryEmit(R.string.chat_voice_too_short)
            return
        }
        viewModelScope.launch {
            val bytes = recorder.stop()
            if (bytes == null) {
                _events.tryEmit(R.string.chat_voice_record_failed)
                return@launch
            }
            // Second gate, on the bytes rather than the clock: the encoder can lag the button, so a press
            // held just past the threshold may still have produced less audio than it looked like.
            // durationMs is pure header arithmetic, so this costs nothing.
            if ((VoiceAudio.durationMs(bytes) ?: 0) < MIN_VOICE_MS) {
                _events.tryEmit(R.string.chat_voice_too_short)
                return@launch
            }
            when (val result = attachments.ingestVoice(bytes)) {
                is AttachmentStore.IngestResult.Success -> {
                    // The description rides on the staged attachment itself: the review row reads it from
                    // there, and MeshManager writes it onto the row it creates, against the (possibly
                    // ciphertext) hash that row will actually hold.
                    _pendingAttachment.value = result.ingested
                }

                is AttachmentStore.IngestResult.Failed -> {
                    _events.tryEmit(R.string.chat_voice_record_failed)
                }
            }
        }
    }

    /** Abandons an in-progress recording — the user slid to cancel. Nothing is ingested, so there's no GC. */
    fun cancelVoiceRecording() {
        recordingTicker?.cancel()
        recordingTicker = null
        _voiceRecording.value = null
        recorder.cancel()
    }

    /** Plays (or pauses, when it's already the loaded note) the voice note stored under [hash]. */
    fun playVoice(
        hash: String,
        key: String?,
    ) = voicePlayer.play(hash, key)

    /** Scrubs the loaded voice note to [positionMs]; ignored unless [hash] is the note that is loaded. */
    fun seekVoice(
        hash: String,
        positionMs: Int,
    ) = voicePlayer.seek(hash, positionMs)

    /** The user confirmed the explicit-image warning: stage the (already-ingested) image for sending. */
    fun confirmFlaggedAttachment() {
        _pendingAttachment.value = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
    }

    /** The user declined the explicit-image warning: drop it and GC the ingested-but-unsent blob. */
    fun dismissFlaggedAttachment() {
        val pending = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /**
     * Discards the staged attachment; its blob (ingested on pick or on finishing a recording) is GC'd unless
     * a sent message references it. A staged voice note's description rides on the attachment itself, so it
     * goes with it — nothing separate to clear.
     */
    fun clearAttachment() {
        val pending = _pendingAttachment.value ?: return
        _pendingAttachment.value = null
        // Removing a card is a decision about this draft: the same link is not fetched again until the draft
        // is emptied or sent, and a fetch still running for it is cancelled by the epoch bump.
        pending.link?.let { card ->
            dismissedUrl = card.url
            draftEpoch.value++
        }
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /**
     * Exports the attachment blob [hash] to the public `Pictures/Knit` folder and toasts the result.
     *
     * [key] and [mime] come from the message row the user tapped, which is exactly what
     * [app.getknit.knit.ui.image.BlobFetcher] takes to render that bubble — so what gets saved is what is
     * on screen, by construction. Both matter:
     *
     * - A DM/group attachment's stored blob is `iv || ciphertext` ([AttachmentCrypto]), content-addressed
     *   by the *ciphertext* hash, so it has to be opened before it leaves the app. Without [key] this
     *   wrote 300 KB of ciphertext into the gallery under an image mime and reported success.
     * - `blobs.mime` describes those stored (ciphertext) bytes and is only ever as good as whatever named
     *   the blob when it landed — since ADR 035 a fetcher default on the spool path rather than the frame.
     *   The row's mime is the plaintext's own type; the blob row is just the fallback.
     */
    fun saveAttachment(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        viewModelScope.launch {
            val raw = blobs.bytes(hash)
            val bytes = if (key != null && raw != null) AttachmentCrypto.open(raw, b64d(key)) else raw
            val type = mime ?: blobs.mimeFor(hash)
            val ok = bytes != null && type != null && gallerySaver.saveToPictures(bytes, hash, type)
            _events.tryEmit(if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed)
        }
    }

    /**
     * Writes a received **file** attachment to the document [dest] the user just picked, decrypting it on the
     * way exactly as [saveAttachment] does.
     *
     * Saving is deliberately the only exit a file has. Knit does not hand one to another app to *open*: that
     * would need a content provider serving decrypted bytes, and ADR 029's invariant — attachment plaintext
     * lives in the encrypted blob store and nowhere else — is worth more than the convenience. Through the
     * storage picker the bytes go straight from the blob into the stream the user chose, still never landing
     * in our own storage; and an app package the recipient saves still has to clear the platform's own
     * unknown-sources gate before anything can install it.
     */
    fun saveAttachmentTo(
        hash: String,
        key: String?,
        dest: Uri,
    ) {
        viewModelScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    val raw = blobs.bytes(hash)
                    val bytes = if (key != null && raw != null) AttachmentCrypto.open(raw, b64d(key)) else raw
                    bytes != null &&
                        runCatching {
                            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) } != null
                        }.getOrDefault(false)
                }
            _events.tryEmit(if (ok) R.string.chat_file_saved else R.string.chat_file_save_failed)
        }
    }

    /** A message's text was copied to the clipboard; surface the confirmation toast. */
    fun onMessageCopied() {
        _events.tryEmit(R.string.chat_message_copied)
    }

    /** Chat is on screen: suppress this conversation's notifications and clear any active one (the user is reading). */
    fun onChatForeground() {
        chatForeground.value = true
        notifier.setVisibleConversation(conversationId)
    }

    /** Chat left the screen: resume notifying for this conversation's incoming messages. */
    fun onChatBackground() {
        chatForeground.value = false
        notifier.setVisibleConversation(null)
    }

    // Wall clock of the last typing cue we sent, so we throttle to at most one per TYPING_SEND_INTERVAL_MS
    // while the user edits (see onUserTyping). Main-thread-confined (the screen's snapshotFlow collector).
    private var lastTypingSentAt = 0L

    /**
     * The user changed the (non-empty) draft: emit a best-effort "now typing" cue, throttled to at most one per
     * [TYPING_SEND_INTERVAL_MS] and only while the chat is foregrounded. Fires immediately on the first keystroke
     * after an idle gap (the throttle window has elapsed), so the indicator appears promptly on the other side.
     * Cheap and fire-and-forget — the screen may call this on every keystroke.
     */
    fun onUserTyping() {
        // Never in the bridged room: there is nobody on the far side to show a cue to, and the frame it would
        // mint carries no room of its own, so `MeshManager.sendTyping` would publish it as a *Nearby* cue.
        if (isBridged) return
        val now = System.currentTimeMillis()
        if (!chatForeground.value || now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) return
        lastTypingSentAt = now
        viewModelScope.launch { meshManager.sendTyping(conversationId) }
    }

    /**
     * Releases the microphone and silences playback when the chat goes away. The recorder holds an exclusive
     * system resource that no other app can take back, so an abandoned recording must not outlive the screen
     * that started it; playback stops because a voice note continuing to sound from a thread the user has
     * navigated away from reads as a bug, not a feature.
     */
    override fun onCleared() {
        recordingTicker?.cancel()
        recorder.cancel()
        voicePlayer.stop()
    }

    private companion object {
        /** How long the draft must rest on a link before its card is fetched. */
        const val PREVIEW_DEBOUNCE_MS = 600L

        /** Send a typing cue at most this often while actively editing (< the receiver's ~12 s hold, so a peer
         *  who keeps typing re-cues before their indicator would expire). */
        const val TYPING_SEND_INTERVAL_MS = 8_000L

        /** Recording UI refresh — fast enough for a level meter to look live, slow enough to stay cheap. */
        const val VOICE_TICK_MS = 60L

        /**
         * Shortest voice note worth staging. Below this it is a fumbled press rather than speech, and
         * discarding it silently beats leaving an unsendable blip in the composer.
         */
        const val MIN_VOICE_MS = 700
    }
}
