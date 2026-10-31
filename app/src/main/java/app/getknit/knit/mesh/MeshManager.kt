package app.getknit.knit.mesh

import android.util.Log
import app.getknit.knit.TextLimits
import app.getknit.knit.badge.BadgeIssuer
import app.getknit.knit.badge.BadgeJournal
import app.getknit.knit.badge.BadgeRefresher
import app.getknit.knit.badge.PinnedBadgeKey
import app.getknit.knit.badge.hexOf
import app.getknit.knit.badge.sha256
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.TurnServer
import app.getknit.knit.call.TurnSource
import app.getknit.knit.clampToUtf16
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.QuoteVerifier
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toFoundingInfo
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.message.withVideo
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.scopeconfig.ScopeConfigRepository
import app.getknit.knit.data.settings.LoraBoard
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.Identity
import app.getknit.knit.isValidReactionEmoji
import app.getknit.knit.location.GeoUri
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.readsCryptoV3
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.crypto.sealedContent
import app.getknit.knit.mesh.link.FileIntake
import app.getknit.knit.mesh.lora.LoraCtl
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.DeclaredLangs
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRootPayload
import app.getknit.knit.mesh.protocol.GroupSeed
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.StyleKey
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.StyleSpans
import app.getknit.knit.mesh.protocol.TalkSeal
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.AccountIssuer
import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.CommonsStore
import app.getknit.knit.mesh.spool.DeviceKeyring
import app.getknit.knit.mesh.spool.FreeKeyJournal
import app.getknit.knit.mesh.spool.GroupRootPolicy
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.mesh.spool.GroupScopeRoots
import app.getknit.knit.mesh.spool.HolderAskBatch
import app.getknit.knit.mesh.spool.HomeJournal
import app.getknit.knit.mesh.spool.HomeRefresher
import app.getknit.knit.mesh.spool.IssuerGroup
import app.getknit.knit.mesh.spool.MoveResult
import app.getknit.knit.mesh.spool.PairScopeRoots
import app.getknit.knit.mesh.spool.PublicSpoolSource
import app.getknit.knit.mesh.spool.ScopeBlobs
import app.getknit.knit.mesh.spool.ScopeConfigIssuer
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import app.getknit.knit.mesh.spool.ScopePlacement
import app.getknit.knit.mesh.spool.ScopeRegistry
import app.getknit.knit.mesh.spool.ScopeRoots
import app.getknit.knit.mesh.spool.ScopeSpools
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolDialer
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTickets
import app.getknit.knit.mesh.spool.TicketIssuer
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.mesh.spool.TicketRefresher
import app.getknit.knit.mesh.spool.TurnIssue
import app.getknit.knit.mesh.spool.fieldSample
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.presence.OpenToChatPolicy
import app.getknit.knit.presence.OpenToChatWatch
import app.getknit.knit.transfer.TransferSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What one inline ack costs inside a DM's ciphertext (a 22-char frame id plus its CBOR header), ADR 054. */
internal const val INLINE_ACK_BYTES = 23

/** Most receipts one reply carries inline (ADR 054) — beyond this the standalone coalesced tick is the cheaper form. */
internal const val MAX_INLINE_ACKS = 4

/**
 * Orchestrates the mesh: owns the [MeshTransport] and [MeshRouter], handles delivery of new frames
 * (persist chat, ack delivery, cache profiles/avatars, mark receipts), broadcasts this device's
 * profile, and exposes the send/start API used by the foreground service and UI. A process singleton
 * (provided by Koin) so the bound service and the UI share one instance.
 *
 * The central mesh orchestrator: many small frame handlers, and many collaborators injected by design.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class MeshManager(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    // Who has acked each message. Owns the delivery write (tick + per-acker row in one transaction), so
    // the inbound receipt paths go through it rather than straight to [messages].
    private val receipts: MessageReceiptRepository,
    private val groups: GroupRepository,
    private val reactions: ReactionRepository,
    // This phone's pinned messages (ADR 2026-10.2r7e): only swept here, at startup, beside the reactions. Required, not
    // defaulted: a rig that forgets it should fail to compile.
    private val pins: PinRepository,
    private val peers: PeerRepository,
    // The phones this one has met — the lifetime union of the nearby set, fed by [watchMetPeers] for the
    // Your mesh screen. Required, not defaulted: a rig that forgets it should fail to compile.
    private val metPeers: MetPeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val blobStore: MeshBlobStore,
    // The prefixes of attachments a link drop cut off (work item #116, ADR 2026-10.wtyc), shared with both radios:
    // [blobExchange] asks with their length. Purged at every [start] beside the blobtx copies, so no plaintext
    // outlives a session. Required, not defaulted: a rig that forgets it should fail to compile.
    private val partials: PartialBlobs,
    private val forwardStore: ForwardStore,
    private val notifier: Notifier,
    private val textModeration: ScopedTextModerator,
    private val messageCrypto: MessageCrypto,
    private val ratchet: RatchetSessions,
    private val groupRatchet: GroupRatchetSessions,
    // The spool plane's shared group roots (docs/SPOOL_PROTOCOL.md §3.2). NOT gated on the Internet plane
    // being wired: a device with it switched off still adopts and re-gossips roots, which is what carries
    // one across a plane-off member sitting between two plane-on ones. Only minting checks the switch.
    private val groupRoots: GroupRootStore,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    // What this phone does for other people's messages, credited at the two hand-off points below (the
    // router's relay fan-out and the custody re-serve) and flushed on the metrics tick + [stop].
    private val ledger: ContributionLedger,
    private val db: KnitDatabase,
    // Opens WebSocket sessions to spools for [scopeSync]. Null (the default) means the app is built
    // without the Internet plane at all — which is what every unit test wants, and what keeps the mesh
    // seam free of any transitive knowledge of it. A plain parameter: only the [scopeSync] initializer reads it.
    spoolDialer: SpoolDialer? = null,
    // One event per *new* validated default network (`InternetGate.routeChanges`): the Internet plane's
    // cue to dial a relay it is backing off from again at once, because a socket the old route swallowed
    // says nothing about this one. The default never fires, which is every test and every build without
    // the plane; production wiring hands in the platform gate's stream.
    private val routeChanges: Flow<Unit> = emptyFlow(),
    // The TURN servers this phone would measure (C-7.6-52), each time the Internet plane's set changes: the call graph's
    // `TurnMeasure` follows them (di/CallWiring). The default listens to nothing, as every test and a build with no
    // live calls want.
    private val onTurnHosts: (List<String>) -> Unit = {},
    // Whether the phone has a validated Internet route this instant (`InternetGate.isOnline`): the Internet
    // plane reads it before every dial, so a relay is never dialled — and never counted as failing — over
    // no route at all. The default always says yes, which is every test and every build without the plane.
    private val online: () -> Boolean = { true },
    // The commons (docs/SPOOL_PROTOCOL.md §7.4): the joined rooms, their outbox and their members. Null —
    // every test, and a build with no Internet plane — derives no commons scope and refuses a commons send.
    private val commons: CommonsStore? = null,
    // Injectable wall clock so the send path's timestamps (a frame and its stored local copy share one
    // sentAt) are deterministic under test. Defaults to the real clock, so production wiring (the Koin
    // module) is unchanged; mirrors the house convention — ForwardSync(clock = …), AckSync, KeyExchange.
    private val clock: () -> Long = { System.currentTimeMillis() },
    // How long a group tick toward an absent author batches before it escalates into custody
    // ([AckSync.TICK_BATCH_DEBOUNCE_MS]). A policy number, injectable like [clock] so the multi-node JVM
    // harness (`mesh/lab`) can watch a tick cross a relay in milliseconds rather than wait out the field
    // value; production wiring takes the default.
    tickDebounceMs: Long = AckSync.TICK_BATCH_DEBOUNCE_MS,
    // How long a room tick waits for a ride before it is sealed and sent by itself
    // ([AckSync.RIDE_HOLD_MS], ADR 2026-09.y5f3). Injectable for `mesh/lab` exactly like [tickDebounceMs].
    private val rideHoldMs: Long = AckSync.RIDE_HOLD_MS,
    // How long the ✓✓ for a DM that arrived over the LoRa board batches ([DmAckCoalescer.HOLD_MS], ADR 054).
    // Injectable for `mesh/lab` exactly like [tickDebounceMs].
    dmAckHoldMs: Long = DmAckCoalescer.HOLD_MS,
    // The Internet plane's timed round and attachment re-look ([ScopeSync.TICK_INTERVAL_MS],
    // [ScopeSync.ATTACHMENT_RETRY_MS]) — the waits a calendar-only transition rides, which a jumped clock
    // cannot shorten. Injectable for `mesh/lab` like [tickDebounceMs]; only [scopeSync]'s initializer reads them.
    spoolTickMs: Long = ScopeSync.TICK_INTERVAL_MS,
    spoolAttachmentRetryMs: Long = ScopeSync.ATTACHMENT_RETRY_MS,
    // How long a blob round's first ask has before every other neighbour is asked ([BlobExchange.FIRST_ASK_GRACE_MS],
    // ADR 2026-10.f7gg). Injectable for `mesh/lab`, whose scenarios must not hang an assertion on a wall-clock race.
    private val firstAskGraceMs: Long = BlobExchange.FIRST_ASK_GRACE_MS,
    // How long a send of ours with nothing else to defer on holds for its proof round
    // ([AttachmentDeferPolicy.PROOF_WAIT_MS], ADR 2026-10.vy27). Injectable for `mesh/lab`, whose wall-clock hold a stall
    // must not outlast.
    proofWaitMs: Long = AttachmentDeferPolicy.PROOF_WAIT_MS,
    // How often the re-offer round runs for the linked neighbours ([NEIGHBOR_REOFFER_INTERVAL_MS]). Injectable for
    // `mesh/lab`, whose scenario that a failing round does not end the loop (#206) must let the real loop run.
    private val reofferIntervalMs: Long = NEIGHBOR_REOFFER_INTERVAL_MS,
    // Whether this build queries neighbours for a blob's holders before it asks (ADR 2026-10.6e5t;
    // `BuildConfig.BLOB_QUERY`, on in every build by default), and how long it waits for their answers.
    // Off, every neighbour gets ADR 2026-10.f7gg's blind round; answering a query is never gated.
    blobHolderQuery: BlobExchange.HolderQuery = BlobExchange.HolderQuery(),
    // Whether the blob exchange carries the pull budget and deadline (ADR 2026-10.2drc). On in every build; `mesh/lab`
    // turns it off to play the iOS port's rules beside ours.
    blobPullBudgets: Boolean = true,
    // What the session's collectors and everything they launch run on. Production takes the default; the
    // `mesh/lab` harness hands in its seeded chaos dispatcher (`LabChaos`), a busy pool on demand.
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // The per-link meter on broadcast-room posts ([IngressBudget]), handed to the router. A policy object,
    // injectable like [tickDebounceMs] so `mesh/lab` can flood a room past a tiny budget in one scenario;
    // production wiring takes the default and shares [clock].
    private val ingressBudget: IngressBudget = IngressBudget(clock = clock),
    // Puts a post typed in the Meshtastic room on this phone's own board — the LoRa transport's
    // [PublicChannelSink]. A lambda rather than the interface for the reason [MeshPostSink] is a seam in the
    // other direction: the two ends construct each other, so one of them has to be late-bound. The default
    // refuses with NO_BOARD, which is what every test and every board-less build wants.
    private val publicChannel: suspend (body: String) -> PublicPostRefusal? = { PublicPostRefusal.NO_BOARD },
    // A sealed CTL_TRANSFER landed (transfer/TransferManager.onSignal): direct-transfer signaling, late-bound
    // like publicChannel because the manager sends through this class. The default admits nothing. Not a
    // property: the only reader is [pipeline]'s initializer, which hands it straight to onTransferCtl.
    onTransferSignal: suspend (senderId: String, payload: TransferPayload, sentAt: Long) -> Boolean = { _, _, _ -> false },
    // A sealed CTL_CALL landed (call/CallManager.onSignal; experimental, docs/VOICE_CALLS.md): voice-call signaling,
    // late-bound for onTransferSignal's reason. Called on the inbound path, so the far end must return without
    // waiting on the network or the media. The default ignores it — every test, and every build without calls.
    onCallSignal: suspend (senderId: String, payload: CallPayload, sentAt: Long) -> Unit = { _, _, _ -> },
    // A walkie chunk's plaintext from an accepted contact (a `talk` frame, docs/VOICE_CALLS.md §3.11), late-bound and
    // bound to return at once the same way; viaRelay is whether a spool cast it. The default drops it.
    onCallTalk: suspend (senderId: String, chunk: ByteArray, sentAt: Long, frameBytes: Int, viaRelay: Boolean) -> Unit =
        { _, _, _, _, _ -> },
    // Capability bits this build advertises beyond [Protocol.LOCAL_CAPABILITIES] — [Protocol.CAP_CALL] when the call
    // media is wired (di/CallWiring). OR-ed into our own profile; a change republishes it at the next start.
    private val extraCapabilities: Long = 0L,
    // One plaintext, one identity (ADR 2026-10.mptm): handed to the inbound pipeline, which records each sealed
    // attachment's plaintext name and makes a room copy from a sealed one this phone holds. Stateless over [messages]
    // and [blobs], so the default is what every test wants; production hands in the Koin single.
    private val identities: AttachmentIdentities = AttachmentIdentities(messages, blobs),
    // Judges an inbound reply's quote against the original it names (ADR 2026-10.gpa3): handed to the inbound
    // pipeline, and run over the held backlog once per start. The default labels rebuilt quotes in English, which is
    // what every test wants; production hands in the Koin single, which reads the reader's own strings.
    private val quotes: QuoteVerifier = QuoteVerifier(messages),
    // What a notification says in place of a body it cannot show — handed to the inbound pipeline. English by
    // default, which every test wants; production reads the reader's own strings (`notificationLabels(context)`).
    private val notificationLabels: () -> NotificationLabels = { NotificationLabels.ENGLISH },
    // The field log (ADR 2026-10.a239): each session's start, stop, power and radio status, and the counters'
    // quarter-hourly movement. Null — every test — keeps none.
    private val fieldLog: FieldLog? = null,
    // The ticket key swap (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7): the customer keys invites handed
    // this phone and the issuer they are traded with. Both null in a build without Knit Anywhere (release:
    // `di/AnywhereWiring`'s `ANYWHERE_WIRED`), and then nothing ever asks an issuer for anything.
    ticketKeys: TicketKeyJournal? = null,
    ticketIssuer: TicketIssuer? = null,
    // The free half of the swap (docs/VOICE_CALLS.md C-7.6-33 to C-7.6-38): the free issuers, the person's answer to
    // the notice, and the account route. Null with the rest of tickets, so release makes no free account.
    freeKeys: FreeKeyJournal? = null,
    accountIssuer: AccountIssuer? = null,
    // The customer keys' device keys (docs/VOICE_CALLS.md C-7.6-39, ADR 2026-10.wk3m), which sign every request a
    // customer key makes. Null with the rest of tickets, and in every test that signs nothing.
    deviceKeys: DeviceKeyring? = null,
    private val allowCleartext: Boolean = false,
    // The supporter badge (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s): the holder's toggle and held badges, read into the
    // sealed profile, and the issuer that signs them. The journal is wired in every build (a release build simply never
    // holds a badge); the refresher runs only where tickets are wired, since earning one takes a customer key.
    private val badgeJournal: BadgeJournal? = null,
    private val badgeIssuer: BadgeIssuer? = null,
    badgePins: List<PinnedBadgeKey> = emptyList(),
    // This phone's home relay and the public directory it is picked from (ADR 2026-10.8kfx). Null in a test that keeps
    // none; [directory] is null where the build reads no directory (`BuildConfig.SPOOL_DIRECTORY`), and the home then
    // comes from the copy the build ships.
    private val homes: HomeJournal? = null,
    directory: PublicSpoolSource? = null,
) : MeshController,
    TransferSignals,
    CallSignals,
    TurnSource,
    ProfileFrameSource,
    FarPeerFrameSource,
    BridgeFrameSource,
    MeshPostSink {
    // Per-session scope for the collectors + metrics loop + router; cancelled in stop() so they don't
    // accumulate across start/stop cycles (e.g. a Diagnostics-triggered restart()).
    private var sessionScope: CoroutineScope? = null

    // Serialises the database re-arm and the user's tap (ADR 2026-10.js2p): see [rewantMissingBlobsLocked].
    private val rewantLock = Mutex()

    /**
     * Test seam: runs inside a re-arm between its reads and its wants, under [rewantLock] — where `mesh/lab` parks one
     * to race the user's tap against it (`LargeAttachmentLabTest`). Null in production.
     */
    @Volatile
    internal var beforeRewantApplies: (suspend () -> Unit)? = null

    /**
     * Test seam: runs inside a re-arm between custody's reads and the rows' read, under [rewantLock] — where `mesh/lab`
     * writes or deletes a row to race the delivery and a sweep against the re-arm's read order (#141's finding 6,
     * `LargeAttachmentLabTest`). Null in production.
     */
    @Volatile
    internal var betweenRewantReads: (suspend () -> Unit)? = null

    // Content-addressed image fetch over the mesh, backed by the encrypted blob store.
    private val blobExchange =
        BlobExchange(
            transport = transport,
            store = blobStore,
            selfId = { identity.nodeId() },
            now = clock,
            // The chat list observes the blobs table for presence, so no per-message path write is needed
            // when an attachment arrives. A pulled blob may also be a (multi-hop) peer's avatar or a group
            // photo, so attribute it back to whoever advertised it, and — for an E2E attachment — screen its
            // decrypted bytes now that both the ciphertext and (from the delivered message) its key are on hand.
            onObtained = { hash, _ -> pipeline.onObtained(hash) },
            partials = partials,
            onSpliceRefused = metrics::onSpliceRefused,
            // A first ask's grace runs on the session (ADR 2026-10.f7gg); stopped, the tick and link-ups step it.
            firstAskGraceMs = firstAskGraceMs,
            holderQuery = blobHolderQuery,
            pullBudgets = blobPullBudgets,
            onEvent = metrics::onBlobEvent,
            timers = { sessionScope },
            addressees = ::addresseesOf,
            // The recipient of a DM photo of ours said, over a live short-range link, that it holds the bytes: the proof
            // that keeps the photo off every spool for good (ADR 2026-10.vy27). The exchange takes only a short-range
            // answer to its own query, so the plane is a radio's.
            onPeerHolds = { hash, peer -> blobs.recordHolder(hash, peer, DeliveryPlane.Nearby, clock()) },
            log = { Log.d(TAG, it) },
        )

    /**
     * Whom the conversations naming blob [hash] are addressed to, every one of them ([BlobExchange.addresseesAcross]): a
     * group's members, a DM's two ends; null when one is a room's or no message we hold names it. A holder serves those
     * ahead of carriers (ADR 2026-10.nsr7). A picture forwarded under its identity (ADR 2026-10.mptm) from a group into a
     * DM with someone outside it is both's, and the first row alone classed one side carriers by row order.
     */
    private suspend fun addresseesOf(hash: String): Set<String>? =
        BlobExchange.addresseesAcross(messages.conversationsOfAttachment(hash).map { addresseesOfConversation(it) })

    /** Whom [conversation] is addressed to: a group's members, a DM's two ends; null for the room or an unknown group. */
    private suspend fun addresseesOfConversation(conversation: String): Set<String>? =
        when (Conversations.kindFor(conversation)) {
            ConversationKind.GROUP -> groups.find(conversation)?.let { GroupMembersStore.decode(it.members).toSet() }
            ConversationKind.DM -> setOf(conversation, identity.nodeId())
            else -> null
        }

    // Store-and-forward DM custody: carries DMs we originate/relay and re-offers them to neighbors that
    // join later, so a message reaches a recipient that wasn't connected when it was first flooded.
    private val forwardSync =
        ForwardSync(
            transport = transport,
            store = forwardStore,
            clock = clock,
            authenticate = { wire, env -> pipeline.canCarry(wire, env) },
            // Fired once when a chat frame is actually carried: eager-pull its image blob so a custodied image
            // survives to a late joiner (the carrier holds ciphertext it can't read, like the frame itself).
            onCarried = { env, from -> pipeline.onCarriedFrame(env, from) },
            // A carried frame actually sent to a peer that lacked it — the ledger decides whether it counts.
            onServed = { env, to -> ledger.onHandedOff(env, setOf(to)) },
            // A sender whose backlog goes to a peer that may not hold their key, and whose profile custody no
            // longer holds: the pin store's copy goes first (ADR 2026-09.g64k).
            serveKey = { senderId, to -> keyExchange.serveKey(senderId, to) },
            // (The carry store grew → the store impl folds the id into StoreDigest, whose version change re-cues.)
        )

    // Demand-driven recovery of a peer's key/profile: a frame dropped for a missing sender key (the
    // NO_SENDER_KEY case in verifyInbound) triggers a signed, point-to-point request that walks hop-by-hop
    // to a holder, which re-serves the peer's cached signed profile so future frames from it verify.
    private val keyExchange =
        KeyExchange(
            transport = transport,
            selfId = { identity.nodeId() },
            signRaw = messageCrypto::signRaw,
            now = clock,
            metrics = metrics,
            // The signed frame each pin came from, so a restart never leaves a carrier unable to prove a key.
            storedProfile = { peers.profileFrame(it) },
            // The profile cap (#234): a stored proof longer than the door would take is never cached or served.
            maxProfileSignedBytes = forwardStore.maxProfileSignedBytes,
        )

    // The same-identity clone watch (ADR 2026-09.ypcc): a `profile` under our own node id whose stamp this
    // phone never minted lights the "also active on another phone" state in the settings store. Its one
    // side effect is the counter-flood — a fresh stamp so the other phone sees us within a contact — fired
    // once per not-visible → visible edge; `broadcastProfile` bumps the version too, harmlessly (same content).
    private val cloneWatch = CloneWatch(settings, clock) { broadcastProfile() }

    // The contact-card intro driver (docs/CONTACT_CARD.md): turns a peer pinned from an out-of-band card
    // into a confirmed ratchet session by sealing a CTL_PROFILE DM as soon as the peer's prekey is known,
    // re-sending on a floor while unconfirmed, and answering an unconfirmed peer. Its pending/grace peers
    // are what the spool plane derives pair scopes for (spec §3.5). State lives in the settings store.
    private val introSync =
        IntroSync(
            store =
                object : IntroStore {
                    override suspend fun pending(): Map<String, Long> = decodeStamped(settings.pendingIntros.first())

                    override suspend fun grace(): Map<String, Long> = decodeStamped(settings.introGrace.first())

                    override suspend fun write(
                        pending: Map<String, Long>,
                        grace: Map<String, Long>,
                    ) {
                        settings.setIntroState(encodeStamped(pending), encodeStamped(grace))
                    }
                },
            canSeal = { peerId -> canSealTickTo(peerId) && ratchetPrekeyOf(peers.find(peerId)) != null },
            sendIntro = ::sendIntroTo,
            sessionConfirmed = { peerId -> ratchet.sessionFor(peerId)?.confirmed == true },
            // A profile has pinned (it carries the version and the bits; a card alone carries neither) without
            // CAP_RATCHET: a v1-only peer no intro can reach.
            ratchetless = { peerId ->
                peers.find(peerId)?.let { peer ->
                    (peer.protoVersion != null || peer.capabilities != null) &&
                        (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L
                } == true
            },
            // A pair peer named, evicted, lapsed or pinned is a scope-table input landing (ADR 2026-09.dcah).
            // `scopeSync` is declared below; the lambda reads it at call time, like `reopenFrame`.
            onPairsChanged = { scopeSync?.onScopeTableChanged() },
            metrics = metrics,
            clock = clock,
        )

    // Delay-tolerant "delivered" tick for broadcast/group messages. A live-linked author gets today's
    // unicast, non-custodied tick; an absent-but-sealed-capable author's acks batch per author and, after
    // a short debounce, escalate as ONE originated (`relay = true`) ctl frame — flooded, custodied, and
    // spool-eligible, so the tick converges exactly like the message it acks. Legacy (cleartext) ticks and
    // the broadcast room stay unicast-only: flooding a cleartext receipt would re-leak the delivery event
    // ADR 018 sealed away. The lambdas read this manager's fields lazily at call time (the pipeline's
    // originate pattern); flushScope defers to the session scope so debounce wakes die with stop() —
    // retryPending() on the heal heartbeat is the backstop.
    // `internal` for the same reason [dmAcks] is: the JVM tests drive the ride hold directly rather than
    // staging a whole absent-author delivery to get one id into it.
    internal val ackSync =
        AckSync(
            transport = transport,
            selfId = { identity.nodeId() },
            signRaw = messageCrypto::signRaw,
            now = clock,
            metrics = metrics,
            sealTick = { authorId, ackIds -> sealDeliveryTick(authorId, ackIds) },
            canSeal = { authorId -> canSealTickTo(authorId) },
            originateTick = { authorId, ackIds -> originateDeliveryTick(authorId, ackIds) },
            flushScope = { sessionScope },
            debounceMs = tickDebounceMs,
            rideHoldMs = rideHoldMs,
            // Both read [scopeSync], declared below — lazily, at call time, like every other lambda here.
            spoolPresent = { authorId -> isSpoolPresent(authorId) },
            spoolTick = { authorId, ackIds -> spoolDeliveryTick(authorId, ackIds) },
        )

    // The ✓✓ for a DM that arrived over the LoRa board waits here (ADR 054): a burst from one author becomes
    // one sealed tick, and a reply we send meanwhile carries the acks inline (sendChat). The flush is the
    // group escalation's own path — one originated ctl frame, hinted TICK so the board ranks it as feedback —
    // falling back per id to the cleartext receipt, exactly as AckSync does. heal() is the backstop timer.
    internal val dmAcks =
        DmAckCoalescer(
            now = clock,
            flush = { authorId, ackIds -> flushDmAcks(authorId, ackIds) },
            flushScope = { sessionScope },
            holdMs = dmAckHoldMs,
        )

    // The "someone nearby is open to chat" cue: the join of our own flag, the short-range reachable set, the
    // peer rows carrying the flag, the block list and everyone we've already exchanged messages with,
    // batched and cooled down by OpenToChatPolicy. Started on the session scope in start(); its durable half
    // (who was named when, the last post) lives in settings. The acquainted query needs our node id, which
    // only a suspend call can give, so it hangs off a `flow {}` rather than running in this initializer.
    internal val openToChatWatch =
        OpenToChatWatch(
            ownFlag = settings.openToChat,
            neighborIds = nearbyPeers.map { nearby -> nearby.mapTo(HashSet()) { it.nodeId } },
            openIds = peers.observePeers().map { rows -> rows.filter { it.openToChat }.mapTo(HashSet()) { it.nodeId } },
            blocked = settings.blockedNodeIds,
            acquainted = flow { emitAll(messages.observeAcquaintedPeers(identity.nodeId()).map { it.toHashSet() }) },
            loadState = {
                OpenToChatWatch.Persisted(
                    named = OpenToChatPolicy.decodeNamed(settings.openToChatNamed.first()),
                    lastPostAt = settings.openToChatLastPostAt.first(),
                )
            },
            persist = { settings.setOpenToChatCueState(OpenToChatPolicy.encodeNamed(it.named), it.lastPostAt) },
            post = ::postOpenToChat,
            clear = notifier::clearOpenToChat,
            now = clock,
            log = { Log.d(TAG, it) },
        )

    // Bounded in-memory buffer of frames dropped for a missing sender key: parked alongside the key
    // request in verifyInbound and replayed through the deliver path once handleProfile pins the key, so
    // a frame that raced ahead of its sender's profile still lands. The inbound complement of flushPendingFor.
    // One served backlog parks whole: the park's per-sender cap is the custody quota (ADR 2026-09.9xuu).
    private val pendingInbound =
        PendingInbound(now = clock, metrics = metrics, maxPerSender = ForwardRepository.DEFAULT_MAX_PER_SENDER)

    // The group-key sibling: a seed ctl DM that arrived for a group we hold no row for yet (the creator
    // floods the seed before the first frame that carries the roster) is parked before the ratchet commit
    // and replayed by InboundPipeline.reconcileGroup once the group lands. Swept beside pendingInbound.
    private val pendingGroupKeys = PendingGroupKeys(now = clock, metrics = metrics)

    // Receiver-side state for the best-effort "now typing" indicator: which senders are typing in which
    // conversation. Ephemeral and never custodied — a typing cue is fire-and-forget, so nothing is persisted
    // (a live typer re-cues within the TTL). Populated by handleTyping, cleared by deliverChat on a real message.
    private val typingTracker = TypingTracker(scope)

    // Who reached us over the radio mesh through another phone (ADR 2026-10.fw8g) — fed by the pipeline after
    // its signature gate, swept on the metrics tick, read only by the reach tiers. Not a routing input.
    private val relayedPresence = RelayedPresence(selfId = { identity.nodeId() }, clock = clock)

    // Whose own frames crossed the radios to us lately, and through which link (ADR 2026-10.79yb's tenth amendment):
    // the walkie's send path reads it, and nothing else does. Not [relayedPresence], whose linger is for a reader.
    private val talkRadioPaths = TalkRadioPaths(clock = clock)

    /**
     * Every accepted frame, once, after `InboundPipeline.verifyInbound`: the indirect-reach tracker reads it, and so
     * does the name-clash fade's `lastSeenAt` (ADR 2026-10.k8ge), which only buffers here — the metrics tick writes
     * it. Runs ahead of the relay, so nothing in it may throw or wait on the database.
     */
    private suspend fun noteVerified(
        wire: WireEnvelope,
        env: RelayEnvelope,
        fromNodeId: String,
        kind: TransportKind,
    ) {
        relayedPresence.note(wire, env, fromNodeId, kind)
        if (wire.sig.isNotEmpty() &&
            !FrameType.isUnsignedPointToPoint(env.type)
        ) {
            talkRadioPaths.note(env.senderId, fromNodeId, kind, env.sentAt)
        }
        if (isHeardEvidence(wire, env) && env.senderId != identity.nodeId()) peers.noteSeen(env.senderId, heardAt(env, clock()))
    }

    /**
     * Every conversation's scope config (`CTL_SCOPE_CONFIG`, docs/SPOOL_PROTOCOL.md §5) and the peers that declared
     * one: written by the pipeline's two inbound arms and by [sendScopeConfig], read by the scope table. Declared
     * ahead of [pipeline], which takes it.
     */
    internal val scopeConfigs: ScopeConfigRepository = ScopeConfigRepository(db, clock)

    // The inbound half of the mesh: verify → custody → dispatch → deliver/ack, plus the store-and-forward
    // carry gate and the avatar/group-photo/attachment screening. MeshManager still OWNS the DTN services
    // above and the outbound origination choke; the pipeline receives the services by reference and reaches
    // origination through the originate/flushPending lambdas (moderation through classifyText). Declared
    // after the services it consumes; the services' authenticate/onCarried/onObtained callbacks read
    // `pipeline` lazily (they never fire during construction — first only in start()'s launches), so the
    // mutual reference is safe.
    // Explicit type: the DTN services above take `pipeline.*` lambdas while `pipeline` takes the services,
    // so the type must be stated to break the inference cycle (the values still resolve — pipeline is a
    // stable field the deferred lambdas read at call time).
    private val pipeline: InboundPipeline =
        InboundPipeline(
            transport = transport,
            messages = messages,
            receipts = receipts,
            groups = groups,
            reactions = reactions,
            peers = peers,
            blobs = blobs,
            imageScreening = imageScreening,
            blobStore = blobStore,
            identities = identities,
            quotes = quotes,
            notificationLabels = notificationLabels,
            db = db,
            identity = identity,
            settings = settings,
            messageCrypto = messageCrypto,
            notifier = notifier,
            metrics = metrics,
            forwardSync = forwardSync,
            maxProfileSignedBytes = forwardStore.maxProfileSignedBytes,
            blobExchange = blobExchange,
            keyExchange = keyExchange,
            ackSync = ackSync,
            pendingInbound = pendingInbound,
            pendingGroupKeys = pendingGroupKeys,
            typingTracker = typingTracker,
            ratchet = ratchet,
            groupRatchet = groupRatchet,
            clock = clock,
            originate = ::originateSigned,
            originateTick = { originateSigned(it, FanoutHint.TICK) },
            dmAcks = dmAcks,
            holdsLive = forwardStore::holdsLiveReceipt,
            flushPending = ::flushPendingFor,
            classifyText = ::isTextFlagged,
            resealUnacked = ::resealRecentDmsTo,
            // `router` is rebuilt per session (declared below); the lambda reads the current one.
            reopenFrame = { router.reopen(it) },
            redistributeGroupKey = ::redistributeGroupKey,
            flushGroupKeys = ::flushPendingGroupKeysFor,
            replayGroupCustody = ::replayCustodiedGroupFrames,
            replaySeedCustody = ::replayCustodiedSeedDms,
            adoptGroupRoot = ::adoptGroupRoot,
            onGroupRootCtl = ::onGroupRootCtl,
            onProfilePinned = { introSync.onProfilePinned(it) },
            onSelfProfile = { cloneWatch.onSelfProfile(it) },
            onPeerFrameOpened = { senderId, initEph, flagged -> introSync.onPeerFrameOpened(senderId, initEph, flagged) },
            onTransferCtl = onTransferSignal,
            onCallCtl = onCallSignal,
            onCallTalk = { senderId, chunk, sentAt, frameBytes, hop, kind ->
                // An opened chunk is the peer's own bytes over the radio, as good as a signature (C-3.11-6).
                if (hop != null) talkRadioPaths.note(senderId, hop, kind, sentAt)
                onCallTalk(senderId, chunk, sentAt, frameBytes, hop == null)
            },
            commonsTitle = { commons?.find(it)?.name },
            noteVerified = ::noteVerified,
            scopeConfigs = scopeConfigs,
        )

    // Reconstructed per session so its inbound collector + relay jobs live on the session scope and are
    // cancelled by stop() (rather than leaking on the never-cancelled app scope). Declared after `pipeline`
    // so onDeliver targets it.
    private var router = newRouter(scope)

    /**
     * The §9.5 rounds' holder queries for a DM photo of ours, sent as one frame per peer per window (ADR 2026-10.vy27).
     * On the session, which a stop cancels: the batch survives a cancelled flush and goes out with the next.
     */
    private val holderAsks =
        HolderAskBatch(
            scope = { sessionScope },
            send = { peer, hashes -> blobExchange.confirmHeld(hashes, peer) },
        )

    /**
     * §9.5's push-half deferral: an attachment a short-range radio already carried between us and this
     * DM's peer stays off the Internet while that peer is still on the presence plane — and, for
     * [AttachmentDeferPolicy.ACK_GRACE_MS] after a send of ours, one whose ack simply hasn't had time to
     * come back. Wired unconditionally (it is pure and cheap) but only ever consulted by [scopeSync].
     */
    private val attachmentDefer =
        AttachmentDeferPolicy(
            // The short-range set, not the full reach: a LoRa-only sighting can't carry the bytes, so it must
            // not defer a spool upload (H5). [nearbyPeers] is the one definition of that set — a lambda, so
            // reading the property here is safe however late this field is initialized.
            reachable = { nearbyPeers.value.mapTo(mutableSetOf()) { peer -> peer.nodeId } },
            // Same line drawn on the evidence: only a hop that crossed a short-range radio proves a data path
            // moved these bytes. A spool one would defer on the plane the push feeds, a LoRa one on a plane
            // that carries no blob at all. Read from whichever end of the DM we are — our send that they
            // acked, or their send that reached us — so a recipient stops re-uploading what BLE just gave it.
            carriedByRadio = { aHash, peerId -> messages.attachmentCarriedByRadio(aHash, identity.nodeId(), peerId) },
            // The grace's subject: for a minute after a send, a missing ack means the receipt is still in
            // flight rather than that the radios failed. Only a message we authored is waiting on one.
            authoredHere = { aHash -> messages.attachmentAuthoredHere(aHash, identity.nodeId()) },
            // The bytes themselves, which never lapse: the recipient's own radio arrival (written below, once the
            // store took them) or its answer to our holder query. A DM photo so proved never goes up (ADR 2026-10.vy27).
            heldByRecipient = { aHash, peerId -> blobs.heldInPair(aHash, identity.nodeId(), peerId) },
            // Asked over a live short-range link only, and floored per (hash, peer) by the exchange; the answer lands
            // in the same table through `onPeerHolds`, never in this round. Batched per peer ([holderAsks]).
            askHolder = { aHash, peerId -> holderAsks.ask(aHash, peerId) },
            // The same test `BlobExchange.confirmHeld` sends on — a live short-range link whose peer answers the holder
            // query (LoRa's set is always empty) — so a pass holds its proof round only when the question really went out.
            canAskHolder = { peerId ->
                transport.neighbors.value.any { it.nodeId == peerId && it.capabilities and Protocol.CAP_BLOB_HAVE != 0L }
            },
            custodyTtlMs = ForwardRepository.DEFAULT_TTL_MS,
            clock = clock,
            proofWaitMs = proofWaitMs,
        )

    private val spoolTickets = SpoolTickets(settings, clock)

    // The relays a `ScopeSync` worker runs for, published at each reconcile: a peer's home a config names joins the
    // person's own list here (ADR 2026-10.8kfx).
    private val workerRelays = MutableStateFlow<Set<String>>(emptySet())

    // The relays in use: the active list and every relay a worker runs for, empty while the plane is off.
    private val relaysInUse: Flow<Set<String>> =
        combine(settings.activeSpoolUrls, workerRelays) { active, workers -> if (active.isEmpty()) emptySet() else active + workers }

    /**
     * Fetches each active relay's ticket from the issuer its invite named, before the held one runs low (C-7.6-27,
     * ADR 2026-10.3sk7). Built only with the plane, a key store and an issuer; runs only while the session does, and
     * asks nothing while [SettingsStore.activeSpoolUrls] is empty — the plane off, or every relay parked.
     */
    private val ticketRefresher: TicketRefresher? =
        if (spoolDialer != null && ticketKeys != null && ticketIssuer != null) {
            TicketRefresher(
                keys = ticketKeys,
                tickets = spoolTickets,
                issuer = ticketIssuer,
                urls = relaysInUse,
                online = online,
                routeChanges = routeChanges,
                allowCleartext = allowCleartext,
                clock = clock,
                log = { Log.i(SPOOL_TAG, it) },
                free = freeKeys,
                accounts = accountIssuer,
                devices = deviceKeys,
            )
        } else {
            null
        }

    /**
     * Keeps this phone's home relay (ADR 2026-10.8kfx): settles it at each start, reads the public directory at most
     * daily while a relay is active, and re-derives the scope table when the home moves. Built only with the plane.
     */
    private val homeRefresher: HomeRefresher? =
        if (spoolDialer != null && homes != null) {
            HomeRefresher(
                journal = homes,
                source = directory,
                selfId = { identity.nodeId() },
                planeOn = settings.activeSpoolUrls.map { it.isNotEmpty() },
                online = online,
                routeChanges = routeChanges,
                onHomeChanged = { _, _ ->
                    scopeSync?.onScopeTableChanged()
                    scopeIssuer?.poke()
                },
                clock = clock,
                log = { Log.i(SPOOL_TAG, it) },
            )
        } else {
            null
        }

    /**
     * Asks the issuer of a live customer key for this phone's supporter badge while "Show my supporter badge" is on
     * (docs/SUPPORTER_BADGE.md §5, ADR 2026-10.r99s), on the ticket refresher's session. Built only with a key store, an
     * issuer and the journal: a build without tickets never asks.
     */
    private val badgeRefresher: BadgeRefresher? =
        if (ticketKeys != null && badgeJournal != null && badgeIssuer != null) {
            BadgeRefresher(
                journal = badgeJournal,
                keys = ticketKeys,
                issuer = badgeIssuer,
                selfId = { identity.nodeId() },
                pins = badgePins,
                online = online,
                routeChanges = routeChanges,
                allowCleartext = allowCleartext,
                clock = clock,
                log = { Log.i(SPOOL_TAG, it) },
                devices = deviceKeys,
            )
        } else {
            null
        }

    /**
     * The Internet plane (`docs/SPOOL_PROTOCOL.md`) — a custody-plane sibling of [forwardSync], not a
     * third transport. Null when no dialer is injected (unit tests, and any build that ships without
     * it); off at runtime unless the user opts in and configures a spool, so the default install opens
     * no socket. Inbound frames re-enter through [router] exactly as a radio's would, and outbound
     * frames come from the same custody store the radios re-serve from.
     */
    private val scopeSync: ScopeSync? =
        spoolDialer?.let { dialer ->
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { identity.nodeId() },
                        roots = {
                            ratchet.exportedRoots().map {
                                ScopeRoots(it.peerId, it.pairwiseRoot, it.prevPairwiseRoot, it.prevRootExpiresAt, it.pendingResetInit)
                            }
                        },
                        groupRoots = ::groupScopeRoots,
                        pairs = ::pairScopeRoots,
                        commons = { commons?.roots().orEmpty() },
                        placement = ::scopePlacement,
                    ),
                dialer = dialer,
                store = forwardStore,
                selfId = { identity.nodeId() },
                urls = ::baseRelays,
                online = online,
                canCarry = pipeline::canCarry,
                commons = commons,
                hasKey = { peers.find(it)?.pubKey != null },
                deliverCommons = { env, post, conversationId, _ -> pipeline.deliverCommons(env, post, conversationId) },
                onCommonsMember = ::onCommonsMember,
                onOwnProfileTombstoned = { republishProfile(force = true) },
                onPresenceChanged = ::onSpoolPresenceChanged,
                blobs = scopeBlobs(),
                // The same hook a radio pull fires, after the same screening ingest (`scopeBlobs().save`), so
                // NSFW screening, the message rows, and the UI all run unchanged for a spool-delivered image
                // (§9.5). A neighbor that asked us for these bytes
                // while we lacked them asks again on its own tick and is served then (ADR 2026-09.4tx5).
                onAttachmentObtained = { hash -> pipeline.onObtained(hash) },
                deferAttachment = attachmentDefer::defer,
                provedAttachment = attachmentDefer::proved,
                holdsForProof = attachmentDefer::holdsForProof,
                deliver = { wire, env, from -> router.handleInbound(wire, env, from) },
                metrics = metrics,
                clock = clock,
                tickIntervalMs = spoolTickMs,
                attachmentRetryMs = spoolAttachmentRetryMs,
                // Entitlement tickets (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4), held beside the relay
                // list. Imported by [ticketRefresher] from an invite's issuer, or through the debug bridge.
                tickets = spoolTickets,
                // An upload locked for want of a files ticket asks the refresher now (C-7.6-27 amended). Read late:
                // the refresher is built below this field.
                onUploadWanted = { url -> ticketRefresher?.uploadWanted(url) },
                log = { Log.i(SPOOL_TAG, it) },
                field = fieldLog ?: FieldRecorder.NONE,
                onRelaysChanged = { workerRelays.value = it },
                onTurnHosts = onTurnHosts,
            ).also { sync -> fieldLog?.sampler(SPOOL_FIELD_SAMPLER) { sync.status().map { it.fieldSample() } } }
        }

    /**
     * Issues the scope configs that tell this phone's peers where its conversations live (ADR 2026-10.8kfx). Built only
     * with the plane and a home; issues nothing while the home is `public-1`.
     */
    private val scopeIssuer: ScopeConfigIssuer? =
        if (scopeSync != null && homes != null) {
            ScopeConfigIssuer(
                journal = homes,
                selfId = { identity.nodeId() },
                active = settings.activeSpoolUrls,
                winners = scopeConfigs.observeWinners(),
                peerOwn = scopeConfigs::newestOf,
                groups =
                    groups.observeGroups().map { rows ->
                        rows.filter { !it.left }.map { IssuerGroup(it.groupId, GroupMembersStore.decode(it.members).toSet(), it.createdBy) }
                    },
                rootChanges = ratchet.rootChanges,
                dmPeers = { ratchet.exportedRoots().filter { it.confirmed }.mapTo(HashSet()) { it.peerId } },
                send = ::sendScopeConfig,
                clock = clock,
                log = { Log.i(SPOOL_TAG, it) },
            )
        } else {
            null
        }

    @Volatile
    private var started = false

    // nodeId -> avatar hash we last sent that neighbor, so we don't re-push an unchanged avatar on
    // every profile edit or reconnect. Cleared per-peer when they disconnect (see watchNeighbors).
    private val sentAvatarHashes = ConcurrentHashMap<String, String>()

    /** Profile version last sealed to each peer — the per-(peer, version) dedupe of [broadcastSealedProfile]. */
    private val sentProfileVersions = ConcurrentHashMap<String, Long>()

    /**
     * Serializes every signer of our own profile against every writer of its publish stamp ([broadcastProfile],
     * [republishProfile], [ownProfile]). The frame id keys on the stamp and the bytes are signed from live
     * settings, read one key at a time — so a signing that overlaps an edit reads a mix of before and after
     * (the new stamp with the old version, say): same id, different `signed`. Custody keeps whichever landed
     * first while the flood carried the other, and the two byte-variants never fold at the spool
     * (`rules/mesh.md`, "one frame id, one set of bytes"; the lab's `meetOnTheRelay` caught it on a slow
     * runner). Under the lock a stamp is signed exactly once, by whoever wrote it, and every later caller
     * reads that row.
     */
    private val profileLock = Mutex()

    /**
     * Nearby peers — the smoothed [MeshTransport.reachable] set (seen over the coordination plane), not the
     * ≤1 live data-path link, so the UI doesn't blink as the cue-driven transport rotates through ephemeral
     * syncs. Restricted to the **short-range** children
     * ([CompositeMeshTransport.shortRangeReachable]), so *nearby* means a radio saw this peer's own radio;
     * a LoRa sighting is keyed on the frame author and a gateway carries other people's frames, so it says
     * nothing about proximity and belongs in [reachable]. Falls back to the whole set for a non-composite
     * transport (demo/fakes), exactly as the attachment-deferral presence hook does.
     */
    private val nearbyPeers: StateFlow<Set<Peer>>
        get() = (transport as? CompositeMeshTransport)?.shortRangeReachable ?: transport.reachable

    /** Number of nearby peers for the UI status header. */
    override val neighborCount: StateFlow<Int> =
        nearbyPeers
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Nearby peers for the contact picker (message someone nearby) — see [nearbyPeers]. */
    override val neighbors: StateFlow<Set<Peer>> get() = nearbyPeers

    /** Every peer reachable over any plane, long-range included — a superset of [neighbors]. */
    override val reachable: StateFlow<Set<Peer>> get() = transport.reachable

    /** Peers heard over the radio mesh through another phone — see [RelayedPresence]. */
    override val heardIndirectly: StateFlow<Map<String, RelayedHeard>> get() = relayedPresence.heard

    /** The short-range planes, so Diagnostics can tag a direct radio apart from a relay path. */
    override val shortRangeKinds: Set<TransportKind> =
        (transport as? CompositeMeshTransport)?.shortRangeKinds ?: setOf(transport.kind)

    /** Radio health for the Diagnostics screen (Healthy vs Degraded — e.g. radios seized by Quick Share). */
    override val transportHealth: StateFlow<TransportHealth> get() = transport.health

    /**
     * Per-radio status for the Diagnostics screen (Bluetooth vs Wi-Fi Aware: health + live-link/nearby counts),
     * so the merged [transportHealth]/[neighbors] can be broken back out by plane. In production the transport is
     * always a [CompositeMeshTransport]; the fallback describes a single non-composite transport (demo/fakes) as
     * one entry.
     */
    override val transportStatuses: StateFlow<List<TransportStatus>> =
        (transport as? CompositeMeshTransport)?.statuses
            ?: combine(transport.neighbors, transport.reachable, transport.health) { linked, nearby, health ->
                listOf(TransportStatus(transport.kind, health, linked.size, nearby.size))
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** nodeId → the radios each node is reachable over, so Diagnostics can tag a connected node BLE / NAN. */
    override val peerTransports: StateFlow<Map<String, Set<TransportKind>>> =
        (transport as? CompositeMeshTransport)?.peerTransports
            ?: transport.reachable
                .map { set -> set.associate { it.nodeId to setOf(transport.kind) } }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** conversationId → the set of peers currently shown as "typing" there, for the chat UI. Ephemeral (TTL'd). */
    override val typing: StateFlow<Map<String, Set<String>>> get() = typingTracker.typing

    /** The links' incoming files and how far each has got, sampled while a chat waits on one (#115). */
    override val arrivals: Flow<Map<String, ArrivingFile>> = arrivalTicker(transport::arrivingFiles)

    // Demo-screenshot only (DemoSeeder.seedRelays): a pinned spool status that [spoolStatus] answers with
    // instead of the live one. Null in every build that is not `-PseedDemo=true`.
    @Volatile
    private var demoSpools: List<SpoolStatus>? = null

    /**
     * Demo-screenshot only: pin a persistent "now typing" indicator for [conversationId] from [senderId],
     * bypassing the [TypingTracker] TTL so a statically-captured demo screenshot reliably catches it. Called
     * by [app.getknit.knit.demo.DemoSeeder]; deliberately off the [MeshController] interface so the production
     * facade stays clean.
     */
    fun seedDemoTyping(
        conversationId: String,
        senderId: String,
    ) = typingTracker.seedPersistent(conversationId, senderId)

    /**
     * Demo-director only: resolve a typing indicator (bubble → message) the instant a scripted message is
     * about to land, so the trailer's "typing…" cue converts into the message rather than lingering. Mirrors
     * [seedDemoTyping]; kept off the [MeshController] interface so the production facade stays clean.
     */
    fun clearDemoTyping(
        conversationId: String,
        senderId: String,
    ) = typingTracker.onMessageFrom(conversationId, senderId)

    override fun start() {
        if (started) return
        started = true
        blobStore.clearTransfers() // drop any plaintext transfer temp files left by a previous session
        partials.purge() // and the cut transfers' kept prefixes: a resume lives within one session (#116)
        // Child of the app Job so app-scope cancellation still propagates; SupervisorJob isolates a
        // single collector's failure from the rest of the session. The shared handler logs any uncaught
        // throw in a top-level session collector instead of letting it vanish silently.
        val session =
            CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + sessionDispatcher + meshExceptionHandler)
        sessionScope = session
        relayedPresence.start()
        router = newRouter(session)
        router.start()
        transport.start()
        watchNeighbors(session)
        // A link that ended a file's stream frees its parked asks at once (ADR 2026-10.g24x).
        session.launch { transport.fileFeedsChanged.collect { blobExchange.feedsChanged() } }
        // A file streaming in is reported back to its sender, which times its parked asks by it (ADR 2026-10.nsr7).
        session.launch { transport.fileProgress.collect { blobExchange.onFileProgress(it) } }
        watchFastLinks(session)
        watchReachable(session)
        watchMetPeers(session)
        openToChatWatch.start(session)
        val seeded = seedOwnProfileCustody(session)
        watchProfileChanges(session, seeded = seeded)
        watchBadgeChanges(session)
        watchIncomingFiles(session)
        watchIncomingDigests(session)
        resumePendingFetches(session)
        pruneForwardStorePeriodically(session)
        reofferToNeighborsPeriodically(session)
        logMetricsPeriodically(session)
        fieldLog?.start(session, transportStatuses)
        session.launch { introSync.prime() }
        // Replies held from before quotes were checked, or whose check a crash cut short, are judged against the
        // originals this phone holds (ADR 2026-10.gpa3) — after the own-profile seed, so first contact never waits.
        session.launch {
            seeded.join()
            quotes.settleBacklog(identity.nodeId())
        }
        scopeSync?.start(session)
        ticketRefresher?.start(session)
        badgeRefresher?.start(session)
        homeRefresher?.start(session)
        scopeIssuer?.start(session)
        watchRoute(session)
        watchScopeInputs(session)
    }

    /**
     * A Wi-Fi Aware link coming up steps the fast-only blob wants at once (ADR 2026-10.js2p): to a neighbour already
     * linked over Bluetooth it changes no neighbour set, so neither the link-up hooks nor `BlobExchange.linked` see it,
     * and an ephemeral data path may be gone by the next tick. Only the gained ids; a lost link is the next step's
     * business. Armed only on the composite, the one transport that can tell its planes apart.
     */
    private fun watchFastLinks(session: CoroutineScope) {
        val fast = (transport as? CompositeMeshTransport)?.fastLinked ?: return
        session.launch {
            var before = emptySet<String>()
            fast.collect { now ->
                val gained = now - before
                before = now
                blobExchange.fastLinkUp(gained)
            }
        }
    }

    /**
     * A confirmed, replaced or forgotten DM session is a scope-table input landing (`RatchetSessions.rootChanges`):
     * re-derive the table now rather than on the 60 s poll. The other inputs report through their own doors —
     * `IntroSync.onPairsChanged`, the group-root mint and adoption, a commons join, the relay editor. Armed only
     * with a plane to arm (ADR 2026-09.dcah).
     */
    private fun watchScopeInputs(session: CoroutineScope) {
        val sync = scopeSync ?: return
        session.launch { ratchet.rootChanges.collect { sync.onScopeTableChanged() } }
        // Where a scope lives (ADR 2026-10.8kfx): a config won or deferred into eligibility, a node declaring, the
        // directory moving. The home reports through [homeRefresher]'s own hook.
        val homes = homes ?: return
        session.launch {
            merge(scopeConfigs.observeWinners().map { }, scopeConfigs.observeDeclared().map { }, homes.directory.map { })
                .drop(1)
                .collect { sync.onScopeTableChanged() }
        }
    }

    /** The relays every scope's own join ([ScopeSpools.base]): the person's own relays and the home. */
    private suspend fun baseRelays(): List<String> {
        val active = settings.activeSpoolUrls.first()
        val homes = homes ?: return active.toList()
        return ScopeSpools.base(active, homes.home.first()).toList()
    }

    /** One derivation's placement inputs (ADR 2026-10.8kfx), or null without a home store: every scope at every relay. */
    private suspend fun scopePlacement(): ScopePlacement? {
        val homes = homes ?: return null
        return ScopePlacement(
            configs = scopeConfigs.winners(),
            declared = scopeConfigs.declared(),
            home = homes.home.first(),
            active = settings.activeSpoolUrls.first(),
            parked = settings.disabledSpoolUrls.first(),
            directory = homes.directory.first(),
        )
    }

    /**
     * Turns a new validated default network into an early re-dial of every relay the plane is backing off
     * from (`ScopeSync.onRouteChanged`). Armed only while a relay is in use, so a phone with the plane off
     * — or every relay parked — registers no connectivity callback for it; the session's cancellation in
     * [stop] is what unregisters it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchRoute(session: CoroutineScope) {
        val sync = scopeSync ?: return
        session.launch {
            settings.activeSpoolUrls
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .flatMapLatest { armed -> if (armed) routeChanges else emptyFlow() }
                .collect { sync.onRouteChanged() }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        scopeSync?.stop()
        ticketRefresher?.stop()
        badgeRefresher?.stop()
        homeRefresher?.stop()
        scopeIssuer?.stop()
        transport.stop()
        // Clear this session's pending relays on the app scope (the session is about to die); capture
        // the instance so a fast restart reassigning `router` can't retarget the wrong one. Then tear
        // the session down — stopping the inbound collector, the metrics loop, and the watch* collectors.
        val session = sessionScope
        val sessionRouter = router
        scope.launch { sessionRouter.stop() }
        // The session's last metrics tick may be a minute away; bank what it credited before it dies.
        scope.launch { ledger.flush() }
        scope.launch { flushSeenStamps() }
        fieldLog?.let { log -> scope.launch { log.stop() } }
        session?.cancel()
        sessionScope = null
        // A stopped (or paused) mesh hears nothing, so it stops claiming anyone is reachable through it — and,
        // closed, it ignores a frame the cancelled session was still verifying.
        relayedPresence.stop()
        talkRadioPaths.clear()
    }

    /** The one place the router is built, so both constructions report their relays to the ledger alike. */
    private fun newRouter(routerScope: CoroutineScope) =
        MeshRouter(
            transport,
            routerScope,
            // The dedup window on the same clock as everything else here, so a test that moves the calendar
            // (the mesh-in-a-box lab) sees the window lapse; production's clock is the wall clock either way.
            seen = SeenSet(clock = clock),
            metrics = metrics,
            budget = ingressBudget,
            onRelayed = { env, to -> ledger.onHandedOff(env, to) },
            onDeliver = pipeline::onDeliver,
        )

    override fun spoolStatus(): List<SpoolStatus> = demoSpools ?: scopeSync?.status().orEmpty()

    override fun freeTicketAsking(): Set<String> = ticketRefresher?.asking?.value.orEmpty()

    override fun freeTicketWaiting(host: String): Boolean = ticketRefresher?.isWaiting(host) == true

    override suspend fun moveAnywhereHere(host: String): MoveResult = ticketRefresher?.move(host) ?: MoveResult.Unavailable

    /**
     * Demo-screenshot only: pin a fixed set of spool statuses, so the relay indicators (the chat header's
     * globe, the relay settings rows, the Diagnostics section) render against a plane that never opens a
     * socket. A demo build has no `ScopeSync` session at all, and an empty status list is indistinguishable
     * from "no relays" — which is precisely the state a capture must not be of. Called by [DemoSeeder];
     * deliberately off the [MeshController] interface, like [seedDemoTyping], so the production facade
     * stays clean.
     */
    fun seedDemoSpools(statuses: List<SpoolStatus>) {
        demoSpools = statuses
    }

    override fun refreshRelays() {
        scopeSync?.onScopeTableChanged()
        // The user's refresh means "look now": a scope whose table did not change is otherwise healed on its
        // next event or the 60 s tick — and this is also the lab's one poke past a clock jump.
        scopeSync?.onCustodyChanged()
    }

    /**
     * A node's frame was pulled from a commons this device joined (§7.4): it is a member, and a member of
     * the private instance's room is a contact — accepted, so it sits in Contacts, its DMs skip the
     * requests inbox and its peer row is protected from the sweep — exactly what importing their contact
     * card would do, minus the card. Never for our own frames coming back round.
     *
     * Accepted on the first sighting in the room only, as a card is imported once: a restart re-pulls the
     * whole room, and re-accepting on every frame would undo the user removing the member (ADR 2026-09.adgd).
     * The accept goes before the insert, so a crash between them re-accepts on the next pull.
     */
    internal suspend fun onCommonsMember(
        conversationId: String,
        nodeId: String,
    ) {
        if (nodeId == identity.nodeId()) return
        val store = commons ?: return
        if (!store.isMember(conversationId, nodeId)) settings.accept(nodeId)
        store.recordMember(conversationId, nodeId, clock())
    }

    /**
     * The DM half of a commons membership: a session with every member, so a DM between two of them can
     * ride the spool (a DM scope needs a confirmed session, and before one exists only the §3.5 pair scope
     * carries the first frame). Registered without urgency — eight at a time, the intro driver's own bound —
     * for the members whose prekey is pinned and whose session is not confirmed; a member with no prekey
     * yet has a profile on the way and is picked up next heal.
     */
    private suspend fun bootstrapCommonsSessions() {
        val members =
            commons
                ?.allMembers()
                ?.map { it.nodeId }
                ?.toSet()
                .orEmpty()
        if (members.isEmpty()) return
        val me = identity.nodeId()
        members
            .filter { it != me && ratchet.sessionFor(it)?.confirmed != true }
            .filter { ratchetPrekeyOf(peers.find(it)) != null }
            .forEach { introSync.wantIfRoom(it) }
    }

    /**
     * A DM to a commons member whose session is not confirmed yet: this frame carries the X3DH init and
     * would confirm the session on arrival, but over the Internet only a §3.5 pair scope can carry it
     * before then. Registering the intro is what names the pair scope — with urgency, the way a card
     * import does, because the user just pressed send. A non-member, or a confirmed session, is untouched.
     */
    private suspend fun wantCommonsIntro(recipientId: String) {
        if (commons == null) return
        if (ratchet.sessionFor(recipientId)?.confirmed == true) return
        if (commons.allMembers().none { it.nodeId == recipientId }) return
        introSync.want(recipientId)
    }

    override suspend fun importContact(peerId: String) {
        introSync.want(peerId)
        // The radio half of the bootstrap: ask neighbors for the profile (hop-by-hop), so a pair that shares
        // a mesh but never exchanged a profile still gets the prekey without waiting for a re-flood.
        keyExchange.want(peerId)
    }

    override suspend fun cancelIntro(peerId: String) = introSync.cancel(peerId)

    override fun introState(peerId: String): Flow<IntroState?> = introSync.state(peerId)

    override suspend fun ratchetState(): List<RatchetPeerState> =
        peers.observePeers().first().map { peer ->
            val session = ratchet.sessionFor(peer.nodeId)
            RatchetPeerState(
                peerId = peer.nodeId,
                name = peer.name,
                capRatchet = ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET) != 0L,
                peerPrekeyId = peer.prekeyId,
                peerPrekeyPinned = peer.prekeyPub != null,
                hasSession = session != null,
                confirmed = session?.confirmed == true,
                sendEpoch = session?.sendEpoch ?: 0,
                lastResetSentAt = session?.lastResetSentAt ?: 0L,
                establishedAt = session?.establishedAt ?: 0L,
                weAreInitiator = session?.weAreInitiator == true,
                highestPeAcked = session?.highestPeAcked ?: 0,
                rootHash = session?.root?.let { r -> sha256Hex(r).take(ROOT_HASH_HEX_CHARS) },
                prevRootExpiresAt = session?.prevRootExpiresAt ?: 0L,
                hasPeerInitAnchor = session?.peerInitEphPub != null,
                localEpochs = ratchet.debugLocalEpochs(peer.nodeId),
            )
        }

    override suspend fun forceRatchetReset(peerId: String): String? = pipeline.sendSessionReset(peerId, identity.nodeId(), clock())

    /**
     * The maintenance basket launched by [heal], or null before the first; a heal that finds it still running pokes
     * the radios and leaves the rest to it (two baskets at once — the pocket's motion trigger a second ahead of the
     * open app's resume — would run the check-then-act steps, a root mint or a prekey rotation, twice). Volatile:
     * the callers are the service's main thread, a ViewModel scope and the app scope.
     */
    @Volatile
    private var healBasket: Job? = null

    /** When [heal] last ran the three retention sweeps, on [clock]; stamped by the start's own sweep. */
    @Volatile
    private var lastRetentionSweepAt = 0L

    /** When [heal] last replayed the group frames in custody, on [clock]; stamped by the start's own replay. */
    @Volatile
    private var lastGroupReplayAt = 0L

    /**
     * Triggers an immediate rescan/reconnect (heartbeat alarm, device motion, an app resume) and runs the maintenance
     * basket. The radio poke is never withheld: a resume is what lifts the location app-op a Wi-Fi Aware plane is
     * waiting on (ADR 2026-09.535d) and buys the lonely discovery loop its one aggressive re-arm (ADR 2026-09.kb68).
     * The basket runs one at a time, and its two heaviest steps — the retention sweeps and the custody replay — only
     * once their hourly floors have passed: they enforce windows measured in hours and days, and the events they back
     * stop (a seed adopted, a roster pinned) have their own instant path (work item #63).
     */
    override fun heal() {
        if (!started) return
        transport.heal()
        if (healBasket?.isActive == true) return
        // Piggyback the forward-store TTL sweep on the 15-min heartbeat so it runs while backgrounded.
        // Also re-ask neighbors for any key we're still missing, in case the holder is reachable now but
        // never arrived as a fresh neighbor (so onNeighborAdded didn't fire) — belt-and-suspenders for the
        // ongoing-drops retry already driven by want()'s cooldown.
        healBasket =
            sessionScope?.launch {
                sweepExpired()
                keyExchange.retryMissing()
                ackSync.retryPending() // re-send broadcast/group ticks we still owe absent authors (+ age out old ones)
                dmAcks.flushDue() // tick the LoRa-held DM receipts whose hold has run out (ADR 054)
                if (clock() - lastRetentionSweepAt >= HealFloors.RETENTION_SWEEP_MS) sweepKeyRetention()
                if (clock() - lastGroupReplayAt >= HealFloors.GROUP_REPLAY_MS) replayUndeliveredGroupCustody()
                rotatePrekeyIfDue()
                republishProfileIfStale() // refresh the publish stamp before custody would refuse the frame
                broadcastSealedProfile() // catch sessions that only confirmed after the edit fired
                bootstrapCommonsSessions() // a session with every commons member, eight intros at a time (§7.4)
                introSync.retry() // re-send stale contact-card intros, settle confirmed ones, expire pair-scope grace
                mintGroupRootsIfDue() // mint a group's spool root when it is our turn (spec §3.2)
                metrics.onHealCompleted() // the lab's "the basket ran" signal; nothing above may be observed from outside
            }
    }

    /**
     * The three retention sweeps — epoch privs and skipped keys past the DM ratchet's PFS window, group chains and
     * skipped keys past the group window, rotated-away group roots past their drain — nine statements that used to run
     * on every heal. Their windows are 48 h and up, so once an hour ([HealFloors.RETENTION_SWEEP_MS]) keeps every guarantee;
     * the start runs them unconditionally and stamps the floor with everyone else.
     */
    private suspend fun sweepKeyRetention() {
        lastRetentionSweepAt = clock()
        ratchet.sweep(clock())
        groupRatchet.sweep(clock())
        groupRoots.sweep(clock())
    }

    /**
     * Rotates the ratchet signed prekey on its cadence (checked at start + every heal). A rotation bumps
     * the persisted profileVersion — the same monotonic clock a profile edit uses — so the fresh prekey
     * re-floods and replaces the custodied profile frame on every peer.
     */
    private suspend fun rotatePrekeyIfDue() {
        if (!identity.rotatePrekeyIfDue(clock())) return
        broadcastProfile()
    }

    /**
     * The first mesh start after a backup restore (`SettingsStore.restorePending`, set into the settings
     * file by the restore itself). The database came back with its ratchet and sender-key tables empty and
     * the identity with the prekeys it had when the backup was made, so three things put this phone back
     * on speaking terms with everyone it knows:
     *
     * - a **fresh signed prekey**, whatever the newest one's age — the phone the backup came from may have
     *   minted ids past these since, and a peer holding one of those against a different public key would
     *   initiate to a key we do not have;
     * - a **profile bump** ([broadcastProfile]), so the fresh prekey and the restored name outrank whatever
     *   version the old phone last published — peers order profiles on that number, never on the frame's
     *   own time;
     * - a **session reset toward every peer whose session the restore wiped** ([wipedPeers],
     *   [InboundPipeline.sendSessionReset]): the receiver adopts the fresh init, re-seals its still-unacked DMs
     *   of the last 24 h under it and force-flushes its group seeds (`docs/FORWARD_SECRECY_RATCHET.md` §7).
     *   Without it a peer notices nothing until three of its frames fail to open and its own heuristic fires,
     *   six hours apart — for a device that just lost *every* session, that is the slow path. Peers we hold no
     *   prekey for decline here as they would anywhere; their next profile bootstraps the session the
     *   ordinary way.
     *
     * The resets go as [RatchetSessions.ResetCause.AFTER_WIPE] (ADR 2026-09.qerd). The transport is already up, so a peer's backlog
     * may have tripped the heuristic's own reset by now, or a group post may have opened a session with a
     * plain init; a second root inside the peer's one-minute floor is refused and strands the pair for hours.
     * A session found here was made since the wipe, so it gets only the reset marker under it, or nothing when
     * a reset already went. They go before the profile bump, whose arrival makes the peer flush its seeds
     * under whatever session it holds — ahead of the reset, that is the old one, and the flush floor then
     * holds back the forced flush the reset asks for.
     *
     * Runs once: the flag is cleared at the end, and the group chains re-mint on the next group send by
     * themselves (advance rule 5 in `docs/GROUP_FORWARD_SECRECY.md`).
     */
    private suspend fun finishRestore() {
        if (!settings.restorePending.first()) return
        val me = identity.nodeId()
        Log.i(TAG, "finishing a backup restore: fresh prekey, session resets, profile bump")
        identity.rotatePrekey(clock())
        for (peer in wipedPeers(me)) {
            pipeline.sendSessionReset(peer, me, clock(), RatchetSessions.ResetCause.AFTER_WIPE)?.let {
                Log.i(TAG, "no session reset to $peer: $it")
            }
        }
        broadcastProfile()
        settings.clearRestorePending()
    }

    /**
     * Everyone a restore left without a session who could have sealed something to us under the old one: DM
     * peers, and the other members of every group we are in — seeds travel as control DMs over the pairwise
     * sessions, so a contact we share only a group with lost one too (#86).
     */
    private suspend fun wipedPeers(me: String): Set<String> =
        buildSet {
            messages.distinctConversations().filterTo(this) { Conversations.kindFor(it) == ConversationKind.DM }
            groups.active().flatMapTo(this) { GroupMembersStore.decode(it.members) }
            remove(me)
        }

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    override fun restart() {
        if (!started) return
        transport.stop()
        transport.start()
    }

    // No `started` gate: the hold is journaled, and the user may release it with the mesh off.
    override fun releaseInitiatorHold() = transport.releaseInitiatorHold()

    /**
     * Composes a chat message (optionally with an already-ingested image [attachment]), stores it
     * locally (unacked), and floods it to the mesh. The sender already holds the blob, so direct
     * neighbors will pull it by hash and it propagates outward from there.
     *
     * [recipientId] null sends to the broadcast room; a node id sends a 1:1 DM addressed to that
     * peer. A non-null [group] sends a group message (recipientId stays null) — the [GroupInfo] rides
     * on the frame so members can (re)build the group from it. All three still flood (no routing table
     * yet); only the addressed recipient(s) deliver/ack, while relays forward the frame untouched.
     *
     * In the Nearby room only, returns false without sending or storing anything if on-device content
     * filtering flags [text] as abusive (block-on-send); true once the message is stored locally and flooded.
     * A DM or a group message is never classified on the way out: its receivers screen it and hide it behind
     * their own Content filtering toggle (ADR 2026-10.9rh6). A room [attachment] was screened at ingest.
     *
     * `@Suppress("LongMethod")`: ktlint's one-arg-per-line wrapping of the room's and the sealed branch's
     * MessageEntity builds inflates the raw line count past detekt's LongMethod=60; the logic is two
     * straight-line branches, not complex. The sealed content is read off the row (`sealedContent`, #131).
     */
    @Suppress("LongMethod")
    override suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested?,
        mentions: List<Mention>,
        recipientId: String?,
        group: GroupInfo?,
        replyTo: ReplyRef?,
        styles: List<StyleSpan>,
        declared: DeclaredLangs,
    ): Boolean {
        val isRoom = recipientId == null && group == null
        // Whoever built it, a frame carries only what the wire does not imply: English by an English reader costs 0.
        val declared = declared.normalized()
        if (isRoom && isTextFlagged(text, "outgoing", isRoom = true)) return false
        // Indexed by the caller against exactly this text (it rebases across its own trim): normalised, never shifted.
        val spans = StyleSpans.normalize(text, styles)
        val me = identity.nodeId()
        val id = FrameId.new()
        val sentAt = clock()
        val conversationId = Conversations.idFor(me, recipientId, me, group?.id)

        // Broadcast room: plaintext (no fixed recipient set to encrypt to) — the legacy path, unchanged.
        if (isRoom) {
            // Declared by the sealed path's rule: a room post is never named, so only a large one says its size.
            val roomSize =
                attachment
                    ?.takeIf { BluetoothFetchGate.declaresSize(it.sizeBytes.toLong(), named = false) }
                    ?.sizeBytes
                    ?.toLong()
            messages.save(
                MessageEntity(
                    id = id,
                    senderId = me,
                    recipientId = null,
                    conversationId = conversationId,
                    body = text,
                    sentAt = sentAt,
                    received = false,
                    mentions = MentionStore.encode(mentions),
                    attachmentHash = attachment?.hash,
                    attachmentMime = attachment?.mime,
                    // Set for symmetry with the sealed path below. The composer does not offer voice notes
                    // in the room (ADR 034), so in practice this is always null here.
                    voiceDurationMs = attachment?.voice?.durationMs,
                    voicePeaks = attachment?.voice?.peaks,
                    attachmentAnimated = attachment?.animated == true,
                    // The room's hash already is the plaintext's, so only the picker's own bytes are worth a
                    // column: they are how the same original, picked again, finds this post (ADR 2026-10.mptm).
                    attachmentSourceHash = attachment?.sourceHash,
                    attachmentSize = roomSize,
                    styles = StyleStore.encode(spans),
                    bodyLang = declared.lang,
                    authorReads = declared.reads,
                ).withReply(replyTo)
                    .withVideo(attachment?.video),
            )
            val content =
                ChatContent(
                    body = text,
                    mentions = mentions,
                    attachmentHash = attachment?.hash,
                    attachmentMime = attachment?.mime,
                    replyTo = replyTo,
                    // The room's two additive fields (ADR 2026-10.hmfe): the declared size "Ask before large Bluetooth
                    // downloads" judges (ADR 2026-10.js2p), and a video's description for its bubble.
                    attachmentSize = roomSize,
                    video = attachment?.video,
                    // Cleartext in the room like the body (ADR 2026-10.p95v); absent while empty.
                    styles = spans,
                    // Cleartext in the room too (ADR 2026-10.t2h6's 2026-10-29 amendment); absent while implied.
                    lang = declared.langIndex,
                    reads = declared.readsIndex,
                )
            originateSigned(chatEnvelope(id, me, sentAt, recipientId = null, group = null, content))
            return true
        }

        // DM or group: end-to-end encrypt. The attachment (if any) is encrypted to its own key and
        // re-addressed by its ciphertext hash; body/mentions/attachment refs go into the sealed content.
        val sealedAttachment = attachment?.let { sealAttachment(it) }
        // Our own plaintext copy, built before the seal because the sealed content is read off it
        // ([sealedContent], the one builder this and [resealAndFlood] share — #131). Saved below regardless, so the
        // sender always sees their message.
        val row =
            MessageEntity(
                id = id,
                senderId = me,
                recipientId = recipientId,
                conversationId = conversationId,
                body = text,
                sentAt = sentAt,
                received = false,
                mentions = MentionStore.encode(mentions),
                attachmentHash = sealedAttachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
                // A voice note's duration/waveform, derived at ingest and written here rather than by the
                // composer: the row records the *ciphertext* hash, so a caller keying off the plaintext one
                // it staged would update nothing. The recipient derives the identical pair from the same
                // bytes when the blob lands (InboundPipeline.onObtained) — neither value is on the wire.
                voiceDurationMs = attachment?.voice?.durationMs,
                voicePeaks = attachment?.voice?.peaks,
                // A file's name and its plaintext length — the two facts a file bubble needs and cannot
                // read off the bytes. Null for an image or a voice note, whose bubbles describe themselves,
                // so an ordinary photo frame is byte-for-byte what it was before ADR 2026-09.qq2r — unless it is
                // large enough for a recipient's "Ask before large Bluetooth downloads" to gate it, which judges
                // the declared size alone (ADR 2026-10.js2p). They come off the sealed content on the receiving
                // side and off the staged ingest here, and are written against the same ciphertext hash for the
                // same reason.
                attachmentName = attachment?.name,
                attachmentSize =
                    attachment
                        ?.takeIf { BluetoothFetchGate.declaresSize(it.sizeBytes.toLong(), named = it.name != null) }
                        ?.sizeBytes
                        ?.toLong(),
                // Read off the frames at ingest, and by the recipient off the same bytes when they land.
                attachmentAnimated = attachment?.animated == true,
                // The plaintext's own name beside the ciphertext's, and the picker's bytes when ingest changed them:
                // what a later forward or repeat of this file finds it by (ADR 2026-10.mptm). A key-less forward of a
                // room picture already names its plaintext, so it records none.
                attachmentPlainHash = attachment?.hash?.takeIf { sealedAttachment?.key != null },
                attachmentSourceHash = attachment?.sourceHash,
                pendingKey = false,
                // Sealed off the row with the rest ([sealedContent]); the shell below never carries them.
                styles = StyleStore.encode(spans),
                bodyLang = declared.lang,
                authorReads = declared.reads,
            ).withReply(replyTo)
                // A video's duration, size and poster, sealed with the rest (ADR 2026-10.hmfe).
                .withVideo(attachment?.video)
        // The piggyback (ADR 054): a DM to a peer that reads inline acks carries the receipts we still owe
        // it for DMs that came over the board, in place of a standalone tick that would cost ~3 s of LoRa
        // air. Attached only in the v2 arm of the seal; a v1 fallback or a parked DM gives them back.
        val inlineAcks =
            inlineAcksFor(recipientId, group, text, spans, declared, replying = replyTo != null, attached = attachment != null)
        val inlineIds = inlineAcks.ids
        val envelope = sealEnvelopeFor(id, me, sentAt, recipientId, group, row.sealedContent(), inlineIds)
        if (inlineIds.isNotEmpty() && recipientId != null) {
            val carried = envelope != null && EncEnvelope.isDmRatchetVersion(envelope.v)
            if (carried) {
                metrics.onReceiptCoalesced(inlineAcks.dm.size)
                metrics.onReceiptRidden(inlineAcks.room.size)
                ackSync.rode(inlineAcks.room)
            } else {
                // Each half goes back to the hold that owns it. A room ack must never land in [dmAcks]:
                // that hold *originates* what it still holds when the debounce runs out, which is the
                // custody row the ride exists to avoid (ADR 2026-09.aa27).
                dmAcks.giveBack(recipientId, inlineAcks.dm)
                ackSync.giveBackRiding(recipientId, inlineAcks.room)
            }
        }
        // Persist our own plaintext copy regardless, so the sender always sees their message. A DM whose
        // recipient key isn't known yet is flagged pendingKey so handleProfile can retransmit it when the
        // recipient's profile (carrying the key) finally arrives (groups stay unsent, as before).
        messages.save(row.copy(pendingKey = envelope == null && group == null))
        if (envelope == null) {
            // No recipient's key is known yet — nothing can decrypt this. Saved locally above; a DM is
            // marked pendingKey and retransmitted on key arrival, a group message stays unsent.
            Log.w(TAG, "no known keys for recipient(s) of chat $id; not flooded yet")
            return true
        }
        if (recipientId != null && group == null) wantCommonsIntro(recipientId)
        // Expose the (ciphertext) attachment hash — and ONLY the hash — in the cleartext frame alongside the
        // sealed content, so a relaying carrier, blind to the encrypted refs, can still custody the blob.
        // The mime stays sealed in MessageContent (ADR 035): custody addresses bytes by hash and never needed
        // the type, so publishing it only told a carrier whether this was a photo or a voice note. The
        // decryption key is sealed there too, and a fresh per-send key means the ciphertext hash never
        // correlates identical images across sends — so this leaks only "this message carries ~N opaque bytes".
        originateSigned(
            chatEnvelope(
                id,
                me,
                sentAt,
                recipientId,
                group,
                ChatContent(
                    enc = envelope,
                    attachmentHash = sealedAttachment?.hash,
                ),
            ),
            chatHint(recipientId, group),
        )
        return true
    }

    /**
     * A DM the user wrote is [FanoutHint.CHAT]: the one DM-form frame that draws a ✓✓, so the LoRa reach gate may
     * probe with it (ADR 2026-10.a4pm). A group or room frame never rides the long-range plane, so it keeps the
     * default.
     */
    private fun chatHint(
        recipientId: String?,
        group: GroupInfo?,
    ): FanoutHint = if (recipientId != null && group == null) FanoutHint.CHAT else FanoutHint.CONTENT

    /** Builds a [FrameType.CHAT] routing envelope wrapping the given [content] payload. */
    private fun chatEnvelope(
        id: String,
        senderId: String,
        sentAt: Long,
        recipientId: String?,
        group: GroupInfo?,
        content: ChatContent,
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = senderId,
            sentAt = sentAt,
            recipientId = recipientId,
            group = group,
            payload = WireCodec.encodePayload(content),
        )

    /**
     * The seal chokepoint for every encrypted chat (compose-time [sendChat] and flush-time
     * [flushPendingFor]). A DM to a ratchet-capable peer — pinned profile advertising
     * [Protocol.CAP_RATCHET], both on one signed frame with any prekey — goes **v2** (the epoch-ratchet
     * session, created on first use); a group whose every member is ratchet-eligible goes **v2 group form**
     * (the sender-key chain, minted on first use, its seed distributed pairwise before the frame
     * floods); everything else takes the v1 static per-recipient wrap. An eligible seal can still fall
     * back to v1 (peer downgraded mid-session; a member's seed unsendable), which every build reads.
     * Null means nobody addressable holds a key at all — the caller parks the DM pendingKey.
     */
    private suspend fun sealEnvelopeFor(
        id: String,
        me: String,
        sentAt: Long,
        recipientId: String?,
        group: GroupInfo?,
        content: MessageContent,
        // Receipts to carry inline (ADR 054) — v2 DM arm only; every other form seals [content] as given.
        inlineAcks: List<String> = emptyList(),
    ): EncEnvelope? {
        val thread = group?.id ?: recipientId.orEmpty()
        val aad = MessageCrypto.header(id, me, sentAt, thread)
        if (group == null && recipientId != null && recipientId != me) {
            val peer = peers.find(recipientId)
            val bundle = peer?.pubKey?.let { PublicKeyBundle.decode(it) }
            val capable = bundle != null && (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET != 0L
            if (capable) {
                val (plaintext, scheme) = content.copy(acks = inlineAcks.takeIf { it.isNotEmpty() }).sealBytes(peer.readsCryptoV3())
                val sealed = ratchet.sealDm(recipientId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), plaintext, aad, clock(), scheme)
                if (sealed != null) {
                    metrics.onDmSealedV2()
                    if (scheme == EncEnvelope.VERSION_DM_V3) metrics.onDmSealedV3()
                    return sealed
                }
                metrics.onDmSealedV1Fallback()
            }
        }
        if (group != null && recipientId == null) {
            sealGroupRatchet(group, me, content, aad)?.let { return it }
        }
        return messageCrypto.seal(content.encode(), aad, recipientBundles(recipientId, group, me))
    }

    /**
     * The ratchet half of the group seal: all-or-nothing per message. Every other member must be
     * ratchet-eligible ([groupRatchetEligible]) AND a mint's seed must seal to every member
     * ([distributeGroupSeed] — v2-or-nothing, never a v1-wrapped seed); any shortfall returns null and
     * the whole message falls back to v1, which every build reads. Re-evaluated per send, so the group
     * upgrades the instant the last capable profile lands. A minted seed is distributed and its outbox
     * rows recorded BEFORE the group frame floods (first frames may still race their seed — benign:
     * NO_KEY now, the custody re-serve decrypts once the seed lands).
     */
    private suspend fun sealGroupRatchet(
        group: GroupInfo,
        me: String,
        content: MessageContent,
        aad: ByteArray,
    ): EncEnvelope? {
        val members = group.members.filter { it != me }
        if (members.isEmpty() || !groupRatchetEligible(members)) return null
        val sealed = groupRatchet.sealGroup(group.id, me, content.encode(), aad, clock())
        if (sealed == null) {
            metrics.onGroupSealedV1Fallback()
            return null
        }
        val minted = sealed.minted
        if (minted != null && !distributeGroupSeed(group.id, members, minted, me)) {
            // A member couldn't receive the seed (stale prekey, dead session): stay v1 for the whole
            // message. The mint is already persisted — harmless; the next eligible send reuses it and
            // re-attempts the missing distributions.
            metrics.onGroupSealedV1Fallback()
            return null
        }
        metrics.onGroupSealedRatchet()
        return sealed.env
    }

    /** Whether every one of [members] has a pinned profile carrying [Protocol.CAP_RATCHET] and a
     *  valid prekey — the seed rides the DM ratchet, so DM-sealability to every member is the
     *  prerequisite (one bit covers both ratchet forms; they ship together). */
    private suspend fun groupRatchetEligible(members: List<String>): Boolean =
        members.all { nodeId ->
            val peer = peers.find(nodeId) ?: return@all false
            // An identity key X25519 refuses can never take a seed (#172): the group goes v1, as for a member
            // without the bit, rather than minting and retrying a distribution that cannot succeed.
            val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) }
            bundle != null &&
                RatchetCrypto.acceptsPublicKey(bundle.dhPublicKey()) &&
                (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET != 0L &&
                ratchetPrekeyOf(peer) != null
        }

    /**
     * Distributes [seed] to every member as a `CTL_GROUP_KEY` v2 ctl DM (flooded + custodied like any
     * chat, never persisted/notified/acked as a message) and records the outbox rows. **Never v1**: a
     * static-wrapped seed would void the epoch's forward secrecy, so a member whose session can't seal
     * fails the whole distribution (the caller falls back to v1 for the message). Includes blocked
     * members — blocking is local presentation (ADR 010) and must stay invisible. Never called while
     * holding the shared ratchet lock ([RatchetSessions.sealDm] takes it per member).
     */
    private suspend fun distributeGroupSeed(
        groupId: String,
        members: List<String>,
        seed: GroupSeed,
        me: String,
    ): Boolean {
        val payload = groupKeyPayload(groupId, listOf(seed))
        return members.all { memberId -> sendSeedDm(groupId, memberId, payload, seed.epoch, me) }
    }

    /**
     * Seals + floods one group-key ctl DM and stamps the outbox; false when the member can't receive v2.
     *
     * [epoch] is null for a **root-only** gossip — a member that holds a shared root but has never sealed
     * a group frame has no seed to distribute. The outbox stamp is skipped there rather than recording a
     * bogus epoch 0, which would read as "we distributed something" and suppress the real first
     * distribution's re-send triggers.
     */
    private suspend fun sendSeedDm(
        groupId: String,
        memberId: String,
        payload: MessageContent,
        epoch: Int?,
        me: String,
    ): Boolean {
        val peer = peers.find(memberId)
        val bundle = peer?.pubKey?.let { PublicKeyBundle.decode(it) } ?: return false
        val id = FrameId.new()
        val now = clock()
        val aad = MessageCrypto.header(id, me, now, memberId)
        val sealed = ratchet.sealDm(memberId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), payload.encode(), aad, now) ?: return false
        if (epoch != null) {
            groupRatchet.markKeySent(groupId, memberId, epoch, now)
            metrics.onGroupSeedSent()
        }
        payload.gk?.gr?.let { lastRootGossipVersion[groupId to memberId] = it.version }
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = memberId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        return true
    }

    // (groupId, memberId) -> last seed (re-)send toward them, bounding every proactive plane (profile
    // arrival, neighbor join, session reset) and the key-request responder to one send per floor window
    // per pairwise root (see [seedSendFloorOpen]).
    private val lastSeedSendAt = ConcurrentHashMap<Pair<String, String>, SeedSend>()

    /** When a seed last went to a member, and a fingerprint of the pairwise root it went under (null: none yet). */
    private class SeedSend(
        val at: Long,
        val root: Int?,
    )

    // (groupId, memberId) -> the group-root version we last gossiped to them. The root has no ack (unlike
    // an epoch seed), so without this a member whose seeds are all acked would still draw a root-only ctl
    // DM on every profile re-arrival and neighbor re-join, forever. In-memory on purpose: losing it costs
    // one redundant, idempotent re-gossip per member per process, which is the cheap side of the trade.
    private val lastRootGossipVersion = ConcurrentHashMap<Pair<String, String>, Int>()

    /**
     * The group analogue of [flushPendingFor], fired when [memberId]'s profile (re)arrives, when they
     * appear as a neighbor, and after their session reset: for every non-left group we share with a
     * live send chain, re-send the current seed if the outbox says this member hasn't acked it — the
     * "member's prekey arrived later" / partition-merge / wipe healing paths, floored per
     * (group, member) so profile re-floods and link flaps can't turn it into chatter.
     *
     * [force] bypasses the acked-epoch short-circuit and is passed ONLY by the session-reset path: a
     * reset means the peer lost their DB, so an outbox row saying they acked our current epoch is
     * exactly the stale state that would otherwise swallow the re-send — the wipe they just recovered
     * from is what invalidated the ack (the ADR 017 "only wipe-side seed plane" contract). The
     * per-(group, member) floor still applies either way, so a reset can't be a re-send amplifier.
     * Internal for the same reason [redistributeGroupKey] is: the guard is unreachable end-to-end from
     * a JVM rig (it needs an inbound reset through a full receive stack), so the test drives it directly.
     */
    internal suspend fun flushPendingGroupKeysFor(
        memberId: String,
        force: Boolean = false,
    ) {
        val me = identity.nodeId()
        groups.groupsWith(memberId).forEach { group ->
            val chain = groupRatchet.currentSeeds(group.groupId).firstOrNull()
            val outbox = groupRatchet.keySend(group.groupId, memberId)
            val seedDue = chain != null && (force || outbox == null || outbox.ackedEpoch < chain.epoch)
            // A held root is its own reason to send even when the seed half has nothing to say: this is
            // the trigger set (profile arrival, neighbor join, session reset) that reaches a member who
            // was away when we minted, and without it the root would wait for the group's next natural
            // mint — hours to days. Only until we have gossiped THIS version to them, though: a root
            // carries no ack, so an unconditional "we hold one" would re-send forever. Root-only sends
            // carry no epoch, so they never touch the outbox.
            val heldVersion = groupRoots.find(group.groupId)?.takeIf { it.root != null }?.version
            val rootDue = heldVersion != null && lastRootGossipVersion[group.groupId to memberId] != heldVersion
            if (!seedDue && !rootDue) return@forEach
            if (!seedSendFloorOpen(group.groupId, memberId)) return@forEach
            val seeds = if (seedDue) listOfNotNull(chain) else emptyList()
            sendSeedDm(group.groupId, memberId, groupKeyPayload(group.groupId, seeds), seeds.firstOrNull()?.epoch, me)
        }
    }

    /**
     * Answers a member's `CTL_GROUP_KEY_REQ` (the pipeline's redistributeGroupKey lambda): verify the
     * requester is a current member of a non-left group, apply the per-(group, member) floor, and
     * re-seal our **current + draining previous** seeds in one ctl DM. Deliberately NEVER advances the
     * epoch — advance-on-request would hand any member a rekey-fan-out amplifier.
     */
    internal suspend fun redistributeGroupKey(
        groupId: String,
        requesterId: String,
    ) {
        val group = groups.find(groupId) ?: return
        if (group.left || requesterId !in GroupMembersStore.decode(group.members)) return
        val seeds = groupRatchet.currentSeeds(groupId)
        if (seeds.isEmpty()) return
        if (!seedSendFloorOpen(groupId, requesterId)) return
        sendSeedDm(groupId, requesterId, groupKeyPayload(groupId, seeds), seeds.first().epoch, identity.nodeId())
    }

    // ---- the spool plane's shared group root (docs/SPOOL_PROTOCOL.md §3.2) ----

    /**
     * The one builder for every `CTL_GROUP_KEY` we emit, so the four emit sites (seed distribution, the
     * re-send flush, the key-request answer, the root gossip) cannot drift on whether the root rides
     * along. The spec's rule is simply "on **every** seed send and key-request response from any member
     * who holds a root", which is exactly what routing them all through here enforces.
     *
     * The founding roster rides on the same rule (`GroupKeyPayload.group`, spec C-3.2-16): every
     * distribution carries it, so a member holding no row for the group — one reachable only over a
     * relay, whose DM scope carries the seed and whose group scope cannot derive without the root the seed
     * carries — pins the group from the seed itself (`InboundPipeline.pinRosterFromSeed`, work item #47).
     * The roster half only ([GroupEntity.toFoundingInfo]): the photo is a blob and rides the group frames.
     */
    private suspend fun groupKeyPayload(
        groupId: String,
        keys: List<GroupSeed>,
    ): MessageContent {
        val held = groupRoots.find(groupId)?.takeIf { it.root != null }
        return MessageContent(
            body = "",
            ctl = MessageContent.CTL_GROUP_KEY,
            gk =
                GroupKeyPayload(
                    groupId = groupId,
                    keys = keys,
                    gr = held?.let { GroupRootPayload(root = checkNotNull(it.root), version = it.version, minter = it.minter) },
                    group = groups.find(groupId)?.toFoundingInfo(),
                ),
        )
    }

    override fun mintGroupRoots() {
        if (!started) return
        sessionScope?.launch { mintGroupRootsIfDue() }
    }

    /**
     * The mint pass, on the 15-min heal heartbeat and on the [mintGroupRoots] nudge: for every group we
     * hold, become eligible (plane on, fully ratchet-capable — spec §3.3), stamp the grace clock once,
     * and mint when [GroupRootPolicy.mintDue] says it is our turn. One rule covers both mints: version 1
     * when we hold no root, `version + 1` when a processed departure left a re-mint owed.
     *
     * The eligibility stamp is written **before** the first decision on purpose — the grace is measured
     * from it, so a device that never stamps never waits and never mints.
     */
    private suspend fun mintGroupRootsIfDue() {
        if (!settings.spoolEnabled.first()) return
        val me = identity.nodeId()
        val now = clock()
        groups.active().forEach { group ->
            val members = GroupMembersStore.decode(group.members)
            val others = members.filter { it != me }
            // A group we are no longer in, or one everyone else has left, has nothing to carry over the
            // Internet — the same emptiness guard `sealGroupRatchet` applies before minting an epoch.
            if (me !in members || others.isEmpty() || !groupRatchetEligible(others)) return@forEach
            groupRoots.markEligible(group.groupId, now)
            val state = groupRoots.find(group.groupId)
            val version =
                GroupRootPolicy.mintDue(
                    state = state,
                    selfId = me,
                    preferredMinter = GroupRootPolicy.preferredMinter(group.createdBy, members),
                    now = now,
                ) ?: return@forEach
            groupRoots.upsert(
                GroupRootPolicy.rotated(state, group.groupId, GroupRootPolicy.newRoot(), version, me, now),
            )
            metrics.onGroupRootMinted()
            // Our own mint is a root landing like an adopted one: the scope it derives subscribes now.
            scopeSync?.onScopeTableChanged()
            gossipGroupRoot(group.groupId)
        }
    }

    /**
     * Fans our held root to every other member as a `CTL_GROUP_KEY`, carrying whatever seeds we have (an
     * empty list is the normal shape before we have ever sealed a group frame). Floored per
     * (group, member) by the same clock the seed re-sends use — that floor is the ONLY bound on root
     * chatter, deliberately, because bounding adoption instead would strand a device on a dead lineage.
     *
     * [skip] drops the member we just learned this root from: echoing it straight back is inert (their
     * own root is never strictly newer than itself) and doubles the traffic of every convergence round.
     */
    private suspend fun gossipGroupRoot(
        groupId: String,
        skip: String? = null,
    ) {
        val group = groups.find(groupId)?.takeIf { !it.left } ?: return
        if (groupRoots.find(groupId)?.root == null) return
        val me = identity.nodeId()
        val seeds = groupRatchet.currentSeeds(groupId)
        val payload = groupKeyPayload(groupId, seeds)
        GroupMembersStore.decode(group.members).filter { it != me && it != skip }.forEach { memberId ->
            if (seedSendFloorOpen(groupId, memberId)) {
                sendSeedDm(groupId, memberId, payload, seeds.firstOrNull()?.epoch, me)
            }
        }
    }

    /**
     * Adopts a gossiped root, inside the ctl DM's commit so it lands atomically with the chain advance
     * that carried it. Returns whether anything changed.
     *
     * The sender gates mirror the seed path exactly (group held, not left, sender in the effective
     * roster); [GroupRootPolicy.adoptable] adds the spec's two insider-DoS bounds — the minter must be a
     * founding-roster member, and the version must stay inside the ceiling and jump bound.
     */
    private suspend fun adoptGroupRoot(
        senderId: String,
        gk: GroupKeyPayload,
        now: Long,
    ): Boolean {
        val gr = gk.gr ?: return false
        val group = groups.find(gk.groupId) ?: return false
        if (group.left || senderId !in GroupMembersStore.decode(group.members)) return false
        val held = groupRoots.find(gk.groupId)
        if (!GroupRootPolicy.adoptable(gr, foundingRoster(group), held)) return false
        groupRoots.upsert(GroupRootPolicy.rotated(held, gk.groupId, gr.root, gr.version, gr.minter, now))
        metrics.onGroupRootAdopted()
        return true
    }

    /**
     * The post-commit half of every inbound `CTL_GROUP_KEY`, in both directions of the root exchange.
     *
     * **Adopted**: pass the newer root on — the gossip that makes a lineage collapse across the whole
     * roster rather than only the members its minter could reach — and wake the Internet plane so the
     * rotated scope subscribes now instead of at the next reconcile tick.
     *
     * **Not adopted**: if the sender's gossip shows they are *behind* us (an older root, or none at all),
     * send ours back. This is the anti-entropy half, and it is load-bearing rather than belt-and-braces:
     * the root has no ack, so [lastRootGossipVersion] would otherwise suppress a re-send forever after a
     * single lost gossip, and a stale gossip is the only evidence that loss ever happened. Bounded by the
     * ordinary per-(group, member) floor, and self-terminating — once they adopt, their next distribution
     * carries exactly our `(version, minter)` and this branch stops firing.
     */
    private suspend fun onGroupRootCtl(
        senderId: String,
        gk: GroupKeyPayload,
        adopted: Boolean,
    ) {
        if (adopted) {
            // A root just landed, so a group scope may exist that the table does not derive yet: re-derive
            // it now rather than on the 60 s poll (the same reason a pasted invite subscribes at once) — for
            // a member whose only plane is the relay, this is the pull that fetches the founding frame.
            scopeSync?.onScopeTableChanged()
            scopeSync?.onCustodyChanged()
            gossipGroupRoot(gk.groupId, skip = senderId)
            return
        }
        val held = groupRoots.find(gk.groupId)?.takeIf { it.root != null } ?: return
        val theirs = gk.gr
        // They can't be holding anything NEWER — that would have been adopted above — so "not exactly
        // ours" means "behind ours".
        if (theirs != null && theirs.version == held.version && theirs.minter == held.minter) return
        if (groups.find(gk.groupId)?.left != false) return
        if (!seedSendFloorOpen(gk.groupId, senderId)) return
        val seeds = groupRatchet.currentSeeds(gk.groupId)
        sendSeedDm(gk.groupId, senderId, groupKeyPayload(gk.groupId, seeds), seeds.firstOrNull()?.epoch, identity.nodeId())
    }

    /**
     * The Internet plane's view of the local blob store (spec §9.5). Content-addressed and
     * write-once, so `save` is the same ingest the radio path performs — the row's mime over the fetcher's
     * hint, then the receive-side screen, so a group photo or an avatar the spool brought carries a verdict
     * before the obtained hook reads it (#109). A re-arrival is a no-op at the DAO's
     * `OnConflictStrategy.IGNORE`, and GC still bounds it through the ordinary references.
     *
     * `internal` rather than private only so `MeshManagerTest` can pin `save` and `refused` directly;
     * nothing outside this file constructs one.
     */
    internal fun scopeBlobs(): ScopeBlobs =
        object : ScopeBlobs {
            override suspend fun has(aHash: String): Boolean = blobs.exists(aHash)

            override suspend fun bytes(aHash: String): ByteArray? = blobs.bytes(aHash)

            // The row's length, not its bytes: a ticket's `am` check runs before every upload look (C-7.6-32).
            override suspend fun size(aHash: String): Int? = blobs.sizes(listOf(aHash))[aHash]?.toInt()

            // [mime] is only the fetcher's hint (since ADR 035 a sealed frame carries none); `ingest` prefers
            // what our own decrypted row says, and screens the bytes exactly as a radio pull would.
            override suspend fun save(
                aHash: String,
                mime: String,
                bytes: ByteArray,
            ) = blobStore.ingest(aHash, mime, bytes)

            override suspend fun refused(aHash: String): Boolean = refusedImage(aHash)
        }

    /**
     * Whether screening refused [hash] while content filtering is on — a photo whose bytes were dropped and
     * whose verdict was kept so that nothing fetches it again (ADR 2026-09.nxcq). Read by both re-fetch paths:
     * [rewantMissingBlobs] on the radios and the spool's attachment pass through [scopeBlobs].
     */
    private suspend fun refusedImage(hash: String): Boolean =
        settings.contentFilteringEnabled.first() && imageScreening.isImageFlagged(hash)

    /**
     * The scope table's group half: every group we hold that has a root, paired with the founding roster
     * the frame-set rule vets senders against and the rotated-away lineage while it drains.
     */
    private suspend fun groupScopeRoots(): List<GroupScopeRoots> {
        val states = groupRoots.all().filter { it.root != null }.associateBy { it.groupId }
        if (states.isEmpty()) return emptyList()
        return groups.active().mapNotNull { group ->
            val state = states[group.groupId] ?: return@mapNotNull null
            GroupScopeRoots(
                groupId = group.groupId,
                roster = foundingRoster(group),
                members = GroupMembersStore.decode(group.members).toSet(),
                root = checkNotNull(state.root),
                rootVersion = state.version,
                prevRoot = state.prevRoot,
                prevRootVersion = state.prevVersion,
                prevRootExpiresAt = state.prevExpiresAt,
            )
        }
    }

    /**
     * The pair scopes (spec §3.5): one per peer the intro driver names, keyed by the identity pair secret
     * — computable only once the peer's bundle is pinned, which is exactly why a pulled frame from such a
     * scope always passes the carry gate. A malformed pin is skipped rather than thrown on.
     */
    private suspend fun pairScopeRoots(): List<PairScopeRoots> {
        val wanted = introSync.pairPeers()
        if (wanted.isEmpty()) return emptyList()
        val ownPriv = identity.dhIdentityPrivate()
        return wanted.mapNotNull { peerId ->
            val bundle = peers.find(peerId)?.pubKey?.let { PublicKeyBundle.decode(it) } ?: return@mapNotNull null
            runCatching { PairScopeRoots(peerId, ScopeCrypto.pairSecret(ownPriv, bundle.dhPublicKey())) }.getOrNull()
        }
    }

    /**
     * The **founding** roster: the effective members plus everyone who has departed. That is the set the
     * group id was derived from, and the set the scope frame-set rule vets against — a leaver is already
     * departed when its own `groupleave` is evaluated, and a departed member's pre-departure frames stay
     * legitimately re-servable. Safe because the departure re-mint rotates the scope id out from under
     * them (spec §4.4).
     */
    private fun foundingRoster(group: GroupEntity): Set<String> =
        (GroupMembersStore.decode(group.members) + GroupMembersStore.decode(group.departed)).toSet()

    /**
     * Re-enters our OWN custody's undelivered group chat frames through the inbound pipeline. A group
     * frame that arrives before its sender's seed is dropped locally but still custodied by us as a
     * carrier — and once WE hold it, no peer ever re-serves it (our digest already folds its id, so
     * there is no divergence to cue a re-offer). The doc's "custody re-serve decrypts once the seed
     * lands" therefore needs this local half: replay is idempotent (delivered frames stop at the
     * pre-decrypt exists-gate; still-keyless ones re-count a NO_KEY drop, which is exactly what feeds
     * the key-request heuristic that was otherwise starved). Fired on seed adoption for the matching
     * (group, sender) — the instant heal — and from heal()/startup as the backstop.
     */
    private suspend fun replayCustodiedGroupFrames(
        groupId: String?,
        senderId: String?,
    ) {
        val me = identity.nodeId()
        // The store answers the group-chat-from-others question itself (an indexed read, zero rows on a phone in no
        // group); what is left to filter here is the instant path's (group, sender) narrowing.
        forwardStore
            .liveGroupChatFrames(me, clock())
            .filter { frame ->
                val env = frame.envelope
                (groupId == null || env.group?.id == groupId) &&
                    (senderId == null || env.senderId == senderId)
            }.forEach { frame ->
                if (messages.exists(frame.envelope.id)) return@forEach
                // Replay bypasses the router (no second flood), exactly like PendingInbound's replay;
                // onDeliver's verify/custody/ack steps are all idempotent. Each frame runs to completion as a
                // router-admitted one does (ADR 2026-10.f82h): a stop between its commit and its tick would leave
                // the row in place, and this backstop stops at the exists-gate. The loop between frames stays
                // cancellable, so a stop still ends the pass.
                withContext(NonCancellable) {
                    pipeline.onDeliver(
                        WireEnvelope(relay = false, sig = frame.sig, signed = frame.signed),
                        frame.envelope,
                        frame.envelope.senderId,
                    )
                }
            }
    }

    /**
     * The heal/startup backstop: every undelivered group frame in custody, any group, any sender. The start runs it
     * once; a heal only past [HealFloors.GROUP_REPLAY_MS] — it is the net under the instant replays, not their trigger.
     */
    private suspend fun replayUndeliveredGroupCustody() {
        lastGroupReplayAt = clock()
        replayCustodiedGroupFrames(groupId = null, senderId = null)
    }

    /**
     * The DM half of [replayCustodiedGroupFrames], fired on **first sight** of a group: re-enters every
     * custodied chat DM addressed to us from the new roster that never produced a message row. A sender-key
     * seed that outran its roster is parked in memory (`PendingGroupKeys`) and lost with the process — but it
     * was custodied before it was parked, and once we hold it the same three doors are shut as for the group
     * frame: no peer re-serves it (our digest folds its id), the creator's proactive re-send sits behind its
     * [SEED_RESEND_FLOOR_MS], and the key-request heuristic wants three unreadable frames where a new group
     * has one. So the member sat on an unreadable first message for up to the floor (found by the `mesh/lab`
     * restart-with-a-parked-seed case). Replay is idempotent: a delivered DM stops at the exists-gate, a ctl
     * DM the ratchet already consumed drops as a duplicate, and the lost seed opens because a parked frame
     * never advanced the chain. Bounded by the roster and the custody TTL; oldest first so the chain walks
     * forward.
     *
     * [except] is the frame whose roster is creating the row right now (a seed carrying `GroupKeyPayload.group`,
     * `InboundPipeline.pinRosterFromSeed`): it is already in custody by the time the pipeline dispatches it, so
     * without the exclusion this replay would re-enter it nested inside its own pin — the nested pass would
     * adopt, ack and gossip from under the pin, and the outer pass's commit would then silently find its chain
     * index consumed. The outer pass is the one that must do the work; the frame is still being processed.
     */
    private suspend fun replayCustodiedSeedDms(
        groupId: String,
        except: String?,
    ) {
        val me = identity.nodeId()
        val group = groups.find(groupId) ?: return
        val senders = GroupMembersStore.decode(group.members).toSet() - GroupMembersStore.decode(group.departed).toSet() - me
        forwardStore
            .liveFrames(clock())
            .filter { frame ->
                val env = frame.envelope
                env.id != except &&
                    env.type == FrameType.CHAT &&
                    env.recipientId == me &&
                    env.group == null &&
                    env.senderId in senders
            }
            // Ratchet-form only: the duplicate drop is what makes re-entering a consumed DM a no-op, and a seed
            // is never anything else. A legacy envelope has no such guard and must not be re-delivered.
            .filter { frame ->
                WireCodec
                    .decodePayload<ChatContent>(frame.envelope.payload)
                    ?.enc
                    ?.v
                    ?.let(EncEnvelope::isDmRatchetVersion) ==
                    true
            }.sortedBy { it.envelope.sentAt }
            .forEach { frame ->
                if (messages.exists(frame.envelope.id)) return@forEach
                // As in [replayCustodiedGroupFrames]: one frame runs to completion, the pass stays cancellable.
                withContext(NonCancellable) {
                    pipeline.onDeliver(
                        WireEnvelope(relay = false, sig = frame.sig, signed = frame.signed),
                        frame.envelope,
                        frame.envelope.senderId,
                    )
                }
            }
    }

    /**
     * Checks-and-stamps the per-(group, member) seed re-send floor. The floor counts only sends under the
     * pairwise root we hold with [memberId] now (ADR 2026-09.qerd): a seed sealed under a root the member has
     * since left is one it can never open, so it must not hold back the one that replaces it. That is the
     * restored member's case — our post went out before we adopted its reset, sealed under the session its
     * restore wiped, and the forced flush the reset asks for then met the floor that send had stamped. A root
     * changes only through the ratchet's own rate-limited paths, so this adds at most one send per change; a
     * bare reset marker under an unchanged root stays floored, which keeps the reset from being an amplifier.
     */
    private suspend fun seedSendFloorOpen(
        groupId: String,
        memberId: String,
    ): Boolean {
        val key = groupId to memberId
        val now = clock()
        val root = ratchet.sessionFor(memberId)?.root?.contentHashCode()
        val last = lastSeedSendAt[key]
        if (last != null && last.root == root && now - last.at < SEED_RESEND_FLOOR_MS) return false
        lastSeedSendAt[key] = SeedSend(now, root)
        return true
    }

    /** The peer's pinned, already-verified ratchet prekey (base64-decoded), or null when absent/garbled. */
    private fun ratchetPrekeyOf(peer: PeerEntity?): RatchetEngine.PeerPrekey? {
        val prekeyId = peer?.prekeyId ?: return null
        val pub = peer.prekeyPub?.let { runCatching { b64d(it) }.getOrNull() } ?: return null
        return RatchetEngine.PeerPrekey(id = prekeyId, pub = pub)
    }

    /**
     * Builds (does not sign) the sealed broadcast/group delivery tick for [ackIds] as a `CTL_RECEIPT`
     * ctl DM to [authorId], or null when the author can't read one (no pin / no CAP_RATCHET / seal
     * failed) and AckSync falls back to the cleartext receipt. A single id rides the original `ack`
     * field; a batch rides the additive `acks` list (a custody-escalated tick covering every id — one
     * chain key however many messages it acks). Deliberately NO blocked gate: a blocked author's
     * broadcast/group message is still ticked (ADR 010 — blocking is local presentation and must stay
     * invisible; the seed distribution takes the same posture). Sealing consumes a chain key, so
     * AckSync seals each owed tick/batch once and never re-seals it.
     */
    private suspend fun sealDeliveryTickEnvelope(
        authorId: String,
        ackIds: List<String>,
    ): SealedTick? {
        val peer = peers.find(authorId) ?: return null
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return null
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return null
        val me = identity.nodeId()
        val id = FrameId.new()
        val now = clock()
        val aad = MessageCrypto.header(id, me, now, authorId)
        val (plaintext, scheme) =
            when (ackIds.size) {
                1 -> MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = ackIds.single())
                else -> MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = ackIds)
            }.sealBytes(peer.readsCryptoV3())
        val sealed =
            ratchet.sealDm(authorId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), plaintext, aad, now, scheme)
                ?: run {
                    metrics.onReceiptSealedFallback()
                    return null
                }
        metrics.onReceiptSealed()
        val v3 = scheme == EncEnvelope.VERSION_DM_V3
        if (v3) metrics.onDmSealedV3()
        return SealedTick(
            env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = id,
                    senderId = me,
                    sentAt = now,
                    recipientId = authorId,
                    payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
                ),
            v3 = v3,
        )
    }

    /**
     * A sealed tick and the scheme it was actually sealed under — the unsigned decision below must follow
     * the seal, not the capability read: a content the compact codec refused fell back to v2 and must stay
     * signed.
     */
    private class SealedTick(
        val env: RelayEnvelope,
        val v3: Boolean,
    )

    /**
     * The live-link form of the tick: `relay = false` (point-to-point like the cleartext tick it replaces —
     * never flooded or custodied), for AckSync to send straight to a linked author. **Unsigned** when it
     * sealed v3 (ADR 059): its AEAD's associated data binds id, sender, sentAt, recipient and the ratchet
     * header, so the frame signature carried nothing the recipient could not already check — and the
     * 64 bytes it cost were the difference between one packet and two on every fast plane. Signed as before
     * toward a peer that seals v2.
     */
    private suspend fun sealDeliveryTick(
        authorId: String,
        ackIds: List<String>,
    ): WireEnvelope? =
        sealDeliveryTickEnvelope(authorId, ackIds)?.let { tick ->
            if (tick.v3) unsigned(tick.env).also { metrics.onTickUnsigned() } else sign(tick.env, relay = false)
        }

    /**
     * The custody-escalated form: the same sealed tick ORIGINATED (`relay = true` — flooded, custodied,
     * spool-eligible via the DM-scope frame-set rule) so it converges to an author who is out of link
     * range, exactly like the message it acks. Returns false when the seal fails and AckSync should
     * fall back to its per-id cleartext entries.
     */
    private suspend fun originateDeliveryTick(
        authorId: String,
        ackIds: List<String>,
        riderCap: Int = AckSync.MAX_BATCH_ACKS,
    ): Boolean {
        // The best carrier there is (ADR 2026-09.aa27): this frame is already sealed to that author, already
        // originated and already custodied, so the room ticks waiting for a ride cost it a few bytes and no
        // row of their own. [riderCap] is the hop's: a frame built for the 3-packet LoRa hop takes at most
        // `DmAckCoalescer.MAX_LORA_TICK_ACKS` ids in all, riders included (ADR 2026-09.y5f3).
        val riding = ackSync.takeRiding(authorId, riderCap - ackIds.size)
        val tick = sealDeliveryTickEnvelope(authorId, ackIds + riding)
        if (tick == null) {
            ackSync.giveBackRiding(authorId, riding)
            return false
        }
        originateSigned(tick.env, FanoutHint.TICK)
        metrics.onReceiptCustodied()
        ackSync.rode(riding)
        metrics.onReceiptRidden(riding.size)
        return true
    }

    /**
     * The ride deadline's spool form (ADR 2026-09.y5f3): the same sealed tick, **signed** and `relay = false`
     * — signed because `ScopeCrypto.seal` takes the 64-byte signature, `relay = false` because it is pushed
     * straight into the author's scope(s) through [ScopeSync.pushDirect] and never originated, so no
     * custody row and no frame on the radios. Null when the seal fails; `pushed = false` when no connected
     * spool took it, and AckSync keeps the signed wire as an owed entry that rides every other path.
     */
    private suspend fun spoolDeliveryTick(
        authorId: String,
        ackIds: List<String>,
    ): AckSync.SpoolTick? {
        val tick = sealDeliveryTickEnvelope(authorId, ackIds) ?: return null
        val wire = sign(tick.env, relay = false)
        val pushed = scopeSync?.pushDirect(authorId, tick.env, wire) == true
        if (pushed) metrics.onReceiptSpooled()
        return AckSync.SpoolTick(wire, pushed)
    }

    /** Whether a connected spool heard from [authorId] within `SPOOL_COVER_MS` — AckSync's first route. */
    private fun isSpoolPresent(authorId: String): Boolean = scopeSync?.presentPeers(clock(), SPOOL_COVER_MS)?.contains(authorId) == true

    @Volatile
    private var spoolPresent: Set<String> = emptySet()

    /**
     * The spool's present set changed (ADR 2026-09.y5f3): the LoRa plane's Internet cover follows it, and a
     * peer that just appeared gets the same treatment as a radio sighting — a parked room tick or a
     * backed-off sealed tick toward it is tried now.
     */
    private fun onSpoolPresenceChanged(present: Set<String>) {
        val newcomers = present - spoolPresent
        spoolPresent = present
        transport.coveredByInternet(present)
        if (newcomers.isEmpty()) return
        sessionScope?.launch { newcomers.forEach { ackSync.onReachable(Peer(it)) } }
    }

    /**
     * The LoRa-held receipts a DM to [recipientId] may carry inline (ADR 054): none for a group or a peer
     * without [Protocol.CAP_INLINE_ACK], and only as many as the hop's body budget for this form leaves room
     * for — acks that pushed the frame past the 3-packet ceiling would lose the message to save a tick.
     * Taken out of the coalescer here; [sendChat] gives them back if the seal falls short of v2.
     */
    private suspend fun inlineAcksFor(
        recipientId: String?,
        group: GroupInfo?,
        text: String,
        styles: List<StyleSpan>,
        declared: DeclaredLangs,
        replying: Boolean,
        attached: Boolean,
    ): InlineAcks {
        if (recipientId == null || group != null) return InlineAcks()
        val waiting = dmAcks.pending(recipientId).size + ackSync.ridingFor(recipientId)
        if (waiting == 0) return InlineAcks()
        val peer = peers.find(recipientId) ?: return InlineAcks()
        if ((peer.capabilities ?: 0L) and Protocol.CAP_INLINE_ACK == 0L) return InlineAcks()
        // A styled body's ranges ride in the same frame (ADR 2026-10.p95v), and so do its declared languages (ADR
        // 2026-10.t2h6), both counted at the named layout's two-byte key: the larger of the two seal forms, so a fallback
        // to v2 never overflows.
        val room =
            LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying, attached) - LoraSizeHint.utf8Length(text) -
                StyleSpans.wireCost(styles, StyleKey.NAMED) - declared.wireCost(named = true)
        val fit = (room / INLINE_ACK_BYTES).coerceIn(0, MAX_INLINE_ACKS)
        if (fit == 0) return InlineAcks()
        // DM acks take the slots first: someone is waiting on each of those and this ride is the only path
        // they have that costs nothing. A room ack has no single addressee waiting and is content to catch
        // the next carrier, so it fills what is left.
        val dm = dmAcks.take(recipientId, fit)
        return InlineAcks(dm = dm, room = ackSync.takeRiding(recipientId, fit - dm.size))
    }

    /**
     * The receipt ids one outbound DM carries inline, split by the hold each came from — [dmAcks] for a DM
     * receipt held off the board (ADR 054), [AckSync]'s ride hold for a broadcast-room tick waiting for a
     * frame going that way (ADR 2026-09.aa27). The split exists only for the give-back: a seal that falls
     * short of v2 carries neither, and putting them back in the wrong hold would either lose them or
     * originate a custody row for a room tick.
     */
    private class InlineAcks(
        val dm: List<String> = emptyList(),
        val room: List<String> = emptyList(),
    ) {
        val ids: List<String> get() = dm + room
    }

    /**
     * Flushes a coalesced batch of LoRa-held DM receipts (ADR 054): one sealed tick covering every id, or —
     * the seal failing (author unpinned meanwhile) — the cleartext receipt per id, AckSync's fallback rule.
     */
    private suspend fun flushDmAcks(
        authorId: String,
        ackIds: List<String>,
    ) {
        // The best carrier there is (ADR 2026-09.aa27): this frame is already sealed to that author, already
        // originated and already custodied, so the room ticks waiting for a ride cost it a few bytes and no
        // row of their own. Field case: the pocket member that owed two room ticks originated exactly this
        // frame 3 minutes later for an unrelated DM ack, and they would have crossed there instead of
        // waiting 45 minutes for a link.
        if (originateDeliveryTick(authorId, ackIds, riderCap = DmAckCoalescer.MAX_LORA_TICK_ACKS)) {
            metrics.onReceiptCoalesced(ackIds.size - 1)
            return
        }
        val me = identity.nodeId()
        ackIds.forEach { pipeline.ackCleartext(it, me) }
    }

    /**
     * Whether [authorId] could read a sealed tick right now — AckSync's escalation gate. A stale `true`
     * only costs a failed seal at flush time (which falls back to cleartext entries), so this stays a
     * cheap pin + capability check rather than a dry-run seal.
     */
    private suspend fun canSealTickTo(authorId: String): Boolean {
        val peer = peers.find(authorId) ?: return false
        return (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET != 0L && peer.pubKey != null
    }

    /** Resolves the published key bundles for a DM recipient or a group's members (excluding us). */
    private suspend fun recipientBundles(
        recipientId: String?,
        group: GroupInfo?,
        me: String,
    ): Map<String, PublicKeyBundle> {
        val targets =
            when {
                group != null -> group.members.filter { it != me }
                recipientId != null -> listOf(recipientId)
                else -> emptyList()
            }
        // A bundle whose wrap key X25519 refuses (a low-order point a forged profile published, #172) is skipped as a
        // member whose key we don't know is: nobody could wrap to it, and refusing the whole message for it would
        // leave every other member of the group without the post.
        return targets
            .mapNotNull { nodeId ->
                peers
                    .find(nodeId)
                    ?.pubKey
                    ?.let { PublicKeyBundle.decode(it) }
                    ?.takeIf { RatchetCrypto.acceptsPublicKey(it.dhPublicKey()) }
                    ?.let { nodeId to it }
            }.toMap()
    }

    /**
     * The identity a DM or group attachment goes out under: its content-addressed hash, plus its base64 key — null
     * only for a room picture forwarded under its own public, key-less name (ADR 2026-10.mptm).
     */
    private data class SealedAttachment(
        val hash: String,
        val key: String?,
    )

    /**
     * Encrypts the ingested (plaintext) attachment to a fresh key, stores the ciphertext blob under its
     * ciphertext hash (so the existing content-addressed pull/dedup still works), and drops the now-
     * unreferenced plaintext blob.
     *
     * Unless the file already goes by an identity here ([AttachmentStore.Ingested.reuse], ADR 2026-10.mptm): then
     * that identity is the answer and nothing is sealed, so every phone holding it — carriers included — pulls nothing.
     * A room picture keeps its key-less name and its blob; a sealed one keeps its ciphertext and key. If that blob
     * went between the pick and the send, it is sealed fresh as ever. [requireKey] (a commons post, ADR 2026-10.xhfr)
     * reuses a sealed identity only: a key-less one's address is its plaintext's, which the room must never name.
     */
    private suspend fun sealAttachment(
        attachment: AttachmentStore.Ingested,
        requireKey: Boolean = false,
    ): SealedAttachment? {
        attachment.reuse?.takeIf { blobs.exists(it.hash) && (!requireKey || it.key != null) }?.let { held ->
            if (held.hash != attachment.hash) blobs.deleteIfUnreferenced(attachment.hash)
            return SealedAttachment(held.hash, held.key)
        }
        val plain = blobs.bytes(attachment.hash) ?: return null
        val sealed = AttachmentCrypto.seal(plain)
        val ctHash = sha256Hex(sealed.blob)
        blobs.insert(ctHash, attachment.mime, sealed.blob)
        blobs.deleteIfUnreferenced(attachment.hash)
        return SealedAttachment(ctHash, b64(sealed.key))
    }

    /**
     * Floods a group metadata update (e.g. a rename) immediately, independent of any chat message, so
     * members converge without waiting for the next message. The receiver applies it last-writer-wins on
     * the group-update frame's `sentAt`; the local store has already been updated by the caller.
     */
    override suspend fun sendGroupUpdate(group: GroupInfo) {
        originateSigned(
            RelayEnvelope(
                type = FrameType.GROUP_UPDATE,
                id = FrameId.new(),
                senderId = identity.nodeId(),
                sentAt = clock(),
                group = group,
                payload = EMPTY_PAYLOAD, // the roster rides in `group`; no per-type content
            ),
        )
    }

    /**
     * Floods a signed `groupleave` frame announcing that we've left [groupId], so the remaining members
     * drop us from their roster and show a status notice. Sent on departure (before the local tombstone);
     * the leaver is the signer, so a forged leave can't evict anyone else. Custodied like any group frame
     * (`FrameType.isCustodial`), so it reaches members offline at the moment of leaving — which is what
     * bounds how *eventual* their leave-rekey is (docs/GROUP_FORWARD_SECRECY.md #6.1).
     */
    override suspend fun sendGroupLeave(groupId: String) {
        val me = identity.nodeId()
        originateSigned(
            RelayEnvelope(
                type = FrameType.GROUP_LEAVE,
                id = FrameId.new(),
                senderId = me,
                sentAt = clock(),
                payload = WireCodec.encodePayload(GroupLeaveContent(groupId)),
            ),
        )
    }

    /**
     * Toggles this device's emoji reaction on [messageId] and floods the change. Tapping the emoji you
     * already chose retracts it; tapping a different one replaces it (one reaction per person). The
     * change is stored optimistically, then propagates **sealed** — a `CTL_REACTION` ctl chat frame —
     * when the target conversation can carry one ([recipientId] = a capable DM peer, or [group] with
     * every member ratchet-eligible), else as the legacy cleartext `reaction` frame (a broadcast-room
     * target always: the room is plaintext by design). `sentAt` is the wall clock used for
     * last-writer-wins so concurrent reactors across the mesh converge, whichever form each one rode.
     * An emoji the wire refuses ([isValidReactionEmoji]) is logged and ignored before the optimistic
     * row — the picker can't produce one, but the debug bridge can.
     */
    override suspend fun sendReaction(
        messageId: String,
        emoji: String,
        recipientId: String?,
        group: GroupInfo?,
    ) {
        if (!isValidReactionEmoji(emoji)) {
            Log.w(TAG, "reaction refused: ${emoji.length} units on $messageId")
            return
        }
        // Only a peer is a recipient. A room id (the Meshtastic room, a commons) has no session to seal for, so it
        // would reach the cleartext flood below and name a private room's message to every node in range.
        if (recipientId != null && Conversations.kindFor(recipientId) != ConversationKind.DM) {
            Log.w(TAG, "reaction refused: $recipientId is not a peer")
            return
        }
        val me = identity.nodeId()
        val next = if (reactions.currentEmoji(messageId, me) == emoji) null else emoji
        val now = clock()
        reactions.apply(ReactionEntity(messageId, me, next, now))
        if (sealReaction(messageId, next, me, now, recipientId, group)) return
        if (recipientId != null || group != null) metrics.onReactionSealedFallback()
        originateSigned(
            RelayEnvelope(
                type = FrameType.REACTION,
                id = FrameId.new(),
                senderId = me,
                sentAt = now,
                payload = WireCodec.encodePayload(ReactionContent(messageId, next)),
            ),
        )
    }

    /**
     * Seals a DM/group-target reaction as a `CTL_REACTION` ctl frame (the sealed replacement for the
     * mesh-wide cleartext `reaction` flood, which leaked who reacted with what to which message — to
     * non-members included). A DM target rides the pairwise ratchet, a group target the sender-key
     * chain via [sealGroupRatchet] (all-or-nothing eligibility, may mint + distribute a seed — exactly
     * like a group chat). Returns false — caller floods the legacy cleartext frame — for a broadcast
     * target (the room is plaintext by design), an incapable peer/group, or a failed seal. **Never
     * v1-wraps a ctl** (a pre-ratchet build would decrypt it, strip the unknown field, and persist an
     * empty bubble); the sealDm/sealGroupRatchet-direct path is the ctl-sender precedent.
     */
    private suspend fun sealReaction(
        messageId: String,
        emoji: String?,
        me: String,
        now: Long,
        recipientId: String?,
        group: GroupInfo?,
    ): Boolean {
        val content = MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload(messageId, emoji))
        return when {
            group != null -> sealGroupReaction(group, me, now, content)
            recipientId != null && recipientId != me -> sealDmReaction(recipientId, me, now, content)
            else -> false // broadcast target: the room is plaintext by design
        }
    }

    private suspend fun sealGroupReaction(
        group: GroupInfo,
        me: String,
        now: Long,
        content: MessageContent,
    ): Boolean {
        val id = FrameId.new()
        val sealed = sealGroupRatchet(group, me, content, MessageCrypto.header(id, me, now, group.id)) ?: return false
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        metrics.onReactionSealed()
        return true
    }

    private suspend fun sealDmReaction(
        recipientId: String,
        me: String,
        now: Long,
        content: MessageContent,
    ): Boolean {
        val peer = peers.find(recipientId)
        val bundle = peer?.pubKey?.let { PublicKeyBundle.decode(it) }
        val capable = bundle != null && (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET != 0L
        if (!capable) return false
        val id = FrameId.new()
        val aad = MessageCrypto.header(id, me, now, recipientId)
        val (plaintext, scheme) = content.sealBytes(peer.readsCryptoV3())
        val sealed = ratchet.sealDm(recipientId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), plaintext, aad, now, scheme) ?: return false
        if (scheme == EncEnvelope.VERSION_DM_V3) metrics.onDmSealedV3()
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = recipientId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        metrics.onReactionSealed()
        return true
    }

    /**
     * The user tapped "Tap to download" on attachment [hash] (ADR 2026-10.js2p): the approval goes on every row naming
     * it first — the database is what every later re-arm reads — then the fetch is wanted plain, which lifts the
     * fast-only hold at once and asks over whatever plane reaches a holder. Asked of the earliest row's sender first, as
     * the database re-arm would, and the fast plane armed toward it as delivery does. Nothing to do once held. With the
     * mesh stopped the want is only recorded — there is no neighbour to ask and no session to time it — and the approval
     * on the rows is what the next start's re-arm reads, plain.
     *
     * Runs on the manager's own scope and returns at once: the chat calls it from its view model, and leaving the screen
     * while a re-arm holds [rewantLock] must not cancel the tap. The approval is written before the lock is taken, so it
     * never waits on a re-arm; the want runs under the lock, so a re-arm that read the rows before the approval cannot
     * apply its stale fast-only verdict after it. The want is also marked approved, so no later want of a stale verdict —
     * a delivery that read its row before the tap — can turn it fast-only again (the review of ADR 2026-10.js2p).
     */
    override suspend fun downloadAttachment(hash: String) {
        scope.launch {
            messages.approveAttachmentFetch(hash)
            rewantLock.withLock {
                val rows = messages.attachmentFetchRows(hash, me = identity.nodeId())
                val author = rows.firstOrNull()?.senderId ?: return@withLock
                // The rows' deadline too (#195): a tap on a DM past its custody window asks nobody to pull it through.
                blobExchange.want(
                    hash,
                    author = author,
                    fastOnly = false,
                    approved = true,
                    until = PullThroughGate.until(rows),
                    untilKnown = true,
                )
                transport.expectBulkTransfer(author)
            }
        }
    }

    /**
     * Broadcasts a best-effort "now typing" cue for [conversationId] to nearby peers — the presence ping behind
     * the chat typing indicator. Deliberately NOT [originateSigned]: it must never flood, be custodied, or be
     * parked, so it is signed with `relay = false` (single-hop, like a blob request) and sent only over the
     * coordination-plane fast path — targeted [MeshTransport.fastSend] to a DM recipient, [MeshTransport.fastFanout]
     * to every neighbor for the broadcast room / a group. A fresh [FrameId] each time so the receiver's SeenSet
     * never dedups the next cue. Fire-and-forget: if it doesn't fit the ~255 B channel or no one is reachable it
     * simply no-ops. Scope rides the frame the same way a chat does — [RelayEnvelope.recipientId] for a DM, a
     * compact [TypingContent.groupId] for a group (not the heavy [RelayEnvelope.group] roster, which could blow
     * the coordination-plane size cap).
     *
     * A DM's or a group's cue also goes up as a sig (docs/SPOOL_PROTOCOL.md §7.8): the same signed frame, beside the
     * radio copy, on every connected relay that carries sigs and holds the conversation's current scope, so a contact
     * reachable only over the Internet sees it too. The receiver keeps the first copy by frame id. Nothing is sent on
     * any plane while the person has turned typing indicators off.
     */
    override suspend fun sendTyping(conversationId: String) {
        if (!settings.typingIndicatorsEnabled.first()) return
        val me = identity.nodeId()
        val kind = Conversations.kindFor(conversationId)
        // The bridged public room has no typing cue and must not fall through to one. A `typing` frame
        // carries its scope in `recipientId` or `TypingContent.groupId` and this room is neither, so an
        // unguarded cue from here would arrive on every phone in the pocket as a *Nearby* one. The UI
        // already refuses to raise it; this is the net under that, at the layer that mints the frame.
        // The same net under a commons: its scope carries no typing frame at all (§7.4).
        if (kind == ConversationKind.MESHTASTIC || kind == ConversationKind.COMMONS) return
        val recipientId = if (kind == ConversationKind.DM) conversationId else null
        val groupId = if (kind == ConversationKind.GROUP) conversationId else null
        val env =
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = me,
                sentAt = clock(),
                recipientId = recipientId,
                payload = WireCodec.encodePayload(TypingContent(groupId)),
            )
        val wire = sign(env, relay = false)
        if (recipientId != null) transport.fastSend(wire, Peer(recipientId)) else transport.fastFanout(wire)
        if (kind == ConversationKind.DM || kind == ConversationKind.GROUP) scopeSync?.sig(conversationId, env, wire.sig, wire.signed)
    }

    /** On startup, sweep orphaned blobs/reactions/pins/receipts and re-request attachment blobs we're still missing. */
    private fun resumePendingFetches(session: CoroutineScope) {
        session.launch {
            reactions.deleteOrphans(clock()) // reclaim reactions left by deleted messages
            pins.deleteOrphans(settings.blockedNodeIds.first()) // ...pins left by them, or on a since-blocked sender's
            receipts.deleteOrphans() // ...and per-recipient delivery rows left by a deleted thread or a retention trim
            sweepExpired() // carried DMs whose TTL elapsed while we were down (the in-memory sets are usually empty)
            sweepLocalStorage() // bound the local messages/peers tables against a Sybil flood (Message Requests hardening)
            // Reclaim blobs left by attachments staged but never sent (keeps carried ones) — after the sweeps above, so a
            // blob only an evicted peer's avatar, a swept message or an expired carried frame named goes in the same pass
            // rather than at the next start (#234).
            blobs.deleteOrphans()
            // The first value is the startup re-arm; every change of the user's Bluetooth download limit re-arms with
            // the new verdict at once, so choosing "Never" fetches what was waiting without the next tick (ADR
            // 2026-10.js2p). Distinct: every settings flow re-emits on a write to any key.
            settings.bluetoothDownloadLimitBytes.distinctUntilChanged().collect { rewantMissingBlobs() }
        }
    }

    /**
     * Ages out what has outlived its TTL: carried DMs, key-wait frames, parked group seeds, stale
     * (unauthenticated) key-wants and blob fetches that never arrived. Startup, the 10-min prune loop and the
     * 15-min heartbeat all run it. `internal` so `mesh/lab` can run the sweep inside a scenario, where a
     * clock jump moves the expiry but the loops that would notice are `delay()`-based and blind to it.
     */
    internal suspend fun sweepExpired() {
        forwardSync.sweepExpired()
        pendingInbound.sweepExpired()
        pendingGroupKeys.sweepExpired()
        keyExchange.sweepExpired()
        lastSweptBlobs = blobExchange.sweepExpiredHashes()
        // A kept prefix past its hour, or whose blob arrived some other way (the spool, a second link) (#116).
        partials.sweep { blobStore.has(it) }
    }

    /**
     * Re-arms a want for every attachment blob the database still names but the store does not hold. The
     * database is the durable truth; `BlobExchange`'s in-memory fetching set is a bounded, TTL-swept memo of
     * it, and the two drift apart in exactly the shape the LoRa field trial hit (ADR 2026-09.ptv8): a frame
     * heard over the board while the phone had no neighbor parks a want that nobody was asked for, the 30-min
     * sweep reclaims it, and the link-up re-ask ([BlobExchange.reask]) then finds nothing to ask
     * for — the row spins forever, until a restart ran this. So it runs at every point a holder may have just
     * appeared: startup, a neighbor joining, and the 60 s re-offer tick. [BlobExchange.wantAll] is idempotent for
     * a hash already in flight and returns at once for one the store has, so the repeat costs one bounded query.
     */
    private suspend fun rewantMissingBlobs() = rewantLock.withLock { rewantMissingBlobsLocked() }

    /** The blob wants the last [sweepExpired] reclaimed: `mesh/lab` counts a want's generations by them (#195). */
    @Volatile
    internal var lastSweptBlobs: Set<String> = emptySet()
        private set

    /** The blobs this node wants now ([BlobExchange.wantedHashes]): `mesh/lab` asserts a want ended (#195). */
    internal fun wantedBlobs(): Set<String> = blobExchange.wantedHashes()

    /**
     * [rewantMissingBlobs]'s body. Its callers — the tick, a link-up, the limit collector — and the user's tap
     * ([downloadAttachment]) run one at a time: each reads the rows and the limit, then wants by what it read, and one
     * that read before another's write and applied after it would undo it (a tick that read 1 MB re-holding what the
     * switch to Never had just fetched, ADR 2026-10.js2p's review).
     */
    private suspend fun rewantMissingBlobsLocked() {
        val me = identity.nodeId()
        val limit = settings.bluetoothDownloadLimitBytes.first()
        // Custody first, the rows after (#141's finding 6): custody's read loads a room post's declared size only while no
        // row names its hash ([ForwardDao.carriedAttachments]), and a delivery writes its row without this lock, so a row
        // landing between a row read and a custody read after it was judged by neither and wanted plain over Bluetooth.
        // Read in this order every row inserted before the last read is judged: by the row read, or by custody's size
        // when it landed after custody's read. A room post's row inserted after the row read is judged by the same
        // declared size; a sealed frame's row after it is held until the next re-arm (`unopenedForUs`). A row deleted
        // between the reads leaves a row-covered post with no size: it is held until the next re-arm loads it.
        // Carrier-only custody blobs backfill only while under the byte budget (the same pull-time soft cap
        // onCarriedFrame applies), so a restart re-attempts pulls that a live-session over-budget skip left missing.
        val (carried, frames) =
            if (blobs.carrierOnlyBlobBytes() < CARRIER_BLOB_BUDGET_BYTES) {
                val now = clock()
                // Live frames only (#195): a frame past its TTL is carried by nobody, and a re-arm from it minted a root.
                val carried = forwardStore.attachmentsNeedingFetch(now)
                // One read of custody's per-frame rows for the judgements below: the deferral, the fast-only hold and the
                // deadline a DM's carrier pulls it through by.
                carried to if (carried.isNotEmpty()) carriedFrames(me, now) else emptyList()
            } else {
                emptyMap<String, String>() to emptyList()
            }
        betweenRewantReads?.invoke()
        // Own/received message attachments: always re-pull (uncapped, kept alive by their message row). Each is
        // wanted with its row's sender, so a want re-armed after a restart or the sweep asks the author first.
        // All of it goes to the exchange in one batch, so a link-up's holder queries share one frame per neighbour.
        val needed = messages.attachmentsNeedingFetch(me = me)
        val ownHashes = needed.mapTo(HashSet()) { it.hash }
        val wanted = needed.mapTo(mutableListOf<Pair<String, String?>>()) { it.hash to it.senderId }
        // Each want's fast-only flag is derived here from the rows, every time, never kept from the last (ADR
        // 2026-10.js2p): an attachment every row of which declares over the user's limit, untapped, is fetched over
        // Wi-Fi Aware only. One row that would fetch it fetches it for all (a stranger's row cannot hold a contact's).
        val fastOnly = needed.filter { BluetoothFetchGate.fastOnly(it.rows, limit) }.mapTo(HashSet()) { it.hash }
        // And until when its neighbours may be asked to pull it through (#195, ADR 2026-10.2drc): a DM's custody window
        // from its latest row, carried on every ask so nobody echoes it past the window; none for a group's or a room's.
        val until = needed.mapNotNull { m -> PullThroughGate.until(m.rows)?.let { m.hash to it } }.toMap(HashMap())
        // The carried ones skip those already re-requested above as our own/received attachments, and ones a DM's
        // linked recipient is fetching now ([CarrierPullPolicy], ADR 2026-10.g24x) — this tick re-reads them, so the
        // hold ends by itself when the recipient's link goes or the window passes.
        if (carried.isNotEmpty()) {
            val linked = transport.neighbors.value.mapTo(HashSet()) { it.nodeId }
            val held = heldForLinkedRecipients(carried.keys, frames, linked)
            val carriedUntil = carrierDeadlines(frames)
            carried.forEach { (hash, author) ->
                if (hash !in ownHashes && hash !in held) {
                    wanted += hash to author
                    carriedUntil[hash]?.let { until[hash] = it }
                }
            }
            if (limit != null) fastOnly += unopenedForUs(carried.keys - ownHashes, frames, me, limit)
        }
        // A group's decided photo not shown yet (ADR 2026-09.nxcq): the row is the durable want, so a pull lost
        // to a restart or the fetch TTL comes back here and not only on the group's next frame. A photo
        // screening refused keeps its verdict and is never asked for again while filtering is on.
        groups.photoHashesNeedingFetch().forEach { if (!refusedImage(it)) wanted += it to null }
        beforeRewantApplies?.invoke()
        blobExchange.wantAll(wanted, fastOnly, until)
    }

    /**
     * Custody's carried frames naming an attachment we lack, each marked [CarriedAttachment.member] when it is a group's
     * we are in — by the roster the frame itself carries first (a member whose seed is late holds the frame and no group
     * row yet), then by our group rows. The one read [rewantMissingBlobsLocked] makes of them per re-arm.
     */
    private suspend fun carriedFrames(
        me: String,
        now: Long,
    ): List<CarriedAttachment> {
        val ours = groups.active().mapTo(HashSet()) { it.groupId }
        return forwardStore.carriedAttachments(now).map {
            if (it.groupId in ours || me in it.roster.orEmpty()) it.copy(member = true) else it
        }
    }

    /**
     * Until when a carrier pulls each carried attachment through (#195, ADR 2026-10.2drc): when every frame naming it is a
     * DM's, a custody window past the latest one's `sentAt` — frame-global, the same on every node — and none otherwise,
     * as `PullThroughGate` folds a recipient's rows.
     */
    private fun carrierDeadlines(frames: List<CarriedAttachment>): Map<String, Long> =
        frames
            .groupBy { it.hash }
            .mapNotNull { (hash, named) ->
                val sent = named.map { it.sentAt }
                if (named.any { it.recipientId == null } || sent.any { it == null }) {
                    null
                } else {
                    hash to sent.maxOf { it!! } + ForwardRepository.DEFAULT_TTL_MS
                }
            }.toMap()

    /**
     * The carried attachments among [hashes] that no stored row names and that *every* carried frame naming them
     * addresses to us — our DM, a group we are in ([carriedFrames]): frames not opened yet (waiting on a key, or never
     * delivered), their declared size still sealed — or a room post, which is every node's, declaring over the user's
     * [limit] (ADR 2026-10.hmfe). While a limit is set they are fetched fast-only, as `InboundPipeline.onCarriedFrame`
     * leaves them, so the tick's carrier re-arm does not lift what custody held (ADR 2026-10.js2p). The room post's half
     * covers the tick that lands between custody's write and the delivery's row, and a post whose row is never written
     * (a blocked sender) or was swept: judged by the size it declares, as its row would be. Folded as the rows are: one
     * frame we carry for somebody else, or a room post declaring nothing or under the limit, keeps the pull plain, so a
     * stranger's frame naming a hash can lift the gate on our side and never impose it. A hash among [hashes] with no
     * frame left in [frames] was trimmed between custody's two reads (#141's finding 6): nothing says what it was, so it
     * is held until the next re-arm, which no longer names it — js2p's "never plain on a guess".
     */
    private fun unopenedForUs(
        hashes: Set<String>,
        frames: List<CarriedAttachment>,
        me: String,
        limit: Long,
    ): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val byHash = frames.groupBy { it.hash }
        return hashes.filterTo(HashSet()) { hash ->
            byHash[hash]?.all { it.recipientId == me || it.member || roomPostWaits(it, me, limit) } ?: true
        }
    }

    /**
     * Whether [frame] is a room post whose own declared size holds its fetch to Wi-Fi Aware under [limit] (ADR 2026-10.hmfe).
     * One a row named when custody read it ([CarriedAttachment.rowCovered]) brought no size, and its hash is here only
     * because the rows' read after it found no row (a room cap's sweep in between, #141's finding 6): held until the next
     * re-arm loads the size it declares.
     */
    private fun roomPostWaits(
        frame: CarriedAttachment,
        me: String,
        limit: Long,
    ): Boolean {
        if (frame.recipientId != null || frame.groupId != null) return false
        val own = frame.senderId == me
        if (frame.declaredSize == null && frame.rowCovered) return !own
        return BluetoothFetchGate.fastOnly(frame.declaredSize, limit, approved = false, own = own)
    }

    /** The carried attachments among [hashes] whose pull [CarrierPullPolicy] holds back now, [linked] being who is. */
    private fun heldForLinkedRecipients(
        hashes: Set<String>,
        frames: List<CarriedAttachment>,
        linked: Set<String>,
    ): Set<String> {
        if (hashes.isEmpty() || linked.isEmpty()) return emptySet()
        val now = clock()
        // A member is never held back (g24x) — by the frame's own roster too ([carriedFrames]).
        return frames
            .groupBy { it.hash }
            .filter { (hash, named) -> hash in hashes && CarrierPullPolicy.defers(named, linked, now) }
            .keys
    }

    /** Periodically reclaims expired carried DMs, bounding the forward store between heartbeat sweeps. */
    private fun pruneForwardStorePeriodically(session: CoroutineScope) {
        session.everyRound(FORWARD_SWEEP_INTERVAL_MS, onFailure = { roundFailed(MeshRound.SWEEP, it) }) {
            sweepExpired()
            commons?.sweepOutbox(clock() - ScopeRegistry.COMMONS_DEFAULT_TTL_MS) // the room has long expired them
            sweepLocalStorage()
        }
    }

    /**
     * A session loop's round threw ([everyRound]): logged every time, so a round that always fails says so once per
     * interval, and counted for the field log. The loop itself runs the next round on time (#206).
     */
    private fun roundFailed(
        round: MeshRound,
        e: Exception,
    ) {
        Log.w(TAG, "${round.name} round failed; the next runs on time", e)
        metrics.onRoundFailed(round)
    }

    /**
     * Bounds the local `messages` + `peers` tables so a Sybil DM/profile flood can't exhaust storage. Local-only
     * GC (no wire/convergence effect): the protected set — conversations/peers that are accepted, out-of-band
     * verified, or that the user has authored in, plus any group a known peer has posted in — is exempt from
     * wholesale eviction, matching the notify gate's "not a request" predicate ([Conversations.isAccepted]) so a
     * group that reads as a normal chat keeps its history like one. Runs on the prune loop and at startup,
     * alongside the forward-store sweep. `internal` so `mesh/lab` can run a sweep inside a scenario.
     */
    internal suspend fun sweepLocalStorage() {
        val now = clock()
        val me = identity.nodeId()
        val accepted = settings.acceptedConversations.first()
        val verified = peers.verifiedNodeIds().toSet()
        val authored = messages.conversationsIAuthoredIn(me).toSet()
        // A group inherits protection once a known peer has posted in it (the id lookups alone never match a
        // "g-" group id). sendersIn is suspend, so gather in a loop rather than a filter lambda.
        val protectedGroups = mutableListOf<String>()
        for (group in groups.observeGroups().first()) {
            val senders = messages.sendersIn(group.groupId).toSet()
            if (Conversations.isAccepted(group.groupId, accepted, verified, authored, senders)) {
                protectedGroups += group.groupId
            }
        }
        val protectedIds = accepted + verified + authored + protectedGroups
        // The room sweep's known senders: a DM's conversation id *is* its peer's node id, so the three DM
        // signals double as the set of people the user talks to — the rule [Conversations.isAccepted] applies
        // to a group's senders. Group and room ids in the set never match a sender and are harmless.
        messages.sweepRetention(now, protectedIds, knownSenders = accepted + verified + authored + me)
        // A pin evicted takes its cached proof of key with it (#234), or the cache would serve what the store dropped.
        keyExchange.forget(peers.sweepCap(protectedIds))
        // A proof of key stored before the profile cap (#234) is still served to every key request; the door would refuse
        // it, so it goes. The pin it proved stays.
        val oversize = peers.deleteOversizeProfileFrames(forwardStore.maxProfileSignedBytes)
        if (oversize > 0) Log.w(TAG, "dropped $oversize stored profiles over the cap of ${forwardStore.maxProfileSignedBytes} signed bytes")
        // A face a build before the photo ceiling adopted (#234) is cleared and its blob released: a profile photo over
        // the ceiling is never shown. Refused for the session too, so a profile still naming it wants nothing.
        val faces = peers.clearAvatarsLongerThan(FileIntake.MAX_AVATAR_BYTES)
        for (hash in faces) {
            blobExchange.refuseAsPhoto(hash)
            blobs.deleteIfUnreferenced(hash)
        }
        if (faces.isNotEmpty()) {
            metrics.onPhotoOverCeiling(faces.size)
            Log.w(TAG, "cleared ${faces.size} faces over the photo ceiling")
        }
    }

    /**
     * Periodically re-runs the neighbor-join re-offer hooks for **currently-linked** neighbors — the timer-driven
     * anti-entropy a persistent link needs. [watchNeighbors] fires these once per newcomer, which suffices for the
     * cue-driven Wi-Fi Aware transport (its ephemeral links re-join on every sync, so the re-offer re-runs for
     * free), but a Bluetooth link stays up continuously — and the composite masks a NAN→BT handoff as one
     * continuous neighbor — so without this a custody divergence that appears (or an offer lost to a race) after
     * link-up would never reconcile: the peer keeps advertising a differing store digest and no sync ever closes
     * it, which also leaves the Wi-Fi Aware plane forever *wanting* a sync it can't complete. Cheap and idempotent:
     * [ForwardSync.onDigest] returns only the set difference, a duplicate is dropped by the receiver's SeenSet, and
     * a peer no longer holding a live link is a no-op (the send routes to no transport).
     */
    private fun reofferToNeighborsPeriodically(session: CoroutineScope) {
        session.everyRound(reofferIntervalMs, onFailure = { roundFailed(MeshRound.REOFFER, it) }) {
            reofferToNeighbors()
        }
    }

    /**
     * One round of the 60 s re-offer ([reofferToNeighborsPeriodically]). `internal` so `mesh/lab` can run the tick
     * inside a scenario, whose `delay()`-based loop a lab clock jump never wakes.
     */
    internal suspend fun reofferToNeighbors() {
        // A link-set pass that failed part-way runs again now: the link set may never change again to re-run it (#206).
        // On the set the watcher was last handed, never a newer `neighbors.value`: the watcher may hold that one queued
        // on the lock, and would then act on it after the newer one, departing a peer still linked.
        if (neighborPassOwed) neighborPassLock.withLock { neighborPass(neighborsHanded) }
        // First, and outside the link-set guards: a reset's second re-seal is due by the clock, not by a link, and a
        // pass due with nobody linked still takes its long-range and coordination-plane sends (ADR 2026-10.dmgw).
        resealSecondPass()
        val neighbors = transport.neighbors.value
        // Before the blob re-ask below, so a want the 30-min sweep reclaimed is back in the memo it
        // reads from; skipped with nobody linked, where a want has nowhere to go anyway.
        if (neighbors.isNotEmpty()) rewantMissingBlobs()
        neighbors.forEach { peer ->
            forwardSync.onNeighborAdded(peer) // re-advertise our custody digest → pull anything we lack
            keyExchange.onNeighborAdded(peer) // re-ask for keys we still need
            ackSync.onNeighborAdded(peer) // re-send any broadcast/group delivery tick we owe it
            flushPendingGroupKeysFor(peer.nodeId) // re-send unacked group epoch seeds it never got
        }
        // Once for everyone: each blob we still need takes its next step of asks (ADR 2026-10.f7gg).
        if (neighbors.isNotEmpty()) blobExchange.reask()
    }

    // --- Profile broadcasting ---

    /**
     * The link set [watchNeighbors] last acted on. `neighbors` is a conflating `StateFlow`, so a link taken
     * down and brought back inside one wake-up of that watcher is a link that never went down to it: no
     * newcomer on the way back, so no profile push, digest exchange or blob re-ask until the 60 s re-offer.
     * No radio flaps that fast; the mesh-in-a-box lab can, and waits on this before it re-links.
     */
    @Volatile
    internal var linksActedOn: Set<String> = emptySet()
        private set

    // One [neighborPass] at a time: the watcher's on each change, and the re-offer round's retry of a failed one.
    private val neighborPassLock = Mutex()

    // The links whose newcomer hooks all ran: what picks a pass's newcomers. A departure leaves it at once, so a peer
    // that comes back is a newcomer whatever became of the pass that saw it go; a newcomer joins it only once every hook
    // ran. Guarded by [neighborPassLock]; emptied when a session starts watching.
    private var knownLinks = emptySet<String>()

    // The link set the last pass saw, whether or not it ran to its end: what picks a pass's departures. A newcomer whose
    // pass failed after `linked` / the re-ask may already hold our ask, so its departure is acted on like any other
    // (#140). Guarded by [neighborPassLock]; emptied when a session starts watching.
    private var lastLinks = emptySet<String>()

    // Departures whose step has not run to its end: carried into the next pass until it does (#206). Guarded by
    // [neighborPassLock].
    private var owedDepartures = emptySet<String>()

    /**
     * Whether a [neighborPass] failed part-way and is owed again: set by the failure, cleared by the next pass that runs
     * to its end. `neighbors` emits only on a change, so a steady link set would never re-run it; the re-offer round
     * reads this and runs the pass itself.
     */
    @Volatile
    private var neighborPassOwed = false

    // The link set the watcher was last handed, written before it queues on [neighborPassLock]: the set a retry of a
    // failed pass acts on, so the two never act out of order (each pass's set is one the watcher has already seen).
    @Volatile
    private var neighborsHanded: Set<Peer> = emptySet()

    private fun watchNeighbors(session: CoroutineScope) {
        session.launch {
            neighborPassLock.withLock {
                knownLinks = emptySet()
                lastLinks = emptySet()
                owedDepartures = emptySet()
                neighborPassOwed = false
                neighborsHanded = emptySet()
            }
            // A direct collector, on purpose: `neighbors` conflates, and `mesh/lab` reads this body's return as the
            // link set handled (`LabTransport.awaitNeighborsHandled`).
            transport.neighbors.collect {
                neighborsHanded = it
                neighborPassLock.withLock { neighborPass(it) }
            }
        }
    }

    /**
     * Acts on the link set [current]: the departures' step, then the newcomers' hooks. One pass is one round (#206): a
     * throw out of any step is logged and counted ([MeshRound.NEIGHBORS]), and the watcher lives on for the next change.
     * What failed is owed, not marked done — the departures stay in [owedDepartures] until their step returns, the
     * newcomers stay out of [knownLinks] until all their hooks ran (each hook is idempotent: the re-offer round re-runs
     * most of them every minute) — and the next pass, a change or the re-offer round's retry, runs them again. A
     * departure is read against the set the last pass saw ([lastLinks]), not against [knownLinks]: a newcomer whose pass
     * failed after its asks went out is departed like any other. Under [neighborPassLock].
     */
    private suspend fun neighborPass(current: Set<Peer>) {
        val currentIds = current.map { it.nodeId }.toSet()
        val newcomers = current.filter { it.nodeId !in knownLinks }
        // No departure cleanup: under the cue-driven transport a data-path link is ephemeral and
        // reconnects on every sync, so we deliberately keep sentAvatarHashes across the flap —
        // clearing it would re-push every avatar on each brief contact. A peer that truly leaves
        // ages out by TTL. (ForwardSync, by contrast, *does* re-offer its whole carried set on each
        // join now: the digest gate makes a fresh link mean the stores differ, so re-pushing is how
        // an offer lost to a torn-down link self-heals — see ForwardSync.onNeighborAdded. A persistent
        // link (Bluetooth) only joins once, so reofferToNeighborsPeriodically re-runs these hooks on a
        // timer for currently-linked neighbors — the anti-entropy a non-flapping link needs.)
        // An owed departure whose peer is back is moot: `linked` below starts that peer's asks over.
        val departed = (lastLinks - currentIds) + (owedDepartures - currentIds)
        lastLinks = currentIds
        owedDepartures = departed
        knownLinks = knownLinks intersect currentIds
        runRound(onFailure = {
            neighborPassOwed = true
            roundFailed(MeshRound.NEIGHBORS, it)
        }) {
            // The one departure step: a blob whose ask or hold sat on a link that went asks its next holder now,
            // not at the next tick (#140) — an ask stands on a live link until its floor, never on one that is gone.
            // Ahead of [linksActedOn], which `mesh/lab` reads as "the departure has been acted on".
            blobExchange.unlinked(departed)
            owedDepartures = emptySet()
            linksActedOn = currentIds
            // A newcomer may be the holder of bytes whose want the sweep already reclaimed — re-arm from
            // the database first, so the re-ask below has the full missing set to ask it for.
            // A newcomer holds no ask of ours — one sent before may have died with its old link — so each round
            // may ask it again (ADR 2026-10.f7gg). Ahead of the re-arm, whose wants step at once.
            blobExchange.linked(newcomers.map { it.nodeId })
            if (newcomers.isNotEmpty()) rewantMissingBlobs()
            // Blobs we still need take their next step of asks, which reaches a newcomer at once in a round
            // already widened (ADR 2026-10.f7gg). Ahead of the per-newcomer hooks, so the digest stays the
            // batch's last send — the step `mesh/lab` waits on.
            if (newcomers.isNotEmpty()) blobExchange.reask()
            newcomers.forEach {
                pushProfileTo(it)
                forwardSync.onNeighborAdded(it) // re-offer carried DMs addressed to / routable via it
                keyExchange.onNeighborAdded(it) // re-ask the new neighbor for keys we're still missing
                ackSync.onNeighborAdded(it) // re-send any broadcast/group delivery tick we owe it, over the link
                flushPendingGroupKeysFor(it.nodeId) // re-send unacked group epoch seeds (partition merge)
            }
            knownLinks = currentIds
            neighborPassOwed = false
        }
    }

    /**
     * Seed our own profile frame into custody at startup (idempotent: the persisted profileVersion keeps the
     * frame id stable, so a later launch re-seeds the same frame and the store no-ops). Closes the NAN-only
     * cold-start deadlock (`docs/NAN_CONCURRENCY_REAUDIT.md` §3.3): with every custody store empty all
     * digests read 0 ⇒ equal ⇒ no sync is ever wanted, DMs park `pendingKey`, and profiles historically
     * moved only on link-up — which never came. A seeded store gives each node a one-frame set whose id
     * differs per node, so first contact diverges the digests, a link forms, profiles/keys exchange, and
     * parked DMs flush.
     */
    private fun seedOwnProfileCustody(session: CoroutineScope): Job =
        session.launch {
            // Hygiene for a device whose own profile once pinned a `peers` row for itself (the self-addressed
            // seed loop, fixed in InboundPipeline.handleProfile): the row is what made us a sealable "peer",
            // and behind it sit the session the ratchet opened with ourselves and the seed-outbox rows the
            // group flush wrote toward us. The row went with 2.6.0's first start; the rest is invisible from
            // outside (the ratchet dump walks `peers`) and nothing else removes it, so each sweep runs on its
            // own — a no-op once clean.
            val me = identity.nodeId()
            if (peers.forgetSelf(me)) Log.w(TAG, "dropped the peer row this device had pinned for itself")
            if (ratchet.forget(me)) Log.w(TAG, "dropped the ratchet session this device held with itself")
            groups.forgetSelf(me).takeIf { it > 0 }?.let { Log.w(TAG, "dropped $it group seed-outbox rows addressed to ourselves") }
            // Rotation check BEFORE seeding, so a due prekey mints now and the seeded frame (and any
            // first-contact push) already carries it; also the startup ratchet retention sweep.
            rotatePrekeyIfDue()
            finishRestore()
            sweepKeyRetention()
            replayUndeliveredGroupCustody()
            // At startup too, not only on the 15-min heartbeat: this is where a device that just enabled
            // the Internet plane (or just finished its mint grace while the app was closed) actually mints.
            mintGroupRootsIfDue()
            // Stamp a publish time before building the frame: a device whose profile was last edited days
            // ago would otherwise seed a frame custody refuses as dead on arrival, and this seeding exists
            // precisely so first contact has a frame to diverge on.
            republishProfileIfStale()
            ownProfile() // seeds the row when the stamp is new; reuses it when custody already holds it
        }

    /**
     * Flood our profile once per peer-epoch on **first coordination-plane contact** ([MeshTransport.reachable]
     * newcomers) — not only on link-up ([watchNeighbors]) — so the key exchange bootstraps over NAN alone
     * (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.5): `reachable` needs no data path, a small profile rides the
     * fast plane immediately, and a larger one is already in custody (seeded above) where its digest
     * divergence pulls a link up. Coalesced to one origination per [PROFILE_REFLOOD_MIN_MS] no matter how
     * many newcomers arrive (receivers dedupe by the stable frame id + SeenSet, and the custody path covers
     * anyone the flood missed). BLE-driven newcomers trigger it too, harmlessly.
     *
     * A peer's departure from `reachable` and later return re-enters it as a newcomer, but not one worth a
     * flood inside the window it would be dropped in (#73): the frame id is stable, so a copy re-flooded within
     * the receivers' [SeenSet.DEFAULT_TTL_MS] is dropped by every one of them, and a Wi-Fi Aware edge peer
     * flapping across the 150 s linger used to cost the whole mesh a flood every 30 s that nobody kept. The
     * `flooded` memo — keyed on the frame id *and* the peer — lets one flood per (id, peer) through per window,
     * so a profile edit's fresh id makes every peer owed again, a peer that missed the window is served again
     * once it lapses, and a genuinely new peer floods on first sighting as before. A newcomer the 30 s floor
     * skipped is never memoed: it stays owed on its next epoch, exactly as it was.
     */
    private fun watchReachable(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            var lastFloodAt = 0L
            // One flood per (frame id, peer) per receivers' SeenSet window: every send this suppresses is one
            // the far end's router would have dropped (the same invariant as ADR 2026-09.6nmy's link memo).
            val flooded = SeenSet(ttlMillis = SeenSet.DEFAULT_TTL_MS, clock = clock)
            transport.reachable.collect { current ->
                val ids = current.mapTo(HashSet()) { it.nodeId }
                val newcomers = ids - known
                known = ids
                if (newcomers.isEmpty()) return@collect
                // A sighting is what a parked room tick or a backed-off sealed tick was waiting for
                // (ADR 2026-09.y5f3) — before the reflood throttle, which is about our profile, not theirs.
                newcomers.forEach { ackSync.onReachable(Peer(it)) }
                val now = clock()
                if (now - lastFloodAt < PROFILE_REFLOOD_MIN_MS) return@collect
                val (wire, env) = ownProfile()
                val owed = newcomers.filter { !flooded.contains(refloodKey(env.id, it)) }
                if (owed.isEmpty()) return@collect // every newcomer already holds this id — a linger flap
                lastFloodAt = now
                owed.forEach { flooded.add(refloodKey(env.id, it)) }
                originateWire(wire, env)
            }
        }
    }

    /** The `flooded` memo's key in [watchReachable]: this profile frame, flooded for this peer. */
    private fun refloodKey(
        frameId: String,
        peerId: String,
    ): String = "$frameId|$peerId"

    /**
     * Records every phone that enters the nearby set as met (the Your mesh screen's "people this phone has
     * met"). Reads [nearbyPeers] — the same set the header's count and [neighbors] are — rather than the
     * transport's data-path links, so a phone sighted over BLE presence but never linked still counts as
     * met, and so "nearby" and "met" can never disagree about what proximity means (ADR 2026-09.2ajk).
     * Its own collector, off [watchNeighbors]: that one runs the custody re-offer hooks and a DB write does
     * not belong on it. A StateFlow collector that suspends only conflates, so a burst of joins costs one
     * write.
     */
    private fun watchMetPeers(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            nearbyPeers.collect { current ->
                val ids = current.mapTo(HashSet()) { it.nodeId }
                val newcomers = ids - known
                known = ids
                if (newcomers.isNotEmpty()) metPeers.recordMet(newcomers, clock())
            }
        }
    }

    /**
     * Posts the open-to-chat cue for [peerIds] (arrival order): the collision-aware label per peer (ADR 058,
     * the same resolution `InboundPipeline.notifyIncoming` uses) and, for a lone person, their avatar bytes.
     * A peer whose row is gone by now is simply not named.
     */
    private suspend fun postOpenToChat(peerIds: List<String>) {
        val labels = peers.labelIndex()
        val rows = peerIds.mapNotNull { peers.find(it) }
        if (rows.isEmpty()) return
        val names = rows.map { labels.labelFor(it.nodeId, it.name).text }
        val avatar = rows.singleOrNull()?.avatarHash?.let { blobs.bytes(it) }
        notifier.notifyOpenToChat(names, avatar)
    }

    private fun watchProfileChanges(
        session: CoroutineScope,
        seeded: Job,
    ) {
        session.launch {
            var first = true
            // Five flows, each one settings edit: the board's number and key arrive as one value
            // (SettingsStore.loraBoard), so a bind or an unbind is one emission here, never two.
            combine(
                settings.displayName,
                settings.status,
                settings.avatarUpdatedAt,
                settings.openToChat,
                settings.loraBoard,
            ) { name, status, avatarAt, openToChat, board ->
                OwnPresentation(name, status, avatarAt, openToChat, board)
            }
                // Distinct BEFORE the first value is set aside, never after: every one of the five flows is a
                // projection of the one DataStore, which re-emits on a write to ANY key — so the seed's own
                // stamp write, the ledger flush, a read-state stamp, each re-emits the unchanged presentation.
                // With the drop first, the second of those identical values was the first thing distinct saw,
                // and every launch published a "changed" profile on the first settings write after start —
                // a fresh version, a fresh custody row, a sealed update to every contact, at a moment nothing
                // chose (the lab saw it as a third stamp per node, stranded behind an unlink).
                .distinctUntilChanged()
                .collect { live ->
                    if (first) {
                        first = false
                        // The stored state, not an edit — unless custody presents something else: an edit made
                        // while the mesh was stopped (a stamp not yet stale reuses the custodied bytes) or one
                        // written before this collector subscribed (its combine's initial value already carries
                        // it). Either is published once, here; the common launch publishes nothing. Judged
                        // against the row the startup seed leaves, never ahead of it: before the seed has
                        // refreshed a stale stamp, the row for the current one is the stale frame or nothing.
                        seeded.join()
                        if (!custodyPresentsOtherThan(live)) return@collect
                    }
                    broadcastProfile()
                    broadcastSealedProfile()
                }
        }
    }

    /**
     * Whether the profile frame custody holds for the current stamp ([ownProfile], seeded here if absent)
     * presents anything other than [live] — the fields the frame carries, compared as the frame carries
     * them (the avatar by its hash, the name and status normalized and capped).
     */
    private suspend fun custodyPresentsOtherThan(live: OwnPresentation): Boolean {
        val held = WireCodec.decodePayload<ProfileContent>(ownProfile().second.payload) ?: return true
        return held.name != normalizeSingleLine(live.name).clampToUtf16(TextLimits.DISPLAY_NAME) ||
            held.status != normalizeSingleLine(live.status).clampToUtf16(TextLimits.STATUS) ||
            held.avatarHash != settings.ownAvatarHash.first() ||
            held.openToChat != live.openToChat ||
            held.loraNode != live.board?.node ||
            held.loraKey != live.board?.key ||
            // A build that gained or lost a capability bit (CAP_CALL) reuses the held frame for the current stamp,
            // so peers would not see the change until an edit or the 12 h republish.
            held.capabilities != (Protocol.LOCAL_CAPABILITIES or extraCapabilities)
    }

    private fun watchIncomingFiles(session: CoroutineScope) {
        session.launch {
            transport.incomingFiles.handleEach(
                handle = { file ->
                    when (file.kind) {
                        FileKind.AVATAR -> {
                            pipeline.onAvatarReceived(file.fromNodeId, file.key, file.mime, file.path)
                        }

                        FileKind.ATTACHMENT -> {
                            // Noted before the bytes are stored: this channel is the radios' (a board carries no
                            // file, the spool is not a transport), and the §9.5 deferral reads the arrival as
                            // the evidence the row's plane only stands in for — a heal round that finds these
                            // bytes in hand must never find them unexplained, whatever order the row lands in.
                            attachmentDefer.noteRadioArrival(file.key)
                            val stored = blobExchange.onReceived(file.key, file.mime, file.path, file.resumedFrom, from = file.fromNodeId)
                            // Hash-checked and held now, so the memo above becomes proof that outlives the process: this
                            // phone, the photo's recipient, holds bytes a radio gave it, and never uploads them (ADR
                            // 2026-10.vy27). After the store, never before — the memo is a deferral, this is for good.
                            if (stored) blobs.recordHolder(file.key, identity.nodeId(), DeliveryPlane.Nearby, clock())
                        }
                    }
                },
                // The link counts a file as arriving until it is taken here (#138): stored, refused, or thrown on.
                taken = { transport.fileTaken(it.fromNodeId, it.key) },
                // One file's failure, not the collector's (#139): the next file is handled, and this one is re-asked.
                onFailure = { file, t ->
                    Log.w(TAG, "incoming ${file.kind} ${file.key.take(LOG_KEY_CHARS)}… from ${file.fromNodeId} failed", t)
                },
            )
        }
    }

    private fun watchIncomingDigests(session: CoroutineScope) {
        session.launch {
            // A neighbor advertised the custody ids it holds → push it just the frames it lacks (the id-diff).
            transport.incomingDigests.collect { forwardSync.onDigest(it.fromNodeId, it.ids) }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        // Custody our own profile (ORIGIN_SELF), exactly as a peer that receives it carries it (ORIGIN_RELAY).
        // Without this our store is permanently missing our own profile while every peer holds it, so the
        // store-and-forward digests never converge and the mesh churns NDPs forever. Idempotent on the (now
        // persisted, restart-stable) version, so repeated connects don't re-store it.
        val (wire, env) = ownProfile()
        router.sendOwn(wire, env.id, peer)
        sendAvatarIfNeeded(peer)
    }

    /**
     * Publishes a **changed** profile: stamps a fresh publish time, floods the frame, and re-ships the
     * avatar to any neighbor that lacks it.
     *
     * The stamp is what makes the change propagate at all. [currentProfileEnvelope] keys the frame id
     * (and its `sentAt`) on [SettingsStore.profilePublishedAt], so re-flooding under the previous stamp
     * re-sends an id the mesh has already seen: the receiver drops it at [MeshRouter]'s `SeenSet` before
     * [InboundPipeline] ever parses it, and [ForwardSync.onSeen] short-circuits on `store.has(id)` — so
     * our own custody keeps the *pre-change* bytes and re-serves them to every late joiner and across the
     * Internet plane. The change then stays invisible until [republishProfileIfStale] mints a new id up
     * to 12 h later. That is exactly how a display name set moments after first contact stayed blank on
     * the peer while the avatar — pushed as a file, not a frame, so no id dedupes it — arrived normally.
     *
     * Bumping [SettingsStore.profileVersion] is not enough on its own: the version is the receiver's LWW
     * key, and a frame that never reaches [InboundPipeline.handleProfile] never gets to present it.
     *
     * Callers that re-send **unchanged** content deliberately do NOT come through here — [watchReachable]'s
     * first-sighting reflood and [pushProfileTo]'s first-contact push both read [ownProfile], because reusing the
     * id is precisely what lets a receiver dedupe a copy it already holds. Only a content change earns a
     * new stamp — and the version bump, the stamp and the signing happen under [profileLock] as one step,
     * so nothing reads the new stamp with the old version (or the old stamp with the new one) and signs that.
     */
    private suspend fun broadcastProfile() {
        val (wire, env) =
            profileLock.withLock {
                // Monotonic bump, persisted: a version that's stable across restarts is what keeps a
                // custodied profile from minting a new frame every launch (see SettingsStore.profileVersion).
                // Past a badge-only version too (ADR 2026-10.r99s): peers hold it as our watermark.
                settings.setProfileVersion(maxOf(clock(), profileVersion() + 1))
                // Ahead of building the envelope: the stamp IS the frame id (and the custody `sentAt`) it reads.
                settings.setProfilePublishedAt(nextPublishStamp())
                signOwnProfileLocked()
            }
        originateWire(wire, env)
        transport.neighbors.value.forEach { sendAvatarIfNeeded(it) }
    }

    /**
     * A publish stamp strictly past the last one. The stamp is the frame id, so two edits inside one clock
     * tick would otherwise share an id — the second a `SeenSet` duplicate on every peer and a `store.has`
     * no-op in custody, invisible until the next republish. Caller holds [profileLock].
     */
    private suspend fun nextPublishStamp(): Long = maxOf(clock(), settings.profilePublishedAt.first() + 1)

    /**
     * Sends our avatar file to [peer] only if we haven't already sent them this exact avatar. Profile
     * edits that don't touch the avatar (e.g. a status change) re-broadcast the profile frame but no
     * longer re-ship the (unchanged) avatar JPEG to every neighbor.
     */
    private suspend fun sendAvatarIfNeeded(peer: Peer) {
        val hash = settings.ownAvatarHash.first() ?: return
        if (sentAvatarHashes[peer.nodeId] == hash) return
        val avatar = blobStore.fileFor(hash) ?: return
        transport.sendFile(avatar, peer, avatarMeta(hash))
        sentAvatarHashes[peer.nodeId] = hash
    }

    private fun avatarMeta(hash: String): FileMeta = FileMeta(FileKind.AVATAR, key = hash, mime = AVATAR_MIME)

    /**
     * Fans a sealed profile update (`CTL_PROFILE`) to every peer we hold a confirmed v2 session with.
     *
     * This does **not** replace [broadcastProfile]; the two ship together. A cleartext `profile` frame
     * remains the only thing that works at first contact — it is authenticated against the `pubKey`
     * inside its own payload, so it can never be encrypted — and the only thing a pre-ratchet peer can
     * read. What this adds is the copy that reaches an *established* contact with no radio path, since a
     * `profile` frame is deliberately not scope-carried (`docs/SPOOL_PROTOCOL.md` §4.4), and it takes
     * name/status/avatar changes off the cleartext plane for peers that can read it.
     *
     * The target set is confirmed sessions rather than "accepted conversations": a sealed profile
     * discloses strictly less than the cleartext frame already floods to everyone, so narrowing it would
     * cost propagation and buy no privacy.
     *
     * Deduped per (peer, version) rather than floored on a timer. A profile edit is rare and
     * user-visible, so a time floor would suppress a real second edit — worse than the storm it
     * prevents — and one send per version suffices because custody and the Internet plane both carry the
     * frame to a peer that is offline right now.
     *
     * Re-run from [heal] as well as from [watchProfileChanges], because the target set is *confirmed*
     * sessions and an edit does not wait for one. A session that confirms just after the edit would
     * otherwise never be told: the fan-out already ran, found that peer unconfirmed (or found nothing at
     * all), and nothing re-tried. First contact is where this bites — a name is typically set within
     * seconds of meeting a peer, while an initiator's session stays unconfirmed until the peer answers
     * under it. [sentProfileVersions] makes the repeat free once every session has the current version;
     * being in-memory, the first heartbeat after a restart re-sends it once, which the receiver applies
     * idempotently.
     */
    private suspend fun broadcastSealedProfile() {
        publishBadgesIfChanged()
        val version = profileVersion()
        if (version <= 0L) return
        // Who is owed this version comes first: on every heal with nobody owed — the common case — the payload's
        // five settings reads and its build are skipped, not just the sends.
        // Confirmed sessions only: our unanswered reset also exports (ADR 2026-10.j67x), and a profile sealed on it
        // would carry the reset's init unflagged (#90) — the peer's answer confirms it, and the next heal sends.
        val owed = ratchet.exportedRoots().filter { it.confirmed && sentProfileVersions[it.peerId] != version }
        if (owed.isEmpty()) return
        val me = identity.nodeId()
        val avatarHash = settings.ownAvatarHash.first()
        val payload = currentProfilePayload(version, avatarHash)
        owed.forEach { session ->
            if (sendCtlDm(session.peerId, payload, avatarHash, me)) sentProfileVersions[session.peerId] = version
        }
    }

    /** The sealed presentation payload (`CTL_PROFILE`) for the current profile at [version]. */
    private suspend fun currentProfilePayload(
        version: Long,
        avatarHash: String?,
    ): MessageContent {
        val board = settings.loraBoard.first()
        return MessageContent(
            body = "",
            ctl = MessageContent.CTL_PROFILE,
            pr =
                ProfilePayload(
                    name = normalizeSingleLine(settings.displayName.first()).clampToUtf16(TextLimits.DISPLAY_NAME),
                    status = normalizeSingleLine(settings.status.first()).clampToUtf16(TextLimits.STATUS),
                    avatarHash = avatarHash,
                    version = version,
                    openToChat = settings.openToChat.first(),
                    loraNode = board?.node,
                    loraKey = board?.key,
                    badges = shownBadges().first().ifEmpty { null },
                ),
        )
    }

    /**
     * Our profile version as the sealed path states it: the cleartext profile's, or the later one a badge-only change
     * was published under ([BadgeJournal.sealedVersion]). Every edit mints past both.
     */
    private suspend fun profileVersion(): Long = maxOf(settings.profileVersion.first(), badgeJournal?.sealedVersion?.first() ?: 0L)

    /**
     * Publishes a change in the badges our sealed profile carries (ADR 2026-10.r99s) **without** touching the cleartext
     * profile: a fresh version for the sealed path alone, then the sealed fan-out. A cleartext frame whose version
     * moved with no visible field changed would tell every carrier the holder shows a supporter badge. Judged against
     * the fingerprint published last, persisted, so a change made while the mesh was stopped mints a version of its own
     * too, and one version never carries two badge lists.
     */
    private fun watchBadgeChanges(session: CoroutineScope) {
        badgeJournal ?: return
        session.launch {
            shownBadges().collect { if (publishBadgesIfChanged()) broadcastSealedProfile() }
        }
    }

    /**
     * Mints a sealed version for the badge list live now if it is not the one published last, and says whether it did.
     * Run before every sealed send as well as by [watchBadgeChanges], so a send never states a published version with a
     * list that version did not carry (a quick off-and-on the watcher's `combine` conflated, a heal or an intro inside
     * it). Under [profileLock], read and write together.
     */
    private suspend fun publishBadgesIfChanged(): Boolean {
        val journal = badgeJournal ?: return false
        return profileLock.withLock {
            val fingerprint = badgeFingerprint(shownBadges().first())
            if (fingerprint == (journal.publishedFingerprint.first() ?: badgeFingerprint(emptyList()))) return@withLock false
            journal.putSealed(maxOf(clock(), profileVersion() + 1), fingerprint)
            true
        }
    }

    /**
     * The badges our sealed profile carries (docs/SUPPORTER_BADGE.md C-5-10, C-6-4): none while "Show my supporter badge"
     * is off, else the held ones, at most [BadgeEntry.MAX_PER_PROFILE], in their held order, to every recipient alike —
     * opt-in is the whole gate (the maintainer's decision, 2026-10-14). Whether each still verifies is each receiver's
     * question at its own clock.
     */
    private fun shownBadges(): Flow<List<BadgeEntry>> {
        val journal = badgeJournal ?: return flowOf(emptyList())
        return combine(journal.show, journal.held) { on, held ->
            if (on) held.take(BadgeEntry.MAX_PER_PROFILE).map { it.entry() } else emptyList()
        }.distinctUntilChanged()
    }

    /** A local fingerprint of a badge list: what [watchBadgeChanges] compares. Never sent anywhere. */
    private fun badgeFingerprint(badges: List<BadgeEntry>): String =
        hexOf(
            sha256(
                badges.fold(ByteArray(0)) { acc, b ->
                    acc +
                        b.w.size
                            .toString()
                            .encodeToByteArray() + b.w + b.r
                },
            ),
        )

    /**
     * The contact-card intro to [peerId] (`IntroSync`): one sealed `CTL_PROFILE` DM, exactly the frame
     * [broadcastSealedProfile] sends to an established contact — here sent to a peer whose session does
     * not exist yet, so `ratchet.sealDm` runs the X3DH initiation off the pinned prekey and the init rides
     * the frame. No new wire: every deployed build reads it, and the receiver's ordinary profile-version
     * gate makes a stale or version-0 payload a harmless no-op while the session still forms. Bypasses
     * [sentProfileVersions] on purpose — the driver owns its own floors. False when nothing could be sealed.
     */
    private suspend fun sendIntroTo(peerId: String): Boolean {
        val avatarHash = settings.ownAvatarHash.first()
        publishBadgesIfChanged()
        val payload = currentProfilePayload(profileVersion(), avatarHash)
        return sendCtlDm(peerId, payload, avatarHash, identity.nodeId())
    }

    /**
     * [TransferSignals]: one sealed `CTL_TRANSFER` DM to [peerId] — the offer/answer/credentials of a direct
     * Wi-Fi file transfer (`transfer/TransferManager`), never a message. False when nothing could be sealed.
     */
    override suspend fun sendTransferSignal(
        peerId: String,
        payload: TransferPayload,
    ): Boolean = sendCtlDm(peerId, MessageContent(body = "", ctl = MessageContent.CTL_TRANSFER, xf = payload), null, identity.nodeId())

    /**
     * [CallSignals]: one sealed `CTL_CALL` DM to [peerId] — the offer, answer, late candidates or hang-up of a voice
     * call (`call/CallManager`), never a message. It rides every path a DM rides (C-3.1-3). False when nothing could
     * be sealed.
     */
    override suspend fun sendCallSignal(
        peerId: String,
        payload: CallPayload,
    ): Boolean = sendCtlDm(peerId, MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = payload), null, identity.nodeId())

    /**
     * Issues [payload] as this phone's scope config for [conversationId] (`CTL_SCOPE_CONFIG`, docs/SPOOL_PROTOCOL.md
     * §5) — a peer's node id with [ScopeConfigPayload.groupId] null, or a group's id with the same id in the payload —
     * and records it as our own row, so this phone reads the winner its peers will. True when it was sealed and
     * flooded. Nothing calls it yet but tests: the next phase issues a config only where a phone's home is not the
     * shared default relay (ADR 2026-10.8kfx).
     *
     * An ordinary custodied sealed ctl, flooded and refilled like any other, and only ever sealed:
     *  - **No v1 and no cleartext form.** A null seal sends nothing. A v1 wrap would land on a pre-ratchet build as an
     *    empty bubble (ADR 018), and a config has no cleartext twin.
     *  - **Never a session opened for it.** A DM seals only under a confirmed session ([RatchetSessions.sealDm]'s
     *    `confirmedOnly`, judged under the ratchet lock): no first-contact init, and not on our own unanswered reset,
     *    whose init an unflagged copy would carry into the peer's hour-long incidental floor (#90, ADR 2026-10.j67x).
     *    The config waits for the answer, as the sealed profile does ([broadcastSealedProfile]).
     *  - **A group rides its sender-key chain** ([sealGroupRatchet]), only while every other member's pairwise
     *    session is already confirmed — so a seed this send mints goes out over sessions that exist, never an X3DH
     *    opened for the config — and never the v1 group form it would otherwise fall back to.
     *  - **Only what a peer would take.** The payload must pass [ScopeConfigPolicy.validate] and be news against our own
     *    held rows; a config every peer would refuse or keep past is not worth a chain key. Pick its version with
     *    [ScopeConfigPolicy.nextVersion] over [ScopeConfigRepository.winning], the eligible winner. The version is
     *    never checked against our clock: a phone whose clock runs slow still sends, at a low version. Our row is
     *    written before the seal ([ScopeConfigRepository.reserve]) and given back if the seal sends nothing.
     */
    suspend fun sendScopeConfig(
        conversationId: String,
        payload: ScopeConfigPayload,
    ): Boolean {
        val me = identity.nodeId()
        val group = Conversations.kindFor(conversationId) == ConversationKind.GROUP
        if (!group && Conversations.kindFor(conversationId) != ConversationKind.DM) return false
        if (conversationId == me) return false
        val now = clock()
        val config =
            when (val result = ScopeConfigPolicy.validate(payload, conversationId, conversationId.takeIf { group }, me)) {
                is ScopeConfigPolicy.Result.Refused -> return false
                is ScopeConfigPolicy.Result.Valid -> result.config
            }
        // Our own row first, in one transaction with the read that says it is news; given back if nothing is sent.
        val reservation = scopeConfigs.reserve(config, now) ?: return false
        val content = MessageContent(body = "", ctl = MessageContent.CTL_SCOPE_CONFIG, sc = payload)
        val sent = if (group) sealScopeConfigGroup(conversationId, content, me) else sealScopeConfigDm(conversationId, content, me)
        if (!sent) scopeConfigs.release(reservation)
        return sent
    }

    /** [sendScopeConfig]'s DM half: a v2 ctl DM under a confirmed session, or nothing. */
    private suspend fun sealScopeConfigDm(
        peerId: String,
        content: MessageContent,
        me: String,
    ): Boolean {
        val peer = peers.find(peerId) ?: return false
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return false
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return false
        val id = FrameId.new()
        val now = clock()
        val aad = MessageCrypto.header(id, me, now, peerId)
        // The named v2 layout: the compact one has no label for `sc` (MessageContentV2 refuses it).
        val sealed =
            ratchet.sealDm(peerId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), content.encode(), aad, now, confirmedOnly = true)
                ?: return false
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = peerId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        return true
    }

    /** [sendScopeConfig]'s group half: the sender-key form, with every member's session confirmed, or nothing. */
    private suspend fun sealScopeConfigGroup(
        groupId: String,
        content: MessageContent,
        me: String,
    ): Boolean {
        val group = groups.find(groupId)?.takeIf { !it.left }?.toGroupInfo() ?: return false
        if (me !in group.members) return false
        val confirmed = ratchet.exportedRoots().filter { it.confirmed }.mapTo(HashSet()) { it.peerId }
        if (!confirmed.containsAll(group.members.filter { it != me })) return false
        val id = FrameId.new()
        val now = clock()
        val sealed = sealGroupRatchet(group, me, content, MessageCrypto.header(id, me, now, group.id)) ?: return false
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        return true
    }

    /**
     * One walkie chunk to [peerId] (docs/VOICE_CALLS.md §3.11): [chunk] sealed on the DM ratchet into a [FrameType.TALK]
     * frame, as small as the frame can be made, since a burst sends two a second over links as slow as Coded PHY's:
     *  - its payload is a [TalkSeal], the v3 seal under a binary header ([ChatContent] CBOR only for a header that form
     *    cannot carry);
     *  - it is unsigned, because the seal authenticates it to its addressee (the ADR 059 argument), and a relay never
     *    needs to read it;
     *  - its id is [FrameId.TALK_LENGTH] characters of a fresh one: it is a dedup key for minutes and nothing else.
     * No node custodies it, and it never rides LoRa. A live neighbour takes it alone, over its quickest link
     * ([MeshTransport.sendLive]), and relays it nowhere (ttl 0); anyone further gets a flood of at most [TALK_TTL] relays.
     *
     * A peer this phone has no radio path to — not a neighbour, and none of its own frames through a link of ours within
     * [TalkRadioPaths.WINDOW_MS] ([talkRadioPath]) — gets the chunk **cast** through the spools too (§2.7, [ScopeSync.cast]),
     * when a connected one would carry it: on an `ent`
     * spool that needs a cast ticket, ours or the peer's ([peerTicketed], from its walkie signal). The cast copy is
     * signed, as every frame the Internet plane carries is; the radio copy stays unsigned for Coded PHY's sake. Both
     * carry the same id, so the peer keeps whichever arrives first. With links but no path to the peer, the blind flood
     * still goes beside the cast. A neighbour whose live link is near its floor ([MeshTransport.liveLinkFading]) gets
     * the cast beside its radio copy too (ADR 2026-10.79yb's ninth amendment): a dying link keeps counting as a path
     * until its supervision timeout, about 20 s in which every chunk over it is lost. And [castToo] casts it beside the
     * radio copy whatever the radios say: the walkie's own verdict that its radio path loses chunks (`TalkRoute`, from
     * the peer's reports of what it heard), which no link here can see.
     *
     * The frame's size (its envelope's bytes), or null when nothing could be sealed, or there is neither a link nor a
     * spool to hand it to (nothing is sealed then, so the ratchet does not move).
     */
    suspend fun sendCallTalk(
        peerId: String,
        chunk: ByteArray,
        peerTicketed: Boolean = false,
        castToo: Boolean = false,
    ): Int? {
        val links = transport.neighbors.value
        val neighbour = links.firstOrNull { it.nodeId == peerId }
        val cast = castsTalk(peerId, links, peerTicketed, castToo)
        if (links.isEmpty() && !cast) return null
        val peer = peers.find(peerId)
        val bundle = peer?.pubKey?.let { PublicKeyBundle.decode(it) } ?: return null
        val me = identity.nodeId()
        val id = FrameId.new().take(FrameId.TALK_LENGTH)
        val now = clock()
        val aad = MessageCrypto.header(id, me, now, peerId)
        val scheme = if (peer.readsCryptoV3()) EncEnvelope.VERSION_DM_V3 else EncEnvelope.VERSION_RATCHET
        val sealed = ratchet.sealDm(peerId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), chunk, aad, now, scheme) ?: return null
        val compact = sealed.r?.takeIf { sealed.v == EncEnvelope.VERSION_DM_V3 }?.let { TalkSeal.encode(it, sealed.ct) }
        val payload = compact ?: WireCodec.encodePayload(ChatContent(enc = sealed))
        val env = RelayEnvelope(type = FrameType.TALK, id = id, senderId = me, sentAt = now, recipientId = peerId, payload = payload)
        val wire = WireEnvelope(ttl = if (neighbour != null) 0 else TALK_TTL, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
        if (links.isNotEmpty()) router.sendOwn(wire, id, neighbour, live = true)
        val castTook = if (cast) castTalk(peerId, peerTicketed, env, wire) else 0
        return wire.signed.size.takeIf { links.isNotEmpty() || castTook > 0 }
    }

    /**
     * Whether a chunk to [peerId] is cast too: the radios have no path to it ([talkRadioPath]), or its own live link is
     * near its floor ([MeshTransport.liveLinkFading]), or the walkie asked ([castToo]); and a connected spool would
     * carry the cast.
     */
    private fun castsTalk(
        peerId: String,
        links: Set<Peer>,
        peerTicketed: Boolean,
        castToo: Boolean,
    ): Boolean {
        val fading = links.any { it.nodeId == peerId } && transport.liveLinkFading(peerId)
        return (castToo || fading || !radioPathTo(peerId, links)) && canCastTalk(peerId, peerTicketed)
    }

    /**
     * Whether a chunk to [peerId] has a radio path from these [links]: the peer is one, or its own frame came through one
     * within [TalkRadioPaths.WINDOW_MS]. Never [relayedPresence]: its linger is a statement to a reader, not a route.
     */
    private fun radioPathTo(
        peerId: String,
        links: Set<Peer>,
    ): Boolean = links.any { it.nodeId == peerId } || talkRadioPaths.pathTo(peerId, links.mapTo(HashSet()) { it.nodeId })

    /** Whether a walkie chunk to [peerId] has a radio path now ([radioPathTo]): what the call screen labels its way by. */
    fun talkRadioPath(peerId: String): Boolean = radioPathTo(peerId, transport.neighbors.value)

    /** §2.7: casts [wire]'s frame to [peerId] through the spools, signed, and returns how many took it. */
    private suspend fun castTalk(
        peerId: String,
        peerTicketed: Boolean,
        env: RelayEnvelope,
        wire: WireEnvelope,
    ): Int = scopeSync?.cast(peerId, peerTicketed, env, messageCrypto.signRaw(wire.signed), wire.signed) ?: 0

    /**
     * Whether a walkie chunk to [peerId] could be cast through a connected spool now (§2.7), with the peer's ticket
     * flag [peerTicketed] — what [sendCallTalk] casts on. False without the plane.
     */
    fun canCastTalk(
        peerId: String,
        peerTicketed: Boolean,
    ): Boolean = scopeSync?.canCast(peerId, peerTicketed) == true

    /**
     * Whether a cast to [peerId] would reach it: [canCastTalk] on a spool the peer was lately seen on
     * ([ScopeSync.castCovers]). The call screen's label for a relay path; [sendCallTalk] casts on [canCastTalk] alone.
     */
    fun castCoversTalk(
        peerId: String,
        peerTicketed: Boolean,
    ): Boolean = scopeSync?.castCovers(peerId, peerTicketed) == true

    /** Whether this phone holds a cast ticket on a connected spool that carries casts — what its walkie signal says. */
    fun castTicketed(): Boolean = scopeSync?.castTicketed() == true

    /**
     * [TurnSource]: TURN credentials from the connected spools carrying [peerId]'s DM scope, or holding a ticket of
     * ours (ADR 2026-10.xkp4); empty without the plane.
     */
    override suspend fun turnServers(peerId: String): List<TurnServer> = turnServers(peerId) { null }

    /**
     * [TurnSource]: as [turnServers], each spool that names its TURN servers asked for the one [pick] ranks best
     * (C-7.6-51), and each credential carrying its spool's list and asking order, which the call chooses its one
     * server by (C-7.6-53).
     */
    override suspend fun turnServers(
        peerId: String,
        pick: (List<String>) -> String?,
    ): List<TurnServer> = scopeSync?.turnCredentials(peerId, pick).orEmpty().mapIndexed { asked, issue -> issue.toServer(asked) }

    /** [TurnSource]: a credential for the TURN server [entry] names, from a spool that lists it (C-7.6-54). */
    override suspend fun turnServerFor(
        peerId: String,
        entry: String,
    ): TurnServer? = scopeSync?.turnCredentialFor(peerId, entry)?.toServer(asked = 0)

    private fun TurnIssue.toServer(asked: Int) =
        TurnServer(urls = reply.urls, user = reply.user, pass = reply.pass, spoolHosts = hosts, asked = asked)

    /** [TurnSource]: the STUN URIs every connected spool advertised in its HELLO (C-7.6-20); empty without the plane. */
    override fun stunUrls(): List<String> = scopeSync?.stunUrls().orEmpty()

    /** [TurnSource]: whether a connected spool would issue this phone TURN now (`SpoolStatus.turnForUs`); no without the plane. */
    override fun turnAvailable(): Boolean = scopeSync?.turnAvailable() == true

    /**
     * Seals one ctl DM ([payload], a `CTL_PROFILE` or `CTL_TRANSFER`) to [peerId]. For a profile the avatar hash is
     * repeated in the **cleartext**
     * [ChatContent.attachmentHash] — the DB v19 precedent (`docs/WIRE_COMPAT.md`) reapplied: it is what
     * lets a blind carrier custody the avatar bytes, and what the Internet plane's attachment pass reads
     * to fetch them (`docs/SPOOL_PROTOCOL.md` §9.5). The authoritative copy stays inside the seal.
     *
     * The hash and *only* the hash (ADR 035). `AVATAR_MIME` is a constant and so leaked nothing about the
     * avatar itself — but if this stayed the one sealed frame still carrying a cleartext mime, then
     * mime-presence would itself become a fresh distinguisher, sorting sealed frames into "profile update"
     * and "user message" for any carrier. Nulling it here is what stops the fix creating a new signal.
     *
     * A ctl DM is a v2 seal, so it goes only to a peer whose pinned profile carries [Protocol.CAP_RATCHET], the bit
     * [canSealTickTo] asks for: a v1-only reader (an iPhone today) could open none, and `ratchet.sealDm` would otherwise
     * start an X3DH off whatever prekey it found (the interop audit's IOS-7).
     */
    private suspend fun sendCtlDm(
        peerId: String,
        payload: MessageContent,
        avatarHash: String?,
        me: String,
    ): Boolean {
        val peer = peers.find(peerId) ?: return false
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return false
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return false
        val id = FrameId.new()
        val now = clock()
        val aad = MessageCrypto.header(id, me, now, peerId)
        val (plaintext, scheme) = payload.sealBytes(peer.readsCryptoV3())
        val sealed = ratchet.sealDm(peerId, bundle.dhPublicKey(), ratchetPrekeyOf(peer), plaintext, aad, now, scheme) ?: return false
        if (scheme == EncEnvelope.VERSION_DM_V3) metrics.onDmSealedV3()
        originateSigned(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = peerId,
                payload =
                    WireCodec.encodePayload(
                        // Hash only, no mime — the ADR 035 rule applied to the avatar hint: a fetcher
                        // addresses the blob by hash, and the mime only told a carrier what class of image
                        // this control frame carries.
                        ChatContent(
                            enc = sealed,
                            attachmentHash = avatarHash,
                        ),
                    ),
            ),
        )
        return true
    }

    private suspend fun currentProfileEnvelope(): RelayEnvelope {
        val me = identity.nodeId()
        // The LWW key receivers order against. Persisted, so a relaunch is not mistaken for an edit.
        val version = settings.profileVersion.first()
        // …and separately, when we last published. Custody expiry is `sentAt + ttl`, so stamping the frame
        // with the *edit* time (as this did until ADR 022) made a profile older than the custody TTL dead on
        // arrival: it left custody, no late joiner could pull it, and the Internet plane — which seals what
        // custody holds — could not carry it at all. The id keys on the publish stamp too, so a re-publish is
        // a genuinely new custody row rather than a `store.has(id)` no-op in ForwardSync.onSeen.
        val publishedAt = settings.profilePublishedAt.first()
        // The current signed prekey rides every profile (v2 DM bootstrap) — its detached signature lets
        // receivers verify it against the bundle even stored apart from this frame.
        val spk = identity.currentPrekey(clock())
        // One snapshot for both board fields: the number and key of the board this profile names must agree.
        val board = settings.loraBoard.first()
        val content =
            ProfileContent(
                // Normalize/cap defensively: covers legacy values stored before the field gained a cap and
                // the rare process-death-before-the-blur-commit case, so peers never receive an oversized name.
                name = normalizeSingleLine(settings.displayName.first()).clampToUtf16(TextLimits.DISPLAY_NAME),
                status = normalizeSingleLine(settings.status.first()).clampToUtf16(TextLimits.STATUS),
                avatarHash = settings.ownAvatarHash.first(),
                pubKey = identity.publicKeyBundle(),
                deviceTag = identity.deviceTag(),
                protoVersion = Protocol.VERSION,
                capabilities = Protocol.LOCAL_CAPABILITIES or extraCapabilities,
                prekey = PrekeyInfo(id = spk.id, pub = spk.pub, sig = spk.sig),
                version = version,
                openToChat = settings.openToChat.first(),
                // The bound board's node number, so a contact's phone can line a heard radio post up with us —
                // and, on a board that signs, the key that lets it verify the post is ours.
                loraNode = board?.node,
                loraKey = board?.key,
            )
        return RelayEnvelope(
            type = FrameType.PROFILE,
            id = FrameId.profileId(me, publishedAt),
            senderId = me,
            sentAt = publishedAt,
            payload = WireCodec.encodePayload(content),
        )
    }

    /**
     * Refresh the publish stamp when the current profile frame is old enough that custody would soon refuse
     * it ([PROFILE_REPUBLISH_MS], comfortably inside the custody TTL). Not gated on the Internet plane being
     * enabled: a profile that ages out of custody is equally invisible to a radio late joiner, which is the
     * bug this fixes — the plane only made it load-bearing. Bumps no [SettingsStore.profileVersion], so a
     * re-publish is not an edit and cannot advance any receiver's LWW watermark.
     */
    private suspend fun republishProfileIfStale() = republishProfile(force = false)

    /**
     * [republishProfileIfStale]'s body, with [force] for the one caller that knows the current stamp is
     * already useless: a commons that count-evicted our profile tombstones its bytes for the room's TTL, so
     * only a fresh stamp — a fresh frame id — can put us back in front of its members (§7.4).
     */
    private suspend fun republishProfile(force: Boolean) {
        val seeded =
            profileLock.withLock {
                if (!force && clock() - settings.profilePublishedAt.first() < PROFILE_REPUBLISH_MS) return@withLock false
                settings.setProfilePublishedAt(nextPublishStamp())
                // Seed the refreshed frame into custody rather than flooding it: it carries no new information,
                // so the custody digest divergence is enough to move it to neighbors on the next contact. The
                // previous stamp's frame lingers until its own TTL — same version, so a receiver re-applies it
                // idempotently.
                signOwnProfileLocked()
                true
            }
        if (seeded) scopeSync?.onCustodyChanged()
    }

    // --- Signed origination ---

    /**
     * Signs [env] and floods it to the mesh, capturing a carriable DM/group message in the forward store
     * so we re-offer it to neighbors that join later. The single origination choke; non-storable frames
     * are simply not carried.
     */
    private suspend fun originateSigned(
        env: RelayEnvelope,
        hint: FanoutHint = FanoutHint.CONTENT,
    ) {
        // The targeted sibling inside: a sealed DM-form frame goes straight to its addressee over the
        // coordination plane, so it does not wait on an NDP that may not exist. Size-gated and no-op'd by the
        // transport when the peer isn't coordination-plane reachable; custody is still the reliable path.
        // Our own sends are the latency-sensitive case, so the Internet plane is nudged instead of waiting
        // for its tick. Relayed frames ride the next heal round — they are already in flight on the radios.
        originateWire(sign(env), env, hint)
    }

    /** [originateSigned] for a wire already signed — the custodied profile, whose bytes must not be re-minted. */
    private suspend fun originateWire(
        wire: WireEnvelope,
        env: RelayEnvelope,
        hint: FanoutHint = FanoutHint.CONTENT,
    ) {
        // Custody first, then the air (#141): a link that comes up between the two learns of the frame from one of
        // them — the flood reads the links as they are when it goes out, and the newcomer's digest exchange reads a
        // store that already holds the row. Flooding first left a link raised in that gap with neither until the
        // 60 s re-offer. A relayed frame keeps the same order (`InboundPipeline.onDeliver` custodies pre-flood).
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        router.originate(wire, env.id)
        if (shouldFastFanout(env)) transport.fastFanout(wire)
        env.recipientId?.let { if (shouldFastSend(env)) transport.fastSend(wire, Peer(it)) }
        if (shouldLongRangeFanout(env)) transport.longRangeFanout(wire, hint)
        scopeSync?.onCustodyChanged()
    }

    /**
     * Wraps [env] in a signed [WireEnvelope]: the canonical envelope bytes plus our raw Ed25519 signature
     * over exactly those bytes (so every relay reproduces them verbatim and the signature holds mesh-wide).
     */
    private fun sign(
        env: RelayEnvelope,
        relay: Boolean = true,
    ): WireEnvelope {
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = relay, sig = messageCrypto.signRaw(signed), signed = signed)
    }

    /**
     * The unsigned point-to-point form (ADR 059): an empty signature, like the blob request. Only ever a
     * `relay = false` v3 sealed tick — the ratchet AEAD, whose associated data binds id, sender, sentAt,
     * recipient and the header, is its authenticator, and the receiver's unsigned door admits exactly that
     * shape (`InboundPipeline.verifyInbound`). Anything flooded or custodied carries [sign]'s signature,
     * because a carrier can verify a signature and cannot open a session.
     */
    private fun unsigned(env: RelayEnvelope): WireEnvelope =
        WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))

    /**
     * [ProfileFrameSource]: the current signed cleartext profile frame for the LoRa plane's key-bootstrap
     * beacon. Builds and signs the same self-certifying frame the radio paths flood, WITHOUT originating or
     * custodying it — the stable `profile-<me>-<publishedAt>` id makes re-hearing it a SeenSet no-op, so a
     * beacon never floods the mesh a second time.
     */
    override suspend fun signedProfile(): WireEnvelope = ownProfile().first

    /**
     * Our profile as every plane must put it on air: the custodied row for the current publish stamp when
     * there is one, else a fresh signing that is custodied here and now. One id, one set of bytes
     * (ADR 2026-09.y5f3): the mesh-in-a-box lab caught a LoRa beacon signing `profile-me-<stamp>` while a
     * settings write was still landing, so the peer custodied the beacon's bytes and this device its seed's —
     * two blobs under one id at the spool, and a scope digest that could never converge. Taken under
     * [profileLock], so it can never overlap the edit that writes the stamp it reads.
     */
    private suspend fun ownProfile(): Pair<WireEnvelope, RelayEnvelope> = profileLock.withLock { signOwnProfileLocked() }

    /** [ownProfile]'s body — the one place a profile frame is signed. Caller holds [profileLock]. */
    private suspend fun signOwnProfileLocked(): Pair<WireEnvelope, RelayEnvelope> {
        val env = currentProfileEnvelope()
        forwardStore.frame(env.id, clock())?.let { held -> return WireEnvelope(sig = held.sig, signed = held.signed) to held.envelope }
        val wire = sign(env)
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        return wire to env
    }

    /**
     * [MeshPostSink]: one post the bound board heard on its primary channel, delivered into the Meshtastic
     * room here and nowhere else — no frame, no signature, no custody, no fan-out. The pipeline owns the
     * delivery so the row is moderated, notified, counted and stored by the same code every other chat
     * delivery runs.
     */
    override suspend fun onPublicPostHeard(post: MeshPost) = pipeline.deliverMeshPost(post)

    /**
     * A post a Knit user typed in the Meshtastic room — the only thing in Knit written for an audience outside
     * it, and the one send that never touches Knit's mesh: it goes to this phone's own board ([publicChannel])
     * and, once the board has queued it, into the local room as our own row. A refusal stores nothing, so the
     * composer keeps the draft and can say why.
     *
     * Moderated with `isRoom = true`, which [sendChat] would not do: it infers the scope from the addressing
     * shape, and this post is addressed to nobody. A public radio channel deserves the room moderator —
     * profanity as well as toxicity — at least as much as Nearby does. Once, here: the row is written directly
     * rather than through the inbound path, so nothing screens it a second time.
     *
     * The row is stamped [DeliveryPlane.LoRa] because that is the plane it left on. It never earns a ✓✓ —
     * nothing on the channel acks — so the bubble keeps the single "sent" tick, and the details screen can
     * name the plane.
     */
    override suspend fun sendPublicPost(text: String): PublicPostOutcome {
        if (isTextFlagged(text, "outgoing", isRoom = true)) return PublicPostOutcome.Blocked
        publicChannel(text)?.let { return PublicPostOutcome.Refused(it) }
        messages.save(
            MessageEntity(
                id = FrameId.new(),
                senderId = identity.nodeId(),
                conversationId = Conversations.MESHTASTIC,
                body = text,
                sentAt = clock(),
                received = false,
                receivedVia = DeliveryPlane.LoRa.code,
            ),
        )
        return PublicPostOutcome.Queued
    }

    /**
     * A post in a commons (docs/SPOOL_PROTOCOL.md §7.4). Not moderated on the way out — a commons lives on a
     * private spool, so like a DM or a group its members screen what arrives (ADR 2026-10.9rh6). Signed like every frame,
     * stored as the message row plus the exact signed bytes the plane re-seals on every heal round — and then
     * **not** originated: no radio flood, no custody, no fan-out. The spool that runs the room is its only
     * carrier, and the plane is woken to push it now. Cleartext inside the room's seal, like a Nearby post
     * on the air: every member holds the room key, so there is nobody to seal for.
     */
    @Suppress("LongMethod") // the post's own row and its signed frame, built side by side from one attachment
    override suspend fun sendCommons(
        conversationId: String,
        text: String,
        mentions: List<Mention>,
        replyTo: ReplyRef?,
        attachment: AttachmentStore.Ingested?,
        styles: List<StyleSpan>,
        declared: DeclaredLangs,
    ): Boolean {
        val room = commons?.find(conversationId) ?: return false
        val declared = declared.normalized()
        val spans = StyleSpans.normalize(text, styles)
        val me = identity.nodeId()
        val id = FrameId.new()
        val sentAt = clock()
        // Sealed to its own key as a group's attachment is, the key riding cleartext inside the room's seal: the
        // blob's address is then the ciphertext's, which nobody outside the room can compute or ask the radios for.
        val sealedAttachment = attachment?.let { sealAttachment(it, requireKey = true) ?: return false }
        // Declared by the sealed path's rule: only a large one says its size, a named file by the lower bar.
        val size =
            attachment
                ?.takeIf { BluetoothFetchGate.declaresSize(it.sizeBytes.toLong(), named = it.name != null) }
                ?.sizeBytes
                ?.toLong()
        val env =
            RelayEnvelope(
                type = FrameType.COMMONS,
                id = id,
                senderId = me,
                sentAt = sentAt,
                payload =
                    WireCodec.encodePayload(
                        CommonsPost(
                            scope = ScopeCrypto.commonsScopeId(room.secret),
                            chat =
                                ChatContent(
                                    body = text,
                                    mentions = mentions,
                                    attachmentHash = sealedAttachment?.hash,
                                    attachmentMime = attachment?.mime,
                                    replyTo = replyTo,
                                    attachmentSize = size,
                                    video = attachment?.video,
                                    attachmentName = attachment?.name,
                                    attachmentKey = sealedAttachment?.key,
                                    styles = spans,
                                    lang = declared.langIndex,
                                    reads = declared.readsIndex,
                                ),
                        ),
                    ),
            )
        val wire = sign(env)
        commons.post(
            MessageEntity(
                id = id,
                senderId = me,
                conversationId = conversationId,
                body = text,
                sentAt = sentAt,
                received = false,
                receivedVia = DeliveryPlane.Internet.code,
                mentions = MentionStore.encode(mentions),
                attachmentHash = sealedAttachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
                // Derived at ingest; a member derives the same pair from the bytes when they land (onObtained).
                voiceDurationMs = attachment?.voice?.durationMs,
                voicePeaks = attachment?.voice?.peaks,
                attachmentName = attachment?.name,
                attachmentSize = size,
                attachmentAnimated = attachment?.animated == true,
                // What a later forward or repeat of this file finds it by (ADR 2026-10.mptm), as the sealed path keeps.
                attachmentPlainHash = attachment?.hash?.takeIf { sealedAttachment?.key != null },
                attachmentSourceHash = attachment?.sourceHash,
                styles = StyleStore.encode(spans),
                bodyLang = declared.lang,
                authorReads = declared.reads,
            ).withReply(replyTo)
                .withVideo(attachment?.video),
            sig = wire.sig,
            signed = wire.signed,
        )
        scopeSync?.onCustodyChanged()
        return true
    }

    /**
     * A reaction in a commons (C-7.4-13): the toggle [sendReaction] makes, stored optimistically, then posted into the
     * room as a signed `commons` frame every member reads — never the sealed DM or group form (the room has no
     * session to seal for) and never the cleartext flood (a private room's message named on the air). Our earlier
     * reaction to the same post leaves the outbox in the same write ([CommonsStore.postReaction]).
     */
    override suspend fun sendCommonsReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
    ): Boolean {
        if (!isValidReactionEmoji(emoji)) {
            Log.w(TAG, "commons reaction refused: ${emoji.length} units on $messageId")
            return false
        }
        val room = commons?.find(conversationId) ?: return false
        val me = identity.nodeId()
        val next = if (reactions.currentEmoji(messageId, me) == emoji) null else emoji
        val id = FrameId.new()
        val now = clock()
        val env =
            RelayEnvelope(
                type = FrameType.COMMONS,
                id = id,
                senderId = me,
                sentAt = now,
                payload =
                    WireCodec.encodePayload(
                        CommonsPost(scope = ScopeCrypto.commonsScopeId(room.secret), reaction = ReactionContent(messageId, next)),
                    ),
            )
        val wire = sign(env)
        reactions.apply(ReactionEntity(messageId, me, next, now))
        commons.postReaction(conversationId, messageId, id, now, wire.sig, wire.signed)
        scopeSync?.onCustodyChanged()
        return true
    }

    /**
     * [FarPeerFrameSource]: the carried DM-form frames worth re-offering to [nodeId] when a long-range plane
     * first hears it (ADR 039, split by ADR 2026-10.a4pm), own first, then relayed:
     *
     * - **Own:** our newest [FAR_REOFFER_OWN_LIMIT] DMs to the peer it has not acked, chosen by id off
     *   `unackedDmsTo` and read back from custody. Since the LoRa reach gate holds a DM to an unheard peer for
     *   exactly this moment, this is how those DMs reach the air, so the cap is the larger one. Chosen by id
     *   rather than filtered after a newest-N custody read, because that read let acked DMs and our own sealed
     *   ctls (a ✓✓, a reaction — no unacked row) take the slots an unacked DM needed.
     * - **Relayed:** the newest [FAR_REOFFER_RELAYED_LIMIT] live custody frames to the peer written by anyone
     *   else; their fate is unknowable, so they are offered.
     *
     * Verbatim custody bytes re-wrapped like a custody re-serve: the receiver's SeenSet / exists-gate make a
     * duplicate harmless, and a re-delivered DM re-draws its receipt — which is how a tick lost over LoRa heals.
     * The transport enqueues them in this order, FIFO within the DM class.
     */
    override suspend fun framesFor(nodeId: String): List<WireEnvelope> {
        val now = clock()
        val me = identity.nodeId()
        // Over-fetched, then capped after the custody read: an unacked row whose frame custody no longer holds
        // (evicted, or past its TTL) must not take a slot from an older one it still does.
        val ownIds =
            messages
                .unackedDmsTo(nodeId, me, since = now - RESEAL_WINDOW_MS)
                .sortedByDescending { it.sentAt }
                .take(FAR_REOFFER_OWN_LIMIT * 2)
                .map { it.id }
        val own =
            if (ownIds.isEmpty()) {
                emptyList()
            } else {
                forwardStore
                    .liveFramesByIds(ownIds, now)
                    .filter { it.envelope.recipientId == nodeId && it.envelope.senderId == me }
                    .take(FAR_REOFFER_OWN_LIMIT)
            }
        val relayed = forwardStore.liveFramesToFromOthers(nodeId, me, now, FAR_REOFFER_RELAYED_LIMIT)
        return (own + relayed).map { WireEnvelope(sig = it.sig, signed = it.signed) }
    }

    /**
     * [BridgeFrameSource]: the id prefixes of the live frames a LoRa gateway could serve, for the body of its
     * OFFER (ADR 044). Read straight off `liveFrames` — the set the ADR 006 TTL and quota rules already bound
     * identically on every node — filtered to what the plane can actually carry, so a group frame never spends
     * one of the ~48 prefix slots one packet holds.
     *
     * The head of [bridgeCandidates], which is [framesMissing]'s order too: the encoder truncates to fit, so
     * whatever the offer cannot reach is what a far gateway will send us for ever, and the only safe frames to
     * leave unnamed are the ones it would serve last (ADR 2026-09.zkma).
     *
     * `dms = true` here is deliberate even when the user has DMs off this plane. An offer says what we
     * **hold**, not what we would send, and naming a DM we already have is what stops a far gateway serving
     * us a copy of it. It leaks nothing: a prefix is a hash of a random 128-bit frame id. What the switch
     * governs is [framesMissing], which is the side that puts bytes on the air.
     */
    override suspend fun offerPrefixes(limit: Int): IntArray =
        bridgeCandidates(dms = true)
            .take(limit)
            .map { LoraCtl.prefixOf(StoreDigest.hash64(it.envelope.id)) }
            .toIntArray()

    /**
     * [BridgeFrameSource]: the carriable frames we hold whose id prefix [theirPrefixes] does not name — what
     * the publishing gateway is missing. Ranked by [LoraFramePolicy.backfillRank] (key bootstrap, then the
     * room, then sealed DMs — ADR 2026-09.rre4) and newest-first within a rank, then capped at [limit]; the
     * caller's airtime budget is the real bound and usually bites first. The rank is [bridgeCandidates]' own,
     * so this and [offerPrefixes] can never disagree about which frames a truncated offer leaves out.
     *
     * A prefix that collides makes a frame look present when it is not, so it is skipped this round. That is
     * the accepted cost of fitting a useful window into one packet (see [LoraCtl]); nothing here is a trust
     * boundary — every frame carries the originator's signature and is verified at the far end as usual.
     *
     * Like [framesFor], the bytes are a verbatim re-wrap: no re-encoding, no new storage, no custody rule
     * touched, so the content digest's inputs are identical on every node before and after (ADR 006).
     */
    override suspend fun framesMissing(
        theirPrefixes: IntArray,
        limit: Int,
        dms: Boolean,
    ): List<WireEnvelope> {
        val have = theirPrefixes.toHashSet()
        return bridgeCandidates(dms)
            .filter { LoraCtl.prefixOf(StoreDigest.hash64(it.envelope.id)) !in have }
            .take(limit)
            .map { WireEnvelope(sig = it.sig, signed = it.signed) }
    }

    /**
     * The live custody frames the LoRa plane could carry, in [LoraFramePolicy.bridgeOrder] — the one place the
     * bridge's frame-set rule is applied, so the offer and the serve can never disagree about what is eligible
     * *or about which frames a one-packet offer has room to name*. Deliberately the plane's own
     * [LoraFramePolicy] rather than a restatement of it here: a rule stated twice drifts.
     */
    private suspend fun bridgeCandidates(dms: Boolean): List<CarriedFrame> {
        val carried = forwardStore.liveFrames(clock())
        if (carried.isEmpty()) return emptyList()
        val eligible =
            carried.filter { frame ->
                val wire = WireEnvelope(sig = frame.sig, signed = frame.signed)
                LoraFramePolicy.eligible(frame.envelope, wire, LoraFramePolicy.Path.BACKFILL) &&
                    (dms || !LoraFramePolicy.isDmForm(frame.envelope))
            }
        return LoraFramePolicy.bridgeOrder(eligible) { it.envelope }
    }

    /**
     * Whether [text] is non-blank and the on-device moderator flags it as abusive. Not gated by the
     * content-filtering setting, which only governs receive-side hiding. Drives both block-on-send in the
     * two public rooms (Nearby in [sendChat], Meshtastic in [sendPublicPost]; never a DM, group or commons,
     * ADR 2026-10.9rh6) and the stored flag on every inbound message (in [deliverChat]); a flagged inbound
     * message is still stored and merely collapsed
     * in the UI (collapse itself gated at display time by the setting, see [ChatViewModel]), so a false
     * positive never silently drops content. [isRoom] selects the moderation scope: the Nearby
     * broadcast room gets slurs + profanity + toxicity, while DMs and groups get slurs + toxicity (see
     * [ScopedTextModerator]). [direction] (`"outgoing"`/`"incoming"`) only labels the debug log; the
     * verdict score/category/decision is logged under [TEXT_MODERATION_TAG], mirroring the image
     * screen's `ImageModeration` logging — the body itself is never logged (only its length).
     */
    private suspend fun isTextFlagged(
        text: String,
        direction: String,
        isRoom: Boolean,
    ): Boolean {
        // A shared position rides the body as a `geo:` token (location/GeoUri), and it is taken out before
        // either moderator sees the words: the room's lexical pass maps leet digits to letters and splits on
        // the rest, so a run of coordinates can spell a blocked word by accident, and the ML pass was never
        // shown one. Here and nowhere else, since every send path and the inbound classify come through.
        val words = GeoUri.strip(text)
        if (words.isBlank()) return false
        val verdict = textModeration.classify(words, isRoom)
        Log.d(
            TEXT_MODERATION_TAG,
            "$direction text score=${verdict.score} category=${verdict.category} " +
                "label=${verdict.label} flagged=${verdict.flagged} len=${words.length}",
        )
        return verdict.flagged
    }

    /**
     * Retransmits DMs to [recipientId] that were composed before their key was known (saved pendingKey
     * by [sendChat]). Now that [handleProfile] has pinned the key, each is re-sealed and flooded (and
     * captured for carry via [originate]); a still-unresolvable or now-blocked recipient is left pending.
     */
    internal suspend fun flushPendingFor(recipientId: String) {
        if (recipientId in settings.blockedNodeIds.first()) return
        val me = identity.nodeId()
        messages.pendingForRecipient(recipientId).forEach { row ->
            // This DM never flew, and custody takes these fresh bytes: the user's message, probe-eligible (ADR 2026-10.a4pm).
            if (resealAndFlood(row, recipientId, me, FanoutHint.CHAT)) messages.clearPending(row.id)
        }
    }

    /** One reset's re-seal, owed a second pass once the routers' dedup window has run out ([resealSecondPass]). */
    private class SecondPass(
        val dueAt: Long,
        val ids: Set<String>,
    )

    /**
     * Per peer, the DM ids its last reset re-sealed, owed one more seal at [SecondPass.dueAt] (#171, ADR
     * 2026-10.dmgw). A later reset of the same peer replaces its entry; the second pass never records one, so
     * nothing loops. In memory only: a process that restarts inside the window loses its second pass, and the
     * DMs it would have carried reach a peer behind a relay only on the next reset's re-seal.
     */
    private val pendingSecondReseal = ConcurrentHashMap<String, SecondPass>()

    /**
     * Re-seals our recent unacked DMs to [recipientId] under the CURRENT session state — the recovery
     * half of an inbound ratchet **session reset**: a wiped peer can no longer open frames sealed to
     * its dead session, so each gets a fresh seal under the replacement session, flooded live under
     * its original id. Receiver-side idempotency (the exists-gate + upsert) makes duplicates harmless.
     *
     * Its reach is what the routers it meets let through, not custody. Every custody keeps the first bytes
     * it stored under an id (the reset peer's own included), and a relay drops an id it first saw inside
     * [SeenSet.DEFAULT_TTL_MS] — which, for the DMs whose failures fired the reset, is every relay between
     * us. So the same ids are re-sealed once more after [ResetReseal.SECOND_PASS_MS] (the tick in
     * [reofferToNeighbors]), when any window this pass ran into has lapsed (#171, ADR 2026-10.dmgw). A peer
     * reachable live at neither pass still misses them: custody serves only the originals. So does one behind a
     * relay that first saw an original after the reset (a custody pull inside the window), whose own window can
     * cover the second pass too.
     */
    internal suspend fun resealRecentDmsTo(recipientId: String) {
        val rows = unackedToReseal(recipientId)
        // Recorded before the first copy leaves, so whoever sees one (a scenario's oracle) sees the pass owed too. A
        // row that cannot seal now (no key) is owed the second try with the rest; it is still unacked.
        if (rows.isNotEmpty()) {
            pendingSecondReseal[recipientId] = SecondPass(clock() + ResetReseal.SECOND_PASS_MS, rows.mapTo(HashSet()) { it.id })
        }
        reseal(rows, recipientId)
    }

    /**
     * The second pass of every reset re-seal now due ([pendingSecondReseal]): the ids that pass recorded and that
     * are still unacked — not one acked since, not a DM sent after the reset (that one flew under the new
     * session). Once per reset; it records nothing, so it never re-arms. Run from the 60 s tick whether or not
     * anyone is linked, since a due entry would otherwise wait for a link to come back. Each re-seal costs one
     * chain key per DM still unacked at ~11 min.
     */
    private suspend fun resealSecondPass() {
        val now = clock()
        pendingSecondReseal.entries.toList().forEach { (peer, pass) ->
            if (now < pass.dueAt) return@forEach
            // Conditional: a reset that replaced the entry since the snapshot keeps its own.
            if (!pendingSecondReseal.remove(peer, pass)) return@forEach
            // Removed before it runs, so a failure costs this pass and never repeats it; caught, so it never ends
            // the 60 s loop that runs it.
            try {
                val rows = unackedToReseal(peer).filter { it.id in pass.ids }
                Log.d(TAG, "reset re-seal second pass to $peer: ${rows.size} of ${pass.ids.size} still unacked")
                reseal(rows, peer)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // never ends the re-offer loop
            ) {
                Log.w(TAG, "reset re-seal second pass to $peer failed: ${e.message}")
            }
        }
    }

    /** Our unacked DMs of the last [RESEAL_WINDOW_MS] to [recipientId]; none for a blocked peer. */
    private suspend fun unackedToReseal(recipientId: String): List<MessageEntity> {
        if (recipientId in settings.blockedNodeIds.first()) return emptyList()
        return messages.unackedDmsTo(recipientId, identity.nodeId(), since = clock() - RESEAL_WINDOW_MS)
    }

    private suspend fun reseal(
        rows: List<MessageEntity>,
        recipientId: String,
    ) {
        val me = identity.nodeId()
        // CONTENT, not CHAT: custody keeps the first-stored seal of this id (`ForwardSync.onSeen` returns early on
        // `has(id)`), so a re-seal the LoRa reach gate held would later be re-offered as the old bytes the reset
        // peer cannot open. Exempt, the fresh seal flies now (ADR 2026-10.a4pm) — and, sharing the frame's key, it
        // replaces an old-seal copy still queued, which is the copy worth losing.
        rows.forEach { row -> resealAndFlood(row, recipientId, me, FanoutHint.CONTENT) }
    }

    /**
     * Rebuilds one stored DM row's plaintext, seals it through the [sealEnvelopeFor] chokepoint under
     * whatever session state exists NOW (v2 when the peer is ratchet-capable, else v1), and floods it
     * under the ORIGINAL id/sentAt (so receivers dedup and the AEAD header matches). False when nobody
     * addressable holds a key yet.
     */
    private suspend fun resealAndFlood(
        row: MessageEntity,
        recipientId: String,
        me: String,
        hint: FanoutHint,
    ): Boolean {
        // The one builder sendChat seals through too, so a re-seal carries every sealed field the first send did
        // (a file's name and size were dropped here once — #131).
        val envelope =
            sealEnvelopeFor(row.id, me, row.sentAt, recipientId, group = null, content = row.sealedContent())
                ?: return false
        originateSigned(
            chatEnvelope(
                row.id,
                me,
                row.sentAt,
                recipientId,
                group = null,
                // Same cleartext-hash exposure as sendChat — hash only, mime sealed — so a re-sealed DM's
                // image is custodied too.
                ChatContent(
                    enc = envelope,
                    attachmentHash = row.attachmentHash,
                ),
            ),
            hint, // the caller's: see flushPendingFor and resealRecentDmsTo (ADR 2026-10.a4pm)
        )
        return true
    }

    private suspend fun flushSeenStamps() {
        runCatching { peers.flushSeen() }.onFailure { Log.w(TAG, "lastSeenAt flush failed: ${it.message}") }
    }

    /** Periodically logs a transmission snapshot so flood-suppression and byte savings are visible. */
    private fun logMetricsPeriodically(session: CoroutineScope) {
        session.everyRound(METRICS_LOG_INTERVAL_MS, onFailure = { roundFailed(MeshRound.METRICS, it) }) {
            val s = metrics.snapshot()
            Log.d(
                TAG,
                "metrics: originated=${s.frames.framesOriginated} delivered=${s.frames.framesDelivered} " +
                    "relayed=${s.frames.framesRelayed} handedOn=${s.frames.framesHandedOn} suppressed=${s.frames.framesSuppressed} " +
                    "deduped=${s.frames.framesDeduped} bytesSent=${s.frames.bytesSent} " +
                    "dropped=${s.frames.framesDropped} drops=${s.frames.dropsByReason} " +
                    "keyReq=${s.keys.keyRequestsSent} keyServed=${s.keys.keysServed} keyRecovered=${s.keys.keysRecovered} " +
                    "framesHeld=${s.keys.framesHeld} framesReplayed=${s.keys.framesReplayed} " +
                    "groupSeedsHeld=${s.groups.groupSeedsHeld} groupSeedsReplayed=${s.groups.groupSeedsReplayed} " +
                    "receiptsResent=${s.receipts.receiptsResent} " +
                    "receiptsSealed=${s.receipts.receiptsSealed}/${s.receipts.receiptsSealedFallback} " +
                    "reAcksSkipped=${s.receipts.receiptsReAckSkipped} " +
                    "dmSealedV3=${s.seals.dmSealedV3} ticksUnsigned=${s.receipts.ticksUnsigned} " +
                    "reactionsSealed=${s.seals.reactionsSealed}/${s.seals.reactionsSealedFallback} " +
                    "filesNan=${s.files.filesSentNan} filesBt=${s.files.filesSentBt} bulkTimeouts=${s.nan.nanBulkGraceTimeouts}",
            )
            // The contribution ledger banks its deltas on the same tick: one DataStore write a minute at most.
            ledger.flush()
            // So do the name-clash fade's `lastSeenAt` stamps: one peers write a minute at most (ADR 2026-10.k8ge).
            // A presentation stamp; a failed write waits for the next tick rather than ending this loop.
            flushSeenStamps()
            // Bounds the indirect-reach table; the linger itself is applied at read (indirectPeers).
            relayedPresence.sweep()
        }
    }

    private companion object {
        /** Debug-bridge root fingerprint length — enough to compare eras across devices, nothing more. */
        const val ROOT_HASH_HEX_CHARS = 8

        /** How much of a file's key a log line names. */
        const val LOG_KEY_CHARS = 8

        const val TAG = "MeshManager"
        const val TEXT_MODERATION_TAG = "TextModeration"

        /** The Internet plane's logcat tag, shared with `OkHttpSpoolDialer`. */
        const val SPOOL_TAG = "ScopeSync"

        /** The field log's spool sampler: one `spool` line per configured relay every 5 min (ADR 2026-10.a239). */
        private const val SPOOL_FIELD_SAMPLER = "spool"

        const val METRICS_LOG_INTERVAL_MS = 60_000L
        const val FORWARD_SWEEP_INTERVAL_MS = 10 * 60_000L

        // Re-run the neighbor re-offer hooks (custody digest + blob/key re-asks) for currently-linked neighbors
        // this often, so a persistent link (Bluetooth) gets the anti-entropy that Wi-Fi Aware's flapping
        // ephemeral links get for free. Short enough to converge a missed message within ~a minute, long enough
        // to stay cheap on battery/bandwidth.
        const val NEIGHBOR_REOFFER_INTERVAL_MS = 60_000L

        // Min spacing between first-contact profile floods (watchReachable): a burst of newcomers costs one
        // origination; custody + the per-link pushProfileTo cover anyone the coalesced flood skipped. Behind
        // it sits the per-(frame, peer) memo that keeps a linger flap from re-flooding inside the SeenSet
        // window at all (#73).
        const val PROFILE_REFLOOD_MIN_MS = 30_000L

        // How often the profile frame is re-stamped and re-seeded (republishProfileIfStale). Must stay
        // comfortably inside ForwardRepository.DEFAULT_TTL_MS (24h) — custody expiry is `sentAt + ttl`, so a
        // frame is refused as dead on arrival once its stamp ages past that, and the profile silently
        // vanishes from custody and from every scope built on it. Half the TTL leaves a full window of slack
        // for a device that is asleep or offline when a heartbeat is due.
        const val PROFILE_REPUBLISH_MS = 12 * 60 * 60_000L

        /** Avatars are always JPEG on this path (`AvatarStore` re-encodes every input). */
        const val AVATAR_MIME = "image/jpeg"

        /** How far back the post-reset DM re-seal reaches — the custody TTL (older frames left the mesh). */

        const val RESEAL_WINDOW_MS = 24 * 60 * 60_000L

        /**
         * How many of our own unacked DMs a long-range plane re-offers per first hearing of a peer (≤ 3 packets
         * each, ADR 2026-10.a4pm). Six two-packet DMs are ~30 s, two thirds of a LongFast window on `LIVE`, which
         * holds room posts and ticks behind it; with [FAR_REOFFER_RELAYED_LIMIT] one sighting adds at most 10
         * frames to the pacer's 32-frame queue. A longer backlog has waited longer than a LoRa hop should carry
         * and waits for a radio or spool contact (ADR 039 §7).
         */
        const val FAR_REOFFER_OWN_LIMIT = 6

        /** How many relayed DM-form frames a long-range plane re-offers per first hearing of a peer (ADR 039). */
        const val FAR_REOFFER_RELAYED_LIMIT = 4

        /** Per-(group, member) floor on proactive/responder seed re-sends (docs/GROUP_FORWARD_SECRECY.md #10). */
        const val SEED_RESEND_FLOOR_MS = 15 * 60_000L

        /** Relays a walkie chunk to a peer that is not a neighbour may take: reach of four hops, not the mesh's whole. */
        const val TALK_TTL = 3

        /** Payload for a frame whose content lives entirely in the routing envelope (e.g. a group update). */
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}

/**
 * The heal basket's two hourly floors (work item #63). The retention sweeps enforce windows of 48 h and up, and the
 * custody replay is the net under the instant seed-adoption replays; every 15 min was nine statements and a full
 * custody decode for nothing. Outside the private companion so `MeshManagerTest` and the lab can step a clock past
 * them.
 */
internal object HealFloors {
    const val RETENTION_SWEEP_MS = 60 * 60_000L
    const val GROUP_REPLAY_MS = 60 * 60_000L
}

/**
 * When a reset's re-seal goes out again ([MeshManager.resealRecentDmsTo], #171, ADR 2026-10.dmgw). Beside
 * `RESEAL_WINDOW_MS` in spirit; outside the private companion so `MeshManagerTest` and the lab can step a clock past it.
 */
internal object ResetReseal {
    /**
     * How long after a reset's re-seal its second pass is due: the routers' dedup window plus one 60 s tick, so a
     * relay that dropped the first re-seal as a duplicate of the original has forgotten the id. Tied to
     * [SeenSet.DEFAULT_TTL_MS] so the two move together.
     */
    const val SECOND_PASS_MS = SeenSet.DEFAULT_TTL_MS + 60_000L
}

/** The own-profile fields whose edit republishes the profile (`watchProfileChanges`). */
private data class OwnPresentation(
    val name: String,
    val status: String,
    val avatarUpdatedAt: Long,
    val openToChat: Boolean,
    val board: LoraBoard?,
)

/** `"<peerId>|<millis>"` entries ↔ a peer→stamp map, the intro driver's two settings sets. */
private fun decodeStamped(entries: Set<String>): Map<String, Long> =
    entries
        .mapNotNull { entry ->
            val at = entry.lastIndexOf('|')
            if (at <= 0) return@mapNotNull null
            val stamp = entry.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
            entry.substring(0, at) to stamp
        }.toMap()

private fun encodeStamped(map: Map<String, Long>): Set<String> = map.mapTo(mutableSetOf()) { (peerId, stamp) -> "$peerId|$stamp" }
