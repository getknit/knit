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
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupFaces
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.meshRoomChannel
import app.getknit.knit.data.message.newestOriginChannel
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.relay.attachmentReach
import app.getknit.knit.data.relay.attachmentWait
import app.getknit.knit.data.relay.noticeFor
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.relay.reachFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.PeerLabel
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.linkpreview.LinkPreviewPolicy
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.GeoUri
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationFixPolicy
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.location.LocationSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.PublicPostPolicy
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.transfer.OfferOutcome
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.voice.VoicePlayer
import app.getknit.knit.ui.voice.VoiceRecorder
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // True when this row's author is a Knit peer whose pinned key the local user confirmed out of band
    // ([app.getknit.knit.data.peer.PeerEntity.verified]), in a thread where the name above a bubble is the
    // only thing that says who wrote it — a group. A DM says it once in the header instead, and our own
    // rows never say it at all. Never set on a heard Meshtastic post: that shield is [MeshOrigin.verified]'s,
    // and it vouches for a radio rather than for a person.
    val senderVerified: Boolean = false,
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
    // While the bytes are missing, whether the Internet plane can still bring them — the placeholder's
    // second line (work item 50). [AttachmentWait.Nearby] once they are here or when there is nothing to add.
    val attachmentWait: AttachmentWait = AttachmentWait.Nearby,
    // The decoded link-preview card when this attachment is one ([attachmentMime] is the card MIME), its blob
    // is here, it decoded, and — the receiver's guard against a card attached to a link it does not describe —
    // its link is one the body actually contains. Null until then: the bubble draws nothing for a card that
    // has not arrived, never a spinner, since the body's own link is already tappable.
    val linkCard: LinkCard? = null,
    // The position this message carries as a `geo:` token in its body ([app.getknit.knit.location.GeoUri]),
    // parsed once here so the bubble draws a card in the token's place and the token itself stays out of
    // the text it shows. Null for every body without one — including a token that fails the grammar, which
    // is then just text.
    val location: GeoPoint? = null,
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
    // Set only on a post heard on the paired radio's channel, and the flag the bubble reads to render one
    // differently: a muted name, the provenance line, and an avatar that never opens a profile directly —
    // inert for a stranger, and for a resolved contact ([MeshOrigin.peerId]) a tap that opens the caveat
    // about the match first. The one exception is a match the radio's own signature verified
    // ([MeshOrigin.verified]), which is drawn like a Knit author's. Null on every ordinary row, including
    // our own.
    val origin: MeshOrigin? = null,
    // A direct-transfer record ([MessageEntity.KIND_FILE_TRANSFER]) as its card draws it — the row's facts
    // overlaid with live progress. Null on every other row; a row carrying one is a card, never a bubble.
    val transfer: TransferView? = null,
)

/**
 * Who said a post on the radio channel, and how it reached this board — the render-time shape of
 * [MessageEntity]'s `origin*` columns.
 *
 * Everything here is **unauthenticated unless [signed] says otherwise**. A Meshtastic node number and name
 * are self-asserted on an open channel and are trivially spoofable, so [name] is a claim rather than an
 * identity and the UI must never let it look like a Knit peer — and that includes [peerId]: a contact
 * resolved by node number is a match against a self-asserted profile field, so the bubble wears their name
 * and face but keeps the unverified styling, and their avatar reaches that profile only through the caveat.
 * [MeshSignature.CONTACT] is the one state Knit vouches for: the post carried the radio's XEdDSA signature
 * (Meshtastic 2.8) and it verified under the key that contact's own signed profile names, so the bubble
 * takes a Knit author's styling and a shield. Even then it is the *radio* that is proven, not the hands.
 */
data class MeshOrigin(
    /** The speaker's `!hex` id — the only stable handle a heard author has. */
    val nodeLabel: String,
    /** `User.long_name` if the board's NodeDB knew one, else null and the id stands alone. */
    val name: String?,
    /** The contact whose profile claimed the speaker's board when the post was heard, or null for a stranger. */
    val peerId: String?,
    val hops: Int?,
    /** Signal-to-noise at this board, in tenths of a dB. */
    val snrDeci: Int?,
    /** The post entered the mesh over an MQTT uplink, so it may have come from anywhere. */
    val viaMqtt: Boolean,
    /** What the post's signature proved at ingest; frozen on the row like [peerId]. */
    val signed: MeshSignature = MeshSignature.NONE,
) {
    /** The match is a verified one: the words came from the radio [peerId]'s own profile names. */
    val verified: Boolean get() = signed == MeshSignature.CONTACT
}

/**
 * What a heard post's XEdDSA signature proved — the render-time mirror of `MessageEntity.originSigned`,
 * decided once at ingest and frozen on the row. Only [CONTACT] changes how a post is drawn.
 */
enum class MeshSignature {
    /** No usable signature: unsigned (pre-2.8 firmware, or a post past the signature cliff), or nothing to check it against. */
    NONE,

    /** Our own board verified it against the key its NodeDB holds for the number: the radio that has been using it sent it. */
    BOARD,

    /** Verified on this phone under the key the matched contact's own signed profile advertises. */
    CONTACT,

    /** Signed, but not under the key the contact's profile names — some other radio is on their number. Drawn as a stranger. */
    MISMATCH,
    ;

    companion object {
        /** The row's `originSigned` value; anything a newer build might store reads as [NONE], never as trust. */
        fun fromRow(value: Int): MeshSignature =
            when (value) {
                MessageEntity.ORIGIN_SIGNED_BY_BOARD -> BOARD
                MessageEntity.ORIGIN_SIGNED_BY_CONTACT -> CONTACT
                MessageEntity.ORIGIN_SIGNATURE_MISMATCH -> MISMATCH
                else -> NONE
            }
    }
}

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

/**
 * A direct-transfer disclosure waiting to be read, and the tap it stands in front of: [transferId] is the
 * offer to accept once it is read, or null when the user was on their way to the file picker. Held rather
 * than re-derived because the sheet outlives the tap that raised it.
 */
data class TransferConsent(
    val incoming: Boolean,
    val transferId: String? = null,
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
    // The other members a photo-less group's header avatar draws as a cluster (`groupFaces`, ADR
    // 2026-09.zapp); empty for a DM or a group with fewer than two others.
    val groupFaces: List<GroupFace> = emptyList(),
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
    // Whether the Meshtastic room's composer may post from this device, and if not why ([publicPostGateFor]).
    // [PublicPostGate.Open] everywhere else, and as the seed — a send that beats the first emission is judged
    // by the board's own outcome rather than by a stale gate.
    val publicPostGate: PublicPostGate = PublicPostGate.Open,
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
    // True when this thread is the **Meshtastic room** — the paired radio's primary channel, mirrored into a
    // room on this phone. It is a public room like Nearby, and shares its glyph and its "no attachments"
    // refusal, but its authors are not Knit peers: no verified badge, nothing to tap through to, and a contact
    // it lines a post up with is still an unverified match. Distinct from [isRoom] because almost every rule
    // that reads that flag is really asking "is this Nearby", and answering yes here would put a stranger's
    // unauthenticated name wherever Knit shows a person it vouches for.
    val isBridged: Boolean = false,
    // True when this thread is a **commons** — a relay's private room (§7.4). A room: no attachments, no
    // location, no link cards, no typing cue; but its authors are pinned peers, so every bubble
    // wears a real name and avatar and taps through to a profile.
    val isCommons: Boolean = false,
    // The UTF-8 bytes a post's words may occupy here, or null in every thread that is not the Meshtastic
    // room. A hard cap rather than the soft LoRa length hint: a Meshtastic frame carries one short line and
    // the transmit path trims a longer one without asking, so the field refuses the overflow while the
    // author can still choose what to cut. The whole line is the author's, since no name rides in front of
    // it (ADR 2026-09.9469).
    val publicPostBudget: Int? = null,
    // Whether the radio channel this room mirrors is keyed with the stock Meshtastic key everybody holds
    // ([LoraFacts.primaryKeyIsPublic]). It picks the wording of the standing notice and the composer hint —
    // "unencrypted" where the key is public, "not end-to-end encrypted" where the user set their own — so
    // the room never borrows the assurance the padlock gives every other thread. True where there is no
    // board to ask, which is the pessimistic half of the claim rather than the accurate one.
    val publicChannelKeyIsPublic: Boolean = true,
    // Whether a post here still needs the first-use disclosure. Read only by [isBridged] threads.
    val needsPublicConsent: Boolean = false,
    // Whether the thread holds messages older than the loaded window, so the screen can draw a loading row
    // above the oldest bubble and ask for the next page as the reader reaches it. See [ChatWindow].
    val hasOlder: Boolean = false,
    // True only for the initial seed value (see [state]'s stateIn below), before the Room + DataStore +
    // mesh flows have all first-emitted. A long thread takes long enough to read and fold into rows that
    // the seed's empty list would otherwise render as "no messages yet" on a conversation that has
    // hundreds — so the screen shows a bubble skeleton for that gap instead. Defaults false so every real
    // combine emission — and the previews — render content; only the seed passes true.
    val isLoading: Boolean = false,
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
    // Unsent composer text. App-scoped, not a field of this ViewModel: the write that keeps a draft is the
    // one started as the user leaves, which is exactly when this scope is being cancelled.
    private val drafts: DraftRepository,
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
    // Where "Send location" reads the device's position. Collected by the staged tile alone, between the pin
    // tap and the send — see [startLocation] — and by nothing else in the app.
    private val locationSource: LocationSource,
    // The facts flow, not the repository that produces it. Narrow on purpose: this ViewModel needs a
    // Flow<RelayFacts> and nothing else, and the production flow is an infinite poller — under a test's
    // virtual clock its `delay` is instant, so a test that drives this VM with `advanceUntilIdle()` could
    // never reach idle. Taking the flow lets a test supply a finite one.
    private val relayFacts: Flow<RelayFacts>,
    // The LoRa plane's facts, the same way (a pushed flow in production, but a test still supplies its own).
    private val loraFacts: Flow<LoraFacts>,
    private val context: Context,
    // Direct Wi-Fi transfers: the app-wide state machine whose live states overlay this thread's records.
    private val transfers: TransferManager,
    // The joined commons (docs/SPOOL_PROTOCOL.md §7.4): a room's name and members, for a commons thread.
    // Nullable and last so every existing test rig that builds this ViewModel positionally still compiles;
    // a plain parameter, because only the [publicIdentity] initializer reads it.
    commons: CommonsRepository? = null,
) : ViewModel() {
    /** This thread is the broadcast room (vs a 1:1 DM keyed by the peer's node id). */
    private val isRoom = conversationId == Conversations.NEARBY

    /** This thread is the Meshtastic room — the paired radio's primary channel; its authors are not peers. */
    private val isBridged = conversationId == Conversations.MESHTASTIC

    /**
     * This thread is a commons — a relay's private room (§7.4). A room like Nearby (addressed to nobody,
     * names on every bubble, text only) whose authors are pinned peers like a group's, so their names,
     * avatars and profiles are real; it has no roster to gate on, and its ticks take the group's route.
     */
    private val isCommons = Conversations.kindFor(conversationId) == ConversationKind.COMMONS

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

    /** The composer's text as typed, fed by the screen so a link in it can grow a card. */
    private val draft = MutableStateFlow("")

    /**
     * What this thread's composer was left with last time, read once at construction. Held as a [Deferred]
     * because the screen has to *wait* for it: the composer reports its empty field the moment it composes,
     * and persisting that report before this read lands would erase the very draft it is fetching.
     */
    private val storedDraft: Deferred<String> = viewModelScope.async { drafts.load(conversationId) }

    /**
     * The text this thread's drafts row holds — or is scheduled to hold, since writes are debounced — and
     * null until the screen has taken [storedDraft] (see [consumeRestoredDraft]). Until then no draft report
     * is persisted, so a chat opened and left again before the read lands keeps what was in it.
     *
     * A report equal to it is not an edit. The composer's collectors cannot tell a keystroke from a
     * programmatic set, so the restore itself comes back through [onDraftChanged] — on whichever side of the
     * collector's start the read lands — and so does the fresh initial snapshot after a recomposition (a
     * rotation, a profile screen popped off the chat). Persisting either would re-stamp the row's `updatedAt`
     * and float `Draft: …` back over a message that landed after it. Main-thread-confined, like [dismissedUrl].
     */
    private var persisted: String? = null

    /**
     * The text [consumeRestoredDraft] handed to the composer, until the user types past it. Read and cleared
     * only by [onUserTyping], so the draft collector racing the typing one cannot change its answer.
     */
    private var restored: String? = null

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

    /**
     * The rows the window returned, paired with the limit that produced them. The pairing is load-bearing:
     * [ChatUiState.hasOlder] compares the two, and reading [windowLimit] on its own would measure the *next*
     * limit against the *previous* rows for the moment between a page being asked for and arriving — long
     * enough to blink the "loading older" row out from under the user mid-page.
     */
    private data class Window(
        val limit: Int,
        val messages: List<MessageEntity>,
    )

    // How far back into the thread the screen currently reads. Grown a page at a time by [loadOlder] as the
    // user scrolls into history, or jumped straight to a depth by [revealMessage] for a tapped reply quote.
    private val windowLimit = MutableStateFlow(ChatWindow.INITIAL)

    /**
     * This thread's window: the newest [windowLimit] messages, oldest first. **The screen's only subscription
     * to the messages table** — the link-preview walk, the read watermark and the row fold all read this one.
     * Three separate collectors meant Room re-ran the query and rebuilt the whole list three times over for
     * every write to `messages` anywhere in the app, since invalidation is per-table, not per-query.
     *
     * A `StateFlow` rather than a `shareIn` so [loadOlder] can see the window that actually rendered; null
     * until the first emission, which every consumer drops with `filterNotNull()`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val windowed: StateFlow<Window?> =
        windowLimit
            // No distinctUntilChanged: a StateFlow already conflates equal values, so re-collecting the same
            // limit is impossible and the operator is a deprecated no-op on this receiver.
            .flatMapLatest { limit ->
                messages.observeNewestMessages(conversationId, limit).map { Window(limit, it) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The hashes this screen can show a size for: every attachment in the window plus the one staged in the
    // composer. The raw window rather than the blocked-filtered rows — a superset costs nothing and keeps the
    // derivation off the block list. Distinct, so a message write that leaves the set alone (most of them)
    // never re-subscribes the blob read below.
    private val heldHashes: Flow<Set<String>> =
        combine(
            windowed.filterNotNull().map { window -> window.messages.mapNotNull { it.attachmentHash }.toSet() },
            _pendingAttachment.map { it?.hash },
        ) { window, staged -> if (staged == null) window else window + staged }.distinctUntilChanged()

    /**
     * Hash → held byte length for [heldHashes]. **The screen's only subscription to the blobs table** — the
     * row fold, the link-card walk and the staged attachment's reach all read this one. Two collectors of
     * the old whole-table `observeSizes()` meant Room decrypted every leaf page of the largest table in the
     * database twice per blob write anywhere in the app (an attachment or avatar landing, a send) for as
     * long as any chat was open; bounded to the window it is one primary-key seek per shown attachment, and
     * a thread with nothing to size holds no subscription at all. Shaped like [windowed]: null until the
     * first emission, which every consumer drops with `filterNotNull()`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val heldSizes: StateFlow<Map<String, Int>?> =
        heldHashes
            .flatMapLatest { blobs.observeSizes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Held blob sizes + moderation-flagged hashes plus the content-filtering setting, and the decoded
    // link-preview cards, combined upstream so the main bundle stays at the typed 5-flow combine overload.
    // The setting only gates receive-side *hiding* (the chat blur + toxic-text collapse below), so toggling
    // it reactively reveals/hides already-received content without re-screening; what you can send is
    // enforced elsewhere regardless.
    private val blobState =
        combine(
            heldSizes.filterNotNull(),
            imageScreening.observeFlaggedHashes(),
            settings.contentFilteringEnabled,
            linkCards.cards,
            transfers.states,
        ) { sizes, flagged, hideSensitive, cards, live ->
            BlobState(sizes, flagged.toSet(), hideSensitive, cards, live)
        }

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        watchDraftForLinks()
        // Decode every link-preview card in this thread whose blob has landed, once; the store dedups and the
        // rows pick the result up through blobState. The pair list is distinct so a size change elsewhere in
        // the table does not re-walk the thread.
        viewModelScope.launch {
            combine(windowed.filterNotNull(), blobState) { window, blob ->
                window.messages
                    .filter { it.attachmentMime == LinkPreviewBlob.MIME && it.attachmentHash != null && it.attachmentHash in blob.sizes }
                    .map { it.attachmentHash!! to it.attachmentKey }
            }.distinctUntilChanged().collect { held ->
                held.forEach { (hash, key) -> linkCards.ensure(hash, key) }
            }
        }
        // Advance this conversation's read watermark while the chat is on screen: on every stream
        // emission (so messages arriving while you read don't reappear as unread), stamp newest sentAt.
        viewModelScope.launch {
            combine(chatForeground, windowed.filterNotNull()) { foreground, window ->
                // The window is anchored at the newest end and handed over oldest-first, so its last row is
                // the thread's newest message — no scan. Deliberately the *raw* window rather than the
                // blocked-filtered rows: if a blocked peer sent the newest message, skipping it here would
                // leave the thread showing unread forever.
                if (foreground) window.messages.lastOrNull()?.sentAt else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setLastReadAt(conversationId, watermark)
            }
        }
    }

    // Bundles the four message-related streams so the outer combine below stays at the 5-flow typed
    // overload (a 6th flow falls back to unchecked Array<*> casts). Blocked senders' messages are
    // filtered out here, so they also drop out of rows and mention candidates. Carrying the window's
    // blob sizes here is what flips an attachment from "loading" to shown when its bytes arrive — and,
    // since the same rows carry the byte length, what tells the UI whether those bytes can cross a relay.
    private data class MessagesBundle(
        val messages: List<MessageEntity>,
        // Everyone who has ever posted here, straight from the table rather than from [messages] — the
        // window only reaches back so far, and who you can @-mention must not depend on how far you scrolled.
        val senders: List<String>,
        // Whether the thread holds anything older than the window. Computed on the *raw* window, before the
        // blocked filter below: a window made entirely of blocked senders folds to zero rows, and measuring
        // that would report an empty thread with all its history still sitting behind it.
        val hasOlder: Boolean,
        val reactions: List<ReactionEntity>,
        val blocked: Set<String>,
        val blobSizes: Map<String, Int>,
        val flaggedHashes: Set<String>,
        val hideSensitiveContent: Boolean,
        // blob hash -> the decoded link-preview card, for every card this process has opened so far.
        val linkCards: Map<String, LinkCard>,
        // transfer id -> its live state, for the direct-transfer cards in this thread.
        val transfers: Map<String, TransferState>,
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
        val transfers: Map<String, TransferState>,
    )

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

    // The window paired with this thread's full sender list, folded first so the bundle below stays at the
    // typed 5-flow combine overload.
    private val windowWithSenders =
        combine(
            windowed.filterNotNull(),
            messages.observeSendersIn(conversationId),
        ) { window, senders -> window to senders }

    private val messagesWithReactions =
        combine(
            windowWithSenders,
            reactions.observeReactionsIn(conversationId),
            settings.blockedNodeIds,
            blobState,
            groupDelivery,
        ) { (window, senders), reacts, blocked, blob, (group, delivered) ->
            MessagesBundle(
                window.messages.filter { it.senderId !in blocked },
                senders.filter { it !in blocked },
                window.messages.size >= window.limit,
                reacts,
                blocked,
                blob.sizes,
                blob.flagged,
                blob.hideSensitive,
                blob.linkCards,
                blob.transfers,
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
         * The Meshtastic room, which has no LoRa audience to speak of: a post leaves through this phone's own
         * board and a spent window is answered per send (a refusal the composer shows), so no standing
         * congestion notice about delayed delivery applies. Its own object rather than reusing [Room]: that
         * notice is about Knit's queue toward LoRa-only peers, and this room has no peers.
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

                    // A commons post never leaves by a radio at all, so it has no LoRa audience either.
                    ConversationKind.MESHTASTIC, ConversationKind.COMMONS -> LoraAudience.Bridged
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

    // Folded ahead of the state combine, whose typed arity stops at five: who this device posts to a room
    // as, whether it may post to the foreign public channel, and — for a commons thread — the room's row and
    // its members. The two commons flows are empty everywhere else (`flowOf`), so they cost nothing there.
    private class PublicInputs(
        val myName: String,
        val publicConsented: Boolean,
        val commonsRoom: CommonsEntity?,
        val commonsMembers: List<String>,
    )

    private val publicIdentity: Flow<PublicInputs> =
        combine(
            settings.displayName,
            settings.meshtasticPostConsented,
            if (isCommons) commons?.observe(conversationId) ?: flowOf(null) else flowOf(null),
            if (isCommons) commons?.observeMemberIds(conversationId) ?: flowOf(emptyList()) else flowOf(emptyList()),
        ) { name, consented, room, roster -> PublicInputs(name, consented, room, roster) }

    val state: StateFlow<ChatUiState> =
        combine(
            messagesWithReactions,
            peers.observeDirectory(),
            meshStatus,
            myNodeId,
            publicIdentity,
        ) { bundle, directory, mesh, me, publicId ->
            val myName = publicId.myName
            val publicConsented = publicId.publicConsented
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
            val liveTransfers = bundle.transfers
            val group = bundle.group
            val deliveredCounts = bundle.deliveredCounts
            val isGroup = group != null
            // A thread with one peer behind it — the only shape that can be blocked or verified.
            val isPeerThread = !isRoom && !isBridged && !isCommons && !isGroup
            // A group's pinned roster, or a commons' seen members: both feed the @-mention candidates.
            val members = group?.let { GroupMembersStore.decode(it.members) } ?: publicId.commonsMembers
            val peersByNode = directory.byNode
            // Group once, then tally per emoji within each message's bucket. Orphan reactions (no matching
            // message yet) simply never produce a row until their message arrives.
            val reactionsByMessage = reacts.groupBy { it.messageId }
            // Resolve each node id's label once for the whole fold. For an id absent from the peer table —
            // every speaker in the Nearby and bridged rooms — PeerLabels has nothing cached and re-derives
            // the alias tokens and runs its collision loop, and the rows below ask up to twice per message
            // before the candidates and typing peers ask again.
            val labels = HashMap<String, PeerLabel>()

            fun label(nodeId: String) = labels.getOrPut(nodeId) { directory.label(nodeId) }
            val rows =
                msgs.map { m ->
                    // A heard post's author is the Meshtastic speaker, NOT the row's sender — that is this
                    // phone, by convention. Resolve the origin first so the name, avatar and tap target below
                    // all follow from the same answer. A speaker whose board a contact's profile claimed
                    // wears that contact's name and face; an unverified match unless the radio's signature
                    // checked out (`MeshOrigin.verified`), so the bubble keeps the muted name and the
                    // caveat-first avatar until it did.
                    val origin = m.originNode?.let { node -> meshOriginFor(m, node) }
                    val mine = m.senderId == me && origin == null
                    val contact = origin?.peerId?.let { label(it) }
                    val senderLabel = if (mine || origin != null) null else label(m.senderId)
                    val name =
                        contact?.text
                            ?: origin?.let { it.name ?: it.nodeLabel }
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
                        senderDiscriminator = contact?.discriminator ?: senderLabel?.discriminator,
                        senderPlainName = contact?.name ?: senderLabel?.name ?: name,
                        // Groups only: the room's speakers are whoever is in range, the bridged room's are
                        // not Knit peers at all, and a DM's header already carries the badge.
                        senderVerified = isGroup && !mine && origin == null && peersByNode[m.senderId]?.verified == true,
                        kind = m.kind,
                        // Never our own avatar on a heard post (the sender column is ours by convention):
                        // the resolved contact's face where there is one, else the letter avatar.
                        avatarHash =
                            when {
                                origin != null -> origin.peerId?.let { peersByNode[it]?.avatarHash }
                                else -> peersByNode[m.senderId]?.avatarHash
                            },
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
                        // The mirror image: only while the bytes are missing, and only for a bubble that
                        // draws a placeholder — a link card never spins, so it has nothing to wait on.
                        attachmentWait =
                            if (m.attachmentHash != null && heldBytes == null && m.attachmentMime != LinkPreviewBlob.MIME) {
                                attachmentWait(conversationId, relay)
                            } else {
                                AttachmentWait.Nearby
                            },
                        linkCard = linkCardFor(m, cards),
                        location = GeoUri.find(m.body),
                        mentions = MentionStore.decode(m.mentions),
                        reactions = tallies,
                        replyTo = m.replyRef(),
                        origin = origin,
                        transfer = if (m.kind == MessageEntity.KIND_FILE_TRANSFER) transferViewFor(m, liveTransfers) else null,
                    )
                }
            // Autocomplete candidates: everyone we've received a message from, plus a group's roster (so
            // @-mentions work in a fresh group before anyone has spoken), resolved to a display name.
            val candidates =
                (bundle.senders + members)
                    .asSequence()
                    .filter { it != me && it !in blocked }
                    .distinct()
                    .map { id ->
                        val resolved = label(id)
                        MentionCandidate(
                            nodeId = id,
                            displayName = resolved.text,
                            avatarHash = peersByNode[id]?.avatarHash,
                            alias = resolved.alias,
                            discriminator = resolved.discriminator,
                        )
                    }.sortedBy { it.displayName.lowercase() }
                    .toList()
            // The header's cluster: the roster minus us, by node id. Up to four directory lookups, so the
            // memoised label() above is not worth threading through.
            val faces = if (isGroup) groupFaces(members, me, directory) else emptyList()
            // Peers typing in THIS thread, resolved for the indicator row. Skip ourselves (defensive — our
            // own cue never lands here) and blocked senders (as their messages are already filtered out).
            val typingPeers =
                typingMap[conversationId]
                    .orEmpty()
                    .asSequence()
                    .filter { it != me && it !in blocked }
                    .map { id -> TypingPeer(id, label(id).text, peersByNode[id]?.avatarHash) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
            ChatUiState(
                rows = rows,
                hasOlder = bundle.hasOlder,
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
                            ) { id -> label(id).text }
                        }

                        isRoom -> {
                            context.getString(R.string.nearby_title)
                        }

                        // The live board's primary channel (`LongFast`, `MediumFast`, or a name the user
                        // gave it), else the newest post's, else the generic label — one rule with the
                        // chat list (`meshRoomChannel`), so the two agree.
                        isBridged -> {
                            meshRoomChannel(mesh.lora.facts.primaryChannel, newestOriginChannel(msgs))
                                ?: context.getString(R.string.meshtastic_title)
                        }

                        // The relay's advertised name, one rule with the chat list (`conversationTitle`).
                        isCommons -> {
                            publicId.commonsRoom?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.commons_title)
                        }

                        else -> {
                            label(conversationId).text
                        }
                    },
                titleDiscriminator = if (isPeerThread) label(conversationId).discriminator else null,
                // A room uses a glyph; a group shows its photo (or the glyph when unset); a DM the peer avatar.
                avatarHash =
                    when {
                        isRoom || isBridged || isCommons -> null
                        else -> group?.photoHash ?: peersByNode[conversationId]?.avatarHash
                    },
                canSendFile = !isRoom && !isBridged && !isCommons,
                isBridged = isBridged,
                isCommons = isCommons,
                // 166 bytes on a board that signs (so every post leaves signed), the 200-byte client convention on one that does not.
                publicPostBudget = if (isBridged) PublicPostPolicy.onAirBudget(mesh.lora.facts.signs) else null,
                publicChannelKeyIsPublic = mesh.lora.facts.primaryKeyIsPublic,
                needsPublicConsent = isBridged && !publicConsented,
                isBlocked = isPeerThread && conversationId in blocked,
                verified = isPeerThread && peersByNode[conversationId]?.verified == true,
                isGroup = isGroup,
                memberCount = members.size,
                groupFaces = faces,
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

                        // A post here leaves through this phone's own board, and a spent window is answered
                        // per send; what the room says about itself is a static strip, not a reach state.
                        LoraAudience.Bridged -> {
                            LoraReach.Silent
                        }
                    },
                loraCarry = loraCarryFor(conversationId, isGroup, mesh.lora.facts),
                publicPostGate = publicPostGateFor(conversationId, mesh.lora.facts),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom, isLoading = true))

    /** The render shape of a heard post's attribution — the row's `origin*` columns, the `!hex` id derived. */
    private fun meshOriginFor(
        m: MessageEntity,
        node: Long,
    ) = MeshOrigin(
        nodeLabel = meshNodeLabel(node),
        name = m.originName?.takeIf { it.isNotBlank() },
        peerId = m.originPeerId,
        hops = m.originHops,
        snrDeci = m.originSnrDeci,
        viaMqtt = m.originViaMqtt,
        signed = MeshSignature.fromRow(m.originSigned),
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
     * stored the bytes by the time an image is staged, so the row is there to be read, through the same
     * [heldSizes] the rows use ([heldHashes] folds the staged hash in).
     */
    val stagedAttachmentRelay: StateFlow<AttachmentRelay> =
        combine(
            _pendingAttachment,
            heldSizes.filterNotNull(),
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
        // Keep it for next time (debounced, on the application scope). Blank text drops the row, so emptying
        // the field is how a draft is thrown away deliberately. Text the row already holds is not an edit —
        // see [persisted] for the two ways it comes back.
        val held = persisted ?: return
        if (text == held) return
        persisted = text
        drafts.save(conversationId, text)
    }

    /**
     * Hands the screen the text this thread was left with, once, and returns empty on every later call — a
     * rotation re-runs the screen's restore effect, and a draft the user has since cleared must not come
     * back with it. Taking it is also what starts persisting edits: see [onDraftChanged].
     */
    suspend fun consumeRestoredDraft(): String {
        if (persisted != null) return ""
        val text = storedDraft.await()
        persisted = text
        restored = text.ifEmpty { null }
        return text
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
     * was not dismissed or found empty in this draft, and the audience can render one. `collectLatest` cancels a
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
        stageCard(url)
    }

    /**
     * What [send] attaches: whatever is staged, else the card for the draft's first link when the composer
     * would fetch one — waited for, up to [SEND_CARD_HOLD_MS]. A link handed in from the share sheet arrives
     * whole, and Send is the next tap, well inside the debounce and the fetch (the page, the picture, and on a
     * cold start the classifier's model load); without the hold a shared link never carried its card. The send
     * button's spinner covers the wait, and past the bound the text goes alone as it always did. Joins a fetch
     * the loop already has in flight rather than starting a second; [cardWanted] keeps the loop out of one
     * started here.
     */
    private suspend fun attachmentForSend(body: String): AttachmentStore.Ingested? {
        _pendingAttachment.value?.let { return it }
        val url = LinkPreviewPolicy.firstEligible(body) ?: return null
        withTimeoutOrNull(SEND_CARD_HOLD_MS) {
            if (_linkPreviewLoading.value) {
                _linkPreviewLoading.first { !it }
            } else if (cardWanted(url, staged = null, online = linkPreviews.online.value)) {
                stageCard(url)
            }
        }
        return _pendingAttachment.value
    }

    /** Every gate a fetch for [url] has to pass, cheapest first; the audience read comes last because it hits the DB. */
    private suspend fun cardWanted(
        url: String,
        staged: AttachmentStore.Ingested?,
        online: Boolean,
    ): Boolean =
        staged == null &&
            !_linkPreviewLoading.value &&
            url != dismissedUrl &&
            url !in failedUrls &&
            online &&
            settings.linkPreviewsEnabled.first() &&
            // A card is an attachment, and the bridged room takes none — see [stage]. Read from the id rather
            // than from `loraCarry`, which is None here precisely because the room's length rule is its own.
            // A commons takes none either, in this revision. A thread that rides LoRa takes a card exactly as
            // it takes a photo (ADR 2026-09.7x8k): the frame carries its reference under the same body reserve
            // and a board-only reader sees the bare link, which is what the bubble draws for a card it lacks.
            !isBridged &&
            !isCommons &&
            !audienceCannotRenderCards()

    /** One fetch for [url], flagged for the composer's "Loading preview…" line while it runs; stages what it yields. */
    private suspend fun stageCard(url: String) {
        _linkPreviewLoading.value = true
        try {
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
        } finally {
            _linkPreviewLoading.value = false
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
     * A post to the Meshtastic room, which leaves through this phone's own board and nowhere else.
     *
     * The gate comes first — asking for the disclosure with no radio to post through would be a question
     * about nothing — then the first-use disclosure (raised here rather than on opening the room, because a
     * person who only ever reads it should never be asked to decide anything; the draft is kept, so accepting
     * sends what they already wrote), then the board. Every refusal keeps the draft and says why, so a post
     * never silently goes nowhere — the failure the whole room is designed around.
     */
    private fun postPublic(text: String) {
        if (state.value.publicPostGate != PublicPostGate.Open) {
            _events.tryEmit(R.string.chat_mesh_post_not_connected)
            return
        }
        if (state.value.needsPublicConsent) {
            _showPublicConsent.value = true
            return
        }
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch {
            var accepted = false
            try {
                when (val outcome = meshManager.sendPublicPost(text)) {
                    PublicPostOutcome.Queued -> {
                        accepted = true
                        // The same clean slate an accepted chat send leaves: nothing is ever staged here,
                        // but a card fetch still in flight for this draft must not stage into the next.
                        _pendingAttachment.value = null
                        dismissedUrl = null
                        failedUrls.clear()
                        draftEpoch.value++
                        // The post is away; what is about to be cleared from the field is not a draft any more.
                        drafts.clear(conversationId)
                        if (persisted != null) persisted = ""
                        _clearInput.tryEmit(Unit)
                    }

                    PublicPostOutcome.Blocked -> {
                        _events.tryEmit(R.string.moderation_text_blocked)
                    }

                    is PublicPostOutcome.Refused -> {
                        _events.tryEmit(refusalMessage(outcome.reason))
                    }
                }
            } finally {
                if (!accepted) _isSending.value = false
            }
        }
    }

    /** What the composer says for a board's refusal — exhaustive, so a new reason cannot go unsaid. */
    private fun refusalMessage(reason: PublicPostRefusal): Int =
        when (reason) {
            PublicPostRefusal.NO_BOARD, PublicPostRefusal.NOT_READY -> R.string.chat_mesh_post_not_connected

            PublicPostRefusal.KNIT_ON_PRIMARY -> R.string.chat_mesh_post_channel_unusable

            PublicPostRefusal.TOO_SOON -> R.string.chat_mesh_post_too_soon

            PublicPostRefusal.TOO_LARGE -> R.string.chat_mesh_post_too_large

            PublicPostRefusal.NO_AIR -> R.string.chat_mesh_post_no_air

            PublicPostRefusal.NAK -> R.string.chat_mesh_post_refused

            // Only reachable on a screen that outlived its own row (the switch flipped while the thread was
            // open); the row and its composer both go with the setting.
            PublicPostRefusal.ROOM_OFF -> R.string.chat_mesh_post_room_off

            PublicPostRefusal.DEDICATED -> R.string.chat_mesh_post_dedicated
        }

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

    // ---- Location sharing: the composer's pin ----

    /**
     * What the composer holds while a position is staged. [fix] is the best reading so far, null while the
     * first is still sought; [status] is the tile's state; [precision] says whether the grant is fine or only
     * approximate, so the tile can say a two-kilometre radius is by design rather than bad luck.
     */
    data class StagedLocation(
        val fix: LocationFix?,
        val status: Status,
        val precision: LocationPrecision,
    ) {
        enum class Status {
            /** Listening, nothing yet — the one state with a spinner, and the window bounds it. */
            Acquiring,

            /** Listening, with a reading the tile shows and keeps tightening. */
            Refining,

            /** The window closed, or the reading was good enough: the source is off and what is shown is what goes. */
            Ready,

            /** The window closed with no reading at all — indoors, no provider — and the tile offers a retry. */
            Failed,

            /** The system location toggle is off; nothing can answer until the user turns it on. */
            ServicesOff,
        }

        /** The body token this position rides as, or null while there is none. */
        val token: String? get() = fix?.let { GeoUri.format(it.toPoint()) }
    }

    private val _stagedLocation = MutableStateFlow<StagedLocation?>(null)

    /** The position staged in the composer, or null. Its own flow, like [stagedAttachmentRelay]: the composer is the one reader. */
    val stagedLocation: StateFlow<StagedLocation?> = _stagedLocation.asStateFlow()

    private val _showLocationConsent = MutableStateFlow(false)

    /** Whether the pin's first-use disclosure is on screen. See [attachLocation]. */
    val showLocationConsent: StateFlow<Boolean> = _showLocationConsent.asStateFlow()

    /**
     * Fires once the disclosure stands accepted and a position may be sought. The screen answers it by
     * clearing the runtime permission — a composable's job — and then calling [startLocation]. One-shot, like
     * [events], so a screen that is not there to hear it asks nothing.
     */
    private val _locationPermissionNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val locationPermissionNeeded: SharedFlow<Unit> = _locationPermissionNeeded.asSharedFlow()

    private var locationJob: Job? = null

    /** True when the screen leaving cut a window short, so coming back re-arms it. */
    private var locationPaused = false

    /**
     * The pin was tapped. The bridged room refuses (its channel carries one line of text); the first use
     * reads the disclosure; after that the screen is asked to clear the grant and start.
     */
    fun attachLocation() {
        if (isBridged || isCommons) {
            _events.tryEmit(if (isCommons) R.string.chat_commons_text_only else R.string.chat_mesh_text_only)
            return
        }
        viewModelScope.launch {
            if (settings.locationShareConsented.first()) {
                _locationPermissionNeeded.tryEmit(Unit)
            } else {
                _showLocationConsent.value = true
            }
        }
    }

    /**
     * Records the disclosure as accepted, lowers it and carries on to the grant. One tap does both here,
     * unlike [acceptPublicConsent]: the sheet's button says what the pin said, and nothing leaves the phone
     * until the send that follows.
     */
    fun acceptLocationConsent() {
        viewModelScope.launch {
            settings.acceptLocationShareConsent()
            _showLocationConsent.value = false
            _locationPermissionNeeded.tryEmit(Unit)
        }
    }

    /** Lowers the disclosure without recording anything, so the next tap asks again. */
    fun dismissLocationConsent() {
        _showLocationConsent.value = false
    }

    /**
     * The grant is held: seed the tile from the platform's cached reading, then listen for at most
     * [LocationFixPolicy.REFINE_WINDOW_MS] or until a reading is good enough, and freeze. **This is the only
     * place in the app that collects [LocationSource.fixes]** — the invariant "Knit reads your position only
     * when you ask it to" rests on, so a second collector anywhere is a bug.
     */
    fun startLocation() {
        locationJob?.cancel()
        locationPaused = false
        val precision = locationSource.precision()
        if (precision == LocationPrecision.None) {
            _events.tryEmit(R.string.chat_location_denied)
            return
        }
        if (!locationSource.isEnabled()) {
            _stagedLocation.value = StagedLocation(fix = null, status = StagedLocation.Status.ServicesOff, precision = precision)
            return
        }
        _stagedLocation.value = StagedLocation(fix = null, status = StagedLocation.Status.Acquiring, precision = precision)
        locationJob =
            viewModelScope.launch {
                locationSource.lastKnown()?.let { seed ->
                    _stagedLocation.value = StagedLocation(seed, StagedLocation.Status.Refining, precision)
                }
                withTimeoutOrNull(LocationFixPolicy.REFINE_WINDOW_MS) {
                    locationSource
                        .fixes()
                        .map { fix -> LocationFixPolicy.better(_stagedLocation.value?.fix, fix) }
                        .onEach { best -> _stagedLocation.value = StagedLocation(best, StagedLocation.Status.Refining, precision) }
                        .firstOrNull { LocationFixPolicy.isGoodEnough(it) }
                }
                freezeLocation()
            }
    }

    /** Listens again: the tile's retry after a failure, and its refresh once a frozen reading has aged. */
    fun refreshLocation() {
        if (_stagedLocation.value != null) startLocation()
    }

    /** Drops the staged position and stops listening. */
    fun clearLocation() {
        locationJob?.cancel()
        locationJob = null
        locationPaused = false
        _stagedLocation.value = null
    }

    /** The chat left the screen: a tile still listening freezes, so a backgrounded chat never keeps the radio on. */
    private fun pauseLocation() {
        val job = locationJob?.takeIf { it.isActive } ?: return
        job.cancel()
        locationJob = null
        locationPaused = true
        freezeLocation()
    }

    /** Back on screen with a tile the pause froze: a fresh window, since the phone may well have moved. */
    private fun resumeLocation() {
        if (locationPaused && _stagedLocation.value != null) startLocation()
    }

    private fun freezeLocation() {
        _stagedLocation.update { staged ->
            staged?.copy(status = if (staged.fix != null) StagedLocation.Status.Ready else StagedLocation.Status.Failed)
        }
    }

    /**
     * [text] with the staged position folded in, or null when the send must wait: a position still being
     * sought is not sent as nothing — the tile is visibly looking, the send says so, and the draft stays the
     * user's, the rule every refusal here follows.
     *
     * The token goes on its own last line, the text cut first so the token always fits under
     * [TextLimits.MESSAGE]: the receiver clamps the body there (`InboundPipeline`), so a token past the cap
     * would be the part that vanished. Text first, so a build that predates the card reads
     * "See you at the gate / geo:…" in that order.
     */
    private fun bodyWithLocation(
        text: String,
        staged: StagedLocation?,
    ): String? {
        if (staged == null) return text
        val token = staged.token
        if (token == null) {
            _events.tryEmit(R.string.chat_location_not_ready)
            return null
        }
        return listOf(text.take(TextLimits.MESSAGE - token.length - 1), token).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun send(
        text: String,
        mentions: List<Mention> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
        val trimmed = text.trim().take(TextLimits.MESSAGE)
        val staged = _stagedLocation.value
        if (trimmed.isEmpty() && _pendingAttachment.value == null && staged == null) return
        // The Meshtastic room is not addressed to anybody: no recipient, no group, no attachment, no reply.
        // `sendChat` would read that shape as the Nearby room and put the post there, so it has its own path
        // to the board, with its own gate, disclosure and refusals.
        if (isBridged) {
            postPublic(trimmed)
            return
        }
        val body = bodyWithLocation(trimmed, staged) ?: return
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
                val sent = route(body, attachmentForSend(body), mentions, outgoingReply)
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
                    // The position went with the message; nothing is listening any more.
                    clearLocation()
                    // The next draft starts clean: no dismissed link, no failed ones, and a card fetch still in
                    // flight for this one can no longer stage into it.
                    dismissedUrl = null
                    failedUrls.clear()
                    draftEpoch.value++
                    // The message is away; what is about to be cleared from the field is not a draft any more.
                    // Dropped here rather than left to the field's own empty report, which a user who sends
                    // and immediately leaves never gives us — and which, when it does come, now matches the row.
                    drafts.clear(conversationId)
                    if (persisted != null) persisted = ""
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
     * Hands one send to the path its thread kind takes. A commons post has its own (spool only, never the
     * radios): `sendChat` would read a no-recipient, no-group shape as the Nearby room. Text and a quote only
     * there — the staging funnel refused everything else already. The group is re-read at send time so it is
     * never misrouted as a DM in a startup race, and so a pending rename rides this message (its
     * GroupInfo.name converges last-writer-wins).
     */
    private suspend fun route(
        body: String,
        attachment: AttachmentStore.Ingested?,
        mentions: List<Mention>,
        replyTo: ReplyRef?,
    ): Boolean {
        if (isCommons) return meshManager.sendCommons(conversationId, body, mentions, replyTo)
        val group = if (isRoom) null else groups.find(conversationId)
        if (group != null) {
            return meshManager.sendChat(body, attachment, mentions, recipientId = null, group = group.toGroupInfo(), replyTo = replyTo)
        }
        // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
        val recipientId = if (isRoom) null else conversationId
        return meshManager.sendChat(body, attachment, mentions, recipientId, replyTo = replyTo)
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

    // --- direct file transfer (transfer/TransferManager) ---

    private val _showTransferConsent = MutableStateFlow<TransferConsent?>(null)

    /** The direct-transfer disclosure on screen, and what it is standing in front of, or null. See [sendFileDirectly]. */
    val showTransferConsent: StateFlow<TransferConsent?> = _showTransferConsent.asStateFlow()

    private val _transferPickerNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires once the disclosure stands accepted and a file may be picked. The screen answers it by opening the
     * document picker, which is a composable's job — the same hand-off [locationPermissionNeeded] makes.
     */
    val transferPickerNeeded: SharedFlow<Unit> = _transferPickerNeeded.asSharedFlow()

    /**
     * The overflow item was tapped. Refuse here rather than after the picker: somebody who hunts down a 2 GB
     * video and is only then told this thread cannot take one has been made to work for nothing. Then the
     * disclosure, once per device, and then the picker.
     */
    fun sendFileDirectly() {
        viewModelScope.launch {
            refusalForTransfer()?.let {
                _events.tryEmit(it)
                return@launch
            }
            if (settings.directTransferConsented.first()) {
                _transferPickerNeeded.tryEmit(Unit)
            } else {
                _showTransferConsent.value = TransferConsent(incoming = false)
            }
        }
    }

    /**
     * Records the disclosure, lowers it and carries on to whatever raised it — the picker on this side, the
     * offer being answered on the other. One tap does both, as [acceptLocationConsent] does: the sheet's
     * button says what the tap behind it said.
     */
    fun acceptTransferConsent() {
        val request = _showTransferConsent.value ?: return
        viewModelScope.launch {
            settings.acceptDirectTransferConsent()
            _showTransferConsent.value = null
            val id = request.transferId
            if (id == null) _transferPickerNeeded.tryEmit(Unit) else startAccept(id)
        }
    }

    /** Lowers the disclosure without recording anything, so the next attempt asks again. */
    fun dismissTransferConsent() {
        _showTransferConsent.value = null
    }

    /** Offers the file at [uri] to this DM's peer over a one-shot Wi-Fi Direct link; the bytes never ride the mesh. */
    fun offerTransfer(uri: Uri) {
        viewModelScope.launch {
            refusalForTransfer()?.let {
                _events.tryEmit(it)
                return@launch
            }
            val outcome = transfers.offer(conversationId, uri.toString())
            if (outcome is OfferOutcome.Refused) _events.tryEmit(transferRefusalMessage(outcome.refusal))
        }
    }

    /**
     * Why this thread cannot take a large file, or null when it can: a DM only, toward a pinned profile
     * carrying [Protocol.CAP_DIRECT_TRANSFER] (the [refusalForFile] rule — gate the send on a fact the peer
     * told us, never the affordance), and a peer this phone can see right now, since the offer has to be
     * answered while both are still in range.
     */
    private suspend fun refusalForTransfer(): Int? {
        // A group is a roster, not a row: the group repository answers for every id (a relaxed double included),
        // so the same members test refusalForFile uses is the honest one here.
        val members = groups.find(conversationId)?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        if (isRoom || conversationId == Conversations.MESHTASTIC || members.isNotEmpty()) return R.string.chat_transfer_needs_dm
        if ((peers.find(conversationId)?.capabilities ?: 0L) and Protocol.CAP_DIRECT_TRANSFER == 0L) {
            return R.string.chat_transfer_peer_too_old
        }
        if (meshManager.neighbors.value.none { it.nodeId == conversationId }) return R.string.chat_transfer_peer_not_nearby
        return null
    }

    /**
     * Answers an incoming offer. Accepting is the moment this phone's radio changes hands and a file starts
     * arriving in shared storage, so on the first direct transfer it reads the disclosure first.
     */
    fun acceptTransfer(id: String) {
        viewModelScope.launch {
            if (settings.directTransferConsented.first()) {
                startAccept(id)
            } else {
                _showTransferConsent.value = TransferConsent(incoming = true, transferId = id)
            }
        }
    }

    private suspend fun startAccept(id: String) {
        transfers.accept(id)?.let { _events.tryEmit(transferRefusalMessage(it)) }
    }

    fun declineTransfer(id: String) {
        viewModelScope.launch { transfers.decline(id) }
    }

    fun cancelTransfer(id: String) {
        viewModelScope.launch { transfers.cancel(id) }
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
                    // The Meshtastic room carries one line of text and nothing beside it: `sendPublicPost` takes
                    // a string, so anything staged here would sit in the composer and then vanish at send. The
                    // composer already offers no picker and tells the keyboard it takes no images, so what
                    // reaches this is the share sheet — which lists the room, because sharing *text* into it is
                    // a fair thing to want. One refusal at the funnel covers every route into it.
                    isBridged -> {
                        blobs.deleteIfUnreferenced(result.ingested.hash)
                        _events.tryEmit(R.string.chat_mesh_text_only)
                    }

                    // A commons carries text only in this revision (the spool gates attachments separately).
                    isCommons -> {
                        blobs.deleteIfUnreferenced(result.ingested.hash)
                        _events.tryEmit(R.string.chat_commons_text_only)
                    }

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

    /**
     * Reads one more page of history, for a reader who has scrolled to the oldest loaded message.
     *
     * Grows from the limit the *rendered* window was fetched with, not from [windowLimit], so the window
     * advances exactly one page per call and calls arriving while a page is still in flight are absorbed
     * rather than stacking — the screen drives this from a `snapshotFlow` over the visible range, and Room
     * answers asynchronously. (The screen's own latch is what keeps one fling to one page; this keeps the
     * two from compounding.)
     */
    fun loadOlder() {
        val window = windowed.value ?: return
        if (window.messages.size < window.limit) return // the whole thread is already loaded
        windowLimit.compareAndSet(window.limit, (window.limit + ChatWindow.PAGE).coerceAtMost(ChatWindow.MAX))
    }

    /**
     * Grows the window until [messageId] is inside it, for a tapped reply quote whose original is older than
     * anything loaded. One query rather than paging blindly toward it: [MessageRepository.depthOf] answers
     * how deep the message sits from the newest end, which is exactly the window that reaches it.
     *
     * A message that is no longer stored — retention trimmed it — reports depth 0 and is left alone, which
     * keeps the screen's existing behaviour of quietly ignoring a quote it cannot follow.
     */
    fun revealMessage(messageId: String) {
        viewModelScope.launch {
            val depth = messages.depthOf(conversationId, messageId)
            if (depth <= 0) return@launch
            windowLimit.update { current -> maxOf(current, depth.coerceAtMost(ChatWindow.MAX)) }
        }
    }

    /** Chat is on screen: suppress this conversation's notifications and clear any active one (the user is reading). */
    fun onChatForeground() {
        chatForeground.value = true
        notifier.setVisibleConversation(conversationId)
        resumeLocation()
    }

    /** Chat left the screen: resume notifying for this conversation's incoming messages. */
    fun onChatBackground() {
        chatForeground.value = false
        notifier.setVisibleConversation(null)
        pauseLocation()
    }

    // Wall clock of the last typing cue we sent, so we throttle to at most one per TYPING_SEND_INTERVAL_MS
    // while the user edits (see onUserTyping). Main-thread-confined (the screen's snapshotFlow collector).
    private var lastTypingSentAt = 0L

    /**
     * The user changed the (non-empty) draft: emit a best-effort "now typing" cue, throttled to at most one per
     * [TYPING_SEND_INTERVAL_MS] and only while the chat is foregrounded. Fires immediately on the first keystroke
     * after an idle gap (the throttle window has elapsed), so the indicator appears promptly on the other side.
     * Cheap and fire-and-forget — the screen may call this on every keystroke, with the field's [text].
     */
    fun onUserTyping(text: String) {
        // Never in the bridged room: there is nobody on the far side to show a cue to, and the frame it would
        // mint carries no room of its own, so `MeshManager.sendTyping` would publish it as a *Nearby* cue.
        // Nor in a commons: its scope carries no typing frame (§7.4), and the same Nearby misroute would follow.
        if (isBridged || isCommons) return
        // The restore is not a keystroke: the screen's collector reports the stored draft being put back the
        // same way it reports a typed character, and a cue for it shows the peer "typing…" with nothing to
        // follow. The first report past it is the user, and from then on every one is.
        if (text == restored) return
        restored = null
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
        locationJob?.cancel()
    }

    private companion object {
        /** How long the draft must rest on a link before its card is fetched. */
        const val PREVIEW_DEBOUNCE_MS = 600L

        /** The most a send waits for its link's card ([attachmentForSend]); a slower site sends the text alone. */
        const val SEND_CARD_HOLD_MS = 5_000L

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
