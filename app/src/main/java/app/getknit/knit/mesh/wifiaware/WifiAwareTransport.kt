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
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.DigestTracker
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.isValidBlobHash
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
 * - **Data plane** — a single ephemeral NDP TCP socket (link-local IPv6, framed by [AwareFraming]) brought
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
 * initiator, learning who via the first [AwareFraming.Type.HELLO] record); only *subscribe* is ever
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

    /** False on hardware without Wi-Fi Aware — the transport stays [TransportHealth.Degraded] and the UI gates. */
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

    // The one accept-any responder: its ServerSocket, network callback, and the accept loop.
    @Volatile private var responderSocket: ServerSocket? = null
    @Volatile private var responderCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var acceptJob: Job? = null

    // The single live data-path link, keyed by peer nodeId (at most one entry — one NDI).
    private val peers = ConcurrentHashMap<String, PeerConn>()

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

    private var powerJob: Job? = null
    private var loopJob: Job? = null
    private var cueJob: Job? = null
    private var cueHeartbeatJob: Job? = null
    private var diagJob: Job? = null
    private var availabilityRegistered = false

    private data class DiscoveredPeer(val advert: Protocol.PeerWire, val peerHandle: PeerHandle)

    /** A cue destination: a [PeerHandle] and the discovery session it is valid on (sendMessage target). */
    private data class CueTarget(val handle: PeerHandle, val session: DiscoverySession)

    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Degraded
            Log.w(TAG, "Wi-Fi Aware unsupported on this device; mesh disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            instantSupported = runCatching {
                awareManager?.characteristics?.isInstantCommunicationModeSupported() == true
            }.getOrDefault(false)
            registerAvailability()
            attach()
            loopJob = scope.launch { discoveryLoop() }
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
            // Re-cue neighbors and wake the loop the moment our carried set changes, so a peer that now wants
            // our new data can pull it.
            cueJob = scope.launch {
                storeDigest.version.drop(1).collect { cueAll(); healSignal.trySend(Unit) }
            }
            // Heartbeat cue covers best-effort message loss + a peer that hasn't discovered us yet.
            cueHeartbeatJob = scope.launch {
                while (scope.isActive) {
                    delay(CUE_HEARTBEAT_MS)
                    cueAll()
                }
            }
            diagJob = scope.launch {
                while (scope.isActive) {
                    delay(DIAG_INTERVAL_MS)
                    logState()
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
        synchronized(lock) { disc = discovered.keys.toSet(); backoff = retryAfter.toMap() }
        val cues = cueTarget.keys.toSet()
        val wanted = cues.filter { localNodeId > it && digestTracker.reconcileWanted(it, v) }
        val initiable = wanted.filter { it in disc && (backoff[it]?.let { t -> now >= t } ?: true) }
        Log.i(
            TAG,
            "state ver=$v live=${peers.keys} disc=$disc cue=$cues wanted=$wanted " +
                "initiable=$initiable reach=${_reachable.value.map { it.nodeId }} tracker[${digestTracker.debug()}]",
        )
    }

    override fun stop() {
        loopJob?.cancel()
        powerJob?.cancel()
        cueJob?.cancel()
        cueHeartbeatJob?.cancel()
        diagJob?.cancel()
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
            inFlight.clear(); discovered.clear(); retryAfter.clear(); accepting = false
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

    override suspend fun send(wire: WireEnvelope, to: Peer?) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) peers.values.toList() else listOfNotNull(peers[to.nodeId])
        targets.forEach { it.outbound.trySend(Outbound.Frame(bytes)) }
        if (targets.isNotEmpty()) metrics.onBytesSent(bytes.size.toLong() * targets.size)
    }

    override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) {
        peers[to.nodeId]?.outbound?.trySend(Outbound.FileSend(file, meta))
    }

    // --- Attach / discovery ---

    private fun attach() {
        val mgr = awareManager ?: return
        if (!mgr.isAvailable) {
            _health.value = TransportHealth.Degraded
            return
        }
        if (session != null) return
        if (!attaching.compareAndSet(false, true)) return // an attach is already in flight
        if (session != null) { // won the CAS, but a prior attach completed in the meantime
            attaching.set(false)
            return
        }
        val cb = object : AttachCallback() {
            override fun onAttached(newSession: WifiAwareSession) {
                attaching.set(false)
                if (session != null) { // serialized, so shouldn't happen — but never orphan a session
                    runCatching { newSession.close() }
                    return
                }
                session = newSession
                _health.value = TransportHealth.Healthy
                startPublish()
                startSubscribe()
            }

            override fun onAttachFailed() {
                attaching.set(false)
                _health.value = TransportHealth.Degraded
                Log.w(TAG, "Wi-Fi Aware attach failed")
            }

            override fun onAwareSessionTerminated() {
                attaching.set(false)
                subscribing.set(false)
                session = null
                publishSession = null
                subscribeSession = null
                stopResponder()
                synchronized(lock) { accepting = false }
                _health.value = TransportHealth.Degraded
            }
        }
        runCatching { mgr.attach(cb, handler) }.onFailure {
            attaching.set(false)
            _health.value = TransportHealth.Degraded
            Log.w(TAG, "Wi-Fi Aware attach threw", it)
        }
    }

    private fun startPublish() {
        val builder = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(Protocol.advertise(localNodeId).encodeToByteArray())
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        session?.publish(builder.build(), publishCallback, handler)
    }

    private fun startSubscribe() {
        val s = session ?: return
        // Serialize subscribe (re)starts the same way attach is serialized: onSubscribeStarted arrives
        // asynchronously, so a rearmSubscribe() racing a still-pending subscribe would otherwise leave two
        // subscribe sessions outstanding and leak the one onSubscribeStarted doesn't keep.
        if (!subscribing.compareAndSet(false, true)) return
        val builder = SubscribeConfig.Builder().setServiceName(SERVICE_NAME)
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        s.subscribe(builder.build(), subscribeCallback, handler)
    }

    /**
     * Re-fires Wi-Fi Aware's one-shot discovery by restarting **only the subscribe** session (publish and
     * its responder stay up, so we remain discoverable/serviceable and no live NDP anchored to publish is
     * disturbed). Called by [discoveryLoop] only while the NDI slot is free (no live link/handshake), so
     * there is no subscribe-anchored client NDP to drop.
     */
    private fun rearmSubscribe() {
        if (session == null || subscribing.get()) return // don't stack a rearm on a pending subscribe
        synchronized(lock) { discovered.clear() }
        runCatching { subscribeSession?.close() }
        subscribeSession = null
        startSubscribe()
    }

    /**
     * Recover a wedged discovery layer: drop the whole [WifiAwareSession] and re-attach (fresh
     * publish/subscribe/responder). Re-subscribing alone doesn't clear a subscribe stuck in
     * `onSessionConfigFailed` on these chipsets, but a full session reset does. Guarded on a free NDI slot
     * so it never drops a live sync.
     */
    private fun reattach() {
        if (slotBusy()) return
        Log.w(TAG, "re-attaching to recover wedged subscribe")
        stopResponder()
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        publishSession = null
        subscribeSession = null
        session = null
        attaching.set(false)
        subscribing.set(false)
        attach()
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
                !hasHardware -> Unit
                session == null -> attach() // radio came back / first attach failed
                slotBusy() -> Unit // one NDI: a link or handshake is up — wait for the supervisor to free it
                driveSync() -> Unit // a sync-wanted peer we can initiate to → NDP up
                // Re-fire discovery only when a sync is actually blocked on a missing/stale subscribe handle
                // (or we're blind), and no more than once per REARM_COOLDOWN_MS: repeatedly closing/reopening
                // subscribe is what wedges it on these chipsets (persistent onSessionConfigFailed), so we keep
                // it stable while things work and only refresh when a reachable, sync-wanted peer is otherwise
                // unreachable (e.g. it restarted and staled our handle).
                needsRediscovery() && now - lastRearmAt > REARM_COOLDOWN_MS -> {
                    lastRearmAt = now
                    rearmSubscribe()
                }
                else -> Unit
            }
        }
    }

    /** True while the single NDI slot is occupied by a live link, an in-flight handshake, or an accept. */
    private fun slotBusy(): Boolean =
        synchronized(lock) { peers.isNotEmpty() || inFlight.isNotEmpty() || accepting }

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
        val target = synchronized(lock) {
            discovered.entries.firstOrNull { (nodeId, _) ->
                localNodeId > nodeId && nodeId !in peers.keys &&
                    (retryAfter[nodeId]?.let { now >= it } ?: true) &&
                    digestTracker.reconcileWanted(nodeId, localVersion)
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
     * gets a fresh handle. Rate-limited by [REARM_COOLDOWN_MS] so a truly-gone peer (whose cue target is
     * pruned within a heartbeat once our cues to it fail) can't spin subscribe into its wedge state.
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
                digestTracker.reconcileWanted(nodeId, localVersion)
        }
    }

    private fun rediscoverDelayMs(): Long {
        // Tick soon while a sync is still owed (a sync-wanted peer we initiate to, maybe backed off / busy)
        // so we retry promptly; hunt aggressively when we know of nobody; otherwise relax (a cue with a new
        // epoch wakes us via healSignal). Doubled when screen-off on battery.
        val localVersion = storeDigest.version.value
        val base = when {
            cueTarget.isEmpty() -> REDISCOVER_LONELY_MS // blind (no cue targets) → rediscover / recover fast
            cueTarget.keys.any { localNodeId > it && digestTracker.reconcileWanted(it, localVersion) } -> SYNC_RETRY_IDLE_MS
            else -> REDISCOVER_IDLE_MS
        }
        val power = powerState.state.value
        return if (power.interactive || power.charging) base else base * 2
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            publishSession = s
            startResponder() // one accept-any responder for the life of this publish session
        }

        override fun onSessionConfigFailed() {
            Log.w(TAG, "publish config failed")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            onCueReceived(peerHandle, message, publishSession)
        }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
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

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
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

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            onCueReceived(peerHandle, message, subscribeSession)
        }
    }

    /** Record a discovered peer, note it reachable, and cue it (announce our epoch); it cues back. */
    private fun onDiscovered(peerHandle: PeerHandle, ssi: ByteArray) {
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
    private fun onCueReceived(handle: PeerHandle, message: ByteArray, session: DiscoverySession?) {
        val sess = session ?: return
        val cue = parseCue(message) ?: return
        if (cue.nodeId == localNodeId) return
        val firstContact = cueTarget.put(cue.nodeId, CueTarget(handle, sess)) == null
        noteReachable(Peer(cue.nodeId))
        if (digestTracker.onCue(cue.nodeId, cue.version)) healSignal.trySend(Unit) // became reconcile-wanted
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

    private data class Cue(val nodeId: String, val version: Long)

    private fun parseCue(bytes: ByteArray): Cue? {
        val s = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        val i = s.lastIndexOf(CUE_SEP)
        if (i <= 0) return null
        val version = s.substring(i + 1).toLongOrNull() ?: return null
        return Cue(s.substring(0, i), version)
    }

    // --- Client side (initiator) ---

    private fun initiateTo(peerNodeId: String, advert: Protocol.PeerWire, peerHandle: PeerHandle) {
        val sub = subscribeSession ?: return
        if (!beginConnect(peerNodeId)) return
        Log.i(TAG, "initiating to $peerNodeId")
        // The handle is a subscribe-session handle (from discovery); only that can initiate an NDP here.
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, peerHandle).setPskPassphrase(PSK).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        val done = AtomicBoolean(false)
        lateinit var cb: ConnectivityManager.NetworkCallback
        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
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
        val requested = runCatching {
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
        val socket = runCatching { network.socketFactory.createSocket(ip, port) }.getOrNull()
            ?: return failConnect(peerNodeId, cb, staleHandle = false) // NDP came up; socket issue, not a handle problem
        // Send our identity first so the accept-any responder on the other side knows who connected.
        val sent = runCatching {
            val out = socket.getOutputStream()
            out.write(AwareFraming.encode(AwareFraming.Type.HELLO, helloBytes()))
            out.flush()
        }.isSuccess
        if (!sent) {
            runCatching { socket.close() }
            return failConnect(peerNodeId, cb, staleHandle = false)
        }
        registerConn(peerNodeId, advert, socket, BufferedInputStream(socket.getInputStream()), cb)
    }

    // --- Server side (persistent accept-any responder) ---

    private fun startResponder() {
        val pub = publishSession ?: return
        stopResponder()
        val ss = runCatching { ServerSocket(0) }.getOrElse {
            Log.w(TAG, "responder ServerSocket bind failed", it)
            return
        }
        responderSocket = ss
        val specifier = WifiAwareNetworkSpecifier.Builder(pub).setPskPassphrase(PSK).setPort(ss.localPort).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (acceptJob?.isActive != true) acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(ss) }
            }

            override fun onLost(network: Network) {
                // The shared responder network dropped; a server-side link on it will end via its read loop.
                // Re-arm after a beat so we can serve clients again.
                Log.i(TAG, "responder network lost; re-arming")
                handler.postDelayed({ if (session != null && publishSession != null) startResponder() }, RESPONDER_REARM_MS)
            }
        }
        responderCallback = cb
        runCatching { connectivity.requestNetwork(request, cb, handler) }.onFailure {
            Log.w(TAG, "responder requestNetwork failed", it)
            stopResponder()
        }
        Log.i(TAG, "responder listening on port ${ss.localPort}")
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
            val input = BufferedInputStream(socket.getInputStream())
            runCatching { socket.soTimeout = ACCEPT_HELLO_TIMEOUT_MS } // bound the identity read so a stall can't pin the slot
            val first = runCatching { AwareFraming.read(input) }.getOrNull()
            if (first?.type != AwareFraming.Type.HELLO) {
                runCatching { socket.close() }
                return
            }
            val advert = Protocol.parse(first.payload.decodeToString())
            val clientNodeId = advert.nodeId
            // Enforce the tie-break server side (we must be the smaller id) and don't double-link.
            if (clientNodeId == localNodeId || localNodeId >= clientNodeId || clientNodeId in peers.keys) {
                runCatching { socket.close() }
                return
            }
            runCatching { socket.soTimeout = 0 } // back to blocking reads for the normal loop
            Log.i(TAG, "accepted client $clientNodeId")
            registerConn(clientNodeId, advert, socket, input, callback = null) // shared responder: no per-peer callback
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
        socket: Socket,
        input: BufferedInputStream,
        callback: ConnectivityManager.NetworkCallback?,
    ) {
        val conn = PeerConn(peerNodeId, advert, socket, callback)
        val now = SystemClock.elapsedRealtime()
        conn.linkStartedAt = now
        conn.lastActivityAt = now // start the quiescence window at link-up (backfill will extend it)
        val prev = peers.put(peerNodeId, conn)
        prev?.close() // a stale link to the same peer (shouldn't happen, but never leak it)
        synchronized(lock) { inFlight.remove(peerNodeId); retryAfter.remove(peerNodeId) }
        conn.readerJob = scope.launch(Dispatchers.IO) { readLoop(conn, input) }
        conn.writerJob = scope.launch(Dispatchers.IO) { writeLoop(conn) }
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
     * [PeerConn.lastActivityAt] is touched on both read and write, so an idle window means *neither* side has
     * more to send — a premature teardown just re-triggers on the next cue.
     */
    @Suppress("LoopWithTooManyJumpStatements") // poll → skip-mid-file (continue) or done (break)
    private suspend fun superviseLink(conn: PeerConn) {
        val isInitiator = conn.networkCallback != null
        while (scope.isActive && peers[conn.nodeId] === conn) {
            delay(QUIESCENCE_POLL_MS)
            if (conn.rxInProgress || conn.txInProgress) continue // never tear down mid file transfer
            val now = SystemClock.elapsedRealtime()
            val idle = now - conn.lastActivityAt
            val held = now - conn.linkStartedAt
            val done = if (isInitiator) idle >= QUIESCENCE_MS || held >= SYNC_MAX_WINDOW_MS
            else idle >= RESPONDER_QUIESCENCE_MS || held >= RESPONDER_MAX_HOLD_MS
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
            // — the peer was just busy on its one NDI, and with Phase 2 its responder re-attaches to free up.
            if (staleHandle) discovered.remove(peerNodeId)
        }
        noteLinkEnded() // yield to a different sync-wanted peer once the radio settles
        Log.i(TAG, "handshake with $peerNodeId ended without a link (stale=$staleHandle)")
    }

    private fun teardownPeer(peerNodeId: String, backoffMs: Long = REFUSED_BACKOFF_MS) {
        val conn = peers.remove(peerNodeId) ?: return
        conn.close()
        // Only client links own a per-peer network callback; server links share the responder — leave it.
        val wasServerLink = conn.networkCallback == null
        conn.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        // Back off an *initiator* link that ended without a clean sync (a reset — usually the responder was
        // busy on its one NDI, or the NDP dropped), so we don't immediately re-hammer a peer we can't reach
        // yet. A clean quiescence teardown passes backoffMs = 0: it already recorded the sync, so the peer
        // isn't sync-wanted anyway, and this keeps active-chat re-sync latency low.
        if (conn.networkCallback != null && backoffMs > 0) {
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
        if (wasServerLink) {
            handler.postDelayed({
                if (session != null && publishSession != null && !slotBusy()) {
                    lastReattachAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "re-attach after serving $peerNodeId")
                    reattach()
                }
            }, SETTLE_MS)
        }
    }

    /** Open the SETTLE gate after a link/handshake ends and wake the loop once the NDI has been released. */
    private fun noteLinkEnded() {
        lastLinkEndedAt = SystemClock.elapsedRealtime()
        scope.launch { delay(SETTLE_MS); healSignal.trySend(Unit) }
    }

    // --- Socket I/O ---

    private suspend fun readLoop(conn: PeerConn, input: BufferedInputStream) {
        try {
            while (scope.isActive) {
                val msg = AwareFraming.read(input) ?: break
                when (msg.type) {
                    AwareFraming.Type.FRAME -> { conn.touch(); handleFrame(conn, msg.payload) }
                    AwareFraming.Type.FILE_HEADER -> { conn.touch(); conn.beginRxFile(msg.payload) }
                    AwareFraming.Type.FILE_CHUNK -> { conn.touch(); conn.appendRxFile(msg.payload) }
                    AwareFraming.Type.FILE_END -> { conn.touch(); endRxFile(conn) }
                    AwareFraming.Type.KEEPALIVE -> Unit // legacy record from an older peer; ignore
                    AwareFraming.Type.HELLO -> Unit // identity already consumed at accept; ignore any stray
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "read loop ended for ${conn.nodeId}: ${e.message}")
        } finally {
            teardownPeer(conn.nodeId)
        }
    }

    private fun handleFrame(conn: PeerConn, bytes: ByteArray) {
        val wire = WireCodec.decodeWire(bytes)
        if (wire == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        val envelope = WireCodec.decodeEnvelope(wire.signed)
        if (envelope == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        _inbound.tryEmit(InboundFrame(wire, envelope, conn.nodeId))
    }

    /**
     * Drains a peer's outbound queue to its socket. Frames are written immediately; a file is streamed
     * as header→chunks→end, but pending frames are flushed *between* chunks so a large blob never stalls
     * live traffic. Only one file streams at a time (later files wait in [PeerConn.stash]).
     */
    private suspend fun writeLoop(conn: PeerConn) {
        try {
            val out = BufferedOutputStream(conn.socket.getOutputStream())
            while (scope.isActive) {
                val item = conn.nextOutbound() ?: break
                when (item) {
                    is Outbound.Frame -> {
                        AwareFraming.write(out, AwareFraming.Type.FRAME, item.bytes)
                        out.flush()
                        conn.touch()
                    }
                    is Outbound.FileSend -> streamFile(conn, out, item)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "write loop ended for ${conn.nodeId}: ${e.message}")
            teardownPeer(conn.nodeId)
        }
    }

    @Suppress("NestedBlockDepth") // header → (drain frames → read chunk → write chunk) loop → end
    private fun streamFile(conn: PeerConn, out: OutputStream, item: Outbound.FileSend) {
        conn.txInProgress = true // don't let the supervisor tear down mid transfer
        try {
            val header = FileHeaderWire(item.meta.kind.name, item.meta.key, item.meta.mime)
            AwareFraming.write(out, AwareFraming.Type.FILE_HEADER, AwareFraming.encodeFileHeader(header))
            item.file.inputStream().use { input ->
                val buf = ByteArray(AwareFraming.FILE_CHUNK_BYTES)
                while (true) {
                    conn.drainFramesInto(out) // interleave live frames between chunks
                    val n = input.read(buf)
                    if (n == -1) break
                    AwareFraming.write(out, AwareFraming.Type.FILE_CHUNK, if (n == buf.size) buf else buf.copyOf(n))
                    conn.touch()
                }
            }
            AwareFraming.write(out, AwareFraming.Type.FILE_END)
            out.flush()
            conn.touch()
        } finally {
            conn.txInProgress = false
        }
    }

    private fun endRxFile(conn: PeerConn) {
        val (temp, meta) = conn.finishRxFile() ?: return
        scope.launch(Dispatchers.IO) { finalizeIncomingFile(conn.nodeId, temp, meta) }
    }

    /** Moves a fully-received file into the cache under a safe name and announces it (avatar by node, attachment by hash). */
    private fun finalizeIncomingFile(nodeId: String, temp: File, meta: FileMeta) {
        // [meta.key] is peer-supplied and interpolated into the filename: reject anything but a 64-hex
        // content hash so a "../" can't escape the cache dir (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            temp.delete()
            Log.w(TAG, "Rejecting ${meta.kind} from $nodeId: malformed blob key")
            return
        }
        val dest = when (meta.kind) {
            FileKind.AVATAR -> {
                appContext.cacheDir.listFiles { f -> f.name.startsWith("avatar-$nodeId-") }?.forEach { it.delete() }
                File(appContext.cacheDir, "avatar-$nodeId-${meta.key}.jpg")
            }
            FileKind.ATTACHMENT -> File(appContext.cacheDir, "attach-${meta.key}.${extForMime(meta.mime)}")
        }
        val cacheRoot = appContext.cacheDir.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(cacheRoot)) {
            temp.delete()
            Log.w(TAG, "Rejecting ${meta.kind} from $nodeId: path escapes cache dir")
            return
        }
        runCatching {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }.onSuccess {
            _incomingFiles.tryEmit(ReceivedFile(nodeId, dest.absolutePath, meta.kind, meta.key, meta.mime))
        }.onFailure {
            temp.delete()
            dest.delete()
            Log.w(TAG, "Failed saving ${meta.kind} from $nodeId", it)
        }
    }

    // --- Connection admission (single NDI slot) ---

    private fun beginConnect(peerNodeId: String): Boolean = synchronized(lock) {
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
    private fun beginAccept(): Boolean = synchronized(lock) {
        if (peers.isNotEmpty() || inFlight.isNotEmpty() || accepting) return false
        if (SystemClock.elapsedRealtime() < lastLinkEndedAt + SETTLE_MS) return false
        accepting = true
        true
    }

    private fun endAccept() {
        synchronized(lock) { accepting = false }
    }

    private fun refreshNeighbors() {
        _neighbors.value = peers.values.map { Peer(it.nodeId, it.advert.protoVersion, it.advert.capabilities) }.toSet()
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
        val live = peers.values.map { Peer(it.nodeId, it.advert.protoVersion, it.advert.capabilities) }
        val liveIds = live.mapTo(HashSet()) { it.nodeId }
        val nearby = reachablePeers.filterKeys { it in lastSeenAt.keys && it !in liveIds }.values
        _reachable.value = (live + nearby).toSet()
    }

    private fun helloBytes(): ByteArray = Protocol.advertise(localNodeId).encodeToByteArray()

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    // --- Availability ---

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mgr = awareManager ?: return
            if (mgr.isAvailable) {
                if (session == null) scope.launch { attach() }
            } else {
                _health.value = TransportHealth.Degraded
                peers.keys.toList().forEach { teardownPeer(it) }
                stopResponder()
                runCatching { publishSession?.close() }
                runCatching { subscribeSession?.close() }
                runCatching { session?.close() }
                session = null
                publishSession = null
                subscribeSession = null
                attaching.set(false)
                subscribing.set(false)
                synchronized(lock) { accepting = false }
                cueTarget.clear()
                lastSeenAt.clear()
                reachablePeers.clear()
                _reachable.value = emptySet()
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

    /** One live data-path link to a peer: the socket, its outbound queue, I/O jobs, and inbound-file state. */
    private inner class PeerConn(
        val nodeId: String,
        val advert: Protocol.PeerWire,
        val socket: Socket,
        // Non-null for a client link (its own per-peer network request); null for a server link (shares
        // the accept-any responder network — which must NOT be unregistered when one client leaves).
        val networkCallback: ConnectivityManager.NetworkCallback?,
    ) {
        val outbound = Channel<Outbound>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var writerJob: Job? = null
        var reaperJob: Job? = null

        // Quiescence bookkeeping for the per-link supervisor (elapsedRealtime).
        @Volatile var linkStartedAt = 0L
        @Volatile var lastActivityAt = 0L
        @Volatile var rxInProgress = false
        @Volatile var txInProgress = false

        // Files queued behind an in-progress file transfer (only one streams at a time).
        private val stash = ArrayDeque<Outbound.FileSend>()

        // Inbound file reassembly (one active file per socket, so no per-file id needed).
        private var rxOut: OutputStream? = null
        private var rxTemp: File? = null
        private var rxMeta: FileMeta? = null
        private var rxBytes = 0L
        private var rxAborted = false

        /** Mark data-path activity so the supervisor's quiescence window resets (frames + file records only). */
        fun touch() {
            lastActivityAt = SystemClock.elapsedRealtime()
        }

        /** Next outbound item: a file stashed during a prior transfer, else the channel head. */
        suspend fun nextOutbound(): Outbound? =
            stash.removeFirstOrNull() ?: outbound.receiveCatching().getOrNull()

        /** Write any queued frames now (called between file chunks); stash any files for later. */
        fun drainFramesInto(out: OutputStream) {
            while (true) {
                val item = outbound.tryReceive().getOrNull() ?: break
                when (item) {
                    is Outbound.Frame -> AwareFraming.write(out, AwareFraming.Type.FRAME, item.bytes)
                    is Outbound.FileSend -> stash.addLast(item)
                }
            }
        }

        fun beginRxFile(headerPayload: ByteArray) {
            closeRx()
            val header = AwareFraming.decodeFileHeader(headerPayload) ?: run { rxAborted = true; return }
            val temp = File.createTempFile("nan-rx-", ".tmp", appContext.cacheDir)
            rxTemp = temp
            rxOut = BufferedOutputStream(temp.outputStream())
            rxMeta = FileMeta(
                kind = runCatching { FileKind.valueOf(header.kind) }.getOrDefault(FileKind.ATTACHMENT),
                key = header.key,
                mime = header.mime,
            )
            rxBytes = 0L
            rxAborted = false
            rxInProgress = true
        }

        fun appendRxFile(chunk: ByteArray) {
            if (rxAborted) return
            val out = rxOut ?: return
            rxBytes += chunk.size
            if (rxBytes > MAX_INCOMING_FILE_BYTES) {
                Log.w(TAG, "incoming file from $nodeId exceeds ceiling; aborting")
                abortRx()
                return
            }
            runCatching { out.write(chunk) }.onFailure { abortRx() }
        }

        /** Finish the active file, returning (temp, meta) to finalize, or null if none/aborted. */
        fun finishRxFile(): Pair<File, FileMeta>? {
            val out = rxOut
            val temp = rxTemp
            val meta = rxMeta
            rxOut = null; rxTemp = null; rxMeta = null
            rxInProgress = false
            runCatching { out?.close() }
            if (rxAborted || temp == null || meta == null) {
                temp?.delete()
                return null
            }
            return temp to meta
        }

        private fun abortRx() {
            rxAborted = true
            rxInProgress = false
            runCatching { rxOut?.close() }
            rxOut = null
            rxTemp?.delete()
            rxTemp = null
        }

        private fun closeRx() {
            rxInProgress = false
            runCatching { rxOut?.close() }
            rxOut = null
            rxTemp?.delete()
            rxTemp = null
            rxMeta = null
        }

        fun close() {
            readerJob?.cancel()
            writerJob?.cancel()
            reaperJob?.cancel()
            outbound.close()
            closeRx()
            runCatching { socket.close() }
        }
    }

    private sealed interface Outbound {
        class Frame(val bytes: ByteArray) : Outbound
        class FileSend(val file: File, val meta: FileMeta) : Outbound
    }

    private companion object {
        const val TAG = "WifiAwareTransport"

        // The NAN service both nodes publish/subscribe. Bumped to ".v3" for the cue-format change (the cue now
        // carries a content-digest version, not a monotone epoch counter — a v2 node would misread it), so a
        // build across the break hard-partitions at discovery rather than silently mis-deciding syncs.
        const val SERVICE_NAME = "app.getknit.knit.MESH.v3"

        // Fixed app-wide passphrase for link-layer (NDP) encryption. Real authentication is the per-frame
        // Ed25519 signature + E2E layer above the transport; this only keeps the data path off open air.
        const val PSK = "knit-mesh-nan-psk-v1"

        // 2.4 GHz for instant mode: better range than 5 GHz (range is priority #2), at some throughput cost.
        const val INSTANT_BAND = ScanResult.WIFI_BAND_24_GHZ

        // Separator in a cue's `nodeId|epoch` payload (nodeIds/epochs never contain it).
        const val CUE_SEP = '|'

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

        // Delay before re-arming the responder after its network drops, to avoid a tight retry loop.
        const val RESPONDER_REARM_MS = 1_000L

        // Gap after a link/handshake ends before the next requestNetwork, so the framework releases the one
        // NDI first (else "no interfaces available"). Mirrors RESPONDER_REARM_MS.
        const val SETTLE_MS = 1_500L

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
        // dead-initiator backstop, after every clean sync). QUIESCENCE_MS must exceed the
        // watchNeighbors→onNeighborAdded backfill-start latency.
        const val QUIESCENCE_MS = 5_000L
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

        // Min spacing between subscribe re-arms to re-discover a stale/missing peer handle: long enough that a
        // departed peer's cue target is pruned (cue send fails) before we'd re-arm again, so we don't churn
        // subscribe toward its wedge state; short enough that a restarted peer is reconnected within ~1 tick.
        const val REARM_COOLDOWN_MS = 15_000L

        // Receive-side ceiling on a file, matching the send cap (AttachmentStore.MAX_BYTES = 8 MiB) plus
        // headroom for E2E framing (GCM IV+tag) — refuses an unbounded malicious stream that exhausts disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024
    }
}
