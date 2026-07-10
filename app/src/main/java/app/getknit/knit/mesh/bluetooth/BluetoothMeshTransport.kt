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
import app.getknit.knit.mesh.ConnectFailReason
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

    // Providers, not cached handles: the LE scanner/advertiser are re-fetched from the adapter on every
    // (re)start, so the BLE plane survives an adapter off→on cycle (user toggle, airplane, or a BT-stack
    // crash/auto-restart) without a process restart. Caching the handles once — as this used to — silently
    // detached the plane from the stack after any such flap (zero scanner/advertiser registration, reach=[]
    // forever) until the app was killed.
    private val advertiser = BleAdvertiser({ adapter?.bluetoothLeAdvertiser }) { Log.d(TAG, it) }
    private val scanner = BleScanner({ adapter?.bluetoothLeScanner }, ::onScanResult) { Log.d(TAG, it) }

    // Live L2CAP links, keyed by peer nodeId (many, unlike NAN's ≤1).
    private val links = ConcurrentHashMap<String, FramedLink>()

    // Freshest BluetoothDevice per nodeId (updated every sighting, so a rotated random address re-associates).
    private val deviceFor = ConcurrentHashMap<String, BluetoothDevice>()

    // Presence model (smoothed RSSI + dwell + linger) fed by scan sightings; drives promotion + `reachable`.
    private val presence = BlePresenceTracker()

    // Connection bookkeeping guarded by [lock]: in-flight initiator connects + per-peer escalating backoff
    // (streak + next-eligible deadline), reset the moment a link comes up.
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val backoffs = HashMap<String, ConnectBackoffEntry>()
    private val backoffConfig = BackoffConfig(baseMs = CONNECT_BACKOFF_MS, maxMs = MAX_CONNECT_BACKOFF_MS)

    // Observes A2DP audio so a connect failure can be *attributed* to a busy radio (instrumentation only — see
    // [BluetoothAudioMonitor]); read at failure time and exposed via [radioContended] + the periodic diag line.
    private val audioMonitor = BluetoothAudioMonitor(appContext, adapter) { Log.d(TAG, it) }

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

    // Diagnostic-only: reflects A2DP-audio activity so the Diagnostics BLE row can flag a contended radio.
    override val radioContended = audioMonitor.contended

    private lateinit var localNodeId: String

    @Volatile private var serverSocket: BluetoothServerSocket? = null

    @Volatile private var currentPsm = 0

    @Volatile private var lastLinkOrStartAt = 0L
    private var availabilityRegistered = false

    // Two conflated wake channels so the scan and connect loops never steal each other's wakes: connectLoop
    // drains [healSignal] (poked on every event), scanLoop drains [scanWake] (poked only on events that change
    // the scan's boost/floor decision — otherwise a settled clique's per-sighting heals keep it scanning forever).
    private val healSignal = Channel<Unit>(Channel.CONFLATED)
    private val scanWake = Channel<Unit>(Channel.CONFLATED)

    // Cross-plane early-warning: nodeIds another plane (Wi-Fi Aware) can see that we'd initiate to but haven't
    // linked or BLE-sighted yet — pushed by [CompositeMeshTransport.onForeignReachable] — plus their per-peer
    // chase deadlines. The scan boosts to try to catch them on BLE; the chase expires so a NAN-only / out-of-range
    // peer can't pin Boost. [scanFloored] tracks the last-logged tier so a change logs exactly once.
    @Volatile private var foreignReachable: Set<String> = emptySet()

    @Volatile private var chase = ScanDemandPolicy.ChaseState()

    @Volatile private var scanFloored: Boolean? = null

    private var acceptJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var cueJob: Job? = null
    private var powerJob: Job? = null
    private var diagJob: Job? = null
    private var audioJob: Job? = null

    // Forwards a live link's decoded records into our flows and its teardown into [teardownLink].
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
                teardownLink(nodeId, "eof")
                // A flapping link escalates on the same per-peer streak as a failed connect, so a peer that keeps
                // dropping isn't reconnected on a tight loop (which would black out scanning each attempt).
                val (streak, nextAt) = synchronized(lock) { bumpBackoffLocked(nodeId) }
                wake() // link count changed → connectLoop retries and scanLoop re-evaluates demand
                Log.i(TAG, "bt link down $nodeId (eof) streak=$streak retryMs=${nextAt - elapsed()}")
            }
        }

    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Unavailable
            Log.w(TAG, "Bluetooth LE unsupported on this device; BT plane disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            lastLinkOrStartAt = elapsed()
            registerAvailability()
            audioMonitor.start()
            if (adapter?.isEnabled == true) bringUp() else _health.value = TransportHealth.Unavailable
            scanJob = scope.launch { scanLoop() }
            connectJob = scope.launch { connectLoop() }
            // Re-advertise our epoch once the carried set settles so a peer that now wants our data links to pull it.
            // Debounced via collectLatest: a newer cue cancels the pending delay, so the legacy advert (which has no
            // in-place data update — it must stop→restart) churns once per burst, not once per store-and-forward frame.
            cueJob =
                scope.launch {
                    storeDigest.version.drop(1).collectLatest {
                        delay(CUE_READVERTISE_DEBOUNCE_MS)
                        readvertise()
                    }
                }
            powerJob = scope.launch { powerState.state.drop(1).collect { wake() } }
            diagJob = scope.launch { diagLoop() }
            // A2DP forces the scan to its floor (audio contends the radio); wake the scan loop on any change so
            // the falling edge (audio stopped) resumes the normal cadence promptly instead of after the floor gap.
            audioJob = scope.launch { audioMonitor.contended.drop(1).collect { wakeScan() } }
        }
    }

    override fun stop() {
        scanJob?.cancel()
        connectJob?.cancel()
        cueJob?.cancel()
        powerJob?.cancel()
        diagJob?.cancel()
        audioJob?.cancel()
        unregisterAvailability()
        audioMonitor.stop()
        tearDownRadio()
        links.keys.toList().forEach { teardownLink(it, "stop") }
        presence.clear()
        deviceFor.clear()
        synchronized(lock) {
            inFlight.clear()
            backoffs.clear()
        }
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
    }

    override fun heal() {
        if (hasHardware) wake()
    }

    override fun onForeignReachable(peers: Set<String>) {
        // Only chase peers we'd initiate to (larger id initiates); a smaller-id foreign peer connects to us via
        // our always-on advert, so scanning harder for it wouldn't help. Guard localNodeId until start() sets it.
        val initiable = if (::localNodeId.isInitialized) peers.filterTo(HashSet()) { localNodeId > it } else emptySet()
        if (initiable == foreignReachable) return
        val rising = initiable.any { it !in foreignReachable }
        foreignReachable = initiable
        chase = ScanDemandPolicy.onForeign(chase, initiable, elapsed(), PROMOTE_CHASE_MS)
        if (rising) wakeScan() // a new foreign peer appeared → re-evaluate demand now (don't wait out the floor)
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { it.send(bytes) } // FramedLink.send accounts the bytes
    }

    override suspend fun sendFile(
        file: java.io.File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val accepted = links[to.nodeId]?.sendFile(file, meta) ?: false
        if (accepted) metrics.onFileSent(TransportKind.Bluetooth)
        return accepted
    }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
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
        val ss =
            runCatching { a.listenUsingInsecureL2capChannel() }.getOrElse {
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
            BleAdvertPayload.encode(localNodeId, Protocol.LOCAL_CAPABILITIES, storeDigest.current(), currentPsm),
        )
    }

    // --- Scanning (coordination plane) ---

    private suspend fun scanLoop() {
        while (scope.isActive) {
            val inflight = inFlightSnapshot()
            val canScan = adapter?.isEnabled == true && inflight.isEmpty()
            if (!canScan) {
                // A connect is in flight (radio contention) or the adapter is off — wait, don't scan. Log the
                // pause so a scanning gap (which starves presence for the whole mesh) is attributable.
                if (adapter?.isEnabled == true) Log.d(TAG, "scan paused: connect in flight $inflight")
                withTimeoutOrNull(CONNECT_QUIET_MS) { scanWake.receive() }
                continue
            }
            val power = powerState.state.value
            val duty = PowerPolicy.dutyCycle(power)
            scanner.start(if (power.interactive || power.charging) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            delay(duty.scanWindowMs)
            scanner.stop()
            val idle =
                if (floorScan()) {
                    PowerPolicy.settledIdleAfterScan(power, links.size, lonelyForMs())
                } else {
                    PowerPolicy.idleAfterScan(power, links.size, lonelyForMs())
                }
            withTimeoutOrNull(idle) { scanWake.receive() }
        }
    }

    /**
     * Whether to idle at the settled *floor* (energy saver) rather than the boosted cadence: true when A2DP audio
     * contends the radio, or when [ScanDemandPolicy] finds no promotion work — no BLE-sighted candidate we'd
     * initiate to (above the RSSI floor, not linked, not backing off) and no foreign peer still inside its chase
     * window, with at least one link held. Logs the tier on change so on-device behavior is greppable.
     */
    private fun floorScan(): Boolean {
        val now = elapsed()
        val sighted = presence.snapshots(now)
        val backoff = activeBackoff(now)
        val candidates =
            sighted
                .filter {
                    localNodeId > it.nodeId && it.nodeId !in links.keys &&
                        it.nodeId !in backoff && it.smoothedRssi >= PROMOTE_RSSI_FLOOR
                }.mapTo(HashSet()) { it.nodeId }
        val demand =
            ScanDemandPolicy.decide(
                linkCount = links.size,
                promotableCandidates = candidates,
                chase = chase,
                bleSighted = sighted.mapTo(HashSet()) { it.nodeId },
                bleLinked = links.keys.toSet(),
                now = now,
            )
        val floor = audioMonitor.contended.value || demand == ScanDemandPolicy.Demand.Floor
        if (scanFloored != floor) {
            scanFloored = floor
            val reason = if (audioMonitor.contended.value) "a2dp" else "settled"
            Log.d(
                TAG,
                if (floor) {
                    "bt scan → floor ($reason, links=${links.size})"
                } else {
                    "bt scan → boost (candidates=$candidates chase=${chase.deadlines.keys})"
                },
            )
        }
        return floor
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
        publishReachable()
        healSignal.trySend(Unit) // connectLoop: react to every sighting to drive promotion
        // scanLoop: wake ONLY for a genuine boost trigger. Waking on every sighting (incl. already-linked peers)
        // is what keeps a settled clique scanning continuously — gating here is what lets the floor engage.
        if (isBoostTrigger(parsed.nodeId)) scanWake.trySend(Unit)
    }

    /** A just-sighted peer worth boosting the scan for: one we'd initiate to (larger id), not linked, above the
     *  RSSI floor (so it can actually promote), and off connect backoff. Mirrors the [floorScan] candidate gate. */
    private fun isBoostTrigger(nodeId: String): Boolean {
        if (!::localNodeId.isInitialized || localNodeId <= nodeId || nodeId in links.keys) return false
        val rssi = presence.smoothedRssiFor(nodeId) ?: return false
        return rssi >= PROMOTE_RSSI_FLOOR && nodeId !in activeBackoff(elapsed())
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
        publishReachable(now)
        val rssiByNode = snaps.associate { it.nodeId to it.smoothedRssi }
        // Candidates: peers we're the initiator for (tie-break: larger id initiates), not linked, not in flight.
        val candidates = snaps.filter { localNodeId > it.nodeId && it.nodeId !in links.keys && it.nodeId !in inFlightSnapshot() }
        val linkSnaps =
            links.values.map { fl ->
                PromotionPolicy.LinkSnapshot(
                    nodeId = fl.nodeId,
                    smoothedRssi = rssiByNode[fl.nodeId] ?: ABSENT_LINK_RSSI,
                    ageMs = now - fl.linkStartedAt,
                    idleMs = now - fl.lastActivityAt,
                )
            }
        val presentIds = snaps.mapTo(HashSet()) { it.nodeId }
        val backoff =
            synchronized(lock) {
                // Drop backoff state for a peer that has left and whose window has passed, so a returning peer
                // starts fresh instead of inheriting a maxed-out streak.
                backoffs.entries.removeAll { (id, b) ->
                    now >= b.nextAt && id !in presentIds && id !in links.keys && id !in inFlight
                }
                backoffs.filterValues { now < it.nextAt }.keys.toSet()
            }
        val decision = PromotionPolicy.decide(candidates, linkSnaps, backoff)
        if (decision.promote.isNotEmpty() || decision.evict.isNotEmpty()) {
            Log.i(TAG, "promote=${decision.promote} evict=${decision.evict} backoff=$backoff a2dp=${audioMonitor.state.value}")
        }
        decision.evict.forEach { teardownLink(it, "evicted") }
        decision.promote.forEach { initiateTo(it) }
    }

    @Suppress("LongMethod") // the connect + watchdog + two-way HELLO is one linear flow; splitting it obscures it
    private fun initiateTo(nodeId: String) {
        val device = deviceFor[nodeId] ?: return
        val psm = presence.psmFor(nodeId) ?: return
        val advert = presenceAdvert(nodeId) ?: return
        if (!beginConnect(nodeId)) return
        val rssi =
            presence
                .snapshots(elapsed())
                .firstOrNull { it.nodeId == nodeId }
                ?.smoothedRssi
                ?.toInt()
        Log.i(TAG, "bt initiating to $nodeId (psm $psm rssi=$rssi a2dp=${audioMonitor.state.value} links=${links.size})")
        scope.launch(Dispatchers.IO) {
            val startedAt = elapsed()
            val socket =
                runCatching { device.createInsecureL2capChannel(psm) }
                    .getOrElse { t -> return@launch failConnect(nodeId, classify(t), startedAt, t) }
            // Time-box the blocking connect(): on stall, give up this peer's inFlight slot at the timeout so the
            // scan (paused while any connect is in flight) resumes promptly — instead of after connect() finally
            // unwinds. Closing the socket is best-effort only: on some stacks (the API-30 device's Qualcomm
            // cherokee) close() can't abort an in-progress L2CAP connect and itself blocks until the *native*
            // connect timeout (~21s ≫ our 12s), which is exactly what used to pin inFlight and blind the scan for
            // that whole window. [settled] lets whichever fires first — the watchdog or connect() returning — own
            // the single failConnect; the loser just tidies up the socket.
            val settled = AtomicBoolean(false)
            val watchdog =
                scope.launch(Dispatchers.IO) {
                    delay(CONNECT_TIMEOUT_MS)
                    if (settled.compareAndSet(false, true)) {
                        val cause = TimeoutException("connect watchdog ${CONNECT_TIMEOUT_MS}ms")
                        failConnect(nodeId, ConnectFailReason.TIMEOUT, startedAt, cause)
                    }
                    runCatching { socket.close() } // may block until the native connect unwinds; the slot is already freed
                }
            val connectErr = runCatching { socket.connect() }.exceptionOrNull()
            if (!settled.compareAndSet(false, true)) {
                // The watchdog already timed this attempt out and released the slot; just tidy up and stop.
                runCatching { socket.close() }
                return@launch
            }
            watchdog.cancel()
            if (connectErr != null) {
                runCatching { socket.close() }
                return@launch failConnect(nodeId, classify(connectErr), startedAt, connectErr)
            }
            val link = BluetoothSocketLink(socket)
            // Two-way HELLO: send our identity first, then read the responder's reply and require it to match
            // the peer we dialed — so the link's identity is confirmed by the peer over the socket, not taken
            // from the (unauthenticated) scanned advert. Writes-then-reads while the responder reads-then-
            // replies, so neither blocks. A BluetoothSocket has no soTimeout, so bound the reply read by
            // closing the socket from a watchdog.
            val helloErr = runCatching { LinkHandshake.writeHello(link.output, localNodeId) }.exceptionOrNull()
            if (helloErr != null) {
                link.close()
                return@launch failConnect(nodeId, ConnectFailReason.HANDSHAKE, startedAt, helloErr)
            }
            val replyWatchdog =
                scope.launch {
                    delay(ACCEPT_HELLO_TIMEOUT_MS)
                    runCatching { socket.close() }
                }
            val reply = runCatching { LinkHandshake.readHello(link.input) }.getOrNull()
            replyWatchdog.cancel()
            if (reply == null || reply.nodeId != nodeId) {
                link.close()
                return@launch failConnect(
                    nodeId,
                    ConnectFailReason.HANDSHAKE,
                    startedAt,
                    IllegalStateException("hello reply ${reply?.nodeId ?: "absent"} != expected $nodeId"),
                )
            }
            Log.i(TAG, "bt connect ok $nodeId durMs=${elapsed() - startedAt}")
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
        val watchdog =
            scope.launch {
                delay(ACCEPT_HELLO_TIMEOUT_MS)
                runCatching { socket.close() }
            }
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
        // Reply with our identity so the initiator can confirm it reached us (two-way HELLO; the initiator
        // validates this before registering). We read first, then reply — no mutual block.
        if (runCatching { LinkHandshake.replyHello(link.output, localNodeId) }.isFailure) {
            link.close()
            return
        }
        Log.i(TAG, "bt accepted client $clientNodeId")
        registerLink(clientNodeId, advert, link)
    }

    private fun registerLink(
        nodeId: String,
        advert: Protocol.PeerWire,
        link: app.getknit.knit.mesh.link.LinkSocket,
    ) {
        val framed =
            FramedLink(
                nodeId = nodeId,
                peer = Peer(nodeId, advert.protoVersion, advert.capabilities),
                socket = link,
                scope = scope,
                cacheDir = appContext.cacheDir,
                metrics = metrics,
                callbacks = linkCallbacks,
                now = SystemClock::elapsedRealtime,
                paceBytesPerSec = BLE_PACE_BYTES_PER_SEC,
                log = { msg -> Log.d(TAG, msg) },
            )
        val prev = links.put(nodeId, framed)
        prev?.close() // a stale link to the same peer — never leak it
        lastLinkOrStartAt = elapsed()
        synchronized(lock) {
            inFlight.remove(nodeId)
            backoffs.remove(nodeId)
        } // success resets the peer's streak
        metrics.onBtLinkEstablished()
        framed.start()
        refreshNeighbors()
        publishReachable() // a live link ⇒ reachable, even for an inbound peer we never scan-sighted
        wake() // inFlight cleared + link count changed → resume connectLoop and re-evaluate scan demand
        Log.i(TAG, "bt link up: $nodeId (${links.size} live)")
    }

    private fun teardownLink(
        nodeId: String,
        reason: String,
    ) {
        val fl = links.remove(nodeId) ?: return
        fl.close()
        refreshNeighbors()
        publishReachable() // drop the peer from reachable too, unless it's still being scan-sighted
        Log.i(TAG, "bt link down: $nodeId ($reason)")
    }

    /** Per-peer connect backoff: consecutive-failure streak + the elapsed-time deadline before the next attempt. */
    private class ConnectBackoffEntry(
        var streak: Int = 0,
        var nextAt: Long = 0L,
    )

    private fun beginConnect(nodeId: String): Boolean =
        synchronized(lock) {
            if (nodeId in links.keys || nodeId in inFlight) return false
            backoffs[nodeId]?.let { if (elapsed() < it.nextAt) return false }
            inFlight.add(nodeId)
            true
        }

    private fun failConnect(
        nodeId: String,
        reason: ConnectFailReason,
        startedAt: Long,
        cause: Throwable,
    ) {
        val (streak, nextAt) =
            synchronized(lock) {
                inFlight.remove(nodeId)
                bumpBackoffLocked(nodeId)
            }
        metrics.onBtConnectFailed(reason)
        wake()
        // Log the real exception (previously swallowed) + A2DP state, so the intermittent failure is diagnosable.
        Log.w(
            TAG,
            "bt connect $nodeId failed reason=$reason durMs=${elapsed() - startedAt} " +
                "a2dp=${audioMonitor.state.value} links=${links.size} streak=$streak retryMs=${nextAt - elapsed()}",
            cause,
        )
    }

    /** Advance [nodeId]'s failure streak and set its next-eligible deadline via [ConnectBackoffPolicy]. Holds [lock]. */
    private fun bumpBackoffLocked(nodeId: String): Pair<Int, Long> {
        val entry = backoffs.getOrPut(nodeId) { ConnectBackoffEntry() }
        entry.streak += 1
        entry.nextAt = elapsed() + ConnectBackoffPolicy.nextDelayMs(entry.streak, backoffConfig)
        return entry.streak to entry.nextAt
    }

    /** Best-effort bucket for the (otherwise-discarded) connect exception; the raw [cause] is logged regardless.
     *  A watchdog-forced timeout is failed as [ConnectFailReason.TIMEOUT] at its call site, so it never lands here. */
    private fun classify(cause: Throwable?): ConnectFailReason {
        val msg = (cause?.message ?: "").lowercase()
        return when {
            cause is java.net.SocketTimeoutException -> ConnectFailReason.TIMEOUT
            "radio" in msg || "enomem" in msg || "no resources" in msg || "busy" in msg -> ConnectFailReason.RADIO
            "refused" in msg || "reset" in msg -> ConnectFailReason.REFUSED
            else -> ConnectFailReason.OTHER
        }
    }

    private fun refreshNeighbors() {
        _neighbors.value = links.values.map { it.peer }.toSet()
    }

    // --- Availability (adapter on/off) ---

    private val availabilityReceiver =
        object : BroadcastReceiver() {
            // onReceive runs on the main thread; the health flip is cheap so it's set inline for an instant UI
            // update, but bring-up/teardown do blocking radio I/O (opening/closing the L2CAP server socket), so
            // they run on [scope] — off the main thread — mirroring WifiAwareTransport's handler-thread funnel.
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "bluetooth adapter on")
                        scope.launch {
                            // Clean slate before re-acquiring: resets the scanner/advertiser `active` flags and
                            // closes any stale server, so bringUp() + the scan loop re-fetch fresh radio handles
                            // even if a fast off→on bounce skipped the STATE_OFF teardown (a stuck `scanning`
                            // flag would otherwise make BleScanner.start() short-circuit and never re-acquire).
                            tearDownRadio()
                            bringUp()
                            wake()
                        }
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "bluetooth adapter off")
                        _health.value = TransportHealth.Unavailable // Bluetooth switched off (or airplane mode)
                        scope.launch {
                            links.keys.toList().forEach { teardownLink(it, "adapter off") }
                            tearDownRadio()
                            presence.clear()
                            deviceFor.clear()
                            synchronized(lock) {
                                inFlight.clear()
                                backoffs.clear()
                            }
                            _reachable.value = emptySet()
                        }
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

    /** Read-only view of peers currently in connect backoff (no [driveConnections] prune side effect). */
    private fun activeBackoff(now: Long): Set<String> = synchronized(lock) { backoffs.filterValues { now < it.nextAt }.keys.toSet() }

    /** Wake both loops — a demand-relevant event (link up/down, connect fail, power change, adapter on, heal). */
    private fun wake() {
        healSignal.trySend(Unit)
        scanWake.trySend(Unit)
    }

    /** Wake only the scan loop — a scan-cadence event (a boost trigger sighted, a foreign peer, an audio change). */
    private fun wakeScan() {
        scanWake.trySend(Unit)
    }

    /**
     * Publish [reachable] as presence ∪ live links, so a peer we hold a link to stays "nearby" even when the
     * throttled scan hasn't re-sighted it within the presence linger (the reachable ⊇ neighbors invariant the
     * floor depends on). Applies to [_reachable] only — never [_neighbors], which routes sends.
     */
    private fun publishReachable(now: Long = elapsed()) {
        val byId = HashMap<String, Peer>()
        presence.snapshots(now).forEach { byId[it.nodeId] = Peer(it.nodeId, it.protoVersion, it.capabilities) }
        links.values.forEach { byId[it.nodeId] = it.peer } // a live link's Peer is authoritative on a dup nodeId
        _reachable.value = byId.values.toSet()
    }

    private fun localNodeIdOrEmpty(): String = if (::localNodeId.isInitialized) localNodeId else ""

    /** The peer's advert (protoVersion/capabilities) from the latest presence snapshot, for a link we initiate. */
    private fun presenceAdvert(nodeId: String): Protocol.PeerWire? =
        presence
            .snapshots(elapsed())
            .firstOrNull { it.nodeId == nodeId }
            ?.let { Protocol.PeerWire(it.nodeId, it.protoVersion, it.capabilities) }

    // --- Diagnostics ---

    private suspend fun diagLoop() {
        while (scope.isActive) {
            delay(DIAG_INTERVAL_MS)
            audioMonitor.refresh() // re-evaluate audio vs live AudioManager state (the playing edge can be missed)
            logState()
        }
    }

    /** Periodic one-line state dump (mirrors WifiAwareTransport's), so a device session is greppable end-to-end. */
    private fun logState() {
        val now = elapsed()
        val backoffStr =
            synchronized(lock) {
                backoffs.entries.joinToString(",") { (id, b) ->
                    "$id:${((b.nextAt - now) / MS_PER_S).coerceAtLeast(0)}s/${b.streak}"
                }
            }
        Log.d(
            TAG,
            "bt state links=${links.keys} reach=${_reachable.value.map { it.nodeId }} " +
                "inFlight=${inFlightSnapshot()} backoff=[$backoffStr] a2dp=${audioMonitor.state.value} psm=$currentPsm",
        )
    }

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

        // Backoff base after the first failed connect; escalates geometrically per peer (see ConnectBackoffPolicy),
        // capped at MAX_CONNECT_BACKOFF_MS. Replaces the old flat retry — a link-up resets the peer's streak.
        private const val CONNECT_BACKOFF_MS = 10_000L

        // Ceiling the per-peer connect backoff saturates at, so a persistently-failing peer is retried rarely
        // (radio busy / unreachable) instead of on a tight loop that blacks out scanning every attempt.
        private const val MAX_CONNECT_BACKOFF_MS = 180_000L

        // Cadence of the periodic diagnostic state line (links/reach/inFlight/backoff/a2dp), mirroring NAN.
        private const val DIAG_INTERVAL_MS = 12_000L

        // Quiet window to coalesce a burst of digest-cue changes into ONE re-advertise. The cue is only a coarse
        // "my custody set changed, come sync" hint (the on-link DIGEST id-diff is authoritative), and the legacy
        // advert must stop→restart to swap its service data, so settling ~1.5s before republishing is imperceptible
        // to sync latency (the BLE scan floors at minutes) while it removes the per-change advertiser churn.
        private const val CUE_READVERTISE_DEBOUNCE_MS = 1_500L

        private const val MS_PER_S = 1_000L

        // RSSI stand-in for a link whose peer is no longer being sighted (so it sorts as the weakest to evict).
        private const val ABSENT_LINK_RSSI = -127.0

        // How long to keep the scan boosted chasing a foreign (other-plane, e.g. Wi-Fi Aware) peer onto BLE before
        // giving up — so a stationary NAN-only / out-of-BLE-range peer can't pin the scan at full power. Re-armed if
        // the peer leaves and returns; ~60s covers a peer walking from NAN range into BLE range.
        private const val PROMOTE_CHASE_MS = 60_000L

        // The scan boosts only for peers the promotion policy could actually link — mirrors the default
        // PromotionConfig.rssiFloorDbm (-90) so a fainter, un-promotable peer at the edge can't keep re-boosting it.
        // Set generously (BLE reaches further than NAN's NDP): broaden BLE reach to the edge of usable range and
        // exclude only genuinely poor signals, rather than gating to same-room proximity.
        private const val PROMOTE_RSSI_FLOOR = -90.0

        // Average byte/sec cap on a file feed over an L2CAP CoC link (passed to FramedLink; NAN stays unbounded).
        // A blob otherwise bursts into the BT-stack TX queue ahead of any later text frame and saturates the ACL,
        // so chat stalls until the transfer completes and the reverse direction is starved. Holding the feed
        // BELOW real L2CAP throughput keeps that queue shallow, so interleaved frames reach the wire promptly and
        // reverse traffic gets connection-event budget. Deliberately conservative — the transfer is a bit slower
        // in exchange for live chat. Field-tune against the `file …/… <N>B in <ms>ms` timing (FramedLink).
        private const val BLE_PACE_BYTES_PER_SEC = 28 * 1024
    }
}
