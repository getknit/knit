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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.DigestTracker
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.NetSocketLink
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
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
class WifiAwareTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val powerState: PowerStateSource,
    private val storeDigest: StoreDigest,
) : MeshTransport {
    private val appContext = context.applicationContext
    private val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

    override val kind = TransportKind.WifiAware

    // Aware + connectivity callbacks are delivered on this dedicated thread's handler.
    private val callbackThread = HandlerThread("wifi-aware-cb").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private lateinit var localNodeId: String
    private var instantSupported = false

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

    // elapsedRealtime the current attach() called mgr.attach(); lets a later attach() self-heal a stuck
    // [attaching] guard if the framework silently drops an attach (mgr.attach with no onAttached/onAttachFailed).
    @Volatile private var attachStartedAt = 0L

    // The one accept-any responder: its ServerSocket, network callback, and the accept loop.
    @Volatile private var responderSocket: ServerSocket? = null

    @Volatile private var responderCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var acceptJob: Job? = null

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
                teardownPeer(nodeId)
            }
        }

    // Connection bookkeeping guarded by [lock]: peers discovered via subscribe (with a subscribe-session
    // PeerHandle for NDP initiation), the single in-flight handshake, an in-flight accept, and per-peer
    // failure backoff. The single-NDI slot is FREE iff peers/inFlight empty and !accepting.
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val discovered = HashMap<String, DiscoveredPeer>()
    private val retryAfter = HashMap<String, Long>()
    private var accepting = false

    // Anti-entropy state for the cue plane: each peer's advertised digest version + our last-synced version.
    private val digestTracker = DigestTracker()

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

    // Smoothed reachability: last time each peer was seen over the coordination plane, and the best Peer we
    // know for it, so [_reachable] can linger a peer briefly after its ephemeral link drops.
    private val lastSeenAt = ConcurrentHashMap<String, Long>()
    private val reachablePeers = ConcurrentHashMap<String, Peer>()

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
    @Volatile private var lastRearmAt = 0L

    // Watchdog state for the leaked-request wedge (see [checkWedge]). [lastLinkOrAcceptAt] = elapsedRealtime of
    // the last successful data-path link/accept (either role) — the "progress" signal. [syncOwedSince] = start
    // of the current episode where a sync is owed AND no link has formed since (0 = no episode). The wedge is
    // *a sync owed continuously for WEDGE_RESTART_MS with no link* — NOT merely "no link in a while" (an idle,
    // converged mesh does zero data-path work for long stretches, so time-since-last-link is meaningless until
    // something is actually owed). [lastRestartAt] rate-limits the self-restart.
    @Volatile private var lastLinkOrAcceptAt = 0L

    @Volatile private var syncOwedSince = 0L

    @Volatile private var lastRestartAt = 0L

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

    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Unavailable
            Log.w(TAG, "Wi-Fi Aware unsupported on this device; mesh disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            lastLinkOrAcceptAt = SystemClock.elapsedRealtime() // grace window before the wedge watchdog can fire
            instantSupported =
                runCatching {
                    awareManager?.characteristics?.isInstantCommunicationModeSupported() == true
                }.getOrDefault(false)
            registerAvailability()
            attach()
            loopJob = scope.launch { discoveryLoop() }
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
            // Re-cue neighbors and wake the loop the moment our carried set changes, so a peer that now wants
            // our new data can pull it.
            cueJob =
                scope.launch {
                    storeDigest.version.drop(1).collect {
                        cueAll()
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
            diagJob =
                scope.launch {
                    while (scope.isActive) {
                        delay(DIAG_INTERVAL_MS)
                        logState()
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
        val v = storeDigest.version.value
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
        Log.i(
            TAG,
            "state ver=$v live=${peers.keys} disc=$disc cue=$cues wanted=$wanted " +
                "initiable=$initiable reach=${_reachable.value.map { it.nodeId }} tracker[${digestTracker.debug()}]",
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
    private fun anySyncOwed(): Boolean {
        val v = storeDigest.version.value
        val now = SystemClock.elapsedRealtime()
        return cueTarget.keys.any { nodeId ->
            syncWanted(nodeId, v) &&
                (peers.containsKey(nodeId) || lastSeenAt[nodeId]?.let { now - it <= REACHABLE_LINGER_MS } == true) &&
                corroboratedPresent(nodeId)
        }
    }

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
     * MeshService is `START_STICKY`, so the foreground service is recreated. Rate-limited so it can't restart-storm.
     */
    private fun checkWedge() {
        val now = SystemClock.elapsedRealtime()
        val owed = hasHardware && _health.value == TransportHealth.Healthy && session != null && anySyncOwed()
        if (!owed) {
            syncOwedSince = 0L
            return
        } // nothing owed (or can't sync) → not wedged; clear the episode
        // Episode not yet started, or a data-path link formed since it began → making progress → (re)start it.
        if (syncOwedSince == 0L || lastLinkOrAcceptAt >= syncOwedSince) {
            syncOwedSince = now
            return
        }
        if (now - syncOwedSince < WEDGE_RESTART_MS) return // owed, but not long enough with zero links yet
        if (now - lastRestartAt < WEDGE_RESTART_MS) return // never restart-storm
        lastRestartAt = now
        Log.e(TAG, "NAN data plane wedged (sync owed ${now - syncOwedSince}ms with no link) — restarting process")
        logState()
        Process.killProcess(Process.myPid())
    }

    override fun stop() {
        loopJob?.cancel()
        powerJob?.cancel()
        cueJob?.cancel()
        cueHeartbeatJob?.cancel()
        diagJob?.cancel()
        watchdogJob?.cancel()
        unregisterAvailability()
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
        lastLinkEndedAt = 0L
        synchronized(lock) {
            inFlight.clear()
            discovered.clear()
            retryAfter.clear()
            accepting = false
        }
        cueTarget.clear()
        lastSeenAt.clear()
        reachablePeers.clear()
        digestTracker.clear()
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
    }

    override fun heal() {
        if (!hasHardware) return
        healSignal.trySend(Unit)
    }

    override fun suppressDataPath(peers: Set<String>) {
        if (peers == suppressed) return
        suppressed = peers
        // A peer just became Bluetooth-covered (skip syncing it) or dropped off (resume) — re-evaluate now.
        healSignal.trySend(Unit)
    }

    /**
     * The set of peers another plane (Bluetooth) can currently see, pushed by [CompositeMeshTransport]. We use it
     * only to corroborate the wedge watchdog ([checkWedge] / [anySyncOwed]): being called at all means a
     * corroborating plane exists, so a NAN-only device is left on the un-corroborated fallback.
     */
    override fun onForeignReachable(peers: Set<String>) {
        hasForeignPlane = true
        foreignReachable = peers
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
    ) {
        peers[to.nodeId]?.link?.sendFile(file, meta)
    }

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

    private fun attach() =
        onHandler {
            val mgr = awareManager ?: return@onHandler
            if (!mgr.isAvailable) {
                _health.value = TransportHealth.Unavailable // Wi-Fi Aware off (Wi-Fi off / airplane mode)
                return@onHandler
            }
            if (session != null) return@onHandler
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
                        if (gen != attachGen || session != null) { // superseded by a newer attach — never orphan it
                            runCatching { newSession.close() }
                            return
                        }
                        session = newSession
                        reattaching.set(false) // the reattach that kicked off this (current-gen) attach has settled
                        _health.value = TransportHealth.Healthy
                        startPublish(gen)
                        startSubscribe()
                    }

                    override fun onAttachFailed() {
                        attaching.set(false)
                        reattaching.set(false)
                        _health.value = TransportHealth.Degraded
                        Log.w(TAG, "Wi-Fi Aware attach failed")
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
                        synchronized(lock) { accepting = false }
                        // A session terminates both when the radio is switched off and when it's seized; distinguish so
                        // the UI can say "radios off" vs "radio busy". The availability receiver corrects this if it flips.
                        _health.value =
                            if (mgr.isAvailable) TransportHealth.Degraded else TransportHealth.Unavailable
                    }
                }
            runCatching { mgr.attach(cb, handler) }.onFailure {
                attaching.set(false)
                reattaching.set(false)
                _health.value = TransportHealth.Degraded
                Log.w(TAG, "Wi-Fi Aware attach threw", it)
            }
        }

    private fun startPublish(gen: Int) {
        val s = session ?: return
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
                    startResponder() // one accept-any responder for the life of this publish session
                }

                override fun onSessionConfigFailed() {
                    Log.w(TAG, "publish config failed")
                }

                override fun onMessageReceived(
                    peerHandle: PeerHandle,
                    message: ByteArray,
                ) {
                    onCueReceived(peerHandle, message, publishSession)
                }
            }
        val builder =
            PublishConfig
                .Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(Protocol.advertise(localNodeId).encodeToByteArray())
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        runCatching { s.publish(builder.build(), cb, handler) }.onFailure { onSessionDead("publish", it) }
    }

    private fun startSubscribe() {
        val s = session ?: return
        // Serialize subscribe (re)starts the same way attach is serialized: onSubscribeStarted arrives
        // asynchronously, so a rearmSubscribe() racing a still-pending subscribe would otherwise leave two
        // subscribe sessions outstanding and leak the one onSubscribeStarted doesn't keep.
        if (!subscribing.compareAndSet(false, true)) return
        val builder = SubscribeConfig.Builder().setServiceName(SERVICE_NAME)
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        // subscribe() is a binder call into the Aware service; if our client died framework-side (NAN
        // cycled) before onAwareSessionTerminated reached us, it throws (SecurityException: "invalid
        // uid+clientId mapping") — that must never escape into the caller's coroutine (a field crash:
        // rearmSubscribe on a dead session killed the process). Treat it as the terminate we missed.
        runCatching { s.subscribe(builder.build(), subscribeCallback, handler) }.onFailure {
            subscribing.set(false)
            onSessionDead("subscribe", it)
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
            synchronized(lock) { discovered.clear() }
            runCatching { subscribeSession?.close() }
            subscribeSession = null
            startSubscribe()
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
        ++attachGen // invalidate any in-flight discovery callbacks from the dead generation
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        session = null
        publishSession = null
        subscribeSession = null
        stopResponder()
        synchronized(lock) { accepting = false }
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
            if (slotBusy()) return@onHandler
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
            // loop re-evaluates at the session==null fast cadence (ATTACH_RETRY_MS) until attach() takes.
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
    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            withTimeoutOrNull(rediscoverDelayMs()) { healSignal.receive() }
            recomputeReachable() // age out the nearby set even on an idle tick
            val now = SystemClock.elapsedRealtime()
            when {
                !hasHardware -> {
                    Unit
                }

                session == null -> {
                    attach()
                }

                // radio came back / first attach failed
                slotBusy() -> {
                    Unit
                }

                // one NDI: a link or handshake is up — wait for the supervisor to free it
                driveSync() -> {
                    Unit
                }

                // a sync-wanted peer we can initiate to → NDP up
                // Re-fire discovery only when a sync is actually blocked on a missing/stale subscribe handle
                // (or we're blind), and no more than once per REARM_COOLDOWN_MS: repeatedly closing/reopening
                // subscribe is what wedges it on these chipsets (persistent onSessionConfigFailed), so we keep
                // it stable while things work and only refresh when a reachable, sync-wanted peer is otherwise
                // unreachable (e.g. it restarted and staled our handle).
                needsRediscovery() && now - lastRearmAt > REARM_COOLDOWN_MS -> {
                    lastRearmAt = now
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
                instantSupported && needsIcmRelight() && now - lastRearmAt > ICM_REARM_COOLDOWN_MS -> {
                    lastRearmAt = now
                    rearmSubscribe()
                }

                else -> {
                    Unit
                }
            }
        }
    }

    /** True while the single NDI slot is occupied by a live link, an in-flight handshake, or an accept. */
    private fun slotBusy(): Boolean = synchronized(lock) { peers.isNotEmpty() || inFlight.isNotEmpty() || accepting }

    /**
     * Whether an on-demand NDP sync to [nodeId] is worth bringing up: its advertised digest differs from ours
     * ([DigestTracker.reconcileWanted]) **and** it isn't already covered by a higher-preference plane. When the
     * composite reports [nodeId] as Bluetooth-linked (see [suppressDataPath]), BLE carries its data, so we spend
     * our single NDP elsewhere; the moment it drops off Bluetooth it's no longer [suppressed] and syncing to it
     * resumes. This gate is applied at every sync-decision site (drive/rediscover/linger/wedge), so a
     * BLE-covered peer never counts as a sync we owe — which also stops the wedge watchdog from ever firing on a
     * "sync" the other plane is quietly handling.
     */
    private fun syncWanted(
        nodeId: String,
        localVersion: Long,
    ): Boolean = nodeId !in suppressed && digestTracker.reconcileWanted(nodeId, localVersion)

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
        val localVersion = storeDigest.version.value
        val now = SystemClock.elapsedRealtime()
        val target =
            synchronized(lock) {
                discovered.entries
                    .firstOrNull { (nodeId, _) ->
                        localNodeId > nodeId && nodeId !in peers.keys &&
                            (retryAfter[nodeId]?.let { now >= it } ?: true) &&
                            syncWanted(nodeId, localVersion)
                    }?.let { it.key to it.value }
            } ?: return false
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
    private fun needsRediscovery(): Boolean {
        if (cueTarget.isEmpty()) return true
        val localVersion = storeDigest.version.value
        val disc = synchronized(lock) { discovered.keys.toSet() }
        // A peer we hear cues from (alive, nearby), want to sync, are the initiator for, yet hold no subscribe
        // handle for — either never discovered, or its handle just fast-failed and failConnect dropped it
        // (it restarted). NOT merely backing off: a contention timeout keeps its handle, so re-arm doesn't
        // churn on the normal fight for the single NDI.
        return cueTarget.keys.any { nodeId ->
            localNodeId > nodeId && nodeId !in peers.keys && nodeId !in disc &&
                syncWanted(nodeId, localVersion)
        }
    }

    /**
     * True when we should re-arm subscribe purely to **re-light Instant Communication Mode** as a *responder*: a
     * sync is owed to a cue-reachable peer we are NOT the initiator for (`localNodeId < nodeId`), so [driveSync]
     * can never bring the NDP up from our side — only the peer can reach us, and only while our NAN radio stays
     * dense (ICM lit). [needsRediscovery] handles the initiator side (and relights ICM as a side effect), but the
     * smallest node has no initiator peers, so without this its ICM never relights and it receives only in the
     * brief post-attach/post-serve windows. Demand-gated: goes false once the peer converges ([syncWanted] →
     * [DigestTracker.reconcileWanted] drops it) or it becomes BLE-suppressed, so a settled mesh does no re-arm.
     */
    private fun needsIcmRelight(): Boolean {
        val localVersion = storeDigest.version.value
        return cueTarget.keys.any { nodeId ->
            localNodeId < nodeId && nodeId !in peers.keys && syncWanted(nodeId, localVersion)
        }
    }

    private fun rediscoverDelayMs(): Long {
        // Detached (no Aware session): retry attach() promptly. A reattach's inline attach() often can't
        // re-enable NAN immediately after tearing the session down (the chipset needs a beat; isAvailable is
        // transiently false and closing our own session fires no availability broadcast), so recovery falls to
        // the loop's `session == null -> attach()`. Without this, a pure-responder node (nothing sync-wanted to
        // *initiate*) would wait a full REDISCOVER_IDLE_MS with no responder — the 2-minute post-serve wedge.
        if (session == null) return ATTACH_RETRY_MS
        // Tick soon while a sync is still owed (a sync-wanted peer we initiate to, maybe backed off / busy)
        // so we retry promptly; hunt aggressively when we know of nobody; otherwise relax (a cue with a new
        // epoch wakes us via healSignal). Doubled when screen-off on battery.
        val localVersion = storeDigest.version.value
        val base =
            when {
                cueTarget.isEmpty() -> REDISCOVER_LONELY_MS

                // blind (no cue targets) → rediscover / recover fast
                cueTarget.keys.any { localNodeId > it && syncWanted(it, localVersion) } -> SYNC_RETRY_IDLE_MS

                // A sync owed to a peer we only respond to → tick around the ICM keepalive cadence so the loop can
                // relight ICM (needsIcmRelight) before the ~30s auto-disable, instead of sleeping REDISCOVER_IDLE_MS.
                instantSupported && needsIcmRelight() -> ICM_REARM_COOLDOWN_MS

                else -> REDISCOVER_IDLE_MS
            }
        val power = powerState.state.value
        return if (power.interactive || power.charging) base else base * 2
    }

    private val subscribeCallback =
        object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                subscribing.set(false)
                subscribeSession = s
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>,
            ) {
                onDiscovered(peerHandle, serviceSpecificInfo)
            }

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
                if (!slotBusy() && now - lastReattachAt > REATTACH_COOLDOWN_MS) {
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
        synchronized(lock) { discovered[peerNodeId] = DiscoveredPeer(advert, peerHandle) }
        subscribeSession?.let { cueTarget[peerNodeId] = CueTarget(peerHandle, it) } // handle valid on subscribe
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
        if (message.isNotEmpty() && message[0] == MSG_FRAME_TAG) {
            onFastFrame(message)
            return
        }
        val cue = parseCue(message) ?: return
        if (cue.nodeId == localNodeId) return
        val firstContact = cueTarget.put(cue.nodeId, CueTarget(handle, sess)) == null
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
        // ping-pong; steady state is covered by discovery, epoch-change, and heartbeat cues.
        if (firstContact) sendCue(cue.nodeId)
    }

    private fun sendCue(nodeId: String) {
        val target = cueTarget[nodeId] ?: return
        runCatching { target.session.sendMessage(target.handle, msgSeq.getAndIncrement(), encodeCue()) }
            .onFailure { cueTarget.remove(nodeId) } // stale handle/session; refreshed on next discover/receive
    }

    private fun cueAll() = cueTarget.keys.toList().forEach { sendCue(it) }

    private fun encodeCue(): ByteArray = "$localNodeId$CUE_SEP${storeDigest.version.value}".encodeToByteArray()

    private data class Cue(
        val nodeId: String,
        val version: Long,
    )

    private fun parseCue(bytes: ByteArray): Cue? {
        val s = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        val i = s.lastIndexOf(CUE_SEP)
        if (i <= 0) return null
        val version = s.substring(i + 1).toLongOrNull() ?: return null
        return Cue(s.substring(0, i), version)
    }

    /**
     * Fast path (see [MeshTransport.fastFanout]): fan a small broadcast frame out to every neighbor over the
     * coordination plane — one Wi-Fi Aware message each, **no data path** — so it lands near-instantly instead
     * of waiting for a cue-driven pairwise NDP sync. Skips a frame that won't fit the message channel
     * ([COORD_MSG_MAX]); those ride the normal data-path flood + store-and-forward instead. Tagged
     * [MSG_FRAME_TAG] so the receiver ([onFastFrame]) tells it apart from a cue. Best-effort by design (the
     * message channel can drop, like a cue); the reliable copy still arrives via flood/custody and is deduped.
     */
    override fun fastFanout(wire: WireEnvelope) {
        if (!hasHardware) return
        val bytes = WireCodec.encodeWire(wire)
        if (bytes.size + 1 > COORD_MSG_MAX) return
        val msg =
            ByteArray(bytes.size + 1).also {
                it[0] = MSG_FRAME_TAG
                bytes.copyInto(it, 1)
            }
        val targets = cueTarget.keys.toList()
        Log.i(TAG, "fast-fanout ${bytes.size}B → ${targets.size} peers") // TEMP diag
        targets.forEach { nodeId ->
            val target = cueTarget[nodeId] ?: return@forEach
            runCatching { target.session.sendMessage(target.handle, msgSeq.getAndIncrement(), msg) }
                .onFailure { cueTarget.remove(nodeId) } // stale handle/session; refreshed on next discover/receive
        }
    }

    /**
     * Targeted sibling of [fastFanout] (see [MeshTransport.fastSend]): send a small point-to-point frame to one
     * peer over the coordination plane, **no data path** — e.g. a broadcast/group delivery receipt straight back
     * to the message's author, so the tick works even when the message was delivered by a fast-fanout with no
     * live NDP. Best-effort: no-op if [to] isn't a current cue target or the frame won't fit ([COORD_MSG_MAX]).
     */
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (!hasHardware) return
        val target = cueTarget[to.nodeId] ?: return // not coordination-plane-reachable → best-effort skip
        val bytes = WireCodec.encodeWire(wire)
        if (bytes.size + 1 > COORD_MSG_MAX) return
        val msg =
            ByteArray(bytes.size + 1).also {
                it[0] = MSG_FRAME_TAG
                bytes.copyInto(it, 1)
            }
        Log.i(TAG, "fast-send ${bytes.size}B → ${to.nodeId}") // TEMP diag
        runCatching { target.session.sendMessage(target.handle, msgSeq.getAndIncrement(), msg) }
            .onFailure { cueTarget.remove(to.nodeId) } // stale handle/session; refreshed on next discover/receive
    }

    /**
     * A broadcast frame arrived over the coordination plane (tagged [MSG_FRAME_TAG] by a peer's [fastFanout]):
     * decode it and inject it into the normal inbound path exactly like a data-path frame, so the router
     * dedups, relays, and delivers it with no NDP. A later flood/custody copy is dropped by the receiver's
     * SeenSet, so a dropped fast frame self-heals — the fast path is a latency win layered over the reliable one.
     */
    private fun onFastFrame(message: ByteArray) {
        val wire = WireCodec.decodeWire(message.copyOfRange(1, message.size)) ?: return
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (envelope.senderId == localNodeId) return // our own frame echoed back — ignore
        Log.i(TAG, "fast-frame from ${envelope.senderId} id=${envelope.id}") // TEMP diag
        noteReachable(Peer(envelope.senderId))
        _inbound.tryEmit(InboundFrame(wire, envelope, envelope.senderId))
    }

    // --- Client side (initiator) ---

    private fun initiateTo(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        peerHandle: PeerHandle,
    ) {
        val sub = subscribeSession ?: return
        if (!beginConnect(peerNodeId)) return
        Log.i(TAG, "initiating to $peerNodeId")
        // The handle is a subscribe-session handle (from discovery); only that can initiate an NDP here.
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, peerHandle).setPskPassphrase(PSK).build()
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
        // Send our identity first so the accept-any responder on the other side knows who connected.
        val sent = runCatching { LinkHandshake.writeHello(link.output, localNodeId) }.isSuccess
        if (!sent) {
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
                .setPskPassphrase(PSK)
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
        if (filed) Log.i(TAG, "responder listening on port ${ss.localPort}")
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
            runCatching { socket.soTimeout = 0 } // back to blocking reads for the normal loop
            Log.i(TAG, "accepted client $clientNodeId")
            registerConn(clientNodeId, advert, link, callback = null) // shared responder: no per-peer callback
        } finally {
            endAccept()
        }
    }

    private fun stopResponder() {
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
        synchronized(lock) {
            inFlight.remove(peerNodeId)
            retryAfter.remove(peerNodeId)
        }
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
                    val quiescence = if (otherSyncWanted(conn.nodeId)) CONTENDED_QUIESCENCE_MS else QUIESCENCE_MS
                    idle >= quiescence || held >= SYNC_MAX_WINDOW_MS
                } else {
                    idle >= RESPONDER_QUIESCENCE_MS || held >= RESPONDER_MAX_HOLD_MS
                }
            if (done) {
                // Record the sync at our *post-backfill* digest: if we gained nothing new after ingesting the
                // peer's data, our version now equals theirs and the pair stays quiet (the identical-digest
                // skip); if we later gain something, the version moves and re-triggers. Initiator-only — see kdoc.
                if (isInitiator) digestTracker.onReconciled(conn.nodeId, storeDigest.version.value)
                Log.i(TAG, "sync with ${conn.nodeId} done (initiator=$isInitiator idle=${idle}ms held=${held}ms) — disconnecting")
                teardownPeer(conn.nodeId, backoffMs = 0) // clean sync: no anti-churn backoff needed
                break
            }
        }
    }

    /**
     * True if some peer *other than* [current] is sync-wanted and we're its initiator — i.e. another pair is
     * waiting on our single NDI. Lets [superviseLink] cut the post-backfill linger short so the next neighbor
     * gets the message without the full [QUIESCENCE_MS] hold. Reads the concurrent [cueTarget] (every peer we
     * hear cues from — the signal [rediscoverDelayMs] also uses); [DigestTracker] is internally synchronized.
     */
    private fun otherSyncWanted(current: String): Boolean {
        val localVersion = storeDigest.version.value
        return cueTarget.keys.any { nodeId ->
            nodeId != current && localNodeId > nodeId && syncWanted(nodeId, localVersion)
        }
    }

    private fun failConnect(
        peerNodeId: String,
        callback: ConnectivityManager.NetworkCallback,
        staleHandle: Boolean,
    ) {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        synchronized(lock) {
            inFlight.remove(peerNodeId)
            retryAfter[peerNodeId] = SystemClock.elapsedRealtime() + CONNECT_BACKOFF_MS
            // A *fast* failure means this subscribe handle is dead (the peer restarted): drop it so
            // needsRediscovery re-arms subscribe for a fresh one. A slow (contention) failure keeps the handle
            // — the peer was just busy on its one NDI, and its responder re-attaches after serving to free up.
            if (staleHandle) discovered.remove(peerNodeId)
        }
        // A persistently un-formable data plane despite a sync being owed is caught by the wedge watchdog
        // ([checkWedge]) — we no longer nudge the peer to re-attach (that hint was net-negative: it can't clear
        // a leaked responder requestNetwork, and its churn during a wedge is what leaked one).
        noteLinkEnded() // yield to a different sync-wanted peer once the radio settles
        Log.i(TAG, "handshake with $peerNodeId ended without a link (stale=$staleHandle)")
    }

    private fun teardownPeer(
        peerNodeId: String,
        backoffMs: Long = REFUSED_BACKOFF_MS,
    ) {
        val conn = peers.remove(peerNodeId) ?: return
        conn.close()
        // Only client links own a per-peer network callback; server links share the responder — leave it.
        val wasServerLink = !conn.isInitiator
        conn.callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
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
        // A served client just left. On these chipsets a responder that has served one NDP is wedged — it
        // won't accept a second client, and it blocks this node's own client role — and a bare responder
        // re-arm (fresh requestNetwork) does NOT clear it; only a full re-attach does (Phase 0, on-device).
        // So re-attach after every serve to restore serve-ability, once the NDI settles and the slot is free.
        // A pure initiator never serves, so it never hits this path — it keeps initiating on one session
        // (verified Phase 0: 7 initiates / 0 re-attach). Stamps lastReattachAt so the wedged-subscribe
        // recovery doesn't pile a second re-attach on top.
        if (wasServerLink) scheduleServeReattach(peerNodeId)
    }

    /**
     * Re-attach after serving a client, once the NDI slot frees. A served responder is wedged (type 1) and only
     * a full re-attach restores serve-ability. If the slot is momentarily busy (this node started a client link
     * or accept in the meantime), **reschedule** rather than drop it — a dropped re-attach used to strand the
     * responder wedged until the watchdog restart. Stops when the session is gone (the discovery loop's
     * `session == null -> attach()` fast-retry rebuilds it) or once the re-attach fires.
     */
    private fun scheduleServeReattach(peerNodeId: String) {
        handler.postDelayed({
            when {
                session == null || publishSession == null -> {
                    Unit
                }

                // detached; the loop re-attaches
                slotBusy() -> {
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
            // been released (SETTLE) — else requestNetwork fails with "no interfaces available".
            if (peers.isNotEmpty() || inFlight.isNotEmpty() || accepting) return false
            if (SystemClock.elapsedRealtime() < lastLinkEndedAt + SETTLE_MS) return false
            retryAfter[peerNodeId]?.let { if (SystemClock.elapsedRealtime() < it) return false } // backing off
            inFlight.add(peerNodeId)
            true
        }

    /** Reserve the single NDI slot for an inbound accept (mirrors [beginConnect]); paired with [endAccept]. */
    private fun beginAccept(): Boolean =
        synchronized(lock) {
            if (peers.isNotEmpty() || inFlight.isNotEmpty() || accepting) return false
            if (SystemClock.elapsedRealtime() < lastLinkEndedAt + SETTLE_MS) return false
            accepting = true
            true
        }

    private fun endAccept() {
        synchronized(lock) { accepting = false }
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
                reachablePeers.remove(nodeId)
                digestTracker.forget(nodeId)
            }
    }

    // --- Availability ---

    private val availabilityReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val mgr = awareManager ?: return
                if (mgr.isAvailable) {
                    attach() // onHandler-funneled; the attaching/session guards make a redundant call a no-op
                } else {
                    onHandler {
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
                        synchronized(lock) { accepting = false }
                        cueTarget.clear()
                        lastSeenAt.clear()
                        reachablePeers.clear()
                        _neighbors.value = emptySet() // symmetric with stop(); teardownPeer already recomputes it empty
                        _reachable.value = emptySet()
                    }
                }
            }
        }

    private fun registerAvailability() {
        if (availabilityRegistered) return
        appContext.registerReceiver(
            availabilityReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
        )
        availabilityRegistered = true
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        runCatching { appContext.unregisterReceiver(availabilityReceiver) }
        availabilityRegistered = false
    }

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

        /** True on a device with Wi-Fi Aware hardware — the composite includes this plane only if so. */
        fun isSupported(context: Context): Boolean =
            context.getSystemService(Context.WIFI_AWARE_SERVICE) != null &&
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE)

        // The NAN service both nodes publish/subscribe. Bumped on every breaking wire change so a build across
        // the break hard-partitions at discovery rather than silently mis-decoding. ".v3" was the cue-format
        // change (content-digest version, not a monotone epoch); ".v4" re-typed EncEnvelope nonce/ct +
        // WrappedKey.wk from base64 String to raw CBOR @ByteString (a v3 node can't decode a v4 E2E frame);
        // ".v5" added the DIGEST data-path record (a v4 node has no DIGEST case and drops the link on it); ".v6"
        // removed the coordination-plane re-attach hint (tag 0x02) — a clean cut so no .v5 node keeps spamming it.
        const val SERVICE_NAME = "app.getknit.knit.MESH.v6"

        // Fixed app-wide passphrase for link-layer (NDP) encryption. Real authentication is the per-frame
        // Ed25519 signature + E2E layer above the transport; this only keeps the data path off open air.
        const val PSK = "knit-mesh-nan-psk-v1"

        // 2.4 GHz for instant mode: better range than 5 GHz (range is priority #2), at some throughput cost.
        const val INSTANT_BAND = ScanResult.WIFI_BAND_24_GHZ

        // Separator in a cue's `nodeId|epoch` payload (nodeIds/epochs never contain it).
        const val CUE_SEP = '|'

        // Coordination-plane message multiplexing. A cue is a plain "nodeId|version" text string whose first
        // byte is a printable base64 nodeId char, so a single non-printable tag byte cleanly distinguishes a
        // small broadcast frame fanned out over the message channel (fastFanout / onFastFrame, [MSG_FRAME_TAG]).
        // Cues stay untagged (byte-for-byte unchanged). (Tag 0x02 was a since-removed "please re-attach" nudge.)
        const val MSG_FRAME_TAG: Byte = 0x01

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
        // fire immediately after a reattach teardown recovers in seconds, not a full REDISCOVER_IDLE_MS.
        const val ATTACH_RETRY_MS = 3_000L

        // Gap after a link/handshake ends before the next requestNetwork, so the framework releases the one
        // NDI first (else "no interfaces available").
        const val SETTLE_MS = 1_500L

        // Wait this long after a peer's cue advances its digest before waking the sync loop, so a broadcast
        // fast-fanout racing the cue can deliver + carry the frame and converge the digest first — turning the
        // NDP sync into a fallback for genuinely-missed data instead of a reflex that fights the fanout for the
        // coordination plane. Short: the cue and the fast-frame are sent back-to-back, so they arrive within it.
        const val CUE_SETTLE_MS = 600L

        // Idle discovery-loop cadence: aggressive when we know of nobody (re-fire one-shot discovery), long
        // once we've discovered/heard peers (a cue with a new epoch wakes us via healSignal). ×2 screen-off.
        const val REDISCOVER_LONELY_MS = 8_000L
        const val REDISCOVER_IDLE_MS = 120_000L

        // Cue heartbeat: re-advertise our epoch to known peers periodically (covers best-effort message loss
        // and a peer that discovered us but whom we haven't discovered back).
        const val CUE_HEARTBEAT_MS = 30_000L

        // Temporary: how often to dump connection-engine decision state to logcat while debugging.
        const val DIAG_INTERVAL_MS = 12_000L

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

        // Recovery re-attach when subscribe is wedged: min spacing between resets, and a small settle delay
        // before the reset fires.
        const val REATTACH_COOLDOWN_MS = 20_000L
        const val REATTACH_DELAY_MS = 800L

        // Fallback release of the reattach() single-flight guard if the fresh attach never calls back at all
        // (normally its onAttached/onAttachFailed clears it in well under a second). Bounds "stuck reattaching".
        const val ATTACH_WATCHDOG_MS = 10_000L

        // Leaked-request wedge watchdog ([checkWedge]): how often to evaluate, and how long a sync must stay
        // *owed with no link forming* (the owed-episode, not time-since-last-link) before self-restarting. The
        // restart window doubles as the min restart spacing so a persistently-unreachable peer can't loop us.
        const val WEDGE_CHECK_MS = 30_000L
        const val WEDGE_RESTART_MS = 180_000L

        // Min spacing between subscribe re-arms to re-discover a stale/missing peer handle: long enough that a
        // departed peer's cue target is pruned (cue send fails) before we'd re-arm again, so we don't churn
        // subscribe toward its wedge state; short enough that a restarted peer is reconnected within ~1 tick.
        const val REARM_COOLDOWN_MS = 15_000L

        // Min spacing between subscribe re-arms done purely to keep Instant Communication Mode lit on a
        // pure-responder node (see [needsIcmRelight] / the discovery loop). Just under the framework's ~30s ICM
        // auto-disable so a relit window is refreshed before it lapses, but no faster — subscribe re-arm churn
        // risks the subscribe wedge, so this only fires while a sync is actually owed to a peer we can't initiate to.
        const val ICM_REARM_COOLDOWN_MS = 25_000L
    }
}
