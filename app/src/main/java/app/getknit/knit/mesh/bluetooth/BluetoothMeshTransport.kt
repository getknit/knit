package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.identity.Identity
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
import app.getknit.knit.mesh.power.PowerPolicy
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
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over **Bluetooth LE** — the second mesh plane, running simultaneously with
 * [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] behind [app.getknit.knit.mesh.CompositeMeshTransport].
 * Unlike Wi-Fi Aware's one-NDP-at-a-time constraint, BLE holds **many persistent links at once**, which is the
 * whole point (the dense-venue case): peers who stay near each other get responsive, always-on messaging
 * without data-path churn. It also serves as a legacy plane for phones lacking Wi-Fi Aware hardware.
 *
 * Two planes, like NAN but simpler:
 * - **Coordination** — BLE advertising ([BleAdvertiser]) of a 16-byte [BleAdvertPayload] (nodeId + caps +
 *   digest cue + L2CAP PSM) and duty-cycled scanning ([BleScanner]); every scan hit feeds [BlePresenceTracker]
 *   (smoothed RSSI, dwell, linger). Identity is the advertised nodeId, so a rotated BLE random address is
 *   transparent.
 * - **Data** — an L2CAP CoC socket per linked peer. The [PromotionPolicy] promotes a peer to a persistent link
 *   once it has dwelled long enough and is close enough (RSSI), bounded by a connection budget with
 *   weakest-first eviction. Each socket feeds the shared [FramedLink] (frames + files + store-and-forward
 *   digests, identical to NAN).
 *
 * Insecure/pairless L2CAP (no system pairing dialog): real authentication is the per-frame Ed25519 signature +
 * E2E layer above the transport, so link-layer bonding is redundant. Permissions are gated at onboarding and
 * the transport self-degrades if one is missing, so the Bluetooth calls are [SuppressLint] "MissingPermission".
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions", "LargeClass") // a transport is inherently many small lifecycle/socket methods
class BluetoothMeshTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val powerState: PowerStateSource,
    private val storeDigest: StoreDigest,
) : MeshTransport {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val hasHardware =
        adapter != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private val advertiser = BleAdvertiser(adapter?.bluetoothLeAdvertiser) { Log.d(TAG, it) }
    private val scanner = BleScanner(adapter?.bluetoothLeScanner, ::onScanResult) { Log.d(TAG, it) }

    // Live L2CAP links, keyed by peer nodeId (many, unlike NAN's ≤1).
    private val links = ConcurrentHashMap<String, FramedLink>()

    // Freshest BluetoothDevice per nodeId (updated every sighting, so a rotated random address re-associates).
    private val deviceFor = ConcurrentHashMap<String, BluetoothDevice>()

    // Presence model (smoothed RSSI + dwell + linger) fed by scan sightings; drives promotion + `reachable`.
    private val presence = BlePresenceTracker()

    // Connection bookkeeping guarded by [lock]: in-flight initiator connects + per-peer failure backoff.
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

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

    override val kind = TransportKind.Bluetooth

    private lateinit var localNodeId: String

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var currentPsm = 0
    @Volatile private var lastLinkOrStartAt = 0L
    private var availabilityRegistered = false

    private val healSignal = Channel<Unit>(Channel.CONFLATED)
    private var acceptJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var cueJob: Job? = null
    private var powerJob: Job? = null

    // Forwards a live link's decoded records into our flows and its teardown into [teardownLink].
    private val linkCallbacks = object : LinkCallbacks {
        override fun onInbound(frame: InboundFrame) { _inbound.tryEmit(frame) }
        override fun onDigest(digest: ReceivedDigest) { _incomingDigests.tryEmit(digest) }
        override fun onFile(file: ReceivedFile) { _incomingFiles.tryEmit(file) }
        override fun onLinkDown(nodeId: String) {
            teardownLink(nodeId, "eof")
            synchronized(lock) { retryAfter[nodeId] = elapsed() + REFUSED_BACKOFF_MS }
            healSignal.trySend(Unit)
        }
    }

    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Degraded
            Log.w(TAG, "Bluetooth LE unsupported on this device; BT plane disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            lastLinkOrStartAt = elapsed()
            registerAvailability()
            if (adapter?.isEnabled == true) bringUp() else _health.value = TransportHealth.Degraded
            scanJob = scope.launch { scanLoop() }
            connectJob = scope.launch { connectLoop() }
            // Re-advertise our epoch when the carried set changes so a peer that now wants our data links to pull it.
            cueJob = scope.launch { storeDigest.version.drop(1).collect { readvertise() } }
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
        }
    }

    override fun stop() {
        scanJob?.cancel(); connectJob?.cancel(); cueJob?.cancel(); powerJob?.cancel()
        unregisterAvailability()
        tearDownRadio()
        links.keys.toList().forEach { teardownLink(it, "stop") }
        presence.clear()
        deviceFor.clear()
        synchronized(lock) { inFlight.clear(); retryAfter.clear() }
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
    }

    override fun heal() {
        if (hasHardware) healSignal.trySend(Unit)
    }

    override suspend fun send(wire: WireEnvelope, to: Peer?) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { it.send(bytes) } // FramedLink.send accounts the bytes
    }

    override suspend fun sendFile(file: java.io.File, to: Peer, meta: FileMeta) {
        links[to.nodeId]?.sendFile(file, meta)
    }

    override suspend fun sendDigest(to: Peer, ids: List<String>) {
        links[to.nodeId]?.sendDigest(ids)
    }

    // --- Radio bring-up / teardown ---

    private fun bringUp() {
        openServer()
        readvertise()
        _health.value = TransportHealth.Healthy
    }

    private fun openServer() {
        val a = adapter ?: return
        closeServer()
        val ss = runCatching { a.listenUsingInsecureL2capChannel() }.getOrElse {
            Log.w(TAG, "L2CAP listen failed", it)
            return
        }
        serverSocket = ss
        currentPsm = ss.psm
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(ss) }
        Log.i(TAG, "L2CAP responder listening on psm $currentPsm")
    }

    private fun closeServer() {
        acceptJob?.cancel()
        acceptJob = null
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        currentPsm = 0
    }

    private fun tearDownRadio() {
        advertiser.stop()
        scanner.stop()
        closeServer()
    }

    private fun readvertise() {
        if (adapter?.isEnabled != true || currentPsm == 0 || !::localNodeId.isInitialized) return
        advertiser.update(
            BleAdvertPayload.encode(localNodeId, Protocol.LOCAL_CAPABILITIES, storeDigest.version.value, currentPsm),
        )
    }

    // --- Scanning (coordination plane) ---

    private suspend fun scanLoop() {
        while (scope.isActive) {
            val canScan = adapter?.isEnabled == true && synchronized(lock) { inFlight.isEmpty() }
            if (!canScan) {
                // A connect is in flight (radio contention) or the adapter is off — wait, don't scan.
                withTimeoutOrNull(CONNECT_QUIET_MS) { healSignal.receive() }
                continue
            }
            val power = powerState.state.value
            val duty = PowerPolicy.dutyCycle(power)
            scanner.start(if (power.interactive || power.charging) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            delay(duty.scanWindowMs)
            scanner.stop()
            val idle = PowerPolicy.idleAfterScan(power, links.size, lonelyForMs())
            withTimeoutOrNull(idle) { healSignal.receive() }
        }
    }

    private fun onScanResult(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(BleConstants.SERVICE_UUID) ?: return
        val parsed = BleAdvertPayload.parse(data) ?: return
        if (parsed.nodeId == localNodeIdOrEmpty()) return
        deviceFor[parsed.nodeId] = result.device
        presence.onSighting(
            BlePresenceTracker.Sighting(
                nodeId = parsed.nodeId,
                rssiDbm = result.rssi,
                protoVersion = Protocol.VERSION, // implied by the matched (versioned) service UUID
                capabilities = parsed.capabilities,
                psm = parsed.psm,
                digestCue = parsed.digestCue,
            ),
            elapsed(),
        )
        _reachable.value = presence.reachable(elapsed())
        healSignal.trySend(Unit)
    }

    // --- Connection engine (promotion → L2CAP links) ---

    private suspend fun connectLoop() {
        while (scope.isActive) {
            withTimeoutOrNull(CONNECT_TICK_MS) { healSignal.receive() }
            if (adapter?.isEnabled == true) driveConnections()
        }
    }

    private fun driveConnections() {
        val now = elapsed()
        val snaps = presence.snapshots(now)
        _reachable.value = snaps.map { Peer(it.nodeId, it.protoVersion, it.capabilities) }.toSet()
        val rssiByNode = snaps.associate { it.nodeId to it.smoothedRssi }
        // Candidates: peers we're the initiator for (tie-break: larger id initiates), not linked, not in flight.
        val candidates = snaps.filter { localNodeId > it.nodeId && it.nodeId !in links.keys && it.nodeId !in inFlightSnapshot() }
        val linkSnaps = links.values.map { fl ->
            PromotionPolicy.LinkSnapshot(
                nodeId = fl.nodeId,
                smoothedRssi = rssiByNode[fl.nodeId] ?: ABSENT_LINK_RSSI,
                ageMs = now - fl.linkStartedAt,
                idleMs = now - fl.lastActivityAt,
            )
        }
        val backoff = synchronized(lock) { retryAfter.filterValues { now < it }.keys.toSet() }
        val decision = PromotionPolicy.decide(candidates, linkSnaps, backoff)
        decision.evict.forEach { teardownLink(it, "evicted") }
        decision.promote.forEach { initiateTo(it) }
    }

    private fun initiateTo(nodeId: String) {
        val device = deviceFor[nodeId] ?: return
        val psm = presence.psmFor(nodeId) ?: return
        val advert = presenceAdvert(nodeId) ?: return
        if (!beginConnect(nodeId)) return
        Log.i(TAG, "bt initiating to $nodeId (psm $psm)")
        scope.launch(Dispatchers.IO) {
            val socket = runCatching { device.createInsecureL2capChannel(psm) }.getOrNull()
                ?: return@launch failConnect(nodeId)
            // Time-box the blocking connect() by closing the socket if it stalls, so a wedged handshake can't
            // pin this peer in inFlight and stall scanning (the "always time-box a connect" discipline).
            val watchdog = scope.launch { delay(CONNECT_TIMEOUT_MS); runCatching { socket.close() } }
            val ok = runCatching { socket.connect() }.isSuccess
            watchdog.cancel()
            if (!ok) {
                runCatching { socket.close() }
                return@launch failConnect(nodeId)
            }
            val link = BluetoothSocketLink(socket)
            // Initiator sends its identity first so the accept-any responder learns who connected.
            val sent = runCatching { LinkHandshake.writeHello(link.output, localNodeId) }.isSuccess
            if (!sent) {
                link.close()
                return@launch failConnect(nodeId)
            }
            registerLink(nodeId, advert, link)
        }
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (scope.isActive && serverSocket === ss) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { superviseAccepted(socket) }
        }
    }

    /** A client connected to our L2CAP responder: read its identity (HELLO, watchdog-bounded), then register. */
    private suspend fun superviseAccepted(socket: BluetoothSocket) {
        val link = BluetoothSocketLink(socket)
        // BluetoothSocket has no soTimeout, so bound the HELLO read by closing the socket if it stalls.
        val watchdog = scope.launch { delay(ACCEPT_HELLO_TIMEOUT_MS); runCatching { socket.close() } }
        val advert = runCatching { LinkHandshake.readHello(link.input) }.getOrNull()
        watchdog.cancel()
        val clientNodeId = advert?.nodeId
        if (advert == null || clientNodeId == null) {
            link.close()
            return
        }
        // Tie-break: the responder must be the SMALLER id (larger initiates); don't double-link.
        if (localNodeId >= clientNodeId || clientNodeId in links.keys) {
            link.close()
            return
        }
        Log.i(TAG, "bt accepted client $clientNodeId")
        registerLink(clientNodeId, advert, link)
    }

    private fun registerLink(nodeId: String, advert: Protocol.PeerWire, link: app.getknit.knit.mesh.link.LinkSocket) {
        val framed = FramedLink(
            nodeId = nodeId,
            peer = Peer(nodeId, advert.protoVersion, advert.capabilities),
            socket = link,
            scope = scope,
            cacheDir = appContext.cacheDir,
            metrics = metrics,
            callbacks = linkCallbacks,
            now = SystemClock::elapsedRealtime,
            log = { msg -> Log.d(TAG, msg) },
        )
        val prev = links.put(nodeId, framed)
        prev?.close() // a stale link to the same peer — never leak it
        lastLinkOrStartAt = elapsed()
        synchronized(lock) { inFlight.remove(nodeId); retryAfter.remove(nodeId) }
        framed.start()
        refreshNeighbors()
        healSignal.trySend(Unit) // inFlight cleared → let the scan loop resume
        Log.i(TAG, "bt link up: $nodeId (${links.size} live)")
    }

    private fun teardownLink(nodeId: String, reason: String) {
        val fl = links.remove(nodeId) ?: return
        fl.close()
        refreshNeighbors()
        Log.i(TAG, "bt link down: $nodeId ($reason)")
    }

    private fun beginConnect(nodeId: String): Boolean = synchronized(lock) {
        if (nodeId in links.keys || nodeId in inFlight) return false
        retryAfter[nodeId]?.let { if (elapsed() < it) return false }
        inFlight.add(nodeId)
        true
    }

    private fun failConnect(nodeId: String) {
        synchronized(lock) {
            inFlight.remove(nodeId)
            retryAfter[nodeId] = elapsed() + CONNECT_BACKOFF_MS
        }
        healSignal.trySend(Unit)
        Log.i(TAG, "bt connect to $nodeId failed")
    }

    private fun refreshNeighbors() {
        _neighbors.value = links.values.map { it.peer }.toSet()
    }

    // --- Availability (adapter on/off) ---

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "bluetooth adapter on")
                    bringUp()
                    healSignal.trySend(Unit)
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "bluetooth adapter off")
                    _health.value = TransportHealth.Degraded
                    links.keys.toList().forEach { teardownLink(it, "adapter off") }
                    tearDownRadio()
                    presence.clear()
                    deviceFor.clear()
                    synchronized(lock) { inFlight.clear() }
                    _reachable.value = emptySet()
                }
            }
        }
    }

    private fun registerAvailability() {
        if (availabilityRegistered) return
        appContext.registerReceiver(availabilityReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        availabilityRegistered = true
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        runCatching { appContext.unregisterReceiver(availabilityReceiver) }
        availabilityRegistered = false
    }

    // --- Helpers ---

    private fun elapsed() = SystemClock.elapsedRealtime()

    private fun lonelyForMs(): Long = if (links.isEmpty()) elapsed() - lastLinkOrStartAt else 0L

    private fun inFlightSnapshot(): Set<String> = synchronized(lock) { inFlight.toSet() }

    private fun localNodeIdOrEmpty(): String = if (::localNodeId.isInitialized) localNodeId else ""

    /** The peer's advert (protoVersion/capabilities) from the latest presence snapshot, for a link we initiate. */
    private fun presenceAdvert(nodeId: String): Protocol.PeerWire? =
        presence.snapshots(elapsed()).firstOrNull { it.nodeId == nodeId }
            ?.let { Protocol.PeerWire(it.nodeId, it.protoVersion, it.capabilities) }

    companion object {
        const val TAG = "BluetoothMeshTransport"

        /** True on a device with a Bluetooth adapter + BLE — the composite includes this plane only if so. */
        fun isSupported(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter != null &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        // Connection-engine cadence: re-evaluate promotions at least this often (also woken by healSignal).
        private const val CONNECT_TICK_MS = 5_000L

        // While a connect is in flight, poll this often for it to clear before resuming scanning.
        private const val CONNECT_QUIET_MS = 2_000L

        // Bound the responder's HELLO read (BluetoothSocket has no soTimeout) — close the socket if it stalls.
        private const val ACCEPT_HELLO_TIMEOUT_MS = 5_000L

        // Time-box the initiator's blocking L2CAP connect() so a wedged handshake can't pin inFlight forever.
        private const val CONNECT_TIMEOUT_MS = 12_000L

        // After a failed connect, skip re-initiating to that peer for this long (anti-churn).
        private const val CONNECT_BACKOFF_MS = 10_000L

        // After a live link drops (socket EOF), brief backoff before reconnecting to a still-present peer.
        private const val REFUSED_BACKOFF_MS = 5_000L

        // RSSI stand-in for a link whose peer is no longer being sighted (so it sorts as the weakest to evict).
        private const val ABSENT_LINK_RSSI = -127.0
    }
}
