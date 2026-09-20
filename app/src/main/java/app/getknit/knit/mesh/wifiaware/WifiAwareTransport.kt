package app.getknit.knit.mesh.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.NanAttachJournal
import app.getknit.knit.data.settings.NanInitiatorJournal
import app.getknit.knit.data.settings.NanInitiatorLatch
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.DigestTracker
import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.canReclaimForegroundService
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FastFramePick
import app.getknit.knit.mesh.link.FragReassembler
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.NetSocketLink
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.wifiAwareSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [MeshTransport] over **Wi-Fi Aware (NAN)** — a direct `android.net.wifi.aware.*` implementation confined
 * to this package so the rest of the app stays transport-independent (talks only to [MeshTransport]).
 *
 * ## Why cue-driven ephemeral sync
 * Real phones expose **one** NAN data interface (`maxNdiInterfaces == 1`), so a node can hold a data path
 * (NDP) to exactly **one** peer at a time — a second data path to a different peer is refused by the
 * framework ("no interfaces available"). We therefore split into two planes:
 *
 * - **Coordination plane** — Wi-Fi Aware *messages* ([DiscoverySession.sendMessage], ~255 B, best-effort)
 *   which ride discovery follow-up frames and need **no data path**. They reach every discovered neighbor
 *   at once and keep working even while the one NDP is busy. Each node advertises a tiny **cue**
 *   (`nodeId|version`, see [StoreDigest]) so a neighbor can tell when its carried set differs from ours.
 * - **Data plane** — a single ephemeral NDP TCP socket (link-local IPv6, framed by [FramedLink]) brought
 *   up **on demand** only to sync, then torn down, freeing the NDI for the next pair.
 *
 * ## Sync lifecycle
 * A node listens (publish + its accept-any responder up, NDI free) and exchanges cues. When [DigestTracker]
 * says a peer's digest differs from ours (and isn't already reconciled), the **larger** nodeId (the tie-break)
 * brings up one NDP to it; on link-up the upstream `onNeighborAdded` backfill drains the carried
 * store-and-forward / key / blob state both ways. When the link goes quiescent (no data for
 * [QUIESCENCE_MS], no file mid-transfer) the initiator tears it down and records the sync — so an idle mesh
 * does *zero* data-path work, and a new message triggers a targeted sync with only the peers that need it.
 * Everything is delay-tolerant (custody carries what one flood doesn't reach), so a rotating series of
 * pairwise syncs propagates epidemically.
 *
 * The accept-any responder is anchored to the *publish* session (accepts a data path from **any**
 * initiator, learning who via the first HELLO record — see [LinkHandshake]); only *subscribe* is ever
 * re-armed (to re-fire one-shot discovery while idle), never while a live NDP is up.
 *
 * Permissions are gated by onboarding; the mesh starts regardless and degrades if a permission/hardware is
 * missing, so the Aware calls are marked [SuppressLint] for "MissingPermission".
 */
@SuppressLint("MissingPermission")
// A transport is inherently many small methods (lifecycle, discovery, sockets, files, cues) and one big class.
@Suppress("TooManyFunctions", "LargeClass")
// The NDP data path uses the accept-any responder (WifiAwareNetworkSpecifier.Builder(publishSession), API 31)
// and setPmk (API 30), so the whole plane requires API 31 — below that isSupported() returns false and the
// composite meshes over Bluetooth LE only. Instant Communication Mode (API 33) is further guarded per call site.
@RequiresApi(Build.VERSION_CODES.S)
class WifiAwareTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val powerState: PowerStateSource,
    private val storeDigest: StoreDigest,
    private val attachJournal: NanAttachJournal,
    private val initiatorJournal: NanInitiatorJournal,
) : MeshTransport {
    private val appContext = context.applicationContext
    private val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // What a give-up is recorded under ([NanAttachJournal], and the initiator hold of [NanInitiatorJournal]):
    // both halves are resets. A new app version may change the attach or data-path sequence, and a ROM update
    // is exactly the event that can fix a vendor HAL that publishes no STA+NAN interface combination
    // (getknit/Knit#9 is a LineageOS device) or a firmware whose NDP negotiation tears the STA down (#78).
    private val giveUpStamp = "${BuildConfig.VERSION_CODE}:${Build.FINGERPRINT}"

    /** False on hardware without Wi-Fi Aware — the transport stays [TransportHealth.Unavailable] and the UI gates. */
    private val hasHardware =
        awareManager != null && appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE)

    // The ≤1 live data-path link — the routing target for send()/sendFile(), flapping as ephemeral syncs
    // come and go. [reachable] is the smoothed UI signal instead.
    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    // "Who's nearby": peers seen recently over the coordination plane (no data path needed), so it stays
    // steady while _neighbors flaps through ephemeral syncs.
    private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
    override val reachable = _reachable.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Healthy)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 32)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 32)
    override val incomingDigests = _incomingDigests.asSharedFlow()

    // Wi-Fi Aware has a coordination-plane message channel (cues/fastFanout), so the composite routes the
    // fast-path (fastFanout/fastSend) here; a Bluetooth sibling with only persistent links leaves this false.
    override val hasFastPlane = true

    // An NDP moves megabytes in under a second vs. BLE L2CAP's seconds-to-minutes, so the composite prefers
    // this plane for large attachment blobs (and only those — frames/digests/avatars stay BLE-first).
    override val highThroughput = true

    override val kind = TransportKind.WifiAware

    // Aware + connectivity callbacks are delivered on this dedicated thread's handler.
    private val callbackThread = HandlerThread("wifi-aware-cb").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private lateinit var localNodeId: String

    @Volatile private var instantSupported = false

    // The radio's actual coordination-plane message cap (maxServiceSpecificInfoLen covers sendMessage too);
    // COORD_MSG_MAX stays as the conservative fallback. Bigger caps let more frames ride the zero-NDP path.
    private val coordMsgMax =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { awareManager?.characteristics?.maxServiceSpecificInfoLength }
                .getOrNull()
                ?.takeIf { it > 0 } ?: COORD_MSG_MAX
        } else {
            // maxServiceSpecificInfoLength is API 33; pre-33 radios have no way to report it, so use the fallback.
            COORD_MSG_MAX
        }

    @Volatile private var session: WifiAwareSession? = null

    @Volatile private var publishSession: PublishDiscoverySession? = null

    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null

    // attach() and subscribe() complete asynchronously (via callbacks), so a `session/subscribeSession
    // != null` check can't stop a second caller from issuing another request while the first is still in
    // flight. These flags serialize them: without the attach guard, start()/discoveryLoop()/the
    // availability receiver can each fire a redundant mgr.attach(), each spinning up its own
    // publish+subscribe pair, orphaning the prior session (→ "destroy: called post GC") and exhausting the
    // chipset's finite discovery-session budget (→ every later subscribe() returns onSessionConfigFailed).
    private val attaching = AtomicBoolean(false)
    private val subscribing = AtomicBoolean(false)

    // Generation token stamped on every fresh attach. The whole attach→publish→subscribe→responder lifecycle
    // runs on the single [handler] thread, and each async discovery callback captures its gen and ignores
    // itself (closing any session/responder it was about to create) once a newer attach has bumped [attachGen].
    // This is what keeps the responder single-owner: a stale onPublishStarted can never arm a second responder
    // requestNetwork behind the live one's back — the leaked role=1 state=104 ghost that pins the single NDI
    // until process death. [reattaching] makes reattach() single-flight so two triggers (subscribe-wedge +
    // after-serve) can't overlap into a double attach.
    @Volatile private var attachGen = 0
    private val reattaching = AtomicBoolean(false)

    // elapsedRealtime the last attach() called mgr.attach(). Two jobs: it lets a later attach() self-heal a
    // stuck [attaching] guard if the framework silently drops an attach (mgr.attach with no
    // onAttached/onAttachFailed), and it is the floor on how often we may call mgr.attach at all
    // ([NanAttachPolicy.tooSoon]) — never cleared by anything, which is what makes the floor hold.
    @Volatile private var attachStartedAt = 0L

    // Consecutive failed attaches, the earliest elapsedRealtime the next one may run, and whether we have
    // stopped attaching outright — each failed attach strands two binder objects in system_server, so this is a
    // leak bound, not a politeness one. All three are cleared by a successful attach, by Aware coming back
    // (the one genuinely new fact about the radio), and by [stop]; deliberately NOT by [heal], which says the
    // app was opened, not that the radio changed. The streak is only ever incremented on the [handler] thread,
    // so the read-modify-write needs no atomic; @Volatile because the discovery loop reads them
    // ([rediscoverDelayMs]) and [stop] clears them from the caller's thread.
    @Volatile private var attachFailStreak = 0

    @Volatile private var attachRetryAfter = 0L

    @Volatile private var attachAbandoned = false

    // The radio is lent to another same-app role (a Wi-Fi Direct group, `transfer/`): the session is closed and
    // attach() refuses until resume(). Never cleared by availability — a same-app hand-over does not flap it.
    @Volatile private var paused = false

    // The OS refused a publish/subscribe for the foreground-only location app-op (API 29-32, Knit off screen —
    // [NanSessionFault.OffScreenLocation]). The session and whatever was up stay up; publish/subscribe stop
    // calling into the framework until the next [heal], which is how the app's own resume reaches us (ADR
    // 2026-09.535d). Handler thread writes; the discovery loop and [heal] read.
    @Volatile private var offScreenBlocked = false

    // Failed attaches over the whole life of the process. The streak above is refundable on purpose; this is
    // not, except by an attach that actually succeeds ([noteAttachSucceeded]). It exists because 2.3.1 shipped
    // the streak with a refund path that a refusing chipset could drive in a loop — see [availabilityReceiver]
    // and [NanAttachPolicy]. A bound is only worth what its refund path is worth, so this one has none.
    @Volatile private var attachFailTotal = 0

    // Last availability the receiver was told about, so it can tell a genuine false→true transition from a
    // broadcast that merely repeats `true`. Null until the first one arrives; see [availabilityReceiver].
    @Volatile private var lastAvailable: Boolean? = null

    // The one accept-any responder: its ServerSocket, network callback, and the accept loop.
    @Volatile private var responderSocket: ServerSocket? = null

    @Volatile private var responderCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var acceptJob: Job? = null

    // A re-file of the responder request waiting out its [NanResponderPolicy] delay, so [stopResponder] can
    // drop it: a teardown or a fresh publish session supersedes whatever it was about to file.
    @Volatile private var responderRefile: Runnable? = null

    // Consecutive times the framework declared the responder request unfulfillable with nothing of ours holding
    // the interface, and the session cycles that streak has spent (work item #77, [NanResponderPolicy]). The
    // streak is per publish session (a fresh session starts at 0); the cycles are per *episode* and refunded
    // only by a request that is fulfilled — the responder's onAvailable — never by the fresh session or the
    // availability edge the escalation's own cycle produces. Handler-thread writes; [logState] reads.
    @Volatile private var responderRefusals = 0

    @Volatile private var responderCycles = 0

    // The initiator failsafe (work item #78, ADR 2026-09.m8kc): our own Wi-Fi dropping within two minutes of an
    // initiate of ours, three times with no initiator link between, holds the initiator role. Windows on the
    // monotonic clock like the rest of this file; the daily probe on the wall clock because it is journaled.
    private val initiatorPolicy = NanInitiatorPolicy(now = SystemClock::elapsedRealtime, wallNow = System::currentTimeMillis)

    private val _initiatorHeld = MutableStateFlow(false)
    override val initiatorHeld = _initiatorHeld.asStateFlow()

    // Passive watch on the phone's own Wi-Fi (STA) network — `registerNetworkCallback`, never a request, so it
    // holds nothing and asks for nothing. A plain TRANSPORT_WIFI request: the NDP is TRANSPORT_WIFI_AWARE and
    // never matches, and no capability is named because a capability change would masquerade as lost/available.
    @Volatile private var wifiWatchRegistered = false
    private val wifiWatch =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onStaAvailable(network)

            override fun onLost(network: Network) = onStaLost(network)
        }

    // The single live data-path link, keyed by peer nodeId (at most one entry — one NDI). [NanLink] wraps the
    // shared [FramedLink] (socket I/O) with the NAN-specific per-peer network callback + quiescence supervisor.
    private val peers = ConcurrentHashMap<String, NanLink>()

    // Forwards a live [FramedLink]'s decoded records into our flows and its teardown into [teardownPeer].
    // onLinkDown can fire twice (read + write loop end) but teardownPeer is idempotent.
    private val linkCallbacks =
        object : LinkCallbacks {
            override fun onInbound(frame: InboundFrame) {
                _inbound.tryEmit(frame)
            }

            override fun onDigest(digest: ReceivedDigest) {
                _incomingDigests.tryEmit(digest)
            }

            override fun onFile(file: ReceivedFile) {
                _incomingFiles.tryEmit(file)
            }

            override fun onLinkDown(nodeId: String) {
                // A read/write-loop end means packets flowed moments ago (EOF needs the peer's FIN, an error
                // needs a reset — a dead NDP just goes silent and ends via quiescence instead), so the NDP was
                // alive recently: the ghost-proof recycle window (see teardownPeer/recycleResponder).
                teardownPeer(nodeId, eofDriven = true)
            }
        }

    // Connection bookkeeping guarded by [lock]: peers discovered via subscribe (with a subscribe-session
    // PeerHandle for NDP initiation), the single in-flight handshake, an in-flight accept, and per-peer
    // failure backoff. The single-NDI slot is FREE iff peers/inFlight empty and !accepting.
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val discovered = HashMap<String, DiscoveredPeer>()
    private val retryAfter = HashMap<String, Long>()

    // Consecutive *fast* (stale-signature) handshake failures per peer — the fix-#3 churn gate ([NanConnectPolicy]).
    // The 1st fast-fail is treated as a genuinely stale handle (drop + re-discover — a real restart links on the
    // fresh handle first-try, field-drilled 2026-07-04); a 2nd+ means the fresh handle failed too (peer-side
    // wedge/contention), so we keep the handle (stops it driving the re-arm) and back off geometrically. Reset on
    // link-up and on the peer going absent ([pruneAbsentPeers]).
    private val failStreak = HashMap<String, Int>()

    // elapsedRealtime of the last initiate attempt per peer, so driveSync picks the least-recently-attempted
    // eligible target instead of HashMap iteration order (which starves a peer under contention).
    private val lastInitiateAttemptAt = HashMap<String, Long>()

    // Count of accepted sockets still reading their identity (HELLO) — reserved against [serveCap] but not
    // yet in [peers]. A count (not a flag) since P1: concurrent inbound serves are admitted up to the cap.
    private var accepting = 0

    // Concurrent-inbound-serve cap for this device, from the firmware NDP budget (NanServePolicy.capFor over
    // Characteristics.getNumberOfSupportedDataPaths(), read in start()). 1 = legacy single-slot semantics.
    @Volatile private var serveCap = 1

    // True while the standing accept-any responder request has served at least one NDP — from that first
    // serve on, the framework keeps the request's NDI assigned even at 0 live NDPs, blocking this node's own
    // initiator role until the request is recycled (docs/NAN_CONCURRENCY_REAUDIT.md §2). Diagnostic in P1
    // (logState); P2's recycle policy makes it load-bearing. Reset when a fresh (never-served) request files.
    @Volatile private var responderPinsNdi = false

    // Anti-entropy state for the cue plane: each peer's advertised digest version + our last-synced version,
    // plus the no-progress throttle (P3) — on the transport's monotonic clock.
    private val digestTracker = DigestTracker(SystemClock::elapsedRealtime)

    // Peers a bulk (attachment) transfer is pending with (expectBulkTransfer): counts as sync-wanted at the
    // NDP admission sites only — see the syncWanted/digestSyncWanted split — so a large blob can raise the
    // one data path through digest parity + BLE suppression, without ever feeding the wedge/rediscovery
    // recovery machinery. TTL'd + capped + fail-cooled (see BulkWantTracker); cleared on link-up and stop().
    private val bulkWanted = BulkWantTracker(SystemClock::elapsedRealtime)

    // Peers currently served by a higher-preference plane (Bluetooth), pushed by CompositeMeshTransport: we
    // don't bring up an NDP sync to them (BLE carries their data) until they drop off it. Volatile — written
    // from the composite's collector, read on the discovery/handler threads.
    @Volatile
    private var suppressed: Set<String> = emptySet()

    // Peers another plane (Bluetooth) can currently see, pushed by CompositeMeshTransport.onForeignReachable.
    // Used only to corroborate the wedge watchdog ([checkWedge]): a self-kill is only justified for an owed peer
    // that is genuinely nearby (another plane sights it) yet our NAN data path can't serve it — the leak wedge a
    // restart cures — not for one that has simply walked out of NDP range while still trickling us cues. [hasForeignPlane]
    // records whether a corroborating plane exists at all (the composite only wires this up with >1 child), so a
    // NAN-only device falls back to the un-corroborated behaviour. Volatile — written from the composite's collector.
    @Volatile
    private var foreignReachable: Set<String> = emptySet()

    @Volatile
    private var hasForeignPlane = false

    // nodeId -> where to send a cue (a PeerHandle valid on a specific discovery session). Populated from
    // discovery (subscribe handle) and from inbound cues (the session the message arrived on — which is how
    // a pure responder, whose own subscribe is down, still learns a handle to cue larger peers back).
    private val cueTarget = ConcurrentHashMap<String, CueTarget>()
    private val msgSeq = AtomicInteger()

    // Compact fast-path state: one fragId per fragmented frame (shared by every fanout target), and the
    // bounded reassembly store for inbound fragments — touched only on the Aware callback thread.
    private val fragSeq = AtomicInteger()
    private val reassembler =
        FragReassembler<HandleKey>(
            now = SystemClock::elapsedRealtime,
            onDrop = { drop ->
                metrics.onFastDropped(
                    when (drop) {
                        FragReassembler.Drop.TIMEOUT -> FastPathDrop.FRAG_TIMEOUT
                        FragReassembler.Drop.OVERFLOW -> FastPathDrop.FRAG_OVERFLOW
                    },
                )
            },
        )

    // Smoothed reachability: last time each peer was seen over the coordination plane, and the best Peer we
    // know for it, so [_reachable] can linger a peer briefly after its ephemeral link drops.
    private val lastSeenAt = ConcurrentHashMap<String, Long>()
    private val reachablePeers = ConcurrentHashMap<String, Peer>()

    // (session, handle) → the neighbor a cue/advert named on it, so a fast frame is credited to the hop that
    // delivered it and never to the author its envelope names (ADR 061). Learned alongside [cueTarget].
    private val hopTable = NanHopTable<HandleKey>()

    // Wakes the discovery loop immediately (cue made a peer sync-wanted, link up/down + settle, heal(),
    // screen-on) instead of waiting out its idle.
    private val healSignal = Channel<Unit>(Channel.CONFLATED)

    // elapsedRealtime the last data-path link ended; the SETTLE gap after it lets the framework release the
    // single NDI before the next requestNetwork (else "no interfaces available").
    @Volatile private var lastLinkEndedAt = 0L

    // elapsedRealtime of the last recovery re-attach. A re-attach-after-serve stamps it so the wedged-subscribe
    // recovery doesn't also fire in the same window (they'd cascade); the subscribe recovery honours it as a
    // cooldown.
    @Volatile private var lastReattachAt = 0L

    // elapsedRealtime of the last subscribe re-arm (to re-discover a peer whose handle staled), rate-limited
    // by REARM_COOLDOWN_MS so we don't churn subscribe (its wedge trigger) hunting a peer that's really gone.
    @Volatile
    private var lastRearmAt = 0L

    // How long this node has had nobody to cue ([NanLonelyPolicy.lonelySince]): 0 while a cue target exists,
    // else the moment the loop first found none. Reset by a fresh session ([onAttached]) and by a peer another
    // plane sights ([onForeignReachable]), so every "start hunting again" hunts at the aggressive cadence.
    @Volatile
    private var lonelySince = 0L

    // [heal] asked for one re-arm at the aggressive cooldown — the 15-min alarm, a significant-motion trigger,
    // an app resume — consumed by the next loop pass: a phone being walked around re-arms once per trigger,
    // never once per relaxed window, and the loop's own pokes (a digest move, a link end) carry no such token.
    @Volatile
    private var healRearmOwed = false

    @Volatile
    private var lonelyRelaxedLogged = false

    // Non-zero (elapsedRealtime) while a deliberate session cycle sits in its post-teardown settle: the
    // framework's last-client disable runs onAwareDownCleanupDataPaths (the cache wipe that clears a
    // pinned/ghosted request) ~50 ms after session.close() — measured on-device — but the availability
    // BROADCASTS lag by many seconds on a screen-off device, so the settle is a fixed short wait, not a
    // broadcast handshake. The discovery loop re-attaches once it elapses; the receiver must not attach early.
    @Volatile private var sessionCycleSettleStartedAt = 0L

    // elapsedRealtime of the last E5 ICM keepalive (updatePublish) — its own clock, deliberately NOT
    // lastRearmAt: a keepalive is not a subscribe re-arm and must not delay genuine re-discovery.
    @Volatile private var lastIcmKeepaliveAt = 0L

    // SSI republish coalescing (handler-thread only): last updatePublish time and whether a trailing
    // republish is already scheduled, so a burst of digest moves becomes at most one update per
    // SSI_UPDATE_MIN_MS (each update re-fires every subscriber's onServiceDiscovered — P0-verified 5/5).
    private var lastSsiRepublishAt = 0L
    private var ssiRepublishPending = false

    // Latched when a live publish session's updatePublish fails ("publish update failed"): the ICM relight
    // falls back to the legacy subscribe re-arm until the next (re)attach resets it.
    @Volatile private var icmKeepaliveBroken = false

    // Watchdog state for the leaked-request wedge (see [checkWedge]). [lastLinkOrAcceptAt] = elapsedRealtime of
    // the last successful data-path link/accept (either role) — the "progress" signal. [syncOwedSince] = start
    // of the current episode where a sync is owed AND no link has formed since (0 = no episode). The wedge is
    // *a sync owed continuously for WEDGE_RESTART_MS with no link* — NOT merely "no link in a while" (an idle,
    // converged mesh does zero data-path work for long stretches, so time-since-last-link is meaningless until
    // something is actually owed). [lastRestartAt] rate-limits the self-restart. [responderRefreshes] is the
    // Tier-1 budget spent in this episode — capped at MAX_RESPONDER_REFRESHES, because a session cycle that
    // has not produced a link is not curing anything and each repeat re-breaks the very sessions the
    // handshake needs (see [NanWatchdogPolicy]); it resets with the episode.
    @Volatile private var lastLinkOrAcceptAt = 0L

    @Volatile private var syncOwedSince = 0L

    @Volatile private var lastRestartAt = 0L

    @Volatile private var responderRefreshes = 0

    private var powerJob: Job? = null
    private var loopJob: Job? = null
    private var cueJob: Job? = null
    private var cueHeartbeatJob: Job? = null
    private var diagJob: Job? = null
    private var watchdogJob: Job? = null
    private var availabilityRegistered = false

    private data class DiscoveredPeer(
        val advert: Protocol.PeerWire,
        val peerHandle: PeerHandle,
    )

    /** A cue destination: a [PeerHandle] and the discovery session it is valid on (sendMessage target). */
    private data class CueTarget(
        val handle: PeerHandle,
        val session: DiscoverySession,
    )

    /**
     * A coordination-plane sender: the session disambiguates equal [PeerHandle] ids across the
     * publish/subscribe sessions. Keys inbound fragment reassembly (one frame's parts always arrive on one
     * session — they're sent back-to-back to a single [CueTarget]) and the [hopTable].
     */
    private data class HandleKey(
        val session: DiscoverySession,
        val handle: PeerHandle,
    )

    // SDK-tiered ICM/serveCap capability probes (+ their rationale comments) push this just past LongMethod=60.
    @Suppress("LongMethod")
    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Unavailable
            Log.w(TAG, "Wi-Fi Aware unsupported on this device; mesh disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            lastLinkOrAcceptAt = SystemClock.elapsedRealtime() // grace window before the wedge watchdog can fire
            // Instant Communication Mode is API 33; on 29-32 the probe method doesn't exist, so ICM stays off
            // and Wi-Fi Aware falls back to standard discovery windows (slower, but functional).
            instantSupported = instantModeAvailable()
            // Concurrent-serve cap from the firmware NDP budget (8 on Pixel-class hardware → cap 4; Samsung
            // S.LSI ships 1 → legacy single-slot). Unreadable ⇒ 1, the conservative fallback.
            serveCap =
                NanServePolicy.capFor(
                    // numberOfSupportedDataPaths is API 33; pre-33 falls back to the conservative single slot.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { awareManager?.characteristics?.numberOfSupportedDataPaths }.getOrNull() ?: 1
                    } else {
                        1
                    },
                )
            // A radio that refused for a day last time is still refusing. MeshService is START_STICKY, so
            // without this every AMS kill hands the next process a fresh budget to spend re-learning it.
            if (runCatching { attachJournal.awareGiveUpStamp() }.getOrNull() == giveUpStamp) {
                attachAbandoned = true
                Log.w(TAG, "Wi-Fi Aware was abandoned under this build/ROM; not attaching until availability changes")
            }
            // Same durability for the initiator hold (#78): ORs with memory rather than replacing it, because the
            // latch write is asynchronous and a restart() may re-read the journal before it has landed.
            runCatching { initiatorJournal.initiatorLatch() }.getOrNull()?.takeIf { it.stamp == giveUpStamp }?.let {
                initiatorPolicy.restore(it.probedAt)
                _initiatorHeld.value = true
                Log.w(TAG, "initiator role held under this build/ROM (Wi-Fi dropped on our initiates); probing daily")
            }
            registerAvailability()
            registerWifiWatch()
            NanFaultInjector.bind(
                onAvailability = ::handleAvailabilityChanged,
                status = {
                    NanAttachSnapshot(
                        attached = session != null,
                        streak = attachFailStreak,
                        total = attachFailTotal,
                        abandoned = attachAbandoned,
                        retryInMs = (attachRetryAfter - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                    )
                },
                onRefuseResponder = {
                    // `…debug.NANREFUSE`: hand the live responder callback the framework's verdict, on its thread.
                    val cb = responderCallback
                    if (cb != null) handler.post { cb.onUnavailable() }
                    cb != null
                },
                responderStatus = {
                    NanResponderSnapshot(
                        filed = responderCallback != null,
                        refusals = responderRefusals,
                        cycles = responderCycles,
                        armed = 0,
                    )
                },
                onInstantMode = { on ->
                    // `…debug.NANICM`: the hardware's answer bounds it; a session cycle re-files publish/subscribe.
                    instantSupported = on && instantModeAvailable()
                    sessionCycleWithSettle()
                    instantSupported
                },
                onDial = ::debugDial,
                initiatorHooks =
                    NanInitiatorHooks(
                        // `…debug.NANINIT --ez blip true`: the STA lost and back, through the real handlers.
                        blip = {
                            onHandler {
                                onStaLost(null)
                                onStaAvailable(null)
                            }
                        },
                        reset = ::releaseInitiatorHold,
                        forceProbe = {
                            // A probe stamp of 0 is a day old by any wall clock; the next driveSync takes it.
                            if (initiatorPolicy.latched) initiatorPolicy.restore(probedAt = 0L)
                            initiatorPolicy.latched
                        },
                        status = initiatorPolicy::snapshot,
                    ),
            )
            attach()
            loopJob = scope.launch { discoveryLoop() }
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
            // Re-cue neighbors and wake the loop the moment our carried set changes, so a peer that now wants
            // our new data can pull it.
            cueJob =
                scope.launch {
                    storeDigest.version.drop(1).collect {
                        cueAll()
                        republishSsi() // the digest also rides the publish SSI (P4 passive cue channel)
                        healSignal.trySend(Unit)
                    }
                }
            // Heartbeat cue covers best-effort message loss + a peer that hasn't discovered us yet. It also
            // reaps peers that have gone silent (see [pruneAbsentPeers]) first, on the handler thread so the
            // prune serializes with the onDiscovered/onCueReceived add sites and never races a fresh cue.
            cueHeartbeatJob =
                scope.launch {
                    while (scope.isActive) {
                        delay(CUE_HEARTBEAT_MS)
                        onHandler {
                            pruneAbsentPeers()
                            cueAll()
                        }
                    }
                }
            // R8 strips the Log.d in release, not the string logState builds under the lock: debug only.
            if (BuildConfig.DEBUG) {
                diagJob =
                    scope.launch {
                        while (scope.isActive) {
                            delay(DIAG_INTERVAL_MS)
                            logState()
                        }
                    }
            }
            watchdogJob =
                scope.launch {
                    while (scope.isActive) {
                        delay(WEDGE_CHECK_MS)
                        checkWedge()
                    }
                }
        }
    }

    /** Temporary diagnostic: dump the connection-engine decision state so an idle-but-unsynced mesh is visible. */
    private fun logState() {
        val v = storeDigest.current()
        val now = SystemClock.elapsedRealtime()
        val disc: Set<String>
        val backoff: Map<String, Long>
        synchronized(lock) {
            disc = discovered.keys.toSet()
            backoff = retryAfter.toMap()
        }
        val cues = cueTarget.keys.toSet()
        val wanted = cues.filter { localNodeId > it && syncWanted(it, v) }
        val initiable = wanted.filter { it in disc && (backoff[it]?.let { t -> now >= t } ?: true) }
        val inbound = peers.values.count { !it.isInitiator }
        val acc = synchronized(lock) { accepting }
        Log.d(
            TAG,
            "state ver=$v live=${peers.keys} inbound=$inbound acc=$acc cap=$serveCap pin=$responderPinsNdi " +
                "refused=$responderRefusals/$responderCycles disc=$disc cue=$cues wanted=$wanted initiable=$initiable " +
                "reach=${_reachable.value.map { it.nodeId }} tracker[${digestTracker.debug()}] " +
                "bulk[${bulkWanted.debug()}] lonely=${lonelyForMs(now)}ms offscreen=$offScreenBlocked " +
                "init=${initiatorPolicy.snapshot()}",
        )
    }

    /**
     * True if any **currently reachable** peer's advertised digest differs from ours — a sync is owed (in
     * either direction). Reachability is load-bearing, not decorative: a peer that has walked out of range on
     * every plane lingers in [cueTarget] with a stale divergent digest (its cue handle only prunes when a
     * *send* to it throws, which an out-of-range peer's best-effort cue does not), so a digest-only check stays
     * true forever with nobody to sync. [checkWedge] reads this, and an owed-but-unreachable episode is
     * isolation, not a stuck data plane — counting it ran the owed clock to the 180 s self-kill with no peer
     * present (three-Pixel capture, 2026-07-02). A live link or a coordination-plane sighting within
     * [REACHABLE_LINGER_MS] (5 cue heartbeats) counts as reachable; a genuine leaked-request wedge keeps the
     * peer cueing us over the (NDP-free) coordination plane, so it stays reachable and still trips the wedge.
     */
    private fun anySyncOwed(): Boolean =
        NanSyncPolicy.anySyncOwed(snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = false))

    /**
     * As [anySyncOwed] but **without** the [NanSyncPolicy.PeerFacts.corroborated] gate — used by [checkWedge]'s
     * Tier-1 responder refresh, whose action (a cheap, safe [sessionCycleWithSettle]) is worth taking on an owed
     * peer we merely *hear* (reachable), even with no corroborating plane. This is what lets the smallest node —
     * everyone's pure responder — self-heal a wedged responder when Bluetooth is itself dark (so there is
     * nothing to corroborate with); the corroboration gate remains on the Tier-2 process kill only.
     */
    private fun anyReachableSyncOwed(): Boolean =
        NanSyncPolicy.anyReachableSyncOwed(
            snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = false),
        )

    /**
     * Whether an owed [nodeId] is corroborated genuinely-present strongly enough to justify the wedge self-kill.
     * The kill exists to clear the leaked-request wedge (a pinned NDI slot) — useless if the peer is simply out
     * of NDP range while still cueing us over the (longer-reach) coordination plane, which a restart cannot fix.
     * That false case bit the smallest-nodeId node hardest: as everyone's pure responder it can neither initiate
     * to the peer nor complete the peer's NDP, so an out-of-range-but-still-cueing peer kept a sync owed forever
     * and self-killed it (field-observed 2026-07-03, P7 walked off the triangle's far leg). When a corroborating
     * plane exists ([hasForeignPlane], i.e. Bluetooth), require it to *also* sight the peer (evidence it's truly
     * nearby and the fault is our NAN data path); a live NAN link is its own corroboration. A NAN-only device has
     * no corroborator, so it falls back to the prior behaviour.
     */
    private fun corroboratedPresent(nodeId: String): Boolean = !hasForeignPlane || peers.containsKey(nodeId) || nodeId in foreignReachable

    /**
     * Reduce every [cueTarget] candidate to an immutable [NanSyncPolicy.PeerFacts] at one instant ([now]/[v]),
     * so the pure [NanSyncPolicy] folds replace the former inline predicate family. Each field is the exact
     * sub-expression of the original inline predicate. Reads [discovered] under [lock] ONLY when
     * [includeDiscovered] (i.e. only [needsRediscovery]), matching each original predicate's lock footprint —
     * every other caller stays lock-free, exactly as before.
     */
    private fun snapshotPeerFacts(
        now: Long,
        v: Long,
        includeDiscovered: Boolean,
    ): List<NanSyncPolicy.PeerFacts> {
        val disc = if (includeDiscovered) synchronized(lock) { discovered.keys.toSet() } else emptySet<String>()
        return cueTarget.keys.toList().map { id ->
            NanSyncPolicy.PeerFacts(
                initiator = localNodeId > id,
                linked = peers.containsKey(id),
                discovered = id in disc,
                digestWanted = digestSyncWanted(id, v),
                bulkWanted = bulkSyncWanted(id),
                lingerReachable = peers.containsKey(id) || (lastSeenAt[id]?.let { now - it <= REACHABLE_LINGER_MS } == true),
                corroborated = corroboratedPresent(id),
            )
        }
    }

    /**
     * Last-resort self-heal for the **leaked-request wedge** (a framework RESPONDER `requestNetwork` orphaned at
     * `state=104`, pinning the app's single NDP slot even though the NDI reads free — see
     * `docs/DIGEST_PULL_REATTACH.md`). The single-owner/single-flight lifecycle above should make it impossible;
     * this catches any residual/unknown path. The signature is **a sync owed continuously for [WEDGE_RESTART_MS]
     * with no data-path link forming in that whole window**, while Aware is healthy. It is measured as an
     * *owed-episode* ([syncOwedSince]), NOT as time-since-last-link: an idle, converged mesh does zero data-path
     * work for long stretches, so the moment a fresh message makes a sync owed, "no link in N minutes" would be
     * instantly (and wrongly) true — which killed the app the instant the user sent a message. The episode clock
     * starts when divergence appears and **resets on any link** (progress) or on convergence, so a restart only
     * happens when the mesh genuinely cannot sync for the full window. `reattach()` cannot clear this wedge (the
     * orphaned callback ref is lost, so it is never unregistered), so the only proven cure is **process death**;
     * MeshService is `START_STICKY`, so the foreground service is recreated — *provided the system will let it
     * come back*, which off-screen it often won't ([canReclaimForegroundService]). Rate-limited so it can't
     * restart-storm.
     *
     * P2 demotion: with the ghost-proof recycle + the flap-handshake session cycle in front of it, this should
     * ~never fire — it remains the true last resort for a framework cache ghosted while another aware client
     * holds NAN up (no flap possible). Under P1 concurrency an inbound accept also stamps [lastLinkOrAcceptAt],
     * which is correct: a real ghost blocks accepts too, so genuine wedges still trip it, while an
     * initiator-only pin is now a managed state ([responderPinsNdi]) with its own recovery, not a wedge.
     *
     * **Two tiers** (the owed-episode is measured on the *uncorroborated* [anyReachableSyncOwed] so both tiers
     * work when the corroborating Bluetooth plane is itself dark):
     * - **Tier 1 (light, [RESPONDER_REFRESH_MS]):** a sync owed with no link for the window → refresh a
     *   possibly-wedged responder via [sessionCycleWithSettle]. This is the smallest node's self-heal — it is
     *   everyone's pure responder and never recycles (recycle needs an owed *initiate* it never has), so a
     *   responder that goes stuck after serving-then-idle would otherwise stay dead until process death
     *   (field-observed 2026-07-04). The session cycle is safe at 0 NDPs (the NAN-down wipe clears any ghost an
     *   in-place unregister would mint) and cheap, so it needs no corroboration.
     * - **Tier 2 (last resort, [WEDGE_RESTART_MS]):** still owed *and* [anySyncOwed] (corroborated) → process
     *   kill. Corroboration stays on the kill so an out-of-range-but-cueing peer can't self-kill the node, and
     *   [canReclaimForegroundService] gates it so the cure can't cost more than the wedge.
     */
    private fun checkWedge() {
        val now = SystemClock.elapsedRealtime()
        val healthy = hasHardware && _health.value == TransportHealth.Healthy && session != null
        val decision =
            NanWatchdogPolicy.decide(
                healthy = healthy,
                reachableOwed = anyReachableSyncOwed(), // Tier-1 / episode signal (uncorroborated)
                corroboratedOwed = anySyncOwed(), // extra Tier-2 gate (corroborated)
                now = now,
                syncOwedSince = syncOwedSince,
                lastLinkOrAcceptAt = lastLinkOrAcceptAt,
                lastReattachAt = lastReattachAt,
                lastRestartAt = lastRestartAt,
                responderRefreshes = responderRefreshes,
                responderRefreshMs = RESPONDER_REFRESH_MS,
                reattachCooldownMs = REATTACH_COOLDOWN_MS,
                wedgeRestartMs = WEDGE_RESTART_MS,
                maxResponderRefreshes = MAX_RESPONDER_REFRESHES,
            )
        syncOwedSince = decision.nextSyncOwedSince
        responderRefreshes = decision.nextResponderRefreshes
        // Publish the episode clock the watchdog runs on: without this it has no consumer outside this
        // function, and once DM-form chat rides the coordination plane ([shouldFastSend]) a node with a dead
        // data path still delivers messages — it just stops converging custody. This gauge is then the only
        // thing that says so.
        if (syncOwedSince != 0L) metrics.onNanSyncOwed(now - syncOwedSince)
        when (decision.action) {
            NanWatchdogPolicy.Action.None -> {}

            // Tier 1: refresh the (possibly wedged) responder — light, uncorroborated, safe at 0 NDPs.
            NanWatchdogPolicy.Action.RefreshResponder -> {
                lastReattachAt = now
                Log.w(
                    TAG,
                    "sync owed ${now - syncOwedSince}ms with no link — refreshing responder via session cycle " +
                        "($responderRefreshes/$MAX_RESPONDER_REFRESHES this episode)",
                )
                sessionCycleWithSettle()
            }

            // Tier 2: last-resort process kill (MeshService is START_STICKY, so the service is recreated).
            NanWatchdogPolicy.Action.RestartProcess -> {
                // ...but only when the system would actually let the service back. A sticky restart is not an
                // exemption to the Android 12+ background foreground-service-start rule, so killing an
                // unexempted backgrounded process trades a wedged data plane for no mesh at all until the user
                // next opens the app (and, before MeshService learned to decline, a crash). Deliberately leaves
                // the episode clock running and [lastRestartAt] unstamped, so the cure still fires on the next
                // check once the app is foreground or battery-exempt and the wedge has persisted.
                if (!canReclaimForegroundService(appContext)) {
                    Log.w(TAG, "NAN data plane wedged but the foreground service can't be re-claimed — not restarting")
                    return
                }
                lastRestartAt = now
                Log.e(TAG, "NAN data plane wedged (sync owed ${now - syncOwedSince}ms with no link) — restarting process")
                logState()
                Process.killProcess(Process.myPid())
            }
        }
    }

    override fun stop() {
        loopJob?.cancel()
        powerJob?.cancel()
        cueJob?.cancel()
        cueHeartbeatJob?.cancel()
        diagJob?.cancel()
        watchdogJob?.cancel()
        unregisterAvailability()
        unregisterWifiWatch()
        initiatorPolicy.noteWatchStopped() // a blip cannot span the watch; the strikes and the hold stay (ADR 055)
        NanFaultInjector.bind(onAvailability = null, status = null)
        peers.keys.toList().forEach { teardownPeer(it) }
        stopResponder()
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        publishSession = null
        subscribeSession = null
        session = null
        attaching.set(false)
        subscribing.set(false)
        clearAttachBackoff()
        responderRefusals = 0
        responderCycles = 0
        paused = false
        offScreenBlocked = false
        lastLinkEndedAt = 0L
        sessionCycleSettleStartedAt = 0L
        synchronized(lock) {
            inFlight.clear()
            discovered.clear()
            retryAfter.clear()
            failStreak.clear()
            lastInitiateAttemptAt.clear()
            accepting = 0
        }
        cueTarget.clear()
        hopTable.clear()
        reassembler.clear()
        lastSeenAt.clear()
        reachablePeers.clear()
        digestTracker.clear()
        bulkWanted.clearAll()
        lonelySince = 0L
        healRearmOwed = false
        lonelyRelaxedLogged = false
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
    }

    override fun heal() {
        if (!hasHardware) return
        healRearmOwed = true // one re-arm at the aggressive cooldown, however relaxed the lonely cadence is
        retryOffScreenBlocked()
        healSignal.trySend(Unit)
    }

    /**
     * The one retry an off-screen location refusal gets: a [heal] says the app was opened (or the heartbeat /
     * motion trigger fired), and an open app is exactly what lifts the app-op — `KnitApp`'s resume observer
     * starts the service from a visible activity before it heals, which is what grants the running record its
     * while-in-use flag on 30-32. Re-file whichever discovery half is missing on the live session; a retry that
     * throws re-latches through [onSessionFault], so this is one attempt per heal and never a loop. Subscribe
     * is left to the loop's own heal-owed re-arm when it was merely closed by `rearmSubscribe`, and filed here
     * only when nothing is pending.
     */
    private fun retryOffScreenBlocked() =
        onHandler {
            if (!offScreenBlocked) return@onHandler
            offScreenBlocked = false
            if (session == null) return@onHandler // the loop's detached branch attaches; that path publishes
            Log.i(TAG, "retrying discovery after an off-screen location refusal")
            if (publishSession == null) startPublish(attachGen)
            if (subscribeSession == null && !subscribing.get()) startSubscribe()
        }

    /**
     * Lend the radio out ([MeshTransport.pause]): drop every link, the responder and the whole session, and
     * refuse to attach until [resume]. Mirrors [reattach]'s teardown without its re-attach, on the [handler]
     * thread for the same reason. Health reads Degraded meanwhile — the radio is on, we are just not on it.
     */
    override fun pause() {
        if (!hasHardware || paused) return
        paused = true
        Log.i(TAG, "pausing Wi-Fi Aware: radio lent to a same-app role")
        initiatorPolicy.noteRadioOff() // our own P2P group may blip the STA; that is not the firmware's doing
        onHandler {
            peers.keys.toList().forEach { teardownPeer(it) }
            stopResponder()
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
            runCatching { session?.close() }
            publishSession = null
            subscribeSession = null
            session = null
            attaching.set(false)
            subscribing.set(false)
            reattaching.set(false)
            ++attachGen // callbacks still in flight from the closed session are stale now
            synchronized(lock) { accepting = 0 }
            offScreenBlocked = false
            _health.value = TransportHealth.Degraded
        }
    }

    /**
     * Take the radio back ([MeshTransport.resume]). A same-app hand-over never flips availability, so this
     * is the only edge: clear the attach backoff (a new fact about the radio, like a Wi-Fi toggle) and attach
     * now rather than on the discovery loop's next tick.
     */
    override fun resume() {
        if (!hasHardware || !paused) return
        paused = false
        Log.i(TAG, "resuming Wi-Fi Aware after a same-app hand-over")
        clearAttachBackoff()
        attach()
        healSignal.trySend(Unit)
    }

    override fun suppressDataPath(peers: Set<String>) {
        if (peers == suppressed) return
        suppressed = peers
        // A peer just became Bluetooth-covered (skip syncing it) or dropped off (resume) — re-evaluate now.
        healSignal.trySend(Unit)
    }

    /**
     * The set of peers another plane (Bluetooth) can currently see, pushed by [CompositeMeshTransport]. It
     * corroborates the wedge watchdog ([checkWedge] / [anySyncOwed]) — being called at all means a corroborating
     * plane exists, so a NAN-only device is left on the un-corroborated fallback — and a peer that plane sights
     * which we hold no cue target for ends loneliness: the relaxed lonely cadence ([NanLonelyPolicy]) is
     * restarted and the loop woken, so a BLE walk-up relights discovery within one aggressive tick rather than
     * one relaxed one. Rising edge only, and NAN's own discoveries fill `cueTarget` before any echo, so it
     * cannot loop.
     */
    override fun onForeignReachable(peers: Set<String>) {
        hasForeignPlane = true
        val rising = peers.any { it !in foreignReachable && !cueTarget.containsKey(it) }
        foreignReachable = peers
        if (rising) {
            lonelySince = 0L
            healSignal.trySend(Unit)
        }
    }

    /**
     * Arm an on-demand NDP toward [nodeId] for an imminent attachment transfer (see [BulkWantTracker] for why
     * this exists and what it deliberately does NOT feed). Gated on a **fresh** coordination-plane sighting
     * ([BULK_FRESH_MS], not the [REACHABLE_LINGER_MS] UI linger, which keeps ghosts for 150 s) so a NAN-dark
     * peer can't draw initiate→timeout cycles on the single NDI; the tracker's fail-cooldown backstops the
     * ones that slip through. True means the plane is armed (or already linked) and a link is worth waiting
     * a grace for; the actual bring-up rides the existing driveSync path — backoff, single-slot admission,
     * SETTLE, and the initiator tie-break all unchanged.
     */
    override fun expectBulkTransfer(nodeId: String): Boolean {
        if (!hasHardware || session == null) {
            Log.d(TAG, "bulk arm $nodeId: refused (no session)")
            return false
        }
        if (peers.containsKey(nodeId)) return true // already linked — nothing to arm
        if (initiatorHeld(nodeId)) {
            // We will not raise the link, so don't let the composite wait its grace for one (#78).
            Log.d(TAG, "bulk arm $nodeId: refused (initiator held)")
            return false
        }
        val ageMs = lastSeenAt[nodeId]?.let { SystemClock.elapsedRealtime() - it }
        if (ageMs == null || ageMs > BULK_FRESH_MS || !cueTarget.containsKey(nodeId)) {
            Log.d(TAG, "bulk arm $nodeId: refused (sighting ${ageMs ?: "never"}ms old, cue=${cueTarget.containsKey(nodeId)})")
            return false
        }
        if (!bulkWanted.note(nodeId)) {
            Log.d(TAG, "bulk arm $nodeId: refused (post-failure cooldown / cap)")
            return false
        }
        Log.d(TAG, "bulk arm $nodeId (seen ${ageMs}ms ago)")
        healSignal.trySend(Unit) // re-evaluate driveSync now instead of waiting out the idle tick
        return true
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) peers.values.toList() else listOfNotNull(peers[to.nodeId])
        targets.forEach { it.link.send(bytes) } // FramedLink.send accounts the bytes
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val accepted = peers[to.nodeId]?.link?.sendFile(file, meta) ?: false
        if (accepted) metrics.onFileSent(TransportKind.WifiAware)
        return accepted
    }

    override fun arrivingFiles(): Set<String> = peers.values.mapNotNullTo(HashSet()) { it.link.rxKey }

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = peers[nodeId]?.link?.hasPendingFile(key) ?: false

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        peers[to.nodeId]?.link?.sendDigest(ids)
    }

    // --- Attach / discovery ---

    /**
     * Run [block] on the single Aware-callback [handler] thread. The entire attach/publish/subscribe/responder
     * lifecycle funnels through here so it is strictly single-threaded — no cross-thread race on
     * `session`/[attaching]/[responderCallback] between a scope-thread caller (start/discoveryLoop/availability)
     * and a handler-thread one ([reattach] / discovery callbacks).
     */
    private fun onHandler(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    /**
     * `…debug.NANDIAL`: initiate to a discovered peer whatever the tie-break says. The far side's HELLO check
     * still closes the socket if we are the larger id; the NDP forming is the trial. Debug builds only.
     */
    private fun debugDial(peer: String): String {
        val found = synchronized(lock) { discovered[peer] }
        return when {
            subscribeSession == null -> {
                "no subscribe session"
            }

            found == null -> {
                "peer not in discovered (${synchronized(lock) { discovered.keys.toList() }})"
            }

            anyLinkActivity() -> {
                "link activity in progress"
            }

            else -> {
                onHandler { initiateTo(peer, found.advert, found.peerHandle) }
                if (initiatorPolicy.latched) "initiating (initiator held — this dial is today's probe)" else "initiating"
            }
        }
    }

    /** Whether this hardware offers Instant Communication Mode (API 33+, and the chip says so). */
    private fun instantModeAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { awareManager?.characteristics?.isInstantCommunicationModeSupported() == true }
                .getOrDefault(false)

    private fun attach() =
        onHandler {
            val mgr = awareManager ?: return@onHandler
            if (paused) return@onHandler // lent out; resume() attaches
            if (!mgr.isAvailable) {
                _health.value = TransportHealth.Unavailable // Wi-Fi Aware off (Wi-Fi off / airplane mode)
                return@onHandler
            }
            if (session != null) return@onHandler
            // Backed off, or given up, after a run of failures ([NanAttachPolicy]): on a chipset that cannot
            // give us a NAN iface at all, the loop's flat retry strands two binder objects in system_server
            // every 3 s until AMS kills the process for it.
            if (attachAbandoned || SystemClock.elapsedRealtime() < attachRetryAfter) return@onHandler
            // ...and no faster than the floor, whatever the streak says. Every other gate here is refundable by
            // something; this one is not, so it is what holds if a refund path ever runs in a loop again.
            if (NanAttachPolicy.tooSoon(SystemClock.elapsedRealtime() - attachStartedAt)) return@onHandler
            // Self-heal a stuck guard: if a prior mgr.attach never called back within the watchdog, clear it so we
            // can retry (the chipset occasionally drops an attach silently after a session teardown).
            if (attaching.get() && SystemClock.elapsedRealtime() - attachStartedAt > ATTACH_WATCHDOG_MS) {
                attaching.set(false)
            }
            if (!attaching.compareAndSet(false, true)) return@onHandler // an attach is already in flight
            attachStartedAt = SystemClock.elapsedRealtime()
            val gen = ++attachGen // this attach owns this generation; older in-flight callbacks are now stale
            val cb =
                object : AttachCallback() {
                    override fun onAttached(newSession: WifiAwareSession) {
                        attaching.set(false)
                        noteAttachSucceeded() // before the supersede check: the radio opened, which is all that counts
                        if (gen != attachGen || session != null) { // superseded by a newer attach — never orphan it
                            runCatching { newSession.close() }
                            return
                        }
                        session = newSession
                        reattaching.set(false) // the reattach that kicked off this (current-gen) attach has settled
                        _health.value = TransportHealth.Healthy
                        lonelySince = 0L // a fresh session hunts at the aggressive cadence, however long we were alone
                        // A fresh session is a fresh chance whatever teardown path led here (reattach, a session
                        // cycle); a refusal re-latches on the throw, so this costs at most one call.
                        offScreenBlocked = false
                        startPublish(gen)
                        startSubscribe()
                    }

                    override fun onAttachFailed() {
                        attaching.set(false)
                        reattaching.set(false)
                        _health.value = TransportHealth.Degraded
                        noteAttachFailed("Wi-Fi Aware attach failed")
                    }

                    override fun onAwareSessionTerminated() {
                        attaching.set(false)
                        subscribing.set(false)
                        reattaching.set(false)
                        ++attachGen // invalidate any in-flight callbacks from the terminated generation
                        session = null
                        publishSession = null
                        subscribeSession = null
                        stopResponder()
                        synchronized(lock) { accepting = 0 }
                        offScreenBlocked = false
                        // A session terminates both when the radio is switched off and when it's seized; distinguish so
                        // the UI can say "radios off" vs "radio busy". The availability receiver corrects this if it flips.
                        _health.value =
                            if (mgr.isAvailable) TransportHealth.Degraded else TransportHealth.Unavailable
                    }
                }
            if (NanFaultInjector.shouldFailAttach()) {
                // Debug builds only, and armed only by `…debug.NANFAIL` — see [NanFaultInjector]. Placed after
                // attachStartedAt is stamped so the injected run is paced by the real floor, not around it.
                attaching.set(false)
                reattaching.set(false)
                _health.value = TransportHealth.Degraded
                noteAttachFailed("Wi-Fi Aware attach failed (debug fault)")
                return@onHandler
            }
            runCatching { mgr.attach(cb, handler) }.onFailure {
                attaching.set(false)
                reattaching.set(false)
                _health.value = TransportHealth.Degraded
                noteAttachFailed("Wi-Fi Aware attach threw", it)
            }
        }

    /**
     * Record a failed attach and push the next one out along [NanAttachPolicy]'s curve. Both failure paths
     * reach it — the async [AttachCallback.onAttachFailed] and a synchronous throw out of `mgr.attach` — since
     * a permanently-unopenable radio can present as either.
     */
    private fun noteAttachFailed(
        what: String,
        cause: Throwable? = null,
    ) {
        attachFailStreak += 1
        attachFailTotal += 1
        if (NanAttachPolicy.leakBudgetSpent(attachFailTotal)) {
            abandonAttach("$what (total $attachFailTotal) — this process has spent its attach budget", cause)
            return
        }
        if (NanAttachPolicy.giveUp(attachFailStreak)) {
            abandonAttach("$what (streak $attachFailStreak) — this radio will not open", cause)
            return
        }
        val backoff = NanAttachPolicy.backoffMs(attachFailStreak)
        attachRetryAfter = SystemClock.elapsedRealtime() + backoff
        Log.w(TAG, "$what (streak $attachFailStreak) — next attach in ${backoff}ms", cause)
    }

    /**
     * Stop attaching, and remember it across the process death that may well follow (`MeshService` is
     * `START_STICKY`). The durable half is keyed by app version + ROM fingerprint, so a new build or a flashed
     * ROM re-arms on its own — see [NanAttachJournal].
     */
    private fun abandonAttach(
        why: String,
        cause: Throwable?,
    ) {
        attachAbandoned = true
        Log.e(TAG, "$why; no further attaches", cause)
        scope.launch { runCatching { attachJournal.setAwareGiveUpStamp(giveUpStamp) } }
    }

    /**
     * A new fact about the radio ends the backoff episode: a fresh streak, and a fresh chance for a give-up
     * that a Wi-Fi toggle or a ROM change may have invalidated. It deliberately does **not** refund
     * [attachFailTotal] — nothing but an attach that works does that, because this is the path that was being
     * driven in a loop on getknit/Knit#9 and the leak bound has to survive its own callers.
     */
    private fun clearAttachBackoff() {
        attachFailStreak = 0
        attachRetryAfter = 0L
        attachAbandoned = false
    }

    /** As [clearAttachBackoff], plus the two things only a working radio may refund. */
    private fun noteAttachSucceeded() {
        clearAttachBackoff()
        attachFailTotal = 0
        scope.launch { runCatching { attachJournal.setAwareGiveUpStamp("") } }
    }

    private fun startPublish(gen: Int) {
        val s = session ?: return
        if (offScreenBlocked) return // would throw again; [retryOffScreenBlocked] re-files on the next heal
        // Per-generation publish callback: a stale onPublishStarted (from an attach a reattach superseded)
        // closes its session and never arms a responder, so the responder stays single-owner.
        val cb =
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(publish: PublishDiscoverySession) {
                    if (gen != attachGen) {
                        runCatching { publish.close() }
                        return
                    }
                    publishSession = publish
                    clearForegroundOnly()
                    icmKeepaliveBroken = false // a fresh session gets a fresh chance at in-place updates
                    responderRefusals = 0 // the streak was about the old session's request; the cycle budget is not
                    startResponder() // one accept-any responder for the life of this publish session
                }

                override fun onSessionConfigFailed() {
                    // With a live publish session this is an updatePublish (keepalive/SSI republish) failing,
                    // not the initial config. Latch the fallback: the ICM relight reverts to the legacy
                    // subscribe re-arm until the next attach — never loop updatePublish against a failing session.
                    if (publishSession != null) {
                        icmKeepaliveBroken = true
                        metrics.onNanIcmKeepaliveFailed()
                        Log.w(TAG, "publish update failed — ICM relight falling back to subscribe re-arm")
                    } else {
                        Log.w(TAG, "publish config failed")
                    }
                }

                override fun onSessionConfigUpdated() {
                    Log.i(TAG, "publish config updated (keepalive/ssi)")
                }

                override fun onMessageReceived(
                    peerHandle: PeerHandle,
                    message: ByteArray,
                ) {
                    onCueReceived(peerHandle, message, publishSession)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    metrics.onNanMsgAcked()
                }

                override fun onMessageSendFailed(messageId: Int) {
                    metrics.onNanMsgSendFailed()
                }
            }
        runCatching { s.publish(buildPublishConfig(), cb, handler) }.onFailure { onSessionFault("publish", it) }
    }

    /**
     * The publish config, built in one place so every `updatePublish` (ICM keepalive, SSI republish) can
     * never drift from the original. The advert carries a trailing `|d<version>` segment — the store digest
     * as a **passive cue**: a changed SSI re-fires every subscriber's `onServiceDiscovered` (HAL MATCH_ONCE
     * suppresses repeats only "with no new data"; P0-verified 5/5 on this fleet), so peers learn our digest
     * with zero unicast messages. [Protocol.parse] reads only the first three segments, so the advert stays
     * backward-parseable; the unicast cue plane stays as the redundant active channel.
     */
    private fun buildPublishConfig(): PublishConfig {
        val ssi = Protocol.advertise(localNodeId) + NanCueCodec.encodeSsiDigest(storeDigest.current())
        val builder =
            PublishConfig
                .Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(ssi.encodeToByteArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && instantSupported) {
            builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        }
        return builder.build()
    }

    /**
     * Refresh the publish SSI so the digest segment (see [buildPublishConfig]) reflects the current store —
     * coalesced to one `updatePublish` per [SSI_UPDATE_MIN_MS] with a trailing edge, so a backfill burst
     * (every ingested frame moves the digest) becomes a single update carrying the final version. Each update
     * also refreshes the framework's per-session `mUpdateTime`, i.e. it doubles as an ICM keepalive.
     */
    private fun republishSsi() =
        onHandler {
            if (ssiRepublishPending) return@onHandler
            val now = SystemClock.elapsedRealtime()
            val wait = (lastSsiRepublishAt + SSI_UPDATE_MIN_MS - now).coerceAtLeast(0)
            if (wait == 0L) {
                lastSsiRepublishAt = now
                icmKeepalive()
            } else {
                ssiRepublishPending = true
                handler.postDelayed({
                    ssiRepublishPending = false
                    lastSsiRepublishAt = SystemClock.elapsedRealtime()
                    icmKeepalive()
                }, wait)
            }
        }

    /**
     * Refresh the publish session **in place**: `updatePublish` re-announces the SSI (with the current digest
     * segment) and refreshes the framework's per-session `mUpdateTime` — the whole 30 s ICM clock — replacing
     * the churn-prone subscribe re-arm as the relight (P4; E5-validated: ICM stayed lit across 5 consecutive
     * 30 s reconfigure windows, zero session churn). Failure surfaces via "publish update failed", which
     * latches [icmKeepaliveBroken] so the relight falls back to the legacy re-arm until the next attach.
     */
    private fun icmKeepalive() =
        onHandler {
            val pub = publishSession ?: return@onHandler
            Log.i(TAG, "icm keepalive — updatePublish (ssi digest ${storeDigest.current()})")
            runCatching { pub.updatePublish(buildPublishConfig()) }
                .onFailure { Log.w(TAG, "updatePublish threw", it) }
        }

    private fun startSubscribe() {
        val s = session ?: return
        if (offScreenBlocked) return // would throw again; [retryOffScreenBlocked] re-files on the next heal
        // Serialize subscribe (re)starts the same way attach is serialized: onSubscribeStarted arrives
        // asynchronously, so a rearmSubscribe() racing a still-pending subscribe would otherwise leave two
        // subscribe sessions outstanding and leak the one onSubscribeStarted doesn't keep.
        if (!subscribing.compareAndSet(false, true)) return
        val builder = SubscribeConfig.Builder().setServiceName(SERVICE_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && instantSupported) {
            builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        }
        // subscribe() is a binder call into the Aware service; if our client died framework-side (NAN
        // cycled) before onAwareSessionTerminated reached us, it throws (SecurityException: "invalid
        // uid+clientId mapping") — that must never escape into the caller's coroutine (a field crash:
        // rearmSubscribe on a dead session killed the process). Treat it as the terminate we missed.
        runCatching { s.subscribe(builder.build(), subscribeCallback, handler) }.onFailure {
            subscribing.set(false)
            onSessionFault("subscribe", it)
        }
    }

    /**
     * Re-fires Wi-Fi Aware's one-shot discovery by restarting **only the subscribe** session (publish and
     * its responder stay up, so we remain discoverable/serviceable and no live NDP anchored to publish is
     * disturbed). Called by [discoveryLoop] only while the NDI slot is free (no live link/handshake), so
     * there is no subscribe-anchored client NDP to drop.
     */
    private fun rearmSubscribe() =
        onHandler {
            // Handler-funneled like the rest of the session lifecycle: the discovery loop calls this from its
            // worker thread, which raced onAwareSessionTerminated nulling [session] on the handler thread.
            if (session == null || subscribing.get()) return@onHandler // don't stack a rearm on a pending subscribe
            if (offScreenBlocked) return@onHandler // keep whatever subscribe survived; the re-file would only throw
            synchronized(lock) { discovered.clear() }
            runCatching { subscribeSession?.close() }
            subscribeSession = null
            startSubscribe()
        }

    /**
     * A publish/subscribe threw. Two causes want opposite responses ([NanSessionFault]): a dead framework-side
     * client is torn down and re-attached ([onSessionDead]); a location refusal with Knit off screen is held
     * ([onDiscoveryRefusedOffScreen]) — the session is fine, and re-attaching would only throw again.
     */
    private fun onSessionFault(
        op: String,
        cause: Throwable,
    ) = when (NanSessionFault.classify(cause)) {
        NanSessionFault.DeadSession -> onSessionDead(op, cause)
        NanSessionFault.OffScreenLocation -> onDiscoveryRefusedOffScreen(op, cause)
    }

    /**
     * The OS refused a publish/subscribe because the location app-op is foreground-only and Knit is off screen
     * (API 29-32; [NanSessionFault.OffScreenLocation]). **Nothing is torn down**: the attach, any live publish,
     * the responder and every link need no location and keep serving — the node is still discoverable, it just
     * cannot discover. Latch so the loop's re-arm / relight / attach paths stop calling into the framework
     * (each would throw on the aggressive cadence), and say so as a verdict the UI can name rather than the
     * "radio busy" a churn would look like. Lifted by [retryOffScreenBlocked] on the next [heal]. The
     * `MeshService` type fix (ADR 2026-09.535d) is what keeps this from firing at all after the app has been
     * opened once; a boot- or sticky-started service on 30-32 is the residual that still lands here.
     */
    private fun onDiscoveryRefusedOffScreen(
        op: String,
        cause: Throwable,
    ) = onHandler {
        subscribing.set(false)
        if (!offScreenBlocked) {
            Log.w(TAG, "$op refused off screen (location app-op) — holding discovery until the app is next opened", cause)
            metrics.onNanOffScreenRefused()
        }
        offScreenBlocked = true
        if (session != null) _health.value = TransportHealth.ForegroundOnly
    }

    /** A publish or subscribe took: whatever refusal was latched is over. Handler thread. */
    private fun clearForegroundOnly() {
        if (_health.value == TransportHealth.ForegroundOnly) _health.value = TransportHealth.Healthy
        offScreenBlocked = false
    }

    /**
     * The framework-side Aware client died under us: a session binder call threw (e.g. `SecurityException:
     * Attempting to use invalid uid+clientId mapping` when NAN cycled between our `session` null-check and
     * the call, the terminate callback lost or still in flight). Mirror [AttachCallback.onAwareSessionTerminated]'s
     * cleanup, then poke the loop so its `session == null -> attach()` branch recovers at the fast cadence.
     */
    private fun onSessionDead(
        op: String,
        cause: Throwable,
    ) = onHandler {
        Log.w(TAG, "$op threw on a dead Aware session — dropping session for re-attach", cause)
        attaching.set(false)
        subscribing.set(false)
        reattaching.set(false)
        offScreenBlocked = false
        ++attachGen // invalidate any in-flight discovery callbacks from the dead generation
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        session = null
        publishSession = null
        subscribeSession = null
        stopResponder()
        synchronized(lock) { accepting = 0 }
        _health.value =
            if (awareManager?.isAvailable == true) TransportHealth.Degraded else TransportHealth.Unavailable
        healSignal.trySend(Unit)
    }

    /**
     * Recover a wedged discovery/responder layer: drop the whole [WifiAwareSession] and re-attach (fresh
     * publish/subscribe/responder). Re-subscribing alone doesn't clear a subscribe stuck in
     * `onSessionConfigFailed`, and a *bare* responder re-arm doesn't clear a responder wedged after serving
     * one client — only a full session reset does. **Single-flight** ([reattaching]) so the subscribe-wedge
     * and after-serve triggers can't overlap into a double attach — the churn that used to orphan a responder
     * `requestNetwork` (the role=1 state=104 ghost that pins the single NDI until process death). Runs on the
     * [handler] thread and guarded on a free NDI slot so it never drops a live sync.
     */
    private fun reattach() =
        onHandler {
            if (anyLinkActivity()) return@onHandler
            if (paused) return@onHandler
            if (!reattaching.compareAndSet(false, true)) return@onHandler // collapse concurrent re-attach triggers
            Log.w(TAG, "re-attaching to recover wedged discovery/responder")
            stopResponder()
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
            runCatching { session?.close() }
            publishSession = null
            subscribeSession = null
            session = null
            attaching.set(false)
            subscribing.set(false)
            attach() // bumps attachGen, stamping fresh callbacks and invalidating any prior-gen stragglers
            // Wake the discovery loop NOW that session is null. Its wait was computed while session was non-null
            // (a long idle cadence), so nulling session mid-wait doesn't shorten it — without this poke it would
            // sleep out the full REDISCOVER_IDLE_MS before retrying attach(), leaving the node with no responder for
            // ~2 minutes if the inline attach() above couldn't re-enable NAN right after the teardown. Woken, the
            // loop re-evaluates at the session==null fast cadence (ATTACH_RETRY_MS) until attach() takes — a
            // teardown that needs one beat costs one [NanAttachPolicy] step, which is that same 3 s.
            healSignal.trySend(Unit)
            // attach() clears [reattaching] via its callbacks; if the fresh attach never calls back at all, release
            // the single-flight guard after a bounded delay so a later recovery is never permanently blocked.
            handler.postDelayed({ reattaching.set(false) }, ATTACH_WATCHDOG_MS)
        }

    /**
     * The connection engine. Each tick: if the single NDI slot is free and a discovered peer we're the
     * initiator for is sync-wanted ([DigestTracker]), bring up one NDP to it; if nothing is worth syncing and
     * the slot is free, re-arm subscribe to re-discover. While a link/handshake is up, do nothing — the
     * per-link supervisor tears it down on quiescence. Woken early by [healSignal] (cue, link up/down +
     * settle, heal(), screen-on).
     */
    @Suppress("CyclomaticComplexMethod") // one `when` ladder, one branch per radio state; splitting it hides the precedence
    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            withTimeoutOrNull(rediscoverDelayMs()) { healSignal.receive() }
            recomputeReachable() // age out the nearby set even on an idle tick
            val now = SystemClock.elapsedRealtime()
            val healDemand = healRearmOwed
            healRearmOwed = false
            val rearmCooldown = rearmCooldownMs(now, healDemand)
            when {
                !hasHardware -> {}

                session == null -> {
                    attachAfterCycleSettle(now)
                }

                // radio came back / first attach failed
                anyLinkActivity() -> {}

                // links/handshakes/accepts are live — wait for the supervisors to free the radio
                // P2: a served-then-idle responder request pins the NDI (§2 of the re-audit) and an initiate
                // is owed — the clean in-place recycle window (an NDP still alive) is gone, so cycle the
                // session and wait for the availability flap: the old reattach's NAN-down race, as a handshake.
                responderPinsNdi && initiateOwedToReachable() && now - lastReattachAt > REATTACH_COOLDOWN_MS -> {
                    lastReattachAt = now
                    sessionCycleWithSettle()
                }

                driveSync() -> {}

                // a sync-wanted peer we can initiate to → NDP up
                // Re-fire discovery only when a sync is actually blocked on a missing/stale subscribe handle
                // (or we're blind), and no more than once per REARM_COOLDOWN_MS: repeatedly closing/reopening
                // subscribe is what wedges it on these chipsets (persistent onSessionConfigFailed), so we keep
                // it stable while things work and only refresh when a reachable, sync-wanted peer is otherwise
                // unreachable (e.g. it restarted and staled our handle).
                // Both discovery branches below sit behind the off-screen latch: a re-file would only throw again,
                // and the next [heal] is the retry (ADR 2026-09.535d).
                offScreenBlocked -> {}

                needsRediscovery() && now - lastRearmAt > rearmCooldown -> {
                    lastRearmAt = now
                    Log.i(TAG, "re-arm subscribe (lonely=${lonelyForMs(now)}ms cooldown=${rearmCooldown}ms heal=$healDemand)")
                    rearmSubscribe()
                }

                // Pure-responder ICM keepalive. A node that only ever *responds* — notably the smallest nodeId,
                // everyone's responder — never satisfies needsRediscovery() above (it gates on being an
                // initiator), so it never re-arms subscribe and its Instant Communication Mode lapses ~30s after
                // attach and stays dark, leaving it discoverable/serviceable only in brief windows (the "message
                // every minute or two" sawtooth once BLE drops and NAN is the sole plane). Re-arm subscribe to
                // relight ICM while a sync is genuinely owed to a reachable peer we can't initiate to — demand-
                // gated (stops on convergence) and rate-limited by ICM_REARM_COOLDOWN_MS so it can't churn
                // subscribe toward its wedge state.
                instantSupported && icmRelightDue(now) -> {
                    fireIcmRelight(now)
                }

                else -> {}
            }
        }
    }

    /**
     * The discovery loop's detached branch: re-attach at the fast cadence — unless a deliberate session cycle
     * is in its post-teardown settle ([sessionCycleWithSettle]), where an early attach() is exactly the race
     * that used to let a state=104 ghost survive the cycle. Once [SESSION_CYCLE_SETTLE_MS] elapses the
     * framework's ~50 ms disable + cache wipe has long since run, so attach.
     */
    private fun attachAfterCycleSettle(now: Long) {
        val settleStart = sessionCycleSettleStartedAt
        if (settleStart != 0L && now - settleStart < SESSION_CYCLE_SETTLE_MS) return
        if (settleStart != 0L) {
            sessionCycleSettleStartedAt = 0L
            Log.i(TAG, "session cycle: settle elapsed — re-attaching over the wiped request cache")
        }
        attach()
    }

    /**
     * P2 fallback for the pinned-at-0-NDPs corner (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.2): a responder
     * request that has served (so the framework keeps its NDI assigned) but whose clean recycle window was
     * missed — its last serve ended by quiescence, not EOF, so its NDP may already be gone and unregistering
     * at 0 NDPs IS the state=104 ghost. Tear the whole Aware session down like [reattach], but do NOT
     * re-attach inline: as the last aware client, our detach makes the framework disable NAN and run
     * `onAwareDownCleanupDataPaths` — the request-cache wipe that clears the pin (and the ghost this
     * teardown's own unregister-at-0-NDPs just minted) — **~50 ms after `session.close()`, measured
     * on-device**. The settle wait ([SESSION_CYCLE_SETTLE_MS], enforced by [attachAfterCycleSettle]) covers
     * that with a wide margin before re-attaching, turning [reattach]'s old coin-flip race into a
     * deterministic sequence. Deliberately NOT a broadcast handshake: ACTION_WIFI_AWARE_STATE_CHANGED
     * delivery lags by many seconds on a screen-off device (field-measured), far behind the actual disable.
     * Never runs with live links ([anyLinkActivity]); single-flight via [reattaching].
     */
    private fun sessionCycleWithSettle() =
        onHandler {
            if (anyLinkActivity()) return@onHandler // never drop live links; the loop re-evaluates
            if (!reattaching.compareAndSet(false, true)) return@onHandler
            // Two callers: the discovery-loop pinned-responder branch (initiator NDI freed) and checkWedge's
            // Tier-1 responder self-heal (a wedged responder refreshed) — each logs its own reason first.
            Log.w(TAG, "session cycle: tearing down for a fresh session, settling")
            stopResponder()
            // A PeerHandle is only valid on the discovery session it was learned on, so closing subscribe
            // invalidates every entry in [discovered] — drop them with it, exactly as [rearmSubscribe] and
            // [stop] do. Keeping them is not merely untidy, it is the wedge: [initiateTo] would pair the
            // *fresh* subscribe session with a *dead* handle (the framework then sets an NDP up and drops it,
            // which arrives here as a sub-FAST_FAIL_MS onUnavailable and is misread as a stale peer handle,
            // inflating failStreak and the backoff), while [needsRediscovery] reads the stale entries as "we
            // already hold a handle" and so suppresses the very subscribe re-arm that would refresh them.
            // ++attachGen for the same reason it is bumped on the NAN-down path: discovery callbacks still in
            // flight from the generation we are tearing down must not repopulate the map we just cleared.
            ++attachGen
            synchronized(lock) { discovered.clear() }
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
            runCatching { session?.close() }
            publishSession = null
            subscribeSession = null
            session = null
            attaching.set(false)
            subscribing.set(false)
            sessionCycleSettleStartedAt = SystemClock.elapsedRealtime()
            reattaching.set(false) // guard only collapses concurrent teardowns; the attach comes post-settle
            healSignal.trySend(Unit)
        }

    /**
     * Whether the ICM relight should fire this tick, rate-limited per path: the in-place keepalive on its own
     * clock ([lastIcmKeepaliveAt] — a keepalive is not a subscribe re-arm and must not delay genuine
     * re-discovery), the legacy re-arm fallback ([icmKeepaliveBroken]) on the re-arm clock.
     */
    private fun icmRelightDue(now: Long): Boolean =
        if (!icmKeepaliveBroken) {
            needsIcmRelight() && now - lastIcmKeepaliveAt > ICM_REARM_COOLDOWN_MS
        } else {
            needsIcmRelight() && now - lastRearmAt > ICM_REARM_COOLDOWN_MS
        }

    /** Fire the ICM relight: the in-place [icmKeepalive] (P4 default); the subscribe re-arm when latched broken. */
    private fun fireIcmRelight(now: Long) {
        if (!icmKeepaliveBroken) {
            lastIcmKeepaliveAt = now
            icmKeepalive()
        } else {
            lastRearmAt = now
            rearmSubscribe()
        }
    }

    /**
     * True while any link, in-flight handshake, or accept is live. Since P1 (concurrent serves) this is no
     * longer "the slot is taken" — it is the conservative guard for operations that must not disturb live
     * links (reattach, subscribe-wedge escalation, the after-serve reattach reschedule) and for the discovery
     * loop's do-nothing branch (no initiates/re-arms while anything is active; P2 revisits initiate gating).
     */
    private fun anyLinkActivity(): Boolean = synchronized(lock) { peers.isNotEmpty() || inFlight.isNotEmpty() || accepting > 0 }

    /**
     * Whether an on-demand NDP to [nodeId] is worth bringing up: a pending **bulk (attachment) transfer**
     * ([bulkWanted], via [expectBulkTransfer]) or digest divergence ([digestSyncWanted]). The bulk term
     * punches through both of [digestSyncWanted]'s gates on purpose — in the steady state a BLE-linked pair
     * is suppressed AND digest-converged, so its NDP never exists exactly when an image needs the
     * throughput. Consulted ONLY at the admission/teardown-decision sites ([driveSync], [initiateOwed],
     * [initiateOwedToReachable], [logState]); every *recovery* site reads the digest-pure gate instead —
     * see [digestSyncWanted] for why that split is load-bearing.
     */
    private fun syncWanted(
        nodeId: String,
        localVersion: Long,
    ): Boolean = bulkSyncWanted(nodeId) || digestSyncWanted(nodeId, localVersion)

    /** The bulk half of [syncWanted], under the same initiator hold as [digestSyncWanted]. */
    private fun bulkSyncWanted(nodeId: String): Boolean = bulkWanted.isWanted(nodeId) && !initiatorHeld(nodeId)

    /**
     * [syncWanted] with the initiator hold lifted — the daily probe's view ([NanInitiatorPolicy.probeDue]).
     * Read by [driveSync] alone: the recovery sites keep the held view, or the probe window would run the
     * watchdog's owed clock for a sync the hold is about to refuse again.
     */
    private fun syncWantedForProbe(
        nodeId: String,
        localVersion: Long,
    ): Boolean = bulkWanted.isWanted(nodeId) || (nodeId !in suppressed && digestTracker.reconcileWanted(nodeId, localVersion))

    /**
     * The digest-pure sync gate: [nodeId]'s advertised digest differs from ours
     * ([DigestTracker.reconcileWanted]) **and** it isn't already covered by a higher-preference plane. When the
     * composite reports [nodeId] as Bluetooth-linked (see [suppressDataPath]), BLE carries its data, so we spend
     * our single NDP elsewhere; the moment it drops off Bluetooth it's no longer [suppressed] and syncing to it
     * resumes — so a BLE-covered peer never counts as a sync we owe, which also stops the wedge watchdog from
     * ever firing on a "sync" the other plane is quietly handling.
     *
     * The recovery machinery reads THIS gate, never the bulk-aware [syncWanted]:
     * [NanSyncPolicy.anyReachableSyncOwed] (the wedge watchdog's owed signal — a pending image, whose bytes the
     * BLE fallback still carries, must never run the owed-episode clock toward the Tier-1 session cycle or the
     * Tier-2 process kill), [needsRediscovery] (subscribe re-arm churn is that session's own wedge trigger), [needsIcmRelight],
     * and [rediscoverDelayMs] (no fast-tick cadence for a mark whose wake is the [expectBulkTransfer]
     * healSignal poke).
     *
     * The **initiator hold** ([NanInitiatorPolicy], #78) is folded in here for the same reason as [suppressed]:
     * a peer we have stopped initiating to is not a sync we owe, so the watchdog never runs an owed episode
     * toward the Tier-2 kill for it, the loop never re-arms subscribe hunting a handle it will not dial, and
     * the tick relaxes. The tracker is consulted first so its convergence-observed reset still runs.
     */
    private fun digestSyncWanted(
        nodeId: String,
        localVersion: Long,
    ): Boolean = nodeId !in suppressed && digestTracker.reconcileWanted(nodeId, localVersion) && !initiatorHeld(nodeId)

    /**
     * Brings up an NDP to the next peer we're the initiator for (`localNodeId > nodeId`, the tie-break), not
     * yet linked, not backing off, and **sync-wanted** per [DigestTracker]. The handle comes from [discovered] —
     * a **subscribe**-session handle — because on these chipsets only a subscribe handle can initiate a data
     * path; a publish-session handle (learned from an inbound cue) makes `requestNetwork` silently time out
     * (verified on-device: every `initiating (via pub)` failed, every `(via sub)` linked). A peer whose
     * [discovered] handle went stale (it restarted) or that we only know via cues is refreshed by
     * [needsRediscovery] re-arming subscribe. Returns true if it started a handshake.
     */
    private fun driveSync(): Boolean {
        val localVersion = storeDigest.current()
        val now = SystemClock.elapsedRealtime()
        // A held role's daily probe sees the un-held gates for this one pass; [initiateTo] consumes the probe.
        val probe = initiatorPolicy.probeDue()
        val wanted: (String) -> Boolean =
            if (probe) ({ syncWantedForProbe(it, localVersion) }) else ({ syncWanted(it, localVersion) })
        val target =
            synchronized(lock) {
                discovered.entries
                    .filter { (nodeId, _) ->
                        localNodeId > nodeId && nodeId !in peers.keys &&
                            (retryAfter[nodeId]?.let { now >= it } ?: true) &&
                            wanted(nodeId)
                    }
                    // Custody (digest-divergence) syncs outrank bulk-only marks — a photo burst must never
                    // starve store-and-forward anti-entropy — and within a class the least-recently-attempted
                    // peer goes first, so HashMap iteration order can't starve one under contention.
                    .minWithOrNull(
                        compareBy(
                            { if (digestSyncWanted(it.key, localVersion) || probe) 0 else 1 },
                            { lastInitiateAttemptAt[it.key] ?: 0L },
                        ),
                    )?.let { it.key to it.value }
            } ?: return false
        if (probe) Log.i(TAG, "daily initiator probe: one initiate to ${target.first} under the hold")
        initiateTo(target.first, target.second.advert, target.second.peerHandle)
        return true
    }

    /**
     * True when we should re-fire discovery: either we're blind (no cue targets at all), or a peer we hear
     * cues from (so it's alive and nearby) is sync-wanted and we're its initiator, yet we can't initiate to it
     * — we hold no fresh **subscribe** handle for it (never discovered, or it restarted and our handle keeps
     * failing, so it's backing off). Re-arming subscribe ([rearmSubscribe] clears + refetches [discovered])
     * gets a fresh handle. Rate-limited by [REARM_COOLDOWN_MS] so a truly-gone peer — whose cue target
     * [pruneAbsentPeers] reaps after [REACHABLE_LINGER_MS] of silence — can't spin subscribe into its wedge
     * state past that bounded window.
     */
    private fun needsRediscovery(): Boolean =
        // Blind (no cue targets → empty snapshot → true), or a peer we hear cues from (alive, nearby), want to
        // sync, are the initiator for, yet hold no subscribe handle for. digest-pure: bulk marks must not churn
        // subscribe. includeDiscovered reads `discovered` under lock, matching the old predicate's footprint.
        NanSyncPolicy.needsRediscovery(snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = true))

    /**
     * True when we should re-arm subscribe purely to **re-light Instant Communication Mode** as a *responder*: a
     * sync is owed to a cue-reachable peer we are NOT the initiator for (`localNodeId < nodeId`), so [driveSync]
     * can never bring the NDP up from our side — only the peer can reach us, and only while our NAN radio stays
     * dense (ICM lit). [needsRediscovery] handles the initiator side (and relights ICM as a side effect), but the
     * smallest node has no initiator peers, so without this its ICM never relights and it receives only in the
     * brief post-attach/post-serve windows. Demand-gated: goes false once the peer converges ([syncWanted] →
     * [DigestTracker.reconcileWanted] drops it) or it becomes BLE-suppressed, so a settled mesh does no re-arm.
     */
    private fun needsIcmRelight(): Boolean =
        // digest-pure: a smaller-side bulk mark stays inert.
        NanSyncPolicy.needsIcmRelight(snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = false))

    private fun rediscoverDelayMs(): Long {
        // Detached (no Aware session): retry attach() promptly. A reattach's inline attach() often can't
        // re-enable NAN immediately after tearing the session down (the chipset needs a beat; isAvailable is
        // transiently false and closing our own session fires no availability broadcast), so recovery falls to
        // the loop's `session == null -> attach()`. Without this, a pure-responder node (nothing sync-wanted to
        // *initiate*) would wait a full REDISCOVER_IDLE_MS with no responder — the 2-minute post-serve wedge.
        // ...but no sooner than the attach backoff allows, so a radio that keeps refusing lets the loop sleep
        // rather than waking on the fast cadence only for attach() to turn it away. Once the budget is spent
        // there is nothing left to wake for at all, so fall back to the plain idle cadence.
        if (session == null && attachAbandoned) return REDISCOVER_IDLE_MS
        if (session == null) return maxOf(ATTACH_RETRY_MS, attachRetryAfter - SystemClock.elapsedRealtime())
        // Nobody to cue: hunt aggressively for a while, then — screen off, on battery — relax to the duty cycle
        // ([NanLonelyPolicy]; the power factor is inside the cadence, so it returns before the ×2 below).
        if (cueTarget.isEmpty()) return lonelyCadence(SystemClock.elapsedRealtime()).tickMs
        // Tick soon while a sync is still owed (a sync-wanted peer we initiate to, maybe backed off / busy)
        // so we retry promptly; otherwise relax (a cue with a new epoch wakes us via healSignal). Doubled
        // when screen-off on battery.
        val localVersion = storeDigest.current()
        val base =
            when {
                // an initiator peer is digest-owed → rediscover / recover fast. Digest-pure: a bulk mark's wake
                // is the expectBulkTransfer healSignal poke, not a sustained fast-tick cadence.
                NanSyncPolicy.anyInitiatorDigestOwed(
                    snapshotPeerFacts(SystemClock.elapsedRealtime(), localVersion, includeDiscovered = false),
                ) -> SYNC_RETRY_IDLE_MS

                // A sync owed to a peer we only respond to → tick around the ICM keepalive cadence so the loop can
                // relight ICM (needsIcmRelight) before the ~30s auto-disable, instead of sleeping REDISCOVER_IDLE_MS.
                instantSupported && needsIcmRelight() -> ICM_REARM_COOLDOWN_MS

                else -> REDISCOVER_IDLE_MS
            }
        val power = powerState.state.value
        return if (power.interactive || power.charging) base else base * 2
    }

    /**
     * The loop's cadence while it has nobody to cue — and the one writer of [lonelySince], observed here rather
     * than at the sites that remove a cue target ([NanLonelyPolicy.lonelySince]). Loop thread only.
     */
    private fun lonelyCadence(now: Long): NanLonelyPolicy.Cadence {
        lonelySince = NanLonelyPolicy.lonelySince(cueTarget.isEmpty(), lonelySince, now)
        val cadence = NanLonelyPolicy.cadence(powerState.state.value, lonelyForMs(now), REDISCOVER_LONELY_MS, REARM_COOLDOWN_MS)
        if (cadence.relaxed != lonelyRelaxedLogged) {
            lonelyRelaxedLogged = cadence.relaxed
            if (cadence.relaxed) {
                Log.i(TAG, "lonely: relaxed discovery to ${cadence.tickMs}ms (alone ${lonelyForMs(now)}ms)")
            } else {
                Log.i(TAG, "lonely: aggressive again")
            }
        }
        return cadence
    }

    private fun lonelyForMs(now: Long): Long = lonelySince.let { if (it == 0L) 0L else now - it }

    /**
     * How long since the last subscribe re-arm before the loop may re-arm again: the relaxed lonely cooldown only
     * with nobody to cue and no [heal] token pending, else [REARM_COOLDOWN_MS] — so a heal buys exactly one
     * aggressive re-arm, and any peer at all restores the old rule untouched.
     */
    private fun rearmCooldownMs(
        now: Long,
        healDemand: Boolean,
    ): Long = if (cueTarget.isEmpty() && !healDemand) lonelyCadence(now).rearmCooldownMs else REARM_COOLDOWN_MS

    private val subscribeCallback =
        object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                subscribing.set(false)
                subscribeSession = s
                clearForegroundOnly()
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>,
            ) {
                onDiscovered(peerHandle, serviceSpecificInfo)
            }

            // onServiceLost was added to DiscoverySessionCallback in API 33; the framework never dispatches it
            // on 29-32 (the base class lacks it), so this override is simply dormant there. @RequiresApi keeps
            // lint honest without gating the whole transport.
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onServiceLost(
                peerHandle: PeerHandle,
                reason: Int,
            ) {
                synchronized(lock) { discovered.entries.removeAll { it.value.peerHandle == peerHandle } }
            }

            override fun onSessionConfigFailed() {
                subscribing.set(false)
                subscribeSession = null
                Log.w(TAG, "subscribe config failed")
                // A subscribe stuck in onSessionConfigFailed is wedged — re-subscribing won't clear it, but a
                // full re-attach usually does. Escalate, rate-limited, and only when no live NDP would be lost.
                val now = SystemClock.elapsedRealtime()
                if (!anyLinkActivity() && now - lastReattachAt > REATTACH_COOLDOWN_MS) {
                    lastReattachAt = now
                    handler.postDelayed({ reattach() }, REATTACH_DELAY_MS)
                }
            }

            override fun onMessageReceived(
                peerHandle: PeerHandle,
                message: ByteArray,
            ) {
                onCueReceived(peerHandle, message, subscribeSession)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                metrics.onNanMsgAcked()
            }

            override fun onMessageSendFailed(messageId: Int) {
                metrics.onNanMsgSendFailed()
            }
        }

    /** Record a discovered peer, note it reachable, and cue it (announce our epoch); it cues back. */
    private fun onDiscovered(
        peerHandle: PeerHandle,
        ssi: ByteArray,
    ) {
        if (ssi.isEmpty()) return
        val advert = Protocol.parse(ssi.decodeToString())
        val peerNodeId = advert.nodeId
        if (peerNodeId == localNodeId) return
        val ssiText = ssi.decodeToString()
        Log.i(TAG, "discovered $peerNodeId ssi=$ssiText") // TEMP diag: one line per discovery indication
        synchronized(lock) { discovered[peerNodeId] = DiscoveredPeer(advert, peerHandle) }
        // Passive cue (P4): the publisher's SSI carries its digest as a trailing |d<version> segment, and a
        // changed SSI re-fires this callback — so a re-discovery IS a cue, with zero unicast messages. Same
        // deferred wake as onCueReceived (let a racing fast-fanout converge the digest before an NDP fires).
        NanCueCodec.parseSsiDigest(ssiText)?.let { v ->
            if (digestTracker.onCue(peerNodeId, v)) {
                scope.launch {
                    delay(CUE_SETTLE_MS)
                    healSignal.trySend(Unit)
                }
            }
        }
        subscribeSession?.let {
            cueTarget[peerNodeId] = CueTarget(peerHandle, it) // handle valid on subscribe
            hopTable.learn(HandleKey(it, peerHandle), peerNodeId)
        }
        noteReachable(Peer(peerNodeId, advert.protoVersion, advert.capabilities))
        sendCue(peerNodeId)
        healSignal.trySend(Unit)
    }

    // --- Coordination plane (cues over Wi-Fi Aware messages; no data path) ---

    /** A cue arrived from a peer: learn its handle+epoch, note it reachable, and wake the loop if diverged. */
    private fun onCueReceived(
        handle: PeerHandle,
        message: ByteArray,
        session: DiscoverySession?,
    ) {
        val sess = session ?: return
        if (message.isEmpty() || dispatchFramed(handle, sess, message)) return
        val cue = NanCueCodec.parseCue(message) ?: return
        if (cue.nodeId == localNodeId) return
        val firstContact = cueTarget.put(cue.nodeId, CueTarget(handle, sess)) == null
        hopTable.learn(HandleKey(sess, handle), cue.nodeId)
        noteReachable(Peer(cue.nodeId))
        // The peer's digest moved, so a sync *may* be wanted — but wake the loop only after a short settle. A
        // broadcast fast-fanout cues its new epoch and pushes the frame back-to-back; if we reacted to the cue
        // immediately we'd bring up a redundant NDP that churns the coordination plane (dropping the receipts
        // riding it) just as the fast-frame is about to arrive and converge our digest (DigestTracker's
        // identical-digest skip → no sync). Deferring lets the fast path win the race; the NDP still fires after
        // the settle for data the fast-fanout genuinely missed, or a message too big to fan out.
        if (digestTracker.onCue(cue.nodeId, cue.version)) {
            scope.launch {
                delay(CUE_SETTLE_MS)
                healSignal.trySend(Unit)
            }
        }
        // Cue back exactly once on first contact so the peer learns our epoch (a pure responder whose own
        // subscribe is down bootstraps its reverse-direction handle here). Not a reply to every cue → no
        // ping-pong; steady state is covered by discovery, epoch-change, and heartbeat cues. And wake the loop:
        // lonely → not lonely is a state change even when the digests already agree, and a loop asleep on the
        // relaxed lonely tick must not sleep it out with a peer in range.
        if (firstContact) {
            sendCue(cue.nodeId)
            healSignal.trySend(Unit)
        }
    }

    // storeDigest.current(), not version.value: the digest folds live custody ids only, and expiry moves with
    // time, so an outbound cue must fold any TTL boundary crossed since the last read — the 30 s heartbeat's
    // cueAll() is what bounds boundary staleness on an otherwise-idle node, with no expiry timer for Doze to
    // defer. The fold's version change then fires the digest-change collector (re-cue + SSI republish) by itself.
    private fun sendCue(nodeId: String) {
        val target = cueTarget[nodeId] ?: return
        val cue = NanCueCodec.encodeCue(localNodeId, storeDigest.current())
        runCatching { target.session.sendMessage(target.handle, msgSeq.getAndIncrement(), cue) }
            .onFailure { cueTarget.remove(nodeId) } // stale handle/session; refreshed on next discover/receive
    }

    private fun cueAll() = cueTarget.keys.toList().forEach { sendCue(it) }

    /**
     * Fast path (see [MeshTransport.fastFanout]): fan a small broadcast frame out to every neighbor over the
     * coordination plane — one Wi-Fi Aware message each, **no data path** — so it lands near-instantly instead
     * of waiting for a cue-driven pairwise NDP sync. The encoding is chosen **per peer** ([sendFast]): a peer
     * advertising [Protocol.CAP_FAST_COMPACT] gets the compact `0x03` framing (`mesh/link/FastFrameCodec` —
     * deflated, and split across ≤ 3 `0x04` fragments when one message won't hold it), everyone else the
     * legacy [MSG_FRAME_TAG] framing. A frame no eligible encoding can carry is skipped for that peer
     * (`fastTooBig`) and rides the normal data-path flood + store-and-forward instead. Best-effort by design
     * (the message channel can drop, like a cue); the reliable copy still arrives via flood/custody and is
     * deduped.
     */
    override fun fastFanout(wire: WireEnvelope) {
        if (!hasHardware) return
        val enc = FastEncodings(wire)
        val targets = cueTarget.keys.toList()
        Log.i(TAG, "fast-fanout ${enc.diag()} → ${targets.size} peers") // TEMP diag
        targets.forEach { nodeId -> sendFast(enc, nodeId) }
    }

    /**
     * Targeted sibling of [fastFanout] (see [MeshTransport.fastSend]): send a small point-to-point frame to one
     * peer over the coordination plane, **no data path** — e.g. a broadcast/group delivery receipt straight back
     * to the message's author, so the tick works even when the message was delivered by a fast-fanout with no
     * live NDP. Best-effort: no-op if [to] isn't a current cue target or no eligible encoding fits ([sendFast]).
     */
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (!hasHardware) return
        if (!cueTarget.containsKey(to.nodeId)) return // not coordination-plane-reachable → best-effort skip
        val enc = FastEncodings(wire)
        Log.i(TAG, "fast-send ${enc.diag()} → ${to.nodeId}") // TEMP diag
        sendFast(enc, to.nodeId)
    }

    /**
     * The candidate on-air encodings of one fast frame, each built at most once per [fastFanout]/[fastSend]
     * call however many peers it reaches. A fragmented frame takes ONE [fragSeq] id, shared by every
     * target, so a receiver hearing the same frame twice keys the same reassembly entry.
     */
    private inner class FastEncodings(
        private val wire: WireEnvelope,
    ) {
        /** Legacy [MSG_FRAME_TAG] framing — every build reads it — or null past the message cap. */
        val legacy: List<ByteArray>? by lazy {
            val bytes = WireCodec.encodeWire(wire)
            if (bytes.size + 1 > coordMsgMax) return@lazy null
            listOf(
                ByteArray(bytes.size + 1).also {
                    it[0] = MSG_FRAME_TAG
                    bytes.copyInto(it, 1)
                },
            )
        }

        /** Compact framing: one `0x03` message, `0x04` fragments, or null (unrepresentable / too big). */
        val compact: List<ByteArray>? by lazy {
            val one = FastFrameCodec.encodeCompact(wire) ?: return@lazy null
            if (one.size <= coordMsgMax) {
                listOf(one)
            } else {
                FastFrameCodec.fragment(one, coordMsgMax, fragSeq.getAndIncrement() and FRAG_ID_MASK)
            }
        }

        /**
         * Transcoded framing (ADR 060): the smaller of one `0x05` and one `0x03` message, `0x04` fragments past the
         * cap, or null (unrepresentable / too big). Only a `Protocol.CAP_FRAME_TRANSCODE` peer is sent it.
         */
        val transcoded: List<ByteArray>? by lazy {
            val best = FastFrameCodec.encodeBest(wire, transcode = true) ?: return@lazy null
            if (best.transcodeRefused) metrics.onTranscodeFallback()
            if (best.frame.size <= coordMsgMax) {
                listOf(best.frame)
            } else {
                FastFrameCodec.fragment(best.frame, coordMsgMax, fragSeq.getAndIncrement() and FRAG_ID_MASK)
            }
        }

        /** One grep-stable diag fragment: both encodings' on-air sizes and the compact part count. */
        fun diag(): String {
            val legacyBytes = legacy?.first()?.size ?: -1
            return "legacy=${legacyBytes}B compact=${compact?.sumOf { it.size } ?: -1}B parts=${compact?.size ?: 0}"
        }
    }

    /**
     * Sends [enc]'s best encoding for what [nodeId] advertised: compact toward a [Protocol.CAP_FAST_COMPACT]
     * peer (capabilities join via [reachablePeers] — a cue-only peer reads 0 and stays legacy; the caller's
     * [Peer] argument is address-only and never a capability source), legacy toward the rest. Fragment parts
     * go out consecutively per peer, so firmware tx-queue pressure loses whole later frames rather than
     * part 2 of every peer's copy (the ~8-deep aware tx queue is not otherwise handled — this plane is
     * best-effort).
     */
    private fun sendFast(
        enc: FastEncodings,
        nodeId: String,
    ) {
        val target = cueTarget[nodeId] ?: return
        val caps = reachablePeers[nodeId]?.capabilities ?: 0L
        val choice = FastFramePick.choose(caps, { enc.transcoded }, { enc.compact }, { enc.legacy })
        if (choice == null) {
            metrics.onFastTooBig()
            return
        }
        choice.messages.forEach { msg ->
            runCatching { target.session.sendMessage(target.handle, msgSeq.getAndIncrement(), msg) }
                .onFailure {
                    cueTarget.remove(nodeId) // stale handle/session; refreshed on next discover/receive
                    return
                }
        }
        FastFramePick.record(choice, metrics)
    }

    /**
     * Routes a coordination-plane message whose first byte is a frame tag; false means it is no framed
     * message (a printable first byte — a cue) and the caller should parse it as one. An unknown byte in
     * the reserved non-printable tag space 0x00..0x1F is counted and consumed — the pre-compact builds'
     * silent drop made "peer speaks something newer" invisible in the field.
     */
    private fun dispatchFramed(
        handle: PeerHandle,
        sess: DiscoverySession,
        message: ByteArray,
    ): Boolean {
        val from = HandleKey(sess, handle)
        when (message[0]) {
            MSG_FRAME_TAG -> {
                onFastFrame(from, message)
            }

            FastFrameCodec.TAG_COMPACT, FastFrameCodec.TAG_TRANSCODED -> {
                onCompactFrame(from, message)
            }

            FastFrameCodec.TAG_FRAG -> {
                onFragment(from, message)
            }

            else -> {
                if (message[0].toInt() !in 0x00..0x1F) return false
                metrics.onFastDropped(FastPathDrop.UNKNOWN_TAG)
            }
        }
        return true
    }

    /**
     * A broadcast frame arrived over the coordination plane in the legacy [MSG_FRAME_TAG] framing: decode
     * and [emitFastWire] it. Malformed bytes stay a silent drop, exactly as before the compact tags.
     */
    private fun onFastFrame(
        from: HandleKey,
        message: ByteArray,
    ) {
        val wire = WireCodec.decodeWire(message.copyOfRange(1, message.size)) ?: return
        emitFastWire(wire, via = "legacy", hop = hopTable.hopFor(from))
    }

    /** A `0x03` or `0x05` frame arrived: decode (inflating / rebuilding as its tag says) and [emitFastWire] it. */
    private fun onCompactFrame(
        from: HandleKey,
        message: ByteArray,
    ) {
        val transcoded = message[0] == FastFrameCodec.TAG_TRANSCODED
        val wire = FastFrameCodec.decodeCompact(message)
        if (wire == null) {
            metrics.onFastDropped(if (transcoded) FastPathDrop.TRANSCODE_FAILED else FastPathDrop.DECODE_FAILED)
            return
        }
        emitFastWire(wire, via = if (transcoded) "transcoded" else "compact", hop = hopTable.hopFor(from))
    }

    /**
     * A [FastFrameCodec.TAG_FRAG] part arrived: feed the [reassembler]; a completed set must itself be a
     * tagged compact frame (anti-recursion — a fragment can never carry another fragment) and re-enters
     * through [onCompactFrame]. Incomplete sets just wait; the store's timeout/capacity drops are counted
     * via its onDrop hook.
     */
    private fun onFragment(
        from: HandleKey,
        message: ByteArray,
    ) {
        val frag = FastFrameCodec.parseFragment(message)
        if (frag == null) {
            metrics.onFastDropped(FastPathDrop.DECODE_FAILED)
            return
        }
        val assembled = reassembler.accept(from, frag) ?: return
        if (assembled.isEmpty() || !FastFrameCodec.isFrameTag(assembled[0])) {
            metrics.onFastDropped(FastPathDrop.DECODE_FAILED)
            return
        }
        metrics.onFastReassembled()
        onCompactFrame(from, assembled)
    }

    /**
     * Injects a fast-path [wire] into the normal inbound path exactly like a data-path frame, so the router
     * dedups, relays, and delivers it with no NDP. A later flood/custody copy is dropped by the receiver's
     * SeenSet, so a dropped fast frame self-heals — the fast path is a latency win layered over the reliable one.
     *
     * [hop] is the neighbor that delivered the message ([hopTable]), or null when its handle has not been
     * named by a cue/advert yet. The sighting and the frame's `fromNodeId` are the hop, never the envelope's
     * `senderId`: that is the **author**, which a re-fanned custody frame names as a peer that may be miles
     * away (ADR 061). With no hop there is no sighting, and `fromNodeId` falls back to the author — the
     * split horizon then excludes a node that is at worst not a neighbor, which is harmless.
     */
    private fun emitFastWire(
        wire: WireEnvelope,
        via: String,
        hop: String?,
    ) {
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return
        hop?.let { noteReachable(Peer(it)) }
        if (envelope.senderId == localNodeId) return // our own frame echoed back — ignore
        Log.i(TAG, "fast-frame from ${envelope.senderId} id=${envelope.id} via=$via hop=${hop ?: "?"}") // TEMP diag
        _inbound.tryEmit(InboundFrame(wire, envelope, hop ?: envelope.senderId))
    }

    // --- Client side (initiator) ---

    private fun initiateTo(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        peerHandle: PeerHandle,
    ) {
        val sub = subscribeSession ?: return
        if (!beginConnect(peerNodeId)) return
        synchronized(lock) { lastInitiateAttemptAt[peerNodeId] = SystemClock.elapsedRealtime() } // driveSync's LRU rotation key
        Log.i(TAG, "initiating to $peerNodeId")
        // The handle is a subscribe-session handle (from discovery); only that can initiate an NDP here.
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, peerHandle).setPmk(PMK).build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
        val startedAt = SystemClock.elapsedRealtime()
        val done = AtomicBoolean(false)
        lateinit var cb: ConnectivityManager.NetworkCallback
        cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val ip = info.peerIpv6Addr ?: return
                    if (!done.compareAndSet(false, true)) return
                    val port = info.port
                    scope.launch(Dispatchers.IO) { connectAndRegister(peerNodeId, advert, network, ip, port, cb) }
                }

                override fun onUnavailable() {
                    // Timeout overload fired: the NDP never came up. Release so the interface + inFlight slot
                    // are freed (without this the request leaks and eventually exhausts NAN interfaces). A *fast*
                    // onUnavailable (well under the handshake timeout) is the stale-handle signature — the peer
                    // restarted, so this subscribe handle is dead — which flags a re-discovery; a slow one is just
                    // the peer being busy on its one NDI (contention), which must NOT churn subscribe.
                    if (done.compareAndSet(false, true)) {
                        val staleHandle = SystemClock.elapsedRealtime() - startedAt < FAST_FAIL_MS
                        failConnect(peerNodeId, cb, staleHandle)
                    }
                }

                override fun onLost(network: Network) {
                    teardownPeer(peerNodeId)
                }
            }
        val requested =
            runCatching {
                connectivity.requestNetwork(request, cb, handler, HANDSHAKE_TIMEOUT_MS)
            }.isSuccess
        if (!requested) {
            failConnect(peerNodeId, cb, staleHandle = true) // synchronous reject → bad handle/session
            Log.w(TAG, "client requestNetwork failed for $peerNodeId")
            return
        }
        // An initiate of ours is in the air: the firmware's negotiation it starts is what a Wi-Fi drop in the
        // next two minutes is charged to (#78). Under the hold this is the daily probe — re-journal its time.
        if (initiatorPolicy.noteInitiate()) journalInitiatorLatch(NanInitiatorLatch(giveUpStamp, System.currentTimeMillis()))
        // Watchdog against a *half-open* NDP: onCapabilitiesChanged can fire with the network "available" but
        // no peer IPv6 yet, so we return and wait for a later callback — but if the IPv6 never arrives, the
        // framework considers the request fulfilled (so its onUnavailable timeout never fires) while the NDP
        // pins the single NDI forever (verified: a zombie initiator ndpInfo with peerIpv6=null holding
        // aware_data0, after which every init times out ~6 s for lack of a free interface). If we haven't
        // completed shortly past the handshake timeout, force-clean so the callback unregisters and frees the
        // NDI. The `done` CAS makes this a no-op once a real link came up.
        handler.postDelayed({
            if (done.compareAndSet(false, true)) {
                Log.w(TAG, "initiation watchdog firing for $peerNodeId (half-open NDP never yielded a peer IPv6)")
                failConnect(peerNodeId, cb, staleHandle = false)
            }
        }, HANDSHAKE_TIMEOUT_MS + WATCHDOG_MARGIN_MS)
    }

    private fun connectAndRegister(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        network: Network,
        ip: java.net.Inet6Address,
        port: Int,
        cb: ConnectivityManager.NetworkCallback,
    ) {
        val socket =
            runCatching { network.socketFactory.createSocket(ip, port) }.getOrNull()
                ?: return failConnect(peerNodeId, cb, staleHandle = false) // NDP came up; socket issue, not a handle problem
        val link = NetSocketLink(socket)
        // Two-way HELLO: send our identity first, then read the responder's reply and require it to match the
        // peer we dialed — so the link's identity is confirmed by the peer over the socket, not taken from the
        // (unauthenticated) discovery advert. Writes-then-reads while the responder reads-then-replies, so
        // neither blocks. Bound the reply read with soTimeout so a stalled responder can't pin the NDI.
        val sent = runCatching { LinkHandshake.writeHello(link.output, localNodeId) }.isSuccess
        if (!sent) {
            link.close()
            return failConnect(peerNodeId, cb, staleHandle = false)
        }
        runCatching { socket.soTimeout = ACCEPT_HELLO_TIMEOUT_MS }
        val reply = runCatching { LinkHandshake.readHello(link.input) }.getOrNull()
        runCatching { socket.soTimeout = 0 } // back to blocking reads for the normal loop
        if (reply == null || reply.nodeId != peerNodeId) {
            link.close()
            return failConnect(peerNodeId, cb, staleHandle = false)
        }
        registerConn(peerNodeId, advert, link, cb)
    }

    // --- Server side (persistent accept-any responder) ---

    private fun startResponder() {
        val pub = publishSession ?: return
        // Idempotent, single-owner: at most one responder requestNetwork per publish session. If one is
        // already registered, do nothing — issuing a second whose callback overwrites [responderCallback]
        // orphans the first (the leaked role=1 state=104 request that pins the single NDI until process death).
        // A fresh responder is created only after reattach()/stopResponder() has unregistered + nulled it.
        if (responderCallback != null) return
        stopResponder() // clear any orphaned socket/job (responderCallback is null here)
        val ss =
            runCatching { ServerSocket(0) }.getOrElse {
                Log.w(TAG, "responder ServerSocket bind failed", it)
                return
            }
        responderSocket = ss
        val specifier =
            WifiAwareNetworkSpecifier
                .Builder(pub)
                .setPmk(PMK)
                .setPort(ss.localPort)
                .build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // The request was fulfilled — an NDP landed on it — which is the one proof the responder works,
                    // and so the one refund of the re-file budget ([NanResponderPolicy]): a fresh session or an
                    // availability edge would both be produced by the escalation's own cycle.
                    responderRefusals = 0
                    responderCycles = 0
                    if (acceptJob?.isActive != true) acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(ss) }
                }

                override fun onLost(network: Network) {
                    // The shared responder network dropped — a served client left. Its server link ends via the
                    // read/write loop → teardownPeer → the after-serve reattach() rebuilds a fresh responder; a
                    // radio flap is handled by onAwareSessionTerminated / the availability receiver. Do NOT re-arm
                    // startResponder() here: a re-arm racing the fresh onPublishStarted is exactly what used to
                    // orphan a responder requestNetwork. Log only.
                    Log.i(TAG, "responder network lost")
                }

                override fun onUnavailable() {
                    // The framework declares an accept-any responder request unfulfillable — and silently
                    // REMOVES it from its cache — when an inbound NDP request arrives while this node's own
                    // initiator link holds the one NDI (onDataPathRequest → selectInterfaceForRequest → null →
                    // cache remove + letAppKnowThatRequestsAreUnavailable; field-confirmed 2026-07-04, P0
                    // session). Without this hook the app keeps believing its dead responder is listening and
                    // the node can never serve again until the next full reattach. Re-file a fresh request —
                    // the peer whose inbound was refused backs off and retries against it. Generation-guarded:
                    // never re-file over a newer responder. The re-file is paced and counted by
                    // [refileResponder]: a verdict with nothing of ours on the interface is about the request
                    // itself, and answering it at once made 174 files in 130 ms on the Pixel 3 (work item #77).
                    val self: ConnectivityManager.NetworkCallback = this
                    onHandler {
                        if (responderCallback !== self) return@onHandler
                        stopResponder()
                        refileResponder(pub)
                    }
                }
            }
        responderCallback = cb // tracked before requestNetwork so it is always unregisterable
        val filed =
            runCatching { connectivity.requestNetwork(request, cb, handler) }
                .onFailure {
                    Log.w(TAG, "responder requestNetwork failed", it)
                    stopResponder()
                }.isSuccess
        // Note "listening" ≠ "serving": the framework can still refuse/queue the request (e.g. behind a
        // TERMINATING ghost of a previous responder — dumpsys shows requests=0 at the aware NetworkFactory).
        if (filed) {
            responderPinsNdi = false // a fresh (never-served) request idles interface-less
            Log.i(TAG, "responder listening on port ${ss.localPort}")
            if (NanFaultInjector.shouldRefuseResponder()) {
                // Debug builds only, armed only by `…debug.NANREFUSE` — see [NanFaultInjector]. The framework
                // answered the Pixel 3's every fresh request in ~10 ms; deliver the same verdict on the same clock.
                handler.postDelayed({ cb.onUnavailable() }, INJECTED_REFUSAL_DELAY_MS)
            }
        }
    }

    /**
     * The framework has just declared the responder request on [pub] unfulfillable and [stopResponder] has
     * released it. Decide, per [NanResponderPolicy], when the next one is filed — or whether to stop filing and
     * cycle the session instead (work item #77). Runs on the [handler] thread.
     *
     * A verdict while a link, handshake or accept of ours is live — or inside the post-link settle, when the
     * framework may still hold the interface — is the P0-documented knock refusal: the interface *was* busy,
     * so it says nothing about the request. It is re-filed after the floor and not counted. A verdict with the
     * interface free is about the request, and those are counted: the re-file backs off along the curve, and at
     * [NanResponderPolicy.CYCLE_AT_STREAK] in a row the request is given up on for [sessionCycleWithSettle] —
     * Tier 1's cure for a wedged responder — under the reattach cooldown every other session cycle honours and
     * the per-episode cap [NanResponderPolicy.MAX_CYCLES]. Once the cap is spent the re-file simply keeps
     * following the curve to its one-minute saturation; the watchdog's own tiers remain behind it.
     *
     * The delayed file is guarded on the publish session it was scheduled for and on no responder having been
     * filed in the meantime ([recycleResponder], a fresh `onPublishStarted`), and [stopResponder] drops it, so a
     * teardown never leaves a stale file behind.
     */
    private fun refileResponder(pub: PublishDiscoverySession) {
        val now = SystemClock.elapsedRealtime()
        val contended = anyLinkActivity() || now < lastLinkEndedAt + SETTLE_MS
        if (!contended) responderRefusals++
        val streak = responderRefusals
        if (!contended &&
            NanResponderPolicy.cycleSession(streak, responderCycles) &&
            now - lastReattachAt > REATTACH_COOLDOWN_MS
        ) {
            responderCycles++
            lastReattachAt = now
            Log.w(
                TAG,
                "responder request declared unfulfillable $streak times in a row with no link up — cycling the " +
                    "session ($responderCycles/${NanResponderPolicy.MAX_CYCLES} this episode)",
            )
            sessionCycleWithSettle()
            return
        }
        val delay = if (contended) NanResponderPolicy.contendedRefileMs() else NanResponderPolicy.refileDelayMs(streak)
        if (contended) {
            Log.w(TAG, "responder request declared unfulfillable (inbound NDP during our initiate) — re-filing in ${delay}ms")
        } else {
            Log.w(TAG, "responder request declared unfulfillable with no link up ($streak in a row) — re-filing in ${delay}ms")
        }
        val refile =
            Runnable {
                responderRefile = null
                if (publishSession === pub && responderCallback == null) startResponder()
            }
        responderRefile = refile
        handler.postDelayed(refile, delay)
    }

    @Suppress("LoopWithTooManyJumpStatements") // accept → admit-or-close is naturally break+continue
    private fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive && !ss.isClosed) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            Log.i(TAG, "responder accept() returned a socket")
            if (!beginAccept()) { // single NDI busy (a link/handshake is up, or SETTLE not elapsed)
                runCatching { socket.close() }
                continue
            }
            scope.launch(Dispatchers.IO) { handleAcceptedSocket(socket) }
        }
    }

    /** A client connected to our accept-any responder: read its identity (HELLO), then register the link. */
    private fun handleAcceptedSocket(socket: Socket) {
        try {
            // The wildcard ServerSocket(0) is reachable from ANY network the device sits on (LAN, VPN), not
            // just the aware NDI; NAN peers always arrive from an IPv6 link-local, so reject everything else
            // before reading a byte (the app-layer signatures gate content, but a LAN connector shouldn't get
            // to speak the framing at all).
            val remote = socket.inetAddress
            if (remote !is java.net.Inet6Address || !remote.isLinkLocalAddress) {
                Log.w(TAG, "rejecting responder connection from non-link-local $remote")
                runCatching { socket.close() }
                return
            }
            val link = NetSocketLink(socket)
            runCatching { socket.soTimeout = ACCEPT_HELLO_TIMEOUT_MS } // bound the identity read so a stall can't pin the slot
            // Reads the HELLO from the cached buffered stream that the FramedLink read loop then reuses.
            val advert = LinkHandshake.readHello(link.input)
            if (advert == null) {
                link.close()
                return
            }
            val clientNodeId = advert.nodeId
            // Enforce the tie-break server side (we must be the smaller id) and don't double-link.
            if (clientNodeId == localNodeId || localNodeId >= clientNodeId || clientNodeId in peers.keys) {
                link.close()
                return
            }
            // Reply with our identity so the initiator can confirm it reached the intended peer (two-way
            // HELLO; the initiator validates this before registering). We read first, then reply — no block.
            if (runCatching { LinkHandshake.replyHello(link.output, localNodeId) }.isFailure) {
                link.close()
                return
            }
            runCatching { socket.soTimeout = 0 } // back to blocking reads for the normal loop
            Log.i(TAG, "accepted client $clientNodeId")
            registerConn(clientNodeId, advert, link, callback = null) // shared responder: no per-peer callback
        } finally {
            endAccept()
        }
    }

    private fun stopResponder() {
        responderRefile?.let { handler.removeCallbacks(it) } // a teardown supersedes a re-file still waiting its turn
        responderRefile = null
        acceptJob?.cancel()
        acceptJob = null
        responderCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        responderCallback = null
        responderSocket?.let { runCatching { it.close() } } // unblocks acceptLoop's accept()
        responderSocket = null
    }

    // --- Link registration / teardown ---

    private fun registerConn(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        link: LinkSocket,
        callback: ConnectivityManager.NetworkCallback?,
    ) {
        val framed =
            FramedLink(
                nodeId = peerNodeId,
                peer = Peer(peerNodeId, advert.protoVersion, advert.capabilities),
                socket = link,
                scope = scope,
                cacheDir = appContext.cacheDir,
                metrics = metrics,
                callbacks = linkCallbacks,
                now = SystemClock::elapsedRealtime, // share the supervisor's clock for quiescence
                log = { msg -> Log.d(TAG, msg) },
            )
        val conn = NanLink(framed, callback)
        lastLinkOrAcceptAt = SystemClock.elapsedRealtime() // the data plane produced a link (either role) — clears the wedge watchdog
        val prev = peers.put(peerNodeId, conn)
        prev?.close() // a stale link to the same peer (shouldn't happen, but never leak it)
        if (callback == null) {
            // A serve landed on the standing responder request: it now pins the NDI even after its NDPs end
            // (docs/NAN_CONCURRENCY_REAUDIT.md §2) until recycled, and the concurrent-serve count feeds the
            // P1 observability peak.
            responderPinsNdi = true
            metrics.onNanServes(peers.values.count { !it.isInitiator }.toLong())
        }
        synchronized(lock) {
            inFlight.remove(peerNodeId)
            retryAfter.remove(peerNodeId)
            failStreak.remove(peerNodeId) // link formed → reset the fast-fail streak
        }
        // An initiator link is the one thing that refunds the initiator failsafe: the NDP path works on this
        // hardware, so every Wi-Fi coincidence so far was noise (#78, the ADR 055 rule).
        if (callback != null && initiatorPolicy.noteInitiatorLink()) {
            Log.w(TAG, "initiator link formed under the hold — releasing it; this phone initiates again")
            _initiatorHeld.value = false
            journalInitiatorLatch(NanInitiatorLatch.NONE)
        }
        bulkWanted.clear(peerNodeId) // the mark's job is done; the next transfer re-marks if needed
        framed.start() // launches read + write loops; stamps linkStartedAt/lastActivityAt at link-up
        conn.reaperJob = scope.launch { superviseLink(conn) }
        noteReachable(Peer(peerNodeId, advert.protoVersion, advert.capabilities))
        refreshNeighbors()
        Log.i(TAG, "link up: $peerNodeId")
    }

    /**
     * Per-link supervisor that ends an ephemeral sync. **Both sides** disconnect on bidirectional quiescence
     * (no data — sent *or* received — for their idle threshold, never mid-file). The initiator's threshold
     * ([QUIESCENCE_MS]) is shorter so it normally drives teardown and records the sync (it alone consults that
     * record); the responder's ([RESPONDER_QUIESCENCE_MS]) is a touch longer so the initiator usually wins the
     * race, but it is **essential**: tearing an NDP down does not deliver a TCP FIN/RST to the far side, so a
     * responder that waited only for its read loop to see EOF would pin its single NDI for the full
     * [RESPONDER_MAX_HOLD_MS] backstop after every clean sync (observed: `idle=45103ms`), choking the mesh.
     * [FramedLink.lastActivityAt] is touched on both read and write, so an idle window means *neither* side has
     * more to send — a premature teardown just re-triggers on the next cue.
     */
    @Suppress("LoopWithTooManyJumpStatements") // poll → skip-mid-file (continue) or done (break)
    private suspend fun superviseLink(conn: NanLink) {
        val isInitiator = conn.isInitiator
        while (scope.isActive && peers[conn.nodeId] === conn) {
            delay(QUIESCENCE_POLL_MS)
            if (conn.link.rxInProgress || conn.link.txInProgress) continue // never tear down mid file transfer
            val now = SystemClock.elapsedRealtime()
            val idle = now - conn.link.lastActivityAt
            val held = now - conn.link.linkStartedAt
            val done =
                if (isInitiator) {
                    // Cut the post-backfill linger short when another pair is waiting on our single NDI, so a
                    // message reaches the next neighbor without paying the full QUIESCENCE_MS idle hold — the
                    // main lever on multi-node propagation latency (one NDP at a time). Full linger only when no
                    // one else is sync-wanted, so an active 1:1 chat still re-uses the warm NDP.
                    val quiescence = if (initiateOwed()) CONTENDED_QUIESCENCE_MS else QUIESCENCE_MS
                    idle >= quiescence || held >= SYNC_MAX_WINDOW_MS
                } else {
                    idle >= RESPONDER_QUIESCENCE_MS || held >= RESPONDER_MAX_HOLD_MS
                }
            if (done) {
                // Record the sync at our *post-backfill* digest: if we gained nothing new after ingesting the
                // peer's data, our version now equals theirs and the pair stays quiet (the identical-digest
                // skip); if we later gain something, the version moves and re-triggers. Initiator-only — see kdoc.
                if (isInitiator) digestTracker.onReconciled(conn.nodeId, storeDigest.current())
                Log.i(TAG, "sync with ${conn.nodeId} done (initiator=$isInitiator idle=${idle}ms held=${held}ms) — disconnecting")
                teardownPeer(conn.nodeId, backoffMs = 0) // clean sync: no anti-churn backoff needed
                break
            }
        }
    }

    /**
     * True if an NDP **initiate is owed**: some peer we're the initiator for (`localNodeId > it`, the
     * tie-break), not currently linked, is sync-wanted per [DigestTracker]. A linked peer is excluded by
     * `!in peers.keys` (which also covers "the current link" for every caller — a served peer is larger, so
     * the tie-break excludes it anyway). Drives [superviseLink]'s contended-linger cut (another pair is
     * waiting on the radio), the teardown-time recycle decision (free the NDI only when someone actually
     * needs our initiator role), and the pinned-responder session-cycle fallback. Reads the concurrent
     * [cueTarget]; [DigestTracker] is internally synchronized.
     */
    private fun initiateOwed(): Boolean =
        NanSyncPolicy.initiateOwed(snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = false))

    /**
     * [initiateOwed] restricted to peers heard on the coordination plane within [REACHABLE_LINGER_MS] — the
     * gate for the **expensive** pinned-responder session cycle: a peer that walked away lingers in
     * [cueTarget] with a stale divergent digest until [pruneAbsentPeers] reaps it (~150 s), and cycling the
     * whole session for a peer nobody can reach would burn up to ~7 pointless cycles in that window. The
     * cheap in-place recycle keeps plain [initiateOwed] (a 7 ms no-op if the peer turns out gone).
     */
    private fun initiateOwedToReachable(): Boolean =
        NanSyncPolicy.initiateOwedToReachable(
            snapshotPeerFacts(SystemClock.elapsedRealtime(), storeDigest.current(), includeDiscovered = false),
        )

    private fun failConnect(
        peerNodeId: String,
        callback: ConnectivityManager.NetworkCallback,
        staleHandle: Boolean,
    ) {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        val streak =
            synchronized(lock) {
                inFlight.remove(peerNodeId)
                if (staleHandle) {
                    // Fast (stale-signature) failure — ambiguous between a genuinely stale handle (peer restarted)
                    // and a peer whose responder is wedged/busy on its one NDI (handle fine). [NanConnectPolicy]
                    // discriminates by the consecutive-fast-fail streak: the 1st is treated as stale → drop it so
                    // needsRediscovery re-arms subscribe for a fresh one (a real restart links on that fresh handle
                    // first-try — field-drilled 2026-07-04, 8/8, so the streak tops out at 1). A 2nd+ consecutive
                    // fast-fail means the *fresh* handle failed identically → the fault is the peer's responder, so
                    // KEEP the handle (it stays in `discovered`, which stops it driving the subscribe re-arm whose
                    // repeated close/reopen is that session's own wedge trigger) and let the geometric backoff +
                    // peer-side recovery clear it. The wedge watchdog ([checkWedge]) still backstops a sync owed
                    // with no link. See docs/NAN_CONCURRENCY_REAUDIT.md.
                    val s = (failStreak[peerNodeId] ?: 0) + 1
                    failStreak[peerNodeId] = s
                    retryAfter[peerNodeId] = SystemClock.elapsedRealtime() + NanConnectPolicy.backoffMs(s)
                    if (NanConnectPolicy.dropHandleOnFastFail(s)) discovered.remove(peerNodeId)
                    s
                } else {
                    // Slow (contention) or post-NDP failure: the handle is fine, the peer was just busy on its one
                    // NDI. Keep it, use the flat contention backoff, and leave the fast-fail streak untouched.
                    retryAfter[peerNodeId] = SystemClock.elapsedRealtime() + CONNECT_BACKOFF_MS
                    failStreak[peerNodeId] ?: 0
                }
            }
        // A failed initiate also cools this peer's bulk marking (120 s): the 60 s blobreq re-offer must not
        // re-mark a NAN-dark peer into repeated initiate→timeout occupancy of the single NDI. Digest-driven
        // retries keep their own (shorter) backoff above.
        bulkWanted.noteFailed(peerNodeId)
        // A persistently un-formable data plane despite a sync being owed is caught by the wedge watchdog
        // ([checkWedge]) — we no longer nudge the peer to re-attach (that hint was net-negative: it can't clear
        // a leaked responder requestNetwork, and its churn during a wedge is what leaked one).
        noteLinkEnded() // yield to a different sync-wanted peer once the radio settles
        Log.i(TAG, "handshake with $peerNodeId ended without a link (stale=$staleHandle streak=$streak)")
    }

    private fun teardownPeer(
        peerNodeId: String,
        backoffMs: Long = REFUSED_BACKOFF_MS,
        eofDriven: Boolean = false,
    ) {
        val conn = peers.remove(peerNodeId) ?: return
        conn.close()
        // Only client links own a per-peer network callback; server links share the responder — leave it.
        val wasServerLink = !conn.isInitiator
        conn.callback?.let { cb ->
            // Release-grace: conn.close() just queued the FIN, which rides the still-alive NDP on an upcoming
            // NDL window (~100s of ms) — unregistering now tears the NDP under it, which is why the responder
            // historically never saw a FIN. Holding the request open briefly lets the FIN land so the far
            // side can recycle-while-alive (see recycleResponder); SETTLE_MS (> the grace) still gates this
            // node's own next requestNetwork. P0-validated: FIN delivery 8/8.
            handler.postDelayed(
                { runCatching { connectivity.unregisterNetworkCallback(cb) } },
                INITIATOR_RELEASE_GRACE_MS,
            )
        }
        // Back off an *initiator* link that ended without a clean sync (a reset — usually the responder was
        // busy on its one NDI, or the NDP dropped), so we don't immediately re-hammer a peer we can't reach
        // yet. A clean quiescence teardown passes backoffMs = 0: it already recorded the sync, so the peer
        // isn't sync-wanted anyway, and this keeps active-chat re-sync latency low.
        if (conn.isInitiator && backoffMs > 0) {
            synchronized(lock) { retryAfter[peerNodeId] = SystemClock.elapsedRealtime() + backoffMs }
        }
        noteLinkEnded()
        refreshNeighbors()
        Log.i(TAG, "link down: $peerNodeId")
        // A served client just left. The serve itself never wedges (re-audit E1: 30+ consecutive serves), but
        // from its first serve on the responder request keeps the NDI assigned even at 0 NDPs, blocking this
        // node's own initiator role until the request is recycled (docs/NAN_CONCURRENCY_REAUDIT.md §2). The
        // P2 policy, applied only when the departing link was the LAST live inbound with no accept mid-HELLO
        // (recycling/reattaching terminates EVERY connection on the shared request — never under siblings):
        // - EOF-driven end + an initiate owed: the served NDP is still alive (the initiator's release-grace
        //   holds its endDataPath back), so unregister NOW — the framework's clean teardown — and re-file.
        //   In-place, ~7 ms, no session cycle (P0-validated).
        // - EOF but nothing owed (notably the smallest node, everyone's pure responder): leave the request
        //   serving — E1 proved serve-ability never degrades, and the pin only blocks initiates we don't make.
        // - Quiescence-driven end (no EOF ⇒ the NDP may already be gone): NEVER unregister at 0 NDPs — that
        //   IS the state=104 ghost. If an initiate is owed now or later, the discovery loop's flap-handshake
        //   session cycle (sessionCycleAwaitingFlap) clears the pin.
        if (wasServerLink) {
            val lastInbound =
                synchronized(lock) { peers.values.none { !it.isInitiator } && accepting == 0 }
            when {
                !lastInbound -> Unit

                // Rollback lever: the legacy after-serve session cycle.
                !USE_GHOST_PROOF_RECYCLE -> scheduleServeReattach(peerNodeId)

                eofDriven && initiateOwed() -> recycleResponder()

                else -> Unit
            }
        }
    }

    /**
     * Ghost-proof responder recycle (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.2, P0-validated): unregister the
     * accept-any request **while its just-EOF'd NDP is still alive** (the initiator's release-grace holds the
     * far side's endDataPath back), so the framework's `agent.unwanted()` has an NDP to end and tears the
     * request down cleanly: no 0-NDP TERMINATING ghost, no NDI pin, and — unlike the legacy after-serve
     * reattach — no session cycle (publish/subscribe/discovery/ICM all stay up). Then immediately re-file a
     * fresh responder on the same publish session; if the framework briefly queues it behind the terminating
     * one, `tickleConnectivityIfWaiting` re-admits it within ~ms of the old NDP's end. Frees the NDI for this
     * node's own initiator role in ~7 ms (measured), vs ~3.3 s for the session cycle.
     */
    private fun recycleResponder() =
        onHandler {
            if (responderCallback == null) return@onHandler // no live responder (mid-reattach) — nothing to recycle
            Log.i(TAG, "recycling responder (unregister while the served NDP is still alive)")
            stopResponder()
            startResponder()
        }

    /**
     * P2 rollback path only ([USE_GHOST_PROOF_RECYCLE] = false; delete together once the recycle has soaked):
     * the legacy after-serve session reattach. Its original premise — "a served responder is wedged; only a
     * full re-attach restores serve-ability" — was refuted by the re-audit (E1: serve-ability never degrades);
     * its real effect was releasing the pinned NDI for the initiator role, at the cost of a full session cycle
     * per serve and the reattach-vs-deferred-disable ghost race (`docs/NAN_CONCURRENCY_REAUDIT.md` §2-3).
     * If the slot is momentarily busy, **reschedule** rather than drop; stops when the session is gone.
     */
    private fun scheduleServeReattach(peerNodeId: String) {
        handler.postDelayed({
            when {
                session == null || publishSession == null -> {}

                // detached; the loop re-attaches
                anyLinkActivity() -> {
                    scheduleServeReattach(peerNodeId)
                }

                // a link/handshake is up — retry once it frees
                else -> {
                    lastReattachAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "re-attach after serving $peerNodeId")
                    reattach()
                }
            }
        }, SETTLE_MS)
    }

    /** Open the SETTLE gate after a link/handshake ends and wake the loop once the NDI has been released. */
    private fun noteLinkEnded() {
        lastLinkEndedAt = SystemClock.elapsedRealtime()
        scope.launch {
            delay(SETTLE_MS)
            healSignal.trySend(Unit)
        }
    }

    // --- Connection admission (single NDI slot) ---

    private fun beginConnect(peerNodeId: String): Boolean =
        synchronized(lock) {
            if (peerNodeId in peers.keys || peerNodeId in inFlight) return false
            // One NDI: at most one link/handshake/accept in flight, and only after the previous link's NDI has
            // been released (SETTLE) — else requestNetwork fails with "no interfaces available". Deliberately
            // conservative under P1's concurrent serves too: an initiate while the responder request holds the
            // NDI is framework-refused anyway, so attempting it is pure churn (P2's recycle gives it its turn).
            if (peers.isNotEmpty() || inFlight.isNotEmpty() || accepting > 0) return false
            if (SystemClock.elapsedRealtime() < lastLinkEndedAt + SETTLE_MS) return false
            retryAfter[peerNodeId]?.let { if (SystemClock.elapsedRealtime() < it) return false } // backing off
            inFlight.add(peerNodeId)
            true
        }

    /**
     * Admit an inbound accept per [NanServePolicy] (paired with [endAccept]): up to [serveCap] concurrent
     * serves on the standing accept-any request — an accept consumes no NDI — excluded only by an in-flight
     * *initiator* handshake (which genuinely contends for the single NDI). At cap 1 (tiny/unreadable firmware
     * NDP budget) this is byte-equivalent to the legacy single-slot gate, SETTLE included.
     */
    private fun beginAccept(): Boolean =
        synchronized(lock) {
            val admitted =
                NanServePolicy.admitAccept(
                    inFlightHandshakes = inFlight.size,
                    liveInbound = peers.values.count { !it.isInitiator },
                    acceptsInHello = accepting,
                    cap = serveCap,
                    singleSlotBusy = peers.isNotEmpty() || inFlight.isNotEmpty() || accepting > 0,
                    settleOk = SystemClock.elapsedRealtime() >= lastLinkEndedAt + SETTLE_MS,
                )
            if (admitted) accepting++ else metrics.onNanAcceptRefused()
            admitted
        }

    private fun endAccept() {
        synchronized(lock) { if (accepting > 0) accepting-- }
    }

    private fun refreshNeighbors() {
        _neighbors.value = peers.values.map { it.link.peer }.toSet()
        recomputeReachable()
    }

    /** Note a peer seen over the coordination plane, keeping the best Peer we know for the reachable set. */
    private fun noteReachable(peer: Peer) {
        lastSeenAt[peer.nodeId] = SystemClock.elapsedRealtime()
        // Don't downgrade a fuller Peer (from an advert) to a bare one (from a cue).
        reachablePeers.merge(peer.nodeId, peer) { old, new -> if (new.protoVersion == 0 && old.protoVersion != 0) old else new }
        recomputeReachable()
    }

    /** Reachable = the live link(s) ∪ peers seen within [REACHABLE_LINGER_MS] over the coordination plane. */
    private fun recomputeReachable() {
        val now = SystemClock.elapsedRealtime()
        lastSeenAt.entries.removeAll { now - it.value > REACHABLE_LINGER_MS }
        val live = peers.values.map { it.link.peer }
        val liveIds = live.mapTo(HashSet()) { it.nodeId }
        val nearby = reachablePeers.filterKeys { it in lastSeenAt.keys && it !in liveIds }.values
        _reachable.value = (live + nearby).toSet()
    }

    /**
     * Reap peers we've neither linked to nor heard from on the coordination plane within [REACHABLE_LINGER_MS]
     * (5 cue heartbeats) from all coordination-plane bookkeeping — [cueTarget], [reachablePeers], and the
     * [digestTracker] digest. A best-effort cue to a peer that simply walked out of range never *throws*, so
     * the [sendCue] failure-prune never fires for it; left alone its state lingers forever. That is what the
     * connection engine calls "a truly-gone peer, pruned within a heartbeat once our cues fail" ([needsRediscovery])
     * — a prune the failure path alone never actually delivered. Stale, a ghost makes an **initiator** (larger
     * id) fast-tick ([SYNC_RETRY_IDLE_MS]) and re-arm subscribe hunting it, and keeps us heartbeat-cueing it
     * every [CUE_HEARTBEAT_MS] for nothing. Forgetting its digest means a later reappearance correctly re-syncs
     * (it was gone far longer than a link blip, which is why [DigestTracker.forget] is otherwise link-churn-shy).
     * A reappearing peer is re-added by [onDiscovered]/[onCueReceived]. **Handler-thread only** (called via
     * [onHandler]) so it serializes with those two add sites and never races a fresh cue's insert.
     */
    private fun pruneAbsentPeers() {
        val now = SystemClock.elapsedRealtime()
        cueTarget.keys
            .filter { nodeId ->
                !peers.containsKey(nodeId) && (lastSeenAt[nodeId]?.let { now - it > REACHABLE_LINGER_MS } ?: true)
            }.forEach { nodeId ->
                cueTarget.remove(nodeId)
                hopTable.forget(nodeId)
                reachablePeers.remove(nodeId)
                digestTracker.forget(nodeId)
                synchronized(lock) { failStreak.remove(nodeId) } // gone → fresh streak when it returns
            }
    }

    // --- Availability ---

    private val availabilityReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                handleAvailabilityChanged(awareManager?.isAvailable ?: return)
            }
        }

    /**
     * One Aware availability notification, from the broadcast or from [NanFaultInjector] in a debug build.
     * Split out of the receiver so the lab can drive it directly: the getknit/Knit#9 storm is a chipset
     * re-broadcasting `true` after every refused attach, and that is not reachable from outside the app.
     */
    private fun handleAvailabilityChanged(available: Boolean) {
        val wasAvailable = lastAvailable
        lastAvailable = available
        if (available) {
            // While a deliberate session cycle settles, the loop owns the re-attach — an early attach
            // here is the exact race the settle exists to prevent. (Availability broadcasts also lag
            // many seconds behind the real state on a screen-off device, so this edge is stale anyway.)
            if (sessionCycleSettleStartedAt != 0L) return
            onHandler {
                // Aware came *back*: a new fact about the radio, so a failure streak that predates it
                // says nothing about this attempt. Attach now rather than serving out a stale backoff.
                //
                // Only on the real false→true edge, though. This action fires on every Aware state
                // change, and a chipset that cannot produce a NAN interface re-broadcasts after each
                // refused attach with isAvailable still true. 2.3.1 refunded the streak on those repeats
                // and re-attached ~3.5 ms later — getknit/Knit#9's second act, ~286 attaches a second
                // and an AMS binder-object kill inside ten. A repeat of `true` is not news.
                if (wasAvailable == false) clearAttachBackoff()
                attach() // already on the handler; the attaching/session/floor guards no-op a redundant call
            }
        } else {
            onHandler {
                if (sessionCycleSettleStartedAt != 0L) {
                    Log.i(TAG, "session cycle: NAN down broadcast observed")
                } else {
                    // The radio went off under us (Wi-Fi off / airplane mode): the STA blip that goes with it is the
                    // user's, not evidence against our initiate. Not on our own session cycle, though — on the
                    // Pixel 3 that cycle is the responder's recovery from the very STA drop being judged.
                    initiatorPolicy.noteRadioOff()
                }
                _health.value = TransportHealth.Unavailable // Wi-Fi Aware switched off (Wi-Fi off / airplane mode)
                peers.keys.toList().forEach { teardownPeer(it) }
                ++attachGen // invalidate in-flight discovery callbacks from the torn-down generation
                stopResponder()
                runCatching { publishSession?.close() }
                runCatching { subscribeSession?.close() }
                runCatching { session?.close() }
                session = null
                publishSession = null
                subscribeSession = null
                attaching.set(false)
                subscribing.set(false)
                reattaching.set(false)
                clearAttachBackoff() // symmetric with the up edge; the streak can't outlive the radio it measured
                synchronized(lock) {
                    accepting = 0
                    discovered.clear() // handles die with the sessions closed just above — see [sessionCycleWithSettle]
                }
                cueTarget.clear()
                hopTable.clear()
                lastSeenAt.clear()
                reachablePeers.clear()
                _neighbors.value = emptySet() // symmetric with stop(); teardownPeer already recomputes it empty
                _reachable.value = emptySet()
            }
        }
    }

    /**
     * Subscribe to Wi-Fi Aware availability. Two deliberate details, both about surviving a non-stock ROM:
     * the export flag is passed explicitly instead of leaning on Android 14+'s "registered exclusively for
     * system broadcasts" exemption, and the call is guarded. `ACTION_WIFI_AWARE_STATE_CHANGED` is a
     * `<protected-broadcast>` on stock Android — but a ROM whose framework overlay drops it loses the
     * exemption, and the bare call then throws `SecurityException` out of [start] and kills the process at
     * mesh start on that device. Losing the availability signal only degrades the plane (the discovery loop
     * still polls `isAvailable`); it must never be fatal.
     */
    private fun registerAvailability() {
        if (availabilityRegistered) return
        lastAvailable = awareManager?.isAvailable // seed the edge, so the first broadcast is compared, not assumed
        availabilityRegistered =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    availabilityReceiver,
                    IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "Aware availability receiver registration failed", it) }.isSuccess
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        runCatching { appContext.unregisterReceiver(availabilityReceiver) }
        availabilityRegistered = false
    }

    /**
     * The STA watch behind [NanInitiatorPolicy] (#78). Passive — `registerNetworkCallback` holds no request
     * and keeps nothing up — and best-effort: a registration the framework refuses (the per-process callback
     * cap, a ROM quirk) leaves the failsafe inert and the plane exactly as it was.
     */
    private fun registerWifiWatch() {
        if (wifiWatchRegistered) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        wifiWatchRegistered =
            runCatching { connectivity.registerNetworkCallback(request, wifiWatch, handler) }
                .onFailure { Log.w(TAG, "Wi-Fi watch registration failed; initiator failsafe inert", it) }
                .isSuccess
    }

    private fun unregisterWifiWatch() {
        if (!wifiWatchRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(wifiWatch) }
        wifiWatchRegistered = false
    }

    /** Our Wi-Fi network went away. Handler thread. [network] is null only from the debug injector. */
    private fun onStaLost(network: Network?) {
        Log.i(TAG, "Wi-Fi network lost${network?.let { " ($it)" } ?: ""}")
        initiatorPolicy.noteWifiLost()
    }

    /** A Wi-Fi network is up — the registration echo, a reconnect, or the second half of a blip. Handler thread. */
    private fun onStaAvailable(network: Network?) {
        when (initiatorPolicy.noteWifiAvailable()) {
            NanInitiatorPolicy.Verdict.None -> {}

            NanInitiatorPolicy.Verdict.Strike -> {
                Log.w(
                    TAG,
                    "Wi-Fi dropped and came back${network?.let { " ($it)" } ?: ""} within two minutes of an initiate of ours " +
                        "with no link between — strike ${initiatorPolicy.strikes}/${NanInitiatorPolicy.STRIKES_TO_LATCH}",
                )
            }

            NanInitiatorPolicy.Verdict.Latched -> {
                latchInitiator()
            }

            NanInitiatorPolicy.Verdict.AlreadyHeld -> {
                Log.i(TAG, "Wi-Fi dropped again during the daily initiator probe — staying held")
            }
        }
    }

    /**
     * Three coincidences: hold the initiator role. Nothing is torn down — the responder, discovery, cues and
     * the fast plane keep running, and the hold acts only through [digestSyncWanted]/[bulkSyncWanted], so every
     * admission and recovery site stops seeing a sync it would have to initiate. Journaled under [giveUpStamp]
     * so a restart does not re-learn it by dropping the Wi-Fi three more times (ADR 2026-09.m8kc).
     */
    private fun latchInitiator() {
        Log.w(
            TAG,
            "holding the initiator role: our Wi-Fi dropped ${NanInitiatorPolicy.STRIKES_TO_LATCH} times while we initiated " +
                "— no more data paths from this phone; the responder and discovery stay up, probing once a day",
        )
        _initiatorHeld.value = true
        journalInitiatorLatch(NanInitiatorLatch(giveUpStamp, System.currentTimeMillis()))
        healSignal.trySend(Unit) // the loop re-derives its cadence and owed set without the held peers
    }

    private fun journalInitiatorLatch(latch: NanInitiatorLatch) {
        scope.launch { runCatching { initiatorJournal.setInitiatorLatch(latch) } }
    }

    /** Diagnostics' "Try again" ([MeshTransport.releaseInitiatorHold]): everything to zero, memory and journal. */
    override fun releaseInitiatorHold() {
        val wasHeld = initiatorPolicy.latched
        initiatorPolicy.reset()
        _initiatorHeld.value = false
        journalInitiatorLatch(NanInitiatorLatch.NONE)
        if (wasHeld) Log.i(TAG, "initiator hold released by the user; the next owed sync initiates")
        healSignal.trySend(Unit)
    }

    /** The hold gates a peer we would have to **initiate** to; a larger peer initiating to us is unaffected. */
    private fun initiatorHeld(nodeId: String): Boolean = initiatorPolicy.latched && localNodeId > nodeId

    /**
     * NAN-specific wrapper around a shared [FramedLink]: the per-peer initiator network callback (null for a
     * server link that shares the accept-any responder — which must NOT be unregistered when one client
     * leaves) and the quiescence supervisor job. [FramedLink] owns the socket I/O; this owns what only NAN
     * needs (the single-NDI teardown policy).
     */
    private inner class NanLink(
        val link: FramedLink,
        val callback: ConnectivityManager.NetworkCallback?,
    ) {
        val nodeId: String get() = link.nodeId
        val isInitiator: Boolean get() = callback != null
        var reaperJob: Job? = null

        fun close() {
            reaperJob?.cancel()
            link.close()
        }
    }

    internal companion object {
        const val TAG = "WifiAwareTransport"

        /**
         * True on an **API 31+** device with Wi-Fi Aware hardware — the composite includes this plane only if
         * so. The verdict itself is `wifiAwareSupport` (`mesh/RadioSupport.kt`), shared with the Diagnostics
         * row that explains an absent plane, so the two cannot disagree; the floor and the hardware facts are
         * documented there. The literal `SDK_INT` comparison is redundant with the verdict but is what lint
         * reads: `@ChecksSdkIntAtLeast` lets it treat this as the SDK guard for the `@RequiresApi(S)`
         * constructor at its single call site (`di/MeshModule`).
         */
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifiAwareSupport(context) == PlaneSupport.Supported

        // The NAN service both nodes publish/subscribe — an Apple-`WiFiAwareServices`-conformant DNS-SD name
        // (`_name._proto`, name label ≤15 chars; `_tcp` matches the NDP's TCP data path) so a future iOS client
        // can share discovery. The `knitmesh<N>` digit is the discovery-partition marker: bump it (…mesh2…) on
        // the next breaking wire/discovery change so a build across the break hard-partitions at discovery
        // rather than silently mis-decoding. `_knitmesh1._tcp` is the launch baseline. (Android's setServiceName
        // accepts any UTF-8 string, so the length/charset rule is iOS-facing — confirm it when the iOS client
        // is built.)
        const val SERVICE_NAME = "_knitmesh1._tcp"

        // Fixed app-wide 32-byte PMK for link-layer (NDP) encryption, passed via setPmk so the firmware skips
        // the per-NDP passphrase→PMK PBKDF2 derivation (same security either way — both forms are public
        // constants baked into the app). Real authentication is the per-frame Ed25519 signature + E2E layer
        // above the transport; this only keeps the data path off open air.
        val PMK: ByteArray = MessageDigest.getInstance("SHA-256").digest("knit-mesh-nan-pmk-v1".toByteArray())

        // 2.4 GHz for instant mode: better range than 5 GHz (range is priority #2), at some throughput cost.
        // WIFI_BAND_24_GHZ is API 30 but a compile-time-inlined int, so it's safe on minSdk 29 — and it is only
        // ever read inside the API-33-guarded setInstantCommunicationModeEnabled calls above.
        @SuppressLint("InlinedApi")
        const val INSTANT_BAND = ScanResult.WIFI_BAND_24_GHZ

        // Coordination-plane message multiplexing. A cue is a plain "nodeId|version" text string whose
        // first byte is a printable base64 nodeId char, so the non-printable tag space 0x00..0x1F cleanly
        // distinguishes framed messages. The registry is append-only, like capability bits: 0x01 = legacy
        // tagged-CBOR fast frame ([MSG_FRAME_TAG], kept forever — every build reads it), 0x02 = BURNED (a
        // since-removed "please re-attach" nudge; never recycle), 0x03/0x04 = compact frame / fragment
        // (mesh/link/FastFrameCodec, emitted only toward Protocol.CAP_FAST_COMPACT peers), 0x05 = transcoded
        // frame (ADR 060, only toward Protocol.CAP_FRAME_TRANSCODE peers). Cues stay untagged (byte-for-byte
        // unchanged); an unknown non-printable tag is counted and dropped.
        const val MSG_FRAME_TAG: Byte = 0x01

        /** Low 16 bits of [fragSeq] — the fragment header's id width (FastFrameCodec's u16). */
        const val FRAG_ID_MASK = 0xFFFF

        // Max coordination-plane message the radio accepts (maxServiceSpecificInfoLen = 255 on Pixel 7/8/9, via
        // dumpsys wifiaware). A frame plus its 1-byte tag must fit; a larger frame skips the fast path and rides
        // the data-path flood + store-and-forward instead.
        const val COORD_MSG_MAX = 255

        // Timeout for a client handshake (its requestNetwork timeout overload fires onUnavailable) so an
        // attempt that can't complete frees its slot vs. leaking the interface reservation forever.
        const val HANDSHAKE_TIMEOUT_MS = 15_000

        // Below this, an onUnavailable is a stale-handle fast-fail (peer restarted → dead handle), which
        // drops the handle to trigger re-discovery; at/above it, it's contention (peer busy) and the handle is
        // kept. Comfortably above a normal NDP setup (~hundreds of ms) and far below HANDSHAKE_TIMEOUT_MS.
        const val FAST_FAIL_MS = 3_000L

        // Grace past HANDSHAKE_TIMEOUT_MS before the initiation watchdog force-cleans a half-open NDP (one
        // that came "available" but never yielded a peer IPv6), so the framework's own onUnavailable gets
        // first crack at the never-available case and the watchdog only catches the available-but-stuck leak.
        const val WATCHDOG_MARGIN_MS = 3_000L

        // Bound the responder's initial identity (HELLO) read, so a stalled connector can't pin the slot.
        const val ACCEPT_HELLO_TIMEOUT_MS = 5_000

        // After a failed handshake, skip re-initiating to that peer for this long, so a different
        // sync-wanted peer gets a turn rather than storming the same failing one.
        const val CONNECT_BACKOFF_MS = 10_000L

        // After an initiator link ends in a reset (responder busy on its one NDI, or the NDP dropped), skip
        // re-initiating to that peer for this long — anti-churn, shorter than a handshake failure since the
        // peer is reachable, just busy.
        const val REFUSED_BACKOFF_MS = 5_000L

        // Short loop idle while a sync is still owed (a sync-wanted peer, possibly backed off), so we retry
        // promptly instead of waiting out REDISCOVER_IDLE_MS.
        const val SYNC_RETRY_IDLE_MS = 3_000L

        // Loop cadence while detached (no Aware session): retry attach() promptly so a re-enable that couldn't
        // fire immediately after a reattach teardown recovers in seconds, not a full REDISCOVER_IDLE_MS. This is
        // the *floor* only — consecutive failures stretch it out along [NanAttachPolicy]'s curve and eventually
        // stop it, because each failed attach strands two binder objects in system_server and AMS kills the
        // process over enough of them (that is getknit/Knit#9, not a theoretical concern).
        const val ATTACH_RETRY_MS = 3_000L

        // Gap after a link/handshake ends before the next requestNetwork, so the framework releases the one
        // NDI first (else "no interfaces available").
        const val SETTLE_MS = 1_500L

        // How long an initiator holds its requestNetwork open after closing the socket, so the FIN can ride
        // the still-alive NDP (an NDL transmit window is ~100s of ms away) and the responder gets its
        // recycle-while-alive window. Must stay < SETTLE_MS, which already spaces our next requestNetwork.
        const val INITIATOR_RELEASE_GRACE_MS = 750L

        // P2 rollback switch: false restores the legacy after-serve session reattach (scheduleServeReattach)
        // in place of the ghost-proof recycle + flap-handshake policy. Delete (with scheduleServeReattach)
        // once the recycle has soaked a release.
        const val USE_GHOST_PROOF_RECYCLE = true

        // Post-teardown settle of a deliberate session cycle before re-attaching. The framework's last-client
        // disable + onAwareDownCleanupDataPaths (the request-cache wipe) ran ~50 ms after session.close() in
        // on-device measurement; 2 s covers it with a wide margin. Broadcasts are NOT the signal — their
        // delivery lags many seconds on a screen-off device.
        const val SESSION_CYCLE_SETTLE_MS = 2_000L

        // Min gap between SSI republishes (updatePublish) on digest change, trailing-edge coalesced: a
        // backfill burst becomes one update carrying the final version. Each republish re-fires every
        // subscriber's onServiceDiscovered, so this also bounds that fan-out.
        const val SSI_UPDATE_MIN_MS = 5_000L

        // Wait this long after a peer's cue advances its digest before waking the sync loop, so a broadcast
        // fast-fanout racing the cue can deliver + carry the frame and converge the digest first — turning the
        // NDP sync into a fallback for genuinely-missed data instead of a reflex that fights the fanout for the
        // coordination plane. Short: the cue and the fast-frame are sent back-to-back, so they arrive within it.
        const val CUE_SETTLE_MS = 600L

        // Idle discovery-loop cadence: aggressive when we know of nobody (re-fire one-shot discovery) — for the
        // first minutes; NanLonelyPolicy then relaxes a screen-off node on battery to the duty cycle, since each
        // re-arm relights Instant Communication Mode and a lonely phone used to keep it lit around the clock —
        // and long once we've discovered/heard peers (a cue with a new epoch wakes us via healSignal). ×2 screen-off.
        const val REDISCOVER_LONELY_MS = 8_000L
        const val REDISCOVER_IDLE_MS = 120_000L

        // Cue heartbeat: re-advertise our epoch to known peers periodically (covers best-effort message loss
        // and a peer that discovered us but whom we haven't discovered back).
        const val CUE_HEARTBEAT_MS = 30_000L

        // Temporary: how often to dump connection-engine decision state to logcat while debugging.
        const val DIAG_INTERVAL_MS = 60_000L // debug builds only; the watchdog's pre-kill dump is unconditional

        // Ephemeral-sync teardown. Both sides disconnect on bidirectional quiescence (polled every
        // QUIESCENCE_POLL_MS): the initiator after QUIESCENCE_MS of idle (or the hard SYNC_MAX_WINDOW_MS cap),
        // the responder after the slightly longer RESPONDER_QUIESCENCE_MS so the initiator usually drives —
        // but the responder MUST self-detect quiescence because an NDP teardown delivers no FIN/RST, so it
        // can't rely on its read loop seeing EOF (else it pins its one NDI for RESPONDER_MAX_HOLD_MS, the
        // dead-initiator backstop, after every clean sync). Both windows must exceed the
        // watchNeighbors→onNeighborAdded backfill-start latency. QUIESCENCE_MS also doubles as a *linger* so
        // an active 1:1 chat re-uses the warm NDP instead of re-paying setup per message — but when another
        // peer is sync-wanted that linger just idles the one NDI, so the initiator drops to
        // CONTENDED_QUIESCENCE_MS and yields the radio to the next pair (superviseLink → otherSyncWanted).
        const val QUIESCENCE_MS = 5_000L
        const val CONTENDED_QUIESCENCE_MS = 2_000L
        const val QUIESCENCE_POLL_MS = 1_000L
        const val RESPONDER_QUIESCENCE_MS = 8_000L
        const val SYNC_MAX_WINDOW_MS = 30_000L
        const val RESPONDER_MAX_HOLD_MS = 45_000L

        // How long a peer lingers in the smoothed [reachable] set after its last coordination-plane sighting.
        // Comfortably exceeds the idle cue heartbeat + rediscover cadence so the UI doesn't blink to
        // "disconnected" between sightings.
        const val REACHABLE_LINGER_MS = 150_000L

        // Freshness gate for arming a bulk-transfer NDP ([expectBulkTransfer]): the peer must have been heard
        // on the coordination plane this recently. Deliberately much tighter than REACHABLE_LINGER_MS — the
        // linger keeps walked-away/dozing ghosts for 150 s, and marking one draws initiate→timeout cycles on
        // the single NDI for a link that can never form. ~One cue heartbeat + slack.
        const val BULK_FRESH_MS = 45_000L

        // Recovery re-attach when subscribe is wedged: min spacing between resets, and a small settle delay
        // before the reset fires.
        const val REATTACH_COOLDOWN_MS = 20_000L
        const val REATTACH_DELAY_MS = 800L

        // Fallback release of the reattach() single-flight guard if the fresh attach never calls back at all
        // (normally its onAttached/onAttachFailed clears it in well under a second). Bounds "stuck reattaching".
        const val ATTACH_WATCHDOG_MS = 10_000L

        // How long after a responder file the debug fault ([NanFaultInjector], `…debug.NANREFUSE`) delivers its
        // verdict: the ~10 ms the framework took on the Pixel 3 (work item #77).
        const val INJECTED_REFUSAL_DELAY_MS = 10L

        // Leaked-request wedge watchdog ([checkWedge]): how often to evaluate, and how long a sync must stay
        // *owed with no link forming* (the owed-episode, not time-since-last-link) before self-restarting. The
        // restart window doubles as the min restart spacing so a persistently-unreachable peer can't loop us.
        const val WEDGE_CHECK_MS = 30_000L
        const val WEDGE_RESTART_MS = 180_000L

        // Tier-1 responder self-heal: how long a sync stays owed with no link forming before a session cycle
        // refreshes a possibly-wedged responder. Well under WEDGE_RESTART_MS (the last-resort process kill) and
        // comfortably above a healthy owed→link latency, so it only fires on a genuine stuck episode.
        const val RESPONDER_REFRESH_MS = 45_000L

        // ...and how many of those cycles one episode may spend. WEDGE_CHECK_MS (30 s) is longer than
        // REATTACH_COOLDOWN_MS (20 s), so the cooldown never blocks Tier 1 in production and this cap is the
        // only thing that stops it: three tries at ~30 s spacing, then quiet so the radio holds still long
        // enough for a handshake to finish and for Tier 2's 180 s corroborated escalation to be reachable at
        // all. Uncapped, the cycle destroys the sessions each initiate needs and the wedge sustains itself
        // (three-Pixel capture 2026-09-07 — see [NanWatchdogPolicy]).
        const val MAX_RESPONDER_REFRESHES = 3

        // Min spacing between subscribe re-arms to re-discover a stale/missing peer handle: long enough that a
        // departed peer's cue target is pruned (cue send fails) before we'd re-arm again, so we don't churn
        // subscribe toward its wedge state; short enough that a restarted peer is reconnected within ~1 tick.
        // With nobody to cue at all, NanLonelyPolicy stretches it under the relaxed tick (105 s / 285 s).
        const val REARM_COOLDOWN_MS = 15_000L

        // Min spacing between subscribe re-arms done purely to keep Instant Communication Mode lit on a
        // pure-responder node (see [needsIcmRelight] / the discovery loop). Just under the framework's ~30s ICM
        // auto-disable so a relit window is refreshed before it lapses, but no faster — subscribe re-arm churn
        // risks the subscribe wedge, so this only fires while a sync is actually owed to a peer we can't initiate to.
        const val ICM_REARM_COOLDOWN_MS = 25_000L
    }
}
