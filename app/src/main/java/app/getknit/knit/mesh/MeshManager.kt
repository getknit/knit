package app.getknit.knit.mesh

import android.util.Log
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.isValidReactionEmoji
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.readsCryptoV3
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.lora.LoraCtl
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.ChatContent
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
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.GroupRootPolicy
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.mesh.spool.GroupScopeRoots
import app.getknit.knit.mesh.spool.PairScopeRoots
import app.getknit.knit.mesh.spool.ScopeBlobs
import app.getknit.knit.mesh.spool.ScopeRegistry
import app.getknit.knit.mesh.spool.ScopeRoots
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolDialer
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.presence.OpenToChatPolicy
import app.getknit.knit.presence.OpenToChatWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val peers: PeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val blobStore: MeshBlobStore,
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
    private val db: KnitDatabase,
    // Opens WebSocket sessions to spools for [scopeSync]. Null (the default) means the app is built
    // without the Internet plane at all — which is what every unit test wants, and what keeps the mesh
    // seam free of any transitive knowledge of it.
    private val spoolDialer: SpoolDialer? = null,
    // Injectable wall clock so the send path's timestamps (a frame and its stored local copy share one
    // sentAt) are deterministic under test. Defaults to the real clock, so production wiring (the Koin
    // module) is unchanged; mirrors the house convention — ForwardSync(clock = …), AckSync, KeyExchange.
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Puts a post typed in the Meshtastic room on this phone's own board — the LoRa transport's
    // [PublicChannelSink]. A lambda rather than the interface for the reason [MeshPostSink] is a seam in the
    // other direction: the two ends construct each other, so one of them has to be late-bound. The default
    // refuses with NO_BOARD, which is what every test and every board-less build wants.
    private val publicChannel: suspend (body: String) -> PublicPostRefusal? = { PublicPostRefusal.NO_BOARD },
) : MeshController,
    ProfileFrameSource,
    FarPeerFrameSource,
    BridgeFrameSource,
    MeshPostSink {
    // Per-session scope for the collectors + metrics loop + router; cancelled in stop() so they don't
    // accumulate across start/stop cycles (e.g. a Diagnostics-triggered restart()).
    private var sessionScope: CoroutineScope? = null

    // Content-addressed image fetch over the mesh, backed by the encrypted blob store.
    private val blobExchange =
        BlobExchange(
            transport = transport,
            store = blobStore,
            selfId = { identity.nodeId() },
            // The chat list observes the blobs table for presence, so no per-message path write is needed
            // when an attachment arrives. A pulled blob may also be a (multi-hop) peer's avatar or a group
            // photo, so attribute it back to whoever advertised it, and — for an E2E attachment — screen its
            // decrypted bytes now that both the ciphertext and (from the delivered message) its key are on hand.
            onObtained = { hash, _ -> pipeline.onObtained(hash) },
        )

    // Store-and-forward DM custody: carries DMs we originate/relay and re-offers them to neighbors that
    // join later, so a message reaches a recipient that wasn't connected when it was first flooded.
    private val forwardSync =
        ForwardSync(
            transport = transport,
            store = forwardStore,
            authenticate = { wire, env -> pipeline.canCarry(wire, env) },
            // Fired once when a chat frame is actually carried: eager-pull its image blob so a custodied image
            // survives to a late joiner (the carrier holds ciphertext it can't read, like the frame itself).
            onCarried = { pipeline.onCarriedFrame(it) },
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
            isBlocked = { it in settings.blockedNodeIds.first() },
            metrics = metrics,
        )

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
            metrics = metrics,
            sealTick = { authorId, ackIds -> sealDeliveryTick(authorId, ackIds) },
            canSeal = { authorId -> canSealTickTo(authorId) },
            originateTick = { authorId, ackIds -> originateDeliveryTick(authorId, ackIds) },
            flushScope = { sessionScope },
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
    private val pendingInbound = PendingInbound(metrics = metrics)

    // Receiver-side state for the best-effort "now typing" indicator: which senders are typing in which
    // conversation. Ephemeral and never custodied — a typing cue is fire-and-forget, so nothing is persisted
    // (a live typer re-cues within the TTL). Populated by handleTyping, cleared by deliverChat on a real message.
    private val typingTracker = TypingTracker(scope)

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
            db = db,
            identity = identity,
            settings = settings,
            messageCrypto = messageCrypto,
            notifier = notifier,
            metrics = metrics,
            forwardSync = forwardSync,
            blobExchange = blobExchange,
            keyExchange = keyExchange,
            ackSync = ackSync,
            pendingInbound = pendingInbound,
            typingTracker = typingTracker,
            ratchet = ratchet,
            groupRatchet = groupRatchet,
            clock = clock,
            originate = ::originateSigned,
            originateTick = { originateSigned(it, FanoutHint.TICK) },
            dmAcks = dmAcks,
            flushPending = ::flushPendingFor,
            classifyText = ::isTextFlagged,
            resealUnacked = ::resealRecentDmsTo,
            redistributeGroupKey = ::redistributeGroupKey,
            flushGroupKeys = ::flushPendingGroupKeysFor,
            replayGroupCustody = ::replayCustodiedGroupFrames,
            adoptGroupRoot = ::adoptGroupRoot,
            onGroupRootCtl = ::onGroupRootCtl,
            onProfilePinned = { introSync.onProfilePinned(it) },
            onPeerFrameOpened = { senderId, carriesInit -> introSync.onPeerFrameOpened(senderId, carriesInit) },
        )

    // Reconstructed per session so its inbound collector + relay jobs live on the session scope and are
    // cancelled by stop() (rather than leaking on the never-cancelled app scope). Declared after `pipeline`
    // so onDeliver targets it.
    private var router = MeshRouter(transport, scope, metrics = metrics, onDeliver = pipeline::onDeliver)

    /**
     * §9.5's push-half deferral: an attachment we authored and whose recipient acked stays off the
     * Internet while that peer is still on the presence plane. Wired unconditionally (it is pure and
     * cheap) but only ever consulted by [scopeSync].
     */
    private val attachmentDefer =
        AttachmentDeferPolicy(
            // The short-range set, not the full reach: a LoRa-only sighting can't carry the bytes, so it must
            // not defer a spool upload (H5). [nearbyPeers] is the one definition of that set — a lambda, so
            // reading the property here is safe however late this field is initialized.
            reachable = { nearbyPeers.value.mapTo(mutableSetOf()) { peer -> peer.nodeId } },
            ackedBySender = { aHash -> messages.attachmentAcked(aHash, identity.nodeId()) },
            custodyTtlMs = ForwardRepository.DEFAULT_TTL_MS,
            clock = clock,
        )

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
                                ScopeRoots(it.peerId, it.pairwiseRoot, it.prevPairwiseRoot, it.prevRootExpiresAt)
                            }
                        },
                        groupRoots = ::groupScopeRoots,
                        pairs = ::pairScopeRoots,
                    ),
                dialer = dialer,
                store = forwardStore,
                selfId = { identity.nodeId() },
                urls = { settings.activeSpoolUrls.first().toList() },
                canCarry = pipeline::canCarry,
                blobs = scopeBlobs(),
                // The same hook a radio pull fires, so NSFW screening, the message rows, and the UI all
                // run unchanged for a spool-delivered image (§9.5).
                onAttachmentObtained = pipeline::onObtained,
                deferAttachment = attachmentDefer::defer,
                deliver = { wire, env, from -> router.handleInbound(wire, env, from) },
                metrics = metrics,
                clock = clock,
            )
        }

    @Volatile
    private var started = false

    // nodeId -> avatar hash we last sent that neighbor, so we don't re-push an unchanged avatar on
    // every profile edit or reconnect. Cleared per-peer when they disconnect (see watchNeighbors).
    private val sentAvatarHashes = ConcurrentHashMap<String, String>()

    /** Profile version last sealed to each peer — the per-(peer, version) dedupe of [broadcastSealedProfile]. */
    private val sentProfileVersions = ConcurrentHashMap<String, Long>()

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
        // Child of the app Job so app-scope cancellation still propagates; SupervisorJob isolates a
        // single collector's failure from the rest of the session. The shared handler logs any uncaught
        // throw in a top-level session collector instead of letting it vanish silently.
        val session =
            CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + meshExceptionHandler)
        sessionScope = session
        router = MeshRouter(transport, session, metrics = metrics, onDeliver = pipeline::onDeliver)
        router.start()
        transport.start()
        watchNeighbors(session)
        watchReachable(session)
        openToChatWatch.start(session)
        seedOwnProfileCustody(session)
        watchProfileChanges(session)
        watchIncomingFiles(session)
        watchIncomingDigests(session)
        resumePendingFetches(session)
        pruneForwardStorePeriodically(session)
        reofferToNeighborsPeriodically(session)
        logMetricsPeriodically(session)
        session.launch { introSync.prime() }
        scopeSync?.start(session)
    }

    override fun stop() {
        if (!started) return
        started = false
        scopeSync?.stop()
        transport.stop()
        // Clear this session's pending relays on the app scope (the session is about to die); capture
        // the instance so a fast restart reassigning `router` can't retarget the wrong one. Then tear
        // the session down — stopping the inbound collector, the metrics loop, and the watch* collectors.
        val session = sessionScope
        val sessionRouter = router
        scope.launch { sessionRouter.stop() }
        session?.cancel()
        sessionScope = null
    }

    override fun spoolStatus(): List<SpoolStatus> = demoSpools ?: scopeSync?.status().orEmpty()

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

    override suspend fun importContact(peerId: String) {
        introSync.want(peerId)
        // The radio half of the bootstrap: ask neighbors for the profile (hop-by-hop), so a pair that shares
        // a mesh but never exchanged a profile still gets the prekey without waiting for a re-flood.
        keyExchange.want(peerId)
    }

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

    /** Triggers an immediate rescan/reconnect (heartbeat alarm, device motion) and sweeps stale carry. */
    override fun heal() {
        if (!started) return
        transport.heal()
        // Piggyback the forward-store TTL sweep on the 15-min heartbeat so it runs while backgrounded.
        // Also re-ask neighbors for any key we're still missing, in case the holder is reachable now but
        // never arrived as a fresh neighbor (so onNeighborAdded didn't fire) — belt-and-suspenders for the
        // ongoing-drops retry already driven by want()'s cooldown.
        sessionScope?.launch {
            forwardSync.sweepExpired()
            pendingInbound.sweepExpired()
            keyExchange.sweepExpired() // age out stale (unauthenticated) key-wants; blob fetches that never arrived
            blobExchange.sweepExpired()
            keyExchange.retryMissing()
            ackSync.retryPending() // re-send broadcast/group ticks we still owe absent authors (+ age out old ones)
            dmAcks.flushDue() // tick the LoRa-held DM receipts whose hold has run out (ADR 054)
            ratchet.sweep(clock()) // retire epoch privs / skipped keys — the ratchet's PFS window enforcement
            groupRatchet.sweep(clock()) // retire group chains / skipped keys — the group PFS window
            groupRoots.sweep(clock()) // drop rotated-away group roots past their drain window
            replayUndeliveredGroupCustody() // re-try own-custody group frames whose seed arrived late
            rotatePrekeyIfDue()
            republishProfileIfStale() // refresh the publish stamp before custody would refuse the frame
            broadcastSealedProfile() // catch sessions that only confirmed after the edit fired
            introSync.retry() // re-send stale contact-card intros, settle confirmed ones, expire pair-scope grace
            mintGroupRootsIfDue() // mint a group's spool root when it is our turn (spec §3.2)
        }
    }

    /**
     * Rotates the ratchet signed prekey on its cadence (checked at start + every heal). A rotation bumps
     * the persisted profileVersion — the same monotonic clock a profile edit uses — so the fresh prekey
     * re-floods and replaces the custodied profile frame on every peer.
     */
    private suspend fun rotatePrekeyIfDue() {
        if (!identity.rotatePrekeyIfDue(clock())) return
        settings.setProfileVersion(maxOf(clock(), settings.profileVersion.first() + 1))
        broadcastProfile()
    }

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    override fun restart() {
        if (!started) return
        transport.stop()
        transport.start()
    }

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
     * Returns false without sending or storing anything if on-device content filtering flags [text] as
     * abusive (block-on-send); true once the message is stored locally and flooded. The [attachment] is
     * screened separately at ingest time, so by here it is already clean.
     *
     * `@Suppress("LongMethod")`: ktlint's one-arg-per-line wrapping of the two parallel MessageEntity /
     * content builds inflates the raw line count past detekt's LongMethod=60; the logic is two
     * straight-line branches, not complex.
     */
    @Suppress("LongMethod")
    override suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested?,
        mentions: List<Mention>,
        recipientId: String?,
        group: GroupInfo?,
        replyTo: ReplyRef?,
    ): Boolean {
        if (isTextFlagged(text, "outgoing", isRoom = recipientId == null && group == null)) return false
        val me = identity.nodeId()
        val id = FrameId.new()
        val sentAt = clock()
        val conversationId = Conversations.idFor(me, recipientId, me, group?.id)

        // Broadcast room: plaintext (no fixed recipient set to encrypt to) — the legacy path, unchanged.
        if (recipientId == null && group == null) {
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
                ).withReply(replyTo),
            )
            val content =
                ChatContent(
                    body = text,
                    mentions = mentions,
                    attachmentHash = attachment?.hash,
                    attachmentMime = attachment?.mime,
                    replyTo = replyTo,
                )
            originateSigned(chatEnvelope(id, me, sentAt, recipientId = null, group = null, content))
            return true
        }

        // DM or group: end-to-end encrypt. The attachment (if any) is encrypted to its own key and
        // re-addressed by its ciphertext hash; body/mentions/attachment refs go into the sealed content.
        val sealedAttachment = attachment?.let { sealAttachment(it) }
        // The piggyback (ADR 054): a DM to a peer that reads inline acks carries the receipts we still owe
        // it for DMs that came over the board, in place of a standalone tick that would cost ~3 s of LoRa
        // air. Attached only in the v2 arm of the seal; a v1 fallback or a parked DM gives them back.
        val inlineAcks = inlineAcksFor(recipientId, group, text, replying = replyTo != null, attached = attachment != null)
        val inlineIds = inlineAcks.ids
        val content =
            MessageContent(
                body = text,
                mentions = mentions,
                attachmentHash = sealedAttachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
                // A file's name and its plaintext length — the two facts a file bubble needs and cannot
                // read off the bytes. Null for an image or a voice note, whose bubbles describe themselves,
                // so an ordinary photo frame is byte-for-byte what it was before ADR 2026-09.qq2r.
                attachmentName = attachment?.name,
                attachmentSize = attachment?.takeIf { it.name != null }?.sizeBytes?.toLong(),
                replyTo = replyTo,
            )
        val envelope = sealEnvelopeFor(id, me, sentAt, recipientId, group, content, inlineIds)
        if (inlineIds.isNotEmpty() && recipientId != null) {
            val carried = envelope != null && EncEnvelope.isDmRatchetVersion(envelope.v)
            if (carried) {
                metrics.onReceiptCoalesced(inlineAcks.dm.size)
                metrics.onReceiptRidden(inlineAcks.room.size)
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
        messages.save(
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
                // A file's name/size come off the sealed content on the receiving side and off the staged
                // ingest here, and are written against the same ciphertext hash for the same reason.
                attachmentName = attachment?.name,
                attachmentSize = attachment?.takeIf { it.name != null }?.sizeBytes?.toLong(),
                pendingKey = envelope == null && group == null,
            ).withReply(replyTo),
        )
        if (envelope == null) {
            // No recipient's key is known yet — nothing can decrypt this. Saved locally above; a DM is
            // marked pendingKey and retransmitted on key arrival, a group message stays unsent.
            Log.w(TAG, "no known keys for recipient(s) of chat $id; not flooded yet")
            return true
        }
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
        )
        return true
    }

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
            peer.pubKey != null &&
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
    // arrival, neighbor join, session reset) and the key-request responder to one send per floor window.
    private val lastSeedSendAt = ConcurrentHashMap<Pair<String, String>, Long>()

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
            val seeds = if (seedDue && chain != null) listOf(chain) else emptyList()
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
     * write-once, so `save` is the same insert the radio path performs — a re-arrival is a no-op at
     * the DAO's `OnConflictStrategy.IGNORE`, and GC still bounds it through the ordinary references.
     *
     * `internal` rather than private only so `MeshManagerTest` can pin the mime-resolution rule in
     * `save` directly; nothing outside this file constructs one.
     */
    internal fun scopeBlobs(): ScopeBlobs =
        object : ScopeBlobs {
            override suspend fun has(aHash: String): Boolean = blobs.exists(aHash)

            override suspend fun bytes(aHash: String): ByteArray? = blobs.bytes(aHash)

            // [mime] is only the fetcher's hint, and since ADR 035 a sealed frame no longer carries one — so
            // prefer what our own decrypted row says this hash is, and fall back to the hint (an old peer's
            // cleartext mime, or ScopeSync's image/jpeg default) only when no row names it.
            override suspend fun save(
                aHash: String,
                mime: String,
                bytes: ByteArray,
            ) = blobs.insert(aHash, messages.attachmentMimeForHash(aHash) ?: mime, bytes)
        }

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
        forwardStore
            .liveFrames(clock())
            .filter { frame ->
                val env = frame.envelope
                env.type == FrameType.CHAT &&
                    env.group != null &&
                    env.senderId != me &&
                    (groupId == null || env.group.id == groupId) &&
                    (senderId == null || env.senderId == senderId)
            }.forEach { frame ->
                if (messages.exists(frame.envelope.id)) return@forEach
                // Replay bypasses the router (no second flood), exactly like PendingInbound's replay;
                // onDeliver's verify/custody/ack steps are all idempotent.
                pipeline.onDeliver(
                    WireEnvelope(relay = false, sig = frame.sig, signed = frame.signed),
                    frame.envelope,
                    frame.envelope.senderId,
                )
            }
    }

    /** The heal/startup backstop: every undelivered group frame in custody, any group, any sender. */
    private suspend fun replayUndeliveredGroupCustody() = replayCustodiedGroupFrames(groupId = null, senderId = null)

    /** Checks-and-stamps the per-(group, member) seed re-send floor. */
    private fun seedSendFloorOpen(
        groupId: String,
        memberId: String,
    ): Boolean {
        val key = groupId to memberId
        val now = clock()
        if (now - (lastSeedSendAt[key] ?: 0L) < SEED_RESEND_FLOOR_MS) return false
        lastSeedSendAt[key] = now
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
    ): Boolean {
        val tick = sealDeliveryTickEnvelope(authorId, ackIds) ?: return false
        originateSigned(tick.env, FanoutHint.TICK)
        metrics.onReceiptCustodied()
        return true
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
        replying: Boolean,
        attached: Boolean,
    ): InlineAcks {
        if (recipientId == null || group != null) return InlineAcks()
        val waiting = dmAcks.pending(recipientId).size + ackSync.ridingFor(recipientId)
        if (waiting == 0) return InlineAcks()
        val peer = peers.find(recipientId) ?: return InlineAcks()
        if ((peer.capabilities ?: 0L) and Protocol.CAP_INLINE_ACK == 0L) return InlineAcks()
        val room = LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying, attached) - LoraSizeHint.utf8Length(text)
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
        val riding = ackSync.takeRiding(authorId, AckSync.MAX_BATCH_ACKS - ackIds.size)
        if (originateDeliveryTick(authorId, ackIds + riding)) {
            metrics.onReceiptCoalesced(ackIds.size - 1)
            metrics.onReceiptRidden(riding.size)
            return
        }
        ackSync.giveBackRiding(authorId, riding)
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
        return targets
            .mapNotNull { nodeId ->
                peers
                    .find(nodeId)
                    ?.pubKey
                    ?.let { PublicKeyBundle.decode(it) }
                    ?.let { nodeId to it }
            }.toMap()
    }

    /** Encrypted, content-addressed copy of a just-ingested attachment, plus its base64 key. */
    private data class SealedAttachment(
        val hash: String,
        val key: String,
    )

    /**
     * Encrypts the ingested (plaintext) attachment to a fresh key, stores the ciphertext blob under its
     * ciphertext hash (so the existing content-addressed pull/dedup still works), and drops the now-
     * unreferenced plaintext blob.
     */
    private suspend fun sealAttachment(attachment: AttachmentStore.Ingested): SealedAttachment? {
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
     * Broadcasts a best-effort "now typing" cue for [conversationId] to nearby peers — the presence ping behind
     * the chat typing indicator. Deliberately NOT [originateSigned]: it must never flood, be custodied, or be
     * parked, so it is signed with `relay = false` (single-hop, like a blob request) and sent only over the
     * coordination-plane fast path — targeted [MeshTransport.fastSend] to a DM recipient, [MeshTransport.fastFanout]
     * to every neighbor for the broadcast room / a group. A fresh [FrameId] each time so the receiver's SeenSet
     * never dedups the next cue. Fire-and-forget: if it doesn't fit the ~255 B channel or no one is reachable it
     * simply no-ops. Scope rides the frame the same way a chat does — [RelayEnvelope.recipientId] for a DM, a
     * compact [TypingContent.groupId] for a group (not the heavy [RelayEnvelope.group] roster, which could blow
     * the coordination-plane size cap).
     */
    override suspend fun sendTyping(conversationId: String) {
        val me = identity.nodeId()
        val kind = Conversations.kindFor(conversationId)
        // The bridged public room has no typing cue and must not fall through to one. A `typing` frame
        // carries its scope in `recipientId` or `TypingContent.groupId` and this room is neither, so an
        // unguarded cue from here would arrive on every phone in the pocket as a *Nearby* one. The UI
        // already refuses to raise it; this is the net under that, at the layer that mints the frame.
        if (kind == ConversationKind.MESHTASTIC) return
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
    }

    /** On startup, sweep orphaned blobs/reactions/receipts and re-request attachment blobs we're still missing. */
    private fun resumePendingFetches(session: CoroutineScope) {
        session.launch {
            blobs.deleteOrphans() // reclaim blobs left by attachments staged but never sent (keeps carried ones)
            reactions.deleteOrphans(clock()) // reclaim reactions left by deleted messages
            receipts.deleteOrphans() // ...and per-recipient delivery rows left by a deleted thread or a retention trim
            forwardSync.sweepExpired() // drop carried DMs whose TTL elapsed while we were down
            pendingInbound.sweepExpired() // and any key-wait frames whose TTL lapsed (in-memory, so usually a no-op)
            keyExchange.sweepExpired() // stale unauthenticated key-wants
            blobExchange.sweepExpired() // never-arriving blob fetches
            sweepLocalStorage() // bound the local messages/peers tables against a Sybil flood (Message Requests hardening)
            // Own/received message attachments: always re-pull (uncapped, kept alive by their message row).
            val ownHashes = messages.hashesNeedingFetch()
            ownHashes.forEach { blobExchange.want(it) }
            // Carrier-only custody blobs backfill only while under the byte budget (the same pull-time soft cap
            // onCarriedFrame applies), so a restart re-attempts pulls that a live-session over-budget skip left
            // missing; skip ones already re-requested above as our own/received attachments.
            if (blobs.carrierOnlyBlobBytes() < CARRIER_BLOB_BUDGET_BYTES) {
                val own = ownHashes.toHashSet()
                forwardStore.attachmentHashesNeedingFetch().forEach { if (it !in own) blobExchange.want(it) }
            }
        }
    }

    /** Periodically reclaims expired carried DMs, bounding the forward store between heartbeat sweeps. */
    private fun pruneForwardStorePeriodically(session: CoroutineScope) {
        session.launch {
            while (true) {
                delay(FORWARD_SWEEP_INTERVAL_MS)
                forwardSync.sweepExpired()
                pendingInbound.sweepExpired()
                keyExchange.sweepExpired()
                blobExchange.sweepExpired()
                sweepLocalStorage()
            }
        }
    }

    /**
     * Bounds the local `messages` + `peers` tables so a Sybil DM/profile flood can't exhaust storage. Local-only
     * GC (no wire/convergence effect): the protected set — conversations/peers that are accepted, out-of-band
     * verified, or that the user has authored in, plus any group a known peer has posted in — is exempt from
     * wholesale eviction, matching the notify gate's "not a request" predicate ([Conversations.isAccepted]) so a
     * group that reads as a normal chat keeps its history like one. Runs on the prune loop and at startup,
     * alongside the forward-store sweep.
     */
    private suspend fun sweepLocalStorage() {
        val now = clock()
        val accepted = settings.acceptedConversations.first()
        val verified = peers.verifiedNodeIds().toSet()
        val authored = messages.conversationsIAuthoredIn(identity.nodeId()).toSet()
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
        messages.sweepRetention(now, protectedIds)
        peers.sweepCap(protectedIds)
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
        session.launch {
            while (true) {
                delay(NEIGHBOR_REOFFER_INTERVAL_MS)
                transport.neighbors.value.forEach { peer ->
                    forwardSync.onNeighborAdded(peer) // re-advertise our custody digest → pull anything we lack
                    blobExchange.onNeighborAdded(peer) // re-ask for blobs we still need
                    keyExchange.onNeighborAdded(peer) // re-ask for keys we still need
                    ackSync.onNeighborAdded(peer) // re-send any broadcast/group delivery tick we owe it
                    flushPendingGroupKeysFor(peer.nodeId) // re-send unacked group epoch seeds it never got
                }
            }
        }
    }

    // --- Profile broadcasting ---

    private fun watchNeighbors(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            transport.neighbors.collect { current ->
                val currentIds = current.map { it.nodeId }.toSet()
                val newcomers = current.filter { it.nodeId !in known }
                // No departure cleanup: under the cue-driven transport a data-path link is ephemeral and
                // reconnects on every sync, so we deliberately keep sentAvatarHashes across the flap —
                // clearing it would re-push every avatar on each brief contact. A peer that truly leaves
                // ages out by TTL. (ForwardSync, by contrast, *does* re-offer its whole carried set on each
                // join now: the digest gate makes a fresh link mean the stores differ, so re-pushing is how
                // an offer lost to a torn-down link self-heals — see ForwardSync.onNeighborAdded. A persistent
                // link (Bluetooth) only joins once, so reofferToNeighborsPeriodically re-runs these hooks on a
                // timer for currently-linked neighbors — the anti-entropy a non-flapping link needs.)
                known = currentIds
                newcomers.forEach {
                    pushProfileTo(it)
                    blobExchange.onNeighborAdded(it) // re-ask the new neighbor for blobs we still need
                    forwardSync.onNeighborAdded(it) // re-offer carried DMs addressed to / routable via it
                    keyExchange.onNeighborAdded(it) // re-ask the new neighbor for keys we're still missing
                    ackSync.onNeighborAdded(it) // re-send any broadcast/group delivery tick we owe it, over the link
                    flushPendingGroupKeysFor(it.nodeId) // re-send unacked group epoch seeds (partition merge)
                }
            }
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
    private fun seedOwnProfileCustody(session: CoroutineScope) {
        session.launch {
            // Rotation check BEFORE seeding, so a due prekey mints now and the seeded frame (and any
            // first-contact push) already carries it; also the startup ratchet retention sweep.
            rotatePrekeyIfDue()
            ratchet.sweep(clock())
            groupRatchet.sweep(clock())
            groupRoots.sweep(clock())
            replayUndeliveredGroupCustody()
            // At startup too, not only on the 15-min heartbeat: this is where a device that just enabled
            // the Internet plane (or just finished its mint grace while the app was closed) actually mints.
            mintGroupRootsIfDue()
            // Stamp a publish time before building the frame: a device whose profile was last edited days
            // ago would otherwise seed a frame custody refuses as dead on arrival, and this seeding exists
            // precisely so first contact has a frame to diverge on.
            republishProfileIfStale()
            val env = currentProfileEnvelope()
            forwardSync.onSeen(sign(env), env, ForwardStore.ORIGIN_SELF)
        }
    }

    /**
     * Flood our profile once per peer-epoch on **first coordination-plane contact** ([MeshTransport.reachable]
     * newcomers) — not only on link-up ([watchNeighbors]) — so the key exchange bootstraps over NAN alone
     * (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.5): `reachable` needs no data path, a small profile rides the
     * fast plane immediately, and a larger one is already in custody (seeded above) where its digest
     * divergence pulls a link up. Coalesced to one origination per [PROFILE_REFLOOD_MIN_MS] no matter how
     * many newcomers arrive (receivers dedupe by the stable frame id + SeenSet, and the custody path covers
     * anyone the flood missed). A peer's departure from `reachable` and later return is the epoch boundary —
     * it re-enters as a newcomer and gets one fresh flood. BLE-driven newcomers trigger it too, harmlessly.
     */
    private fun watchReachable(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            var lastFloodAt = 0L
            transport.reachable.collect { current ->
                val ids = current.mapTo(HashSet()) { it.nodeId }
                val newcomers = ids - known
                known = ids
                if (newcomers.isEmpty()) return@collect
                val now = clock()
                if (now - lastFloodAt < PROFILE_REFLOOD_MIN_MS) return@collect
                lastFloodAt = now
                originateSigned(currentProfileEnvelope())
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

    private fun watchProfileChanges(session: CoroutineScope) {
        session.launch {
            // The board's number and key are written in one settings edit and move together, so they fold
            // into one arm here — which also keeps the combine inside Kotlin's five-flow overload.
            val board =
                combine(settings.loraBoardNode, settings.loraBoardKey) { node, key -> node?.let { LoraBoardClaim(it, key) } }
            combine(
                settings.displayName,
                settings.status,
                settings.avatarUpdatedAt,
                settings.openToChat,
                board,
            ) { name, status, avatarAt, openToChat, claim ->
                OwnPresentation(name, status, avatarAt, openToChat, claim)
            }.drop(1) // skip the initial stored value; only react to real edits
                // A Save writes name+status in one transaction; without this the duplicate flow
                // re-emits would broadcast more than once. Also drops no-op saves.
                .distinctUntilChanged()
                .collect {
                    // Monotonic bump, persisted: a version that's stable across restarts is what keeps a
                    // custodied profile from minting a new frame every launch (see SettingsStore.profileVersion).
                    settings.setProfileVersion(maxOf(clock(), settings.profileVersion.first() + 1))
                    broadcastProfile()
                    broadcastSealedProfile()
                }
        }
    }

    private fun watchIncomingFiles(session: CoroutineScope) {
        session.launch {
            transport.incomingFiles.collect { file ->
                when (file.kind) {
                    FileKind.AVATAR -> {
                        pipeline.onAvatarReceived(file.fromNodeId, file.key, file.mime, file.path)
                    }

                    FileKind.ATTACHMENT -> {
                        blobExchange.onReceived(file.key, file.mime, file.path, file.fromNodeId)
                    }
                }
            }
        }
    }

    private fun watchIncomingDigests(session: CoroutineScope) {
        session.launch {
            // A neighbor advertised the custody ids it holds → push it just the frames it lacks (the id-diff).
            transport.incomingDigests.collect { forwardSync.onDigest(it.fromNodeId, it.ids) }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        val env = currentProfileEnvelope()
        val wire = sign(env)
        // Custody our own profile (ORIGIN_SELF), exactly as a peer that receives it carries it (ORIGIN_RELAY).
        // Without this our store is permanently missing our own profile while every peer holds it, so the
        // store-and-forward digests never converge and the mesh churns NDPs forever. Idempotent on the (now
        // persisted, restart-stable) version, so repeated connects don't re-store it.
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
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
     * per-epoch reflood and [pushProfileTo]'s first-contact push both build the envelope directly, because
     * reusing the id is precisely what lets a receiver dedupe a copy it already holds. Only a content
     * change earns a new stamp.
     */
    private suspend fun broadcastProfile() {
        // Ahead of building the envelope: the stamp IS the frame id (and the custody `sentAt`) it reads.
        settings.setProfilePublishedAt(clock())
        originateSigned(currentProfileEnvelope())
        transport.neighbors.value.forEach { sendAvatarIfNeeded(it) }
    }

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
        val version = settings.profileVersion.first()
        if (version <= 0L) return
        val me = identity.nodeId()
        val avatarHash = settings.ownAvatarHash.first()
        val payload = currentProfilePayload(version, avatarHash)
        ratchet.exportedRoots().forEach { session ->
            if (sentProfileVersions[session.peerId] == version) return@forEach
            if (sendProfileDm(session.peerId, payload, avatarHash, me)) sentProfileVersions[session.peerId] = version
        }
    }

    /** The sealed presentation payload (`CTL_PROFILE`) for the current profile at [version]. */
    private suspend fun currentProfilePayload(
        version: Long,
        avatarHash: String?,
    ): MessageContent =
        MessageContent(
            body = "",
            ctl = MessageContent.CTL_PROFILE,
            pr =
                ProfilePayload(
                    name = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME),
                    status = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS),
                    avatarHash = avatarHash,
                    version = version,
                    openToChat = settings.openToChat.first(),
                    loraNode = settings.loraBoardNode.first(),
                    loraKey = settings.loraBoardKey.first(),
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
        val payload = currentProfilePayload(settings.profileVersion.first(), avatarHash)
        return sendProfileDm(peerId, payload, avatarHash, identity.nodeId())
    }

    /**
     * Seals one `CTL_PROFILE` to [peerId]. The avatar hash is repeated in the **cleartext**
     * [ChatContent.attachmentHash] — the DB v19 precedent (`docs/WIRE_COMPAT.md`) reapplied: it is what
     * lets a blind carrier custody the avatar bytes, and what the Internet plane's attachment pass reads
     * to fetch them (`docs/SPOOL_PROTOCOL.md` §9.5). The authoritative copy stays inside the seal.
     *
     * The hash and *only* the hash (ADR 035). `AVATAR_MIME` is a constant and so leaked nothing about the
     * avatar itself — but if this stayed the one sealed frame still carrying a cleartext mime, then
     * mime-presence would itself become a fresh distinguisher, sorting sealed frames into "profile update"
     * and "user message" for any carrier. Nulling it here is what stops the fix creating a new signal.
     */
    private suspend fun sendProfileDm(
        peerId: String,
        payload: MessageContent,
        avatarHash: String?,
        me: String,
    ): Boolean {
        val peer = peers.find(peerId)
        val bundle = peer?.pubKey?.let { PublicKeyBundle.decode(it) } ?: return false
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
        val content =
            ProfileContent(
                // Normalize/cap defensively: covers legacy values stored before the field gained a cap and
                // the rare process-death-before-the-blur-commit case, so peers never receive an oversized name.
                name = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME),
                status = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS),
                avatarHash = settings.ownAvatarHash.first(),
                pubKey = identity.publicKeyBundle(),
                deviceTag = identity.deviceTag(),
                protoVersion = Protocol.VERSION,
                capabilities = Protocol.LOCAL_CAPABILITIES,
                prekey = PrekeyInfo(id = spk.id, pub = spk.pub, sig = spk.sig),
                version = version,
                openToChat = settings.openToChat.first(),
                // The bound board's node number, so a contact's phone can line a heard radio post up with us —
                // and, on a board that signs, the key that lets it verify the post is ours.
                loraNode = settings.loraBoardNode.first(),
                loraKey = settings.loraBoardKey.first(),
            )
        return RelayEnvelope(
            type = FrameType.PROFILE,
            id = "profile-$me-$publishedAt",
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
    private suspend fun republishProfileIfStale() {
        val now = clock()
        if (now - settings.profilePublishedAt.first() < PROFILE_REPUBLISH_MS) return
        settings.setProfilePublishedAt(now)
        // Seed the refreshed frame into custody rather than flooding it: it carries no new information, so
        // the custody digest divergence is enough to move it to neighbors on the next contact. The previous
        // stamp's frame lingers until its own TTL — same version, so a receiver re-applies it idempotently.
        val env = currentProfileEnvelope()
        forwardSync.onSeen(sign(env), env, ForwardStore.ORIGIN_SELF)
        scopeSync?.onCustodyChanged()
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
        val wire = sign(env)
        router.originate(wire, env.id)
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        if (shouldFastFanout(env)) transport.fastFanout(wire)
        if (shouldLongRangeFanout(env)) transport.longRangeFanout(wire, hint)
        // Our own sends are the latency-sensitive case, so nudge the Internet plane instead of waiting for
        // its tick. Relayed frames ride the next heal round — they are already in flight on the radios.
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
    override suspend fun signedProfile(): WireEnvelope = sign(currentProfileEnvelope())

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
     * [FarPeerFrameSource]: the carried DM-form frames worth re-offering to [nodeId] when a long-range plane
     * first hears it (ADR 039) — the newest [FAR_REOFFER_LIMIT] live custody frames addressed to it, minus our
     * own frames the peer has already acked (an own frame with no unacked message row is either delivered or a
     * sealed ctl, neither worth an airslot; a relayed frame's fate is unknowable, so it is offered). Verbatim
     * custody bytes re-wrapped like a custody re-serve: the receiver's SeenSet / exists-gate make a duplicate
     * harmless, and a re-delivered DM re-draws its receipt — which is how a tick lost over LoRa heals.
     */
    override suspend fun framesFor(nodeId: String): List<WireEnvelope> {
        val now = clock()
        val carried = forwardStore.liveFramesTo(nodeId, now, FAR_REOFFER_LIMIT)
        if (carried.isEmpty()) return emptyList()
        val me = identity.nodeId()
        val ownUnacked = messages.unackedDmsTo(nodeId, me, since = now - RESEAL_WINDOW_MS).mapTo(HashSet()) { it.id }
        return carried
            .filter { it.envelope.senderId != me || it.envelope.id in ownUnacked }
            .map { WireEnvelope(sig = it.sig, signed = it.signed) }
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
     * Whether [text] is non-blank and the on-device moderator flags it as abusive. Always runs — not
     * gated by the content-filtering setting, which only governs receive-side hiding. Drives both
     * block-on-send (in [sendChat], a send-side "good-citizen"/Nearby check) and the stored flag on
     * inbound messages (in [deliverChat]); a flagged inbound message is still stored and merely collapsed
     * in the UI (collapse itself gated at display time by the setting, see [ChatViewModel]), so a false
     * positive never silently drops content. [isRoom] selects the moderation scope: the Nearby
     * broadcast room gets profanity + toxicity, while DMs and groups get toxicity only (see
     * [ScopedTextModerator]). [direction] (`"outgoing"`/`"incoming"`) only labels the debug log; the
     * verdict score/category/decision is logged under [TEXT_MODERATION_TAG], mirroring the image
     * screen's `ImageModeration` logging — the body itself is never logged (only its length).
     */
    private suspend fun isTextFlagged(
        text: String,
        direction: String,
        isRoom: Boolean,
    ): Boolean {
        if (text.isBlank()) return false
        val verdict = textModeration.classify(text, isRoom)
        Log.d(
            TEXT_MODERATION_TAG,
            "$direction text score=${verdict.score} category=${verdict.category} " +
                "label=${verdict.label} flagged=${verdict.flagged} len=${text.length}",
        )
        return verdict.flagged
    }

    /**
     * Retransmits DMs to [recipientId] that were composed before their key was known (saved pendingKey
     * by [sendChat]). Now that [handleProfile] has pinned the key, each is re-sealed and flooded (and
     * captured for carry via [originate]); a still-unresolvable or now-blocked recipient is left pending.
     */
    private suspend fun flushPendingFor(recipientId: String) {
        if (recipientId in settings.blockedNodeIds.first()) return
        val me = identity.nodeId()
        messages.pendingForRecipient(recipientId).forEach { row ->
            if (resealAndFlood(row, recipientId, me)) messages.clearPending(row.id)
        }
    }

    /**
     * Re-seals our recent unacked DMs to [recipientId] under the CURRENT session state — the recovery
     * half of an inbound ratchet **session reset**: a wiped peer can no longer open frames sealed to
     * its dead session, but custody still carries them (≤ the 24 h TTL), so a fresh seal under the
     * replacement session puts recoverable copies back on the mesh. Receiver-side idempotency (the
     * exists-gate + upsert) makes duplicates harmless.
     */
    private suspend fun resealRecentDmsTo(recipientId: String) {
        if (recipientId in settings.blockedNodeIds.first()) return
        val me = identity.nodeId()
        messages
            .unackedDmsTo(recipientId, me, since = clock() - RESEAL_WINDOW_MS)
            .forEach { row -> resealAndFlood(row, recipientId, me) }
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
    ): Boolean {
        val content =
            MessageContent(
                body = row.body,
                mentions = MentionStore.decode(row.mentions),
                attachmentHash = row.attachmentHash,
                attachmentMime = row.attachmentMime,
                attachmentKey = row.attachmentKey,
                replyTo = row.replyRef(),
            )
        val envelope =
            sealEnvelopeFor(row.id, me, row.sentAt, recipientId, group = null, content = content)
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
        )
        return true
    }

    /** Periodically logs a transmission snapshot so flood-suppression and byte savings are visible. */
    private fun logMetricsPeriodically(session: CoroutineScope) {
        session.launch {
            while (true) {
                delay(METRICS_LOG_INTERVAL_MS)
                val s = metrics.snapshot()
                Log.d(
                    TAG,
                    "metrics: originated=${s.framesOriginated} delivered=${s.framesDelivered} " +
                        "relayed=${s.framesRelayed} suppressed=${s.framesSuppressed} " +
                        "deduped=${s.framesDeduped} bytesSent=${s.bytesSent} " +
                        "dropped=${s.framesDropped} drops=${s.dropsByReason} " +
                        "keyReq=${s.keyRequestsSent} keyServed=${s.keysServed} keyRecovered=${s.keysRecovered} " +
                        "framesHeld=${s.framesHeld} framesReplayed=${s.framesReplayed} " +
                        "receiptsResent=${s.receiptsResent} " +
                        "receiptsSealed=${s.receiptsSealed}/${s.receiptsSealedFallback} " +
                        "dmSealedV3=${s.dmSealedV3} ticksUnsigned=${s.ticksUnsigned} " +
                        "reactionsSealed=${s.reactionsSealed}/${s.reactionsSealedFallback} " +
                        "filesNan=${s.filesSentNan} filesBt=${s.filesSentBt} bulkTimeouts=${s.nanBulkGraceTimeouts}",
                )
            }
        }
    }

    private companion object {
        /** Debug-bridge root fingerprint length — enough to compare eras across devices, nothing more. */
        const val ROOT_HASH_HEX_CHARS = 8

        const val TAG = "MeshManager"
        const val TEXT_MODERATION_TAG = "TextModeration"
        const val METRICS_LOG_INTERVAL_MS = 60_000L
        const val FORWARD_SWEEP_INTERVAL_MS = 10 * 60_000L

        // Re-run the neighbor re-offer hooks (custody digest + blob/key re-asks) for currently-linked neighbors
        // this often, so a persistent link (Bluetooth) gets the anti-entropy that Wi-Fi Aware's flapping
        // ephemeral links get for free. Short enough to converge a missed message within ~a minute, long enough
        // to stay cheap on battery/bandwidth.
        const val NEIGHBOR_REOFFER_INTERVAL_MS = 60_000L

        // Min spacing between first-contact profile floods (watchReachable): a burst of newcomers costs one
        // origination; custody + the per-link pushProfileTo cover anyone the coalesced flood skipped.
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

        /** How many carried DM-form frames a long-range plane re-offers per first hearing of a peer (≤ 3 packets each). */
        const val FAR_REOFFER_LIMIT = 4

        /** Per-(group, member) floor on proactive/responder seed re-sends (docs/GROUP_FORWARD_SECRECY.md #10). */
        const val SEED_RESEND_FLOOR_MS = 15 * 60_000L

        /** Payload for a frame whose content lives entirely in the routing envelope (e.g. a group update). */
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}

/** The own-profile fields whose edit republishes the profile (`watchProfileChanges`). */
private data class OwnPresentation(
    val name: String,
    val status: String,
    val avatarUpdatedAt: Long,
    val openToChat: Boolean,
    val board: LoraBoardClaim?,
)

/** The bound board as the profile advertises it: its node number and, while it signs, its key (base64). */
private data class LoraBoardClaim(
    val node: Long,
    val key: String?,
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
