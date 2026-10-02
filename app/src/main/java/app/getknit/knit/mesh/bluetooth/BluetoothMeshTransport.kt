package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.BuildConfig
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.BleSideDrop
import app.getknit.knit.mesh.ConnectFailReason
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
import app.getknit.knit.mesh.bleSupport
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.power.ElapsedWait
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [MeshTransport] over **Bluetooth LE** — the second mesh plane, running simultaneously with
 * [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] behind [app.getknit.knit.mesh.CompositeMeshTransport].
 * Unlike Wi-Fi Aware's one-NDP-at-a-time constraint, BLE holds **many persistent links at once**, which is the
 * whole point (the dense-venue case): peers who stay near each other get responsive, always-on messaging
 * without data-path churn. It also serves as a legacy plane for phones lacking Wi-Fi Aware hardware.
 *
 * Three planes, like NAN's two plus one:
 * - **Coordination** — BLE advertising ([BleAdvertiser]) of the 24-byte [BleAdvertPayload] (nodeId + caps +
 *   digest cue + L2CAP PSM + flags) and duty-cycled scanning ([BleScanner]); every scan hit feeds
 *   [BlePresenceTracker] (smoothed RSSI, dwell, linger). Identity is the advertised nodeId, so a rotated BLE
 *   random address is transparent.
 * - **Data** — an L2CAP CoC socket per linked peer. The [PromotionPolicy] promotes a peer to a persistent link
 *   once it has dwelled long enough and is close enough (RSSI), bounded by a connection budget with
 *   weakest-first eviction. Each socket feeds the shared [FramedLink] (frames + files + store-and-forward
 *   digests, identical to NAN).
 * - **Side channel** ([BleSideChannel], optional — null while the build keeps it dark) — the fast plane
 *   ([hasFastPlane]): small floodable frames on non-connectable extended-advertising pages, connectionless,
 *   so they bypass a file transfer head-of-line-blocking a stream and reach a sighted-but-unlinked peer.
 *   [fastFanout] keeps the link copy the composite used to send for us and adds the page; [fastSend] is the
 *   link only — nothing DM-form rides a broadcast carrier. Gated per peer on the advert flag
 *   ([SideCapableTracker]); the receive scan runs only while such a peer is sighted but unlinked, or a file is
 *   streaming on one of our links ([SideScanPolicy]) — linked peers already get the link copy.
 *
 * - **Coded PHY** (an experiment, ADR 2026-10.yvn6 — [phyMode] reads OFF unless `BuildConfig.BLE_CODED_PHY`) — a
 *   second, extended presence set on the Coded PHY and an all-PHY presence scan, so a peer is found and dialed past
 *   1M range; each link to a peer heard on Coded gets a [BlePhyControl] that steps it between 1M and Coded S=8.
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
    // Lets the Meshtastic board dial (a GATT connect on this same controller) pause our scan for its short
    // connect window, exactly as our own in-flight L2CAP connects do. Shared Koin singleton.
    private val arbiter: BleConnectArbiter = BleConnectArbiter(),
    // The side channel, or null while `BuildConfig.BLE_SIDE_PLANE` keeps it dark — the one seam; nothing
    // downstream gates on the flag again.
    private val sideChannel: BleSideChannel? = null,
    // The debug-only link cap (`SettingsStore.debugBleLinkCap`): null — always, in release — runs the shipped
    // budget with the shipped admission table; a number caps held links, dials and inbound admits at it.
    private val linkCap: Flow<Int?> = flowOf(null),
    // `BuildConfig.BLE_GATT_PEERS`: find a peer that advertises only the `0xFE30` UUID (a foreground iPhone) by
    // reading its GATT payload ([GattPayloads], companion change A3). It gates the second scan filter and the reader
    // here, and the advert's FLAG_DIALS_GATT_PEERS with them — a flag without a reader strands the pair.
    private val gattPeers: Boolean = false,
    // The Coded PHY experiment's mode (`SettingsStore.debugBlePhyMode`, ADR 2026-10.yvn6): the DI hands OFF, always,
    // while `BuildConfig.BLE_CODED_PHY` keeps it dark — the one seam; nothing here gates on the flag again.
    private val phyMode: Flow<CodedPhyMode> = flowOf(CodedPhyMode.OFF),
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
    private val advertiser =
        BleAdvertiser(
            { adapter?.bluetoothLeAdvertiser },
            log = { Log.d(TAG, it) },
            onStartStatus = { onPresenceEnabled(true, it) },
            onEnableStatus = ::onPresenceEnabled,
        )

    // When the presence set is enabled again (#112): the stack's own re-enable around a connection can be refused, and
    // only sometimes says so. [presenceAdvert] is how it last fared — off, on, refused <status> or disabled.
    private val advertKeeper = AdvertReassertPolicy.Keeper()
    private val advertWake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var presenceAdvert = "off"
    private val scanner =
        BleScanner({ adapter?.bluetoothLeScanner }, ::onScanResult, log = { Log.d(TAG, it) }, matchesServiceUuid = gattPeers)

    // The Coded PHY experiment (ADR 2026-10.yvn6). [codedMode] is the mode last read from [phyMode], [codedSupported]
    // the controller probe [bringUp] makes; [codedAdvertiser] carries the presence payload again on a Coded set, and
    // [codedAdvert] says how it fared (a refused start leaves it dark until the next bring-up, as the side channel's).
    @Volatile private var codedMode = CodedPhyMode.OFF

    @Volatile private var codedSupported = false

    @Volatile private var codedTxPower = "high"

    @Volatile private var codedAdvert = "off"

    @Volatile private var codedAdvertiser = newCodedAdvertiser(AdvertisingSetParameters.TX_POWER_HIGH)

    // A peer's Coded address (its Coded set's own random address), beside [deviceFor]'s 1M one; and when each peer was
    // last heard on Coded, which is how a link knows its peer can step down — no advert flag is spent on it.
    private val codedDeviceFor = ConcurrentHashMap<String, BluetoothDevice>()
    private val codedCapable = ConcurrentHashMap<String, Long>()

    // Each link's PHY handle, keyed by link like [doorbells], and its peer's device, which a late handle needs.
    private val phyControls = ConcurrentHashMap<FramedLink, BlePhyControl>()
    private val linkDevices = ConcurrentHashMap<FramedLink, BluetoothDevice>()

    // What each unlinked peer's adverts did this minute, logged by [diagLoop] as `bt coded heard` while the experiment
    // runs — the walk test's record of whether a far peer was heard, how loud, and why it did or did not promote.
    private val codedTallies = ConcurrentHashMap<String, CodedTally>()

    // When the Coded set advertises fast: after a link at range drops, until its peer is back or the hold runs out (ADR
    // 2026-10.yvn6, amendment 3). [paceJob] makes the next change — a drop's settle, a link's return, the hold's end.
    private val codedPace = CodedAdvertPace()

    @Volatile private var paceJob: Job? = null

    @Volatile private var codedFast = false

    // The Coded set's own retries, beside the presence set's turns it rides (9utz): a refused Coded enable, or a start
    // the stack failed internally, comes back on the doubling wait. No net of its own — the presence turns are that.
    private val codedKeeper = AdvertReassertPolicy.Keeper(alonePeriodMs = NO_NET_MS, linkedPeriodMs = NO_NET_MS)

    @Volatile private var codedStartRetries = 0

    // Whether the last presence window was Coded-only ([CodedPhyPolicy.scanPhys] alternates), and whether Coded-only
    // windows are running at all, for the one log line per edge. Touched by [scanLoop] alone.
    private var lastScanCoded = false
    private var codedWindows = false

    // Whose GATT payload to read and what each read found (companion change A3), and the reader; [gattReads] counts
    // the reads begun, for the debug state line. [gattJob] is the read in progress, cancelled with the radio.
    private val gattPayloads = GattPayloads()
    private val gattReader = BleGattPayloadReader(appContext, arbiter)
    private val gattReads = AtomicInteger()

    @Volatile private var gattJob: Job? = null

    // Live L2CAP links, keyed by peer nodeId (many, unlike NAN's ≤1).
    private val links = ConcurrentHashMap<String, FramedLink>()

    // Held links whose peer the scan has not once sighted since the link came up — an inbound iPhone this phone has
    // not read the GATT payload of (a backgrounded one, or any while the reader is dark). They score the promotion
    // floor for eviction, not the absent-peer −127 (ADR 2026-09.shzv). A sighting drops one.
    private val neverSighted = ConcurrentHashMap.newKeySet<FramedLink>()

    // The address of each link's peer, so a GATT read never dials an address a link already holds (companion change A3).
    private val linkAddresses = ConcurrentHashMap<FramedLink, String>()

    // The doorbell of each link whose HELLO asked to be rung ([DoorbellPolicy.serves]) — an iPhone, which iOS
    // suspends and only a GATT write wakes (ADR 2026-09.dqvb). Keyed by link like [neverSighted], so a replaced
    // link's doorbell goes with it; [rings] counts the writes, for the debug state line.
    private val doorbells = ConcurrentHashMap<FramedLink, BleDoorbell>()
    private val rings = AtomicInteger()

    // Which frames already crossed which link, either way: the router's flood copy and the fast path's link
    // copy are the same frame twice for the same stream, and a relayed frame's fast copy would go straight
    // back over the link it arrived on. One write per link per frame; the far end's SeenSet would have
    // dropped the rest (see [LinkCrossings] for why that makes it a byte saving and not a delivery change).
    private val crossings = LinkCrossings(clock = SystemClock::elapsedRealtime)

    // Freshest BluetoothDevice per nodeId (updated every sighting, so a rotated random address re-associates).
    private val deviceFor = ConcurrentHashMap<String, BluetoothDevice>()

    // Presence model (smoothed RSSI + dwell + linger) fed by scan sightings; drives promotion + `reachable`.
    private val presence = BlePresenceTracker(codedCreditDb = { CodedPhyDiag.tuning.codedCreditDb })

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

    // The fast path is routed here (not turned into a plain send by the composite): [fastFanout]/[fastSend]
    // replicate the link copy the composite sent and add the side-channel page where it applies.
    override val hasFastPlane = true

    // Diagnostic-only: reflects A2DP-audio activity so the Diagnostics BLE row can flag a contended radio.
    override val radioContended = audioMonitor.contended

    private lateinit var localNodeId: String

    @Volatile private var serverSocket: BluetoothServerSocket? = null

    @Volatile private var currentPsm = 0

    // When this node last held no link: the transport's start, or the last link going down. It times both the scan's
    // lonely cadence (ADR 2026-09.w3xk) and the lonely dial (ADR 2026-09.hj4a). The scan's clock used to run from the
    // last link's *start*, so a phone that lost a link it had held past three minutes slowed its scan within one window,
    // just as the peer it had lost was worth hunting for (the 2026-10-01 Coded walk).
    @Volatile private var noLinkSince = 0L
    private var availabilityRegistered = false
    private var aclEdgesRegistered = false

    // Two conflated wake channels so the scan and connect loops never steal each other's wakes: connectLoop
    // drains [healSignal] (poked on every event), scanLoop drains [scanWake] (poked only on events that change
    // the scan's boost/floor decision — otherwise a settled clique's per-sighting heals keep it scanning forever).
    private val healSignal = Channel<Unit>(Channel.CONFLATED)
    private val scanWake = Channel<Unit>(Channel.CONFLATED)

    // The waits a relink waits on — the scan's window, its pause behind a dial and its hunting gap, the connect loop,
    // the dial and HELLO watchdogs — run on the elapsed clock, so a phone that suspends between events keeps them in
    // wall time instead of stretching them by however long it slept (ADR 2026-10.pj9w). The scan's other gaps are
    // power budgets and keep plain timeouts, as do the waits elsewhere in this file.
    private val elapsedWait = ElapsedWait(::elapsed)

    // Cross-plane early-warning: nodeIds another plane (Wi-Fi Aware) can see that we'd initiate to but haven't
    // linked or BLE-sighted yet — pushed by [CompositeMeshTransport.onForeignReachable] — plus their per-peer
    // chase deadlines. The scan boosts to try to catch them on BLE; the chase expires so a NAN-only / out-of-range
    // peer can't pin Boost. [scanFloored] tracks the last-logged tier so a change logs exactly once.
    @Volatile private var foreignReachable: Set<String> = emptySet()

    @Volatile private var chase = ScanDemandPolicy.ChaseState()

    @Volatile private var scanFloored: Boolean? = null

    // Whether the last scan idle was the relaxed lonely gap (`PowerPolicy.lonelyRelaxed`, ADR 2026-09.w3xk):
    // logged once per transition, the BLE twin of WifiAwareTransport's `lonely: relaxed …` line. Loop thread only.
    private var lonelyRelaxedLogged = false

    // Side channel: which sighted/linked peers advertise the flag, the loop that keeps its scan tier right,
    // and the tier last logged. [sideWake] is its own conflated channel for the same reason [scanWake] is.
    private val sideCapable = SideCapableTracker()
    private val sideWake = Channel<Unit>(Channel.CONFLATED)

    // The last decision and why, as one string: logged on change (a new reason for Off counts), shown on the state line.
    @Volatile private var sideDecision: String = "-"

    private var acceptJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var cueJob: Job? = null
    private var powerJob: Job? = null
    private var diagJob: Job? = null
    private var audioJob: Job? = null
    private var arbiterJob: Job? = null
    private var sideJob: Job? = null
    private var capJob: Job? = null
    private var phyJob: Job? = null
    private var advertJob: Job? = null

    // The debug link cap as last read from [linkCap]; null runs the shipped budget untouched.
    @Volatile private var debugCap: Int? = null

    // A frame heard off a side-channel page enters exactly where a link frame does; the author is the hop
    // (a page carries no hop identity), which is the LoRa plane's rule too. Our own frame relayed back is dropped.
    private val sideListener =
        object : BleSideChannel.Listener {
            override fun onFrame(
                wire: WireEnvelope,
                env: RelayEnvelope,
            ) {
                if (env.senderId == localNodeIdOrEmpty()) {
                    metrics.onBleSideDropped(BleSideDrop.OWN_ECHO)
                    return
                }
                Log.i(TAG, "ble-side heard ${env.type} id=${env.id} from=${env.senderId}")
                _inbound.tryEmit(InboundFrame(wire, env, env.senderId))
            }

            override fun onAvailabilityChanged() {
                readvertise() // the flag follows `live`
                wakeSide()
            }
        }

    // Forwards a live link's decoded records into our flows and its teardown into [teardownLink]. One per link:
    // a link replaced under the same node id still reports its own end, and that must release only its own slot,
    // never the replacement's (ADR 2026-09.shzv).
    private inner class LinkEvents : LinkCallbacks {
        lateinit var link: FramedLink

        override fun onInbound(frame: InboundFrame) {
            // In counts as a crossing too: nothing we later hand this link may be the frame it just gave us.
            crossings.firstCrossing(frame.fromNodeId, FrameKey.of(frame.wire, frame.envelope))
            _inbound.tryEmit(frame)
        }

        override fun onDigest(digest: ReceivedDigest) {
            _incomingDigests.tryEmit(digest)
        }

        override fun onFile(file: ReceivedFile) {
            _incomingFiles.tryEmit(file)
            wakeSide() // the one stream edge a link reports: rxInProgress just cleared, the side scan may go Off
        }

        override fun onLinkDown(nodeId: String) {
            val released = teardownLink(nodeId, "eof", only = link)
            // Replaced by a fresh link from the same peer: that link holds the slot, and the peer did not flap.
            if (!released && links[nodeId] != null) return
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
            noLinkSince = elapsed()
            registerAvailability()
            registerAclEdges()
            audioMonitor.start()
            codedMode = phyMode.first() // before bringUp, so the first advert and scan already follow it
            advertKeeper.start(elapsed()) // before bringUp, so a refused first start is kept and retried
            codedKeeper.start(elapsed())
            if (adapter?.isEnabled == true) bringUp() else _health.value = TransportHealth.Unavailable
            scanJob = scope.launch { scanLoop() }
            connectJob = scope.launch { connectLoop() }
            // Re-advertise our epoch once the carried set settles so a peer that now wants our data links to pull it.
            // Debounced via collectLatest: a newer cue cancels the pending delay, so the advert's data is swapped once
            // per burst, not once per store-and-forward frame.
            cueJob =
                scope.launch {
                    storeDigest.version.drop(1).collectLatest {
                        delay(CUE_READVERTISE_DEBOUNCE_MS)
                        readvertise()
                    }
                }
            powerJob = scope.launch { powerState.state.drop(1).collect { wake() } }
            diagJob = scope.launch { diagLoop() }
            advertJob = scope.launch { advertLoop() }
            // A2DP forces the scan to its floor (audio contends the radio); wake the scan loop on any change so
            // the falling edge (audio stopped) resumes the normal cadence promptly instead of after the floor gap.
            audioJob = scope.launch { audioMonitor.contended.drop(1).collect { wakeScan() } }
            // The board dial holds an arbiter slot; wake the scan on release so it resumes without waiting out the gap.
            arbiterJob = scope.launch { arbiter.busy.drop(1).collect { wakeScan() } }
            // A new cap re-runs the promotion decision now: a lower one sheds the weakest evictable links.
            capJob =
                scope.launch {
                    linkCap.distinctUntilChanged().collect {
                        debugCap = it
                        Log.i(TAG, "bt link cap=${it ?: "default"}")
                        wake()
                    }
                }
            phyJob = scope.launch { phyMode.distinctUntilChanged().collect { applyPhyMode(it) } }
            CodedPhyDiag.status = ::codedPhyStatus
            CodedPhyDiag.setTxPower = ::setCodedTxPower
            sideChannel?.let {
                it.bind(sideListener)
                sideJob = scope.launch { sideScanLoop(it) }
            }
        }
    }

    override fun stop() {
        scanJob?.cancel()
        connectJob?.cancel()
        cueJob?.cancel()
        powerJob?.cancel()
        diagJob?.cancel()
        audioJob?.cancel()
        arbiterJob?.cancel()
        capJob?.cancel()
        phyJob?.cancel()
        advertJob?.cancel()
        paceJob?.cancel()
        CodedPhyDiag.status = null
        CodedPhyDiag.setTxPower = null
        CodedPhyDiag.publishLinkPhys(emptyMap())
        sideJob?.cancel()
        cancelGattRead()
        sideCapable.clear()
        unregisterAvailability()
        unregisterAclEdges()
        audioMonitor.stop()
        tearDownRadio()
        links.keys.toList().forEach { teardownLink(it, "stop") }
        presence.clear()
        deviceFor.clear()
        codedDeviceFor.clear()
        codedCapable.clear()
        codedTallies.clear()
        synchronized(lock) {
            inFlight.clear()
            backoffs.clear()
        }
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
    }

    override fun heal() {
        if (!hasHardware) return
        advertKeeper.dueNow(elapsed()) // the presence set is enabled again at once, whatever the stack last made of it
        advertWake.trySend(Unit)
        wake()
    }

    override fun onForeignReachable(peers: Set<String>) {
        // Only chase peers we'd initiate to (larger id initiates); a larger-id foreign peer connects to us via
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
        val key = FrameKey.ofSigned(wire) // an unsigned frame is point-to-point and never fanned: no memo
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { writeOnce(it, key, bytes, wire) } // FramedLink.send accounts the bytes
    }

    /**
     * [FramedLink.send] unless [key] already crossed this link either way — then the far end has it — and a poke
     * of the link's doorbell, if it has one and the frame rings ([DoorbellPolicy.rings]; [env] when the caller
     * already decoded it). The ring goes with the enqueue, not after the socket write, on purpose: a suspended
     * iPhone reads nothing, so its channel's credits run out and the writer blocks until the ring wakes it.
     */
    private fun writeOnce(
        link: FramedLink,
        key: String?,
        bytes: ByteArray,
        wire: WireEnvelope,
        env: RelayEnvelope? = null,
    ) {
        if (key != null && !crossings.firstCrossing(link.nodeId, key)) {
            metrics.onBleLinkDupSkipped()
            return
        }
        link.send(bytes)
        val doorbell = doorbells[link] ?: return
        if (DoorbellPolicy.rings((env ?: WireCodec.decodeEnvelope(wire.signed))?.type)) doorbell.poke()
    }

    /**
     * The fan-out arm of the fast path: the link copy to every linked peer (what [app.getknit.knit.mesh.CompositeMeshTransport]
     * sent on our behalf while this plane declared no fast plane — a typing cue is never flooded, so this is how
     * it reaches a linked peer), plus a side-channel page when the channel is live and a flagged peer is around.
     * Eligibility is the caller's `shouldFastFanout`; the page never widens or narrows it. `FramedLink.send` is
     * a non-suspending enqueue, so no launch is needed.
     */
    override fun fastFanout(wire: WireEnvelope) {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        val side = sideChannel
        val sideAvailable = side != null && side.live && sideCapable.anyCapable(elapsed(), links.keys)
        val route = BleFastRoutePolicy.fanout(env, links.keys, sideAvailable)
        val bytes = WireCodec.encodeWire(wire)
        val key = FrameKey.of(wire, env)
        route.linkTargets.forEach { links[it]?.let { link -> writeOnce(link, key, bytes, wire, env) } }
        val offer = route.side ?: return
        if (side?.offer(wire, env, offer.kind, offer.coalesceKey) == null) metrics.onBleSideTooBig()
    }

    /** The targeted arm: the linked addressee over its link, never a page (a DM-form frame has one recipient). */
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        val route = BleFastRoutePolicy.send(to.nodeId, links.keys)
        if (route.linkTargets.isEmpty()) return
        val bytes = WireCodec.encodeWire(wire)
        val key = FrameKey.ofSigned(wire)
        route.linkTargets.forEach { links[it]?.let { link -> writeOnce(link, key, bytes, wire) } }
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

    override fun arrivingFiles(): Set<String> = links.values.mapNotNullTo(HashSet()) { it.rxKey }

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.hasPendingFile(key) ?: false

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        links[to.nodeId]?.sendDigest(ids)
    }

    // --- Radio bring-up / teardown ---

    private fun bringUp() {
        openServer()
        sideChannel?.bringUp() // probes the controller first, so the advert below already carries its flag
        probeCoded()
        readvertise()
        _health.value = TransportHealth.Healthy
        wakeSide()
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
        presenceAdvert = "off"
        stopCodedAdvert()
        // The next bring-up starts the Coded set slow; a link that drops after it makes it fast again.
        codedFast = false
        codedAdvertiser.setInterval(BleAdvertiser.CODED_INTERVAL)
        scanner.stop()
        sideChannel?.tearDown()
        closeServer()
    }

    private fun readvertise() {
        if (adapter?.isEnabled != true || currentPsm == 0 || !::localNodeId.isInitialized) return
        // FLAG_DIALS_GATT_PEERS rides the same gate as the reader, so it never claims a read this phone cannot make:
        // a lower-id iPhone waits to be dialed only by a phone that can find it (companion change A3).
        val flags =
            (if (sideChannel?.live == true) BleAdvertPayload.FLAG_SIDE_CHANNEL else 0) or
                (if (gattPeers) BleAdvertPayload.FLAG_DIALS_GATT_PEERS else 0)
        val payload = BleAdvertPayload.encode(localNodeId, Protocol.LOCAL_CAPABILITIES, storeDigest.current(), currentPsm, flags)
        advertiser.update(payload)
        // The same bytes on the Coded set, so its PSM and cue can never lag the presence advert's.
        if (codedOn() && !codedAdvert.startsWith("dark")) {
            if (codedAdvert == "off") codedAdvert = "starting"
            codedAdvertiser.update(payload)
        } else if (!codedOn()) {
            stopCodedAdvert()
        }
    }

    // --- Keeping the presence set on the air (#112) ---

    /**
     * Enables the presence set again whenever [advertKeeper] says it is due — after a connection edge, a reported
     * refusal, a heal, or the net — and the Coded set with it while that one is up. The Coded set rides these turns,
     * and [codedKeeper] adds its own retries: a refused Coded enable, or a start the stack failed internally, comes back
     * on the doubling wait. Every wait is a timeout, so a lost wake costs latency, never liveness.
     */
    private suspend fun advertLoop() {
        while (scope.isActive) {
            if (adapter?.isEnabled != true) {
                // Nothing to enable; bringUp starts the set afresh when the adapter comes back.
                withTimeoutOrNull(ADAPTER_OFF_WAIT_MS) { advertWake.receive() }
                continue
            }
            val now = elapsed()
            val linked = links.isNotEmpty()
            if (advertKeeper.isDue(now, linked)) {
                advertiser.reassert()
                if (codedUp()) codedAdvertiser.reassert()
                advertKeeper.reasserted(now)
                codedKeeper.reasserted(now)
            }
            if (codedKeeper.isDue(now, linked)) {
                if (codedUp()) {
                    codedAdvertiser.reassert()
                } else if (codedAdvert == CODED_DARK_INTERNAL) {
                    retryCodedStart()
                }
                codedKeeper.reasserted(now)
            }
            val wait = minOf(advertKeeper.waitMs(elapsed(), linked), codedKeeper.waitMs(elapsed(), linked))
            withTimeoutOrNull(wait) { advertWake.receive() }
        }
    }

    /**
     * Every outcome the stack reports for the presence set: its start, its own re-enable after a connection to the set
     * (unasked), and each of [advertLoop]'s. A refusal is retried on [advertKeeper]'s doubling wait.
     */
    private fun onPresenceEnabled(
        enabled: Boolean,
        status: Int,
    ) {
        val now = elapsed()
        if (enabled && status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            presenceAdvert = "on"
            advertKeeper.onEnabled(now)?.let { Log.i(TAG, "bt advert enabled again (refused for ${it}ms)") }
            return
        }
        presenceAdvert = if (enabled) "refused $status" else "disabled"
        advertKeeper.onRefused(now)?.let { Log.i(TAG, "bt advert $presenceAdvert, retry in ${it}ms") }
        advertWake.trySend(Unit)
    }

    /** A connection opened or closed: the stack re-enabled its sets around it, and that enable may have been refused. */
    private fun advertEdge() {
        advertKeeper.onConnectionEdge(elapsed())
        advertWake.trySend(Unit)
    }

    // --- Coded PHY (the experiment, ADR 2026-10.yvn6) ---

    /** Whether the experiment runs here: a mode other than OFF, on a controller that has the Coded PHY. */
    private fun codedOn(): Boolean = codedMode != CodedPhyMode.OFF && codedSupported

    /** Reads the controller's Coded and extended-advertising support (adapter on), and clears a refused advert. */
    private fun probeCoded() {
        val a = adapter
        codedSupported =
            a != null && a.isEnabled && runCatching { a.isLeCodedPhySupported && a.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        if (codedAdvert.startsWith("dark")) codedAdvert = "off"
        codedStartRetries = 0
    }

    private fun newCodedAdvertiser(txPower: Int): BleAdvertiser =
        BleAdvertiser(
            { adapter?.bluetoothLeAdvertiser },
            log = { Log.d(TAG, "coded $it") },
            params = BleAdvertiser.codedParams(txPower),
            onStartStatus = ::onCodedAdvertStatus,
            onEnableStatus = ::onCodedEnabled,
            onIntervalRefused = ::onCodedIntervalRefused,
        )

    /** Whether the Coded set is up: live, or refused an enable that [codedKeeper] is retrying. */
    private fun codedUp(): Boolean = codedAdvert == "live" || codedAdvert.startsWith("refused")

    private fun onCodedAdvertStatus(status: Int) {
        val retry = codedAdvert == CODED_RETRYING
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            codedAdvert = "live"
            codedStartRetries = 0
            codedKeeper.onEnabled(elapsed())
            Log.i(TAG, "bt coded advert live (tx=$codedTxPower)")
            return
        }
        // TOO_MANY_ADVERTISERS (a fifth set beside presence, two side slots and the watch) or FEATURE_UNSUPPORTED:
        // dark until the next bring-up; the presence advert and every 1M path carry on as before. INTERNAL_ERROR, which
        // the P7 gave twice beside a busy link on 2026-10-01, is the stack's own failure: retried a few times.
        codedAdvert = "dark $status"
        if (!retry) {
            metrics.onBleCodedAdvertDark()
            Log.i(TAG, "bt coded advert dark $status")
        }
        if (codedAdvert != CODED_DARK_INTERNAL) return
        if (codedStartRetries < MAX_CODED_START_RETRIES) {
            codedKeeper.onRefused(elapsed())
            advertWake.trySend(Unit)
        } else if (retry) {
            Log.i(TAG, "bt coded advert dark $status, retries spent until the next bring-up")
        }
    }

    /** A Coded start the stack failed internally, tried again with the current payload (not the bytes it failed with). */
    private fun retryCodedStart() {
        if (!codedOn()) return
        codedStartRetries += 1
        Log.i(TAG, "bt coded advert retry $codedStartRetries/$MAX_CODED_START_RETRIES")
        codedAdvert = CODED_RETRYING
        readvertise()
    }

    /**
     * Every enable outcome the stack reports for the Coded set, asked for or not. A refusal comes back on [codedKeeper]'s
     * doubling wait and, while the set is fast, counts toward giving the fast window up ([CodedAdvertPace]).
     */
    private fun onCodedEnabled(
        enabled: Boolean,
        status: Int,
    ) {
        if (!enabled) return // a disable nobody here asked for: the next presence turn enables it again
        val now = elapsed()
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            if (codedAdvert.startsWith("refused")) codedAdvert = "live"
            codedKeeper.onEnabled(now)?.let { Log.i(TAG, "bt coded advert enabled again (refused for ${it}ms)") }
            return
        }
        codedAdvert = "refused $status"
        val retryIn = codedKeeper.onRefused(now)
        val fast = if (codedFast) " (fast)" else ""
        retryIn?.let { Log.i(TAG, "bt coded advert refused $status$fast, retry in ${it}ms") }
        advertWake.trySend(Unit)
        if (codedFast) onFastRefused()
    }

    private fun onCodedIntervalRefused(
        interval: Int,
        status: Int,
    ) {
        Log.i(TAG, "bt coded advert interval $interval refused $status")
        if (codedFast) onFastRefused()
    }

    /** A fast Coded enable the controller would not take: past [CodedAdvertPace]'s limit, slow for the rest of the window. */
    private fun onFastRefused() {
        if (!codedPace.onFastRefused()) return
        Log.i(TAG, "bt coded advert fast refused, slow for the rest of the window")
        applyCodedPace()
    }

    /** Puts the Coded set on the interval [codedPace] wants now, and schedules the hold's end. */
    private fun applyCodedPace() {
        val now = elapsed()
        val tuning = CodedPhyDiag.tuning
        val fast = codedOn() && codedPace.fast(now)
        codedAdvertiser.setInterval(
            if (fast) CodedPhyPolicy.advertIntervalUnits(tuning.fastAdvertMs) else BleAdvertiser.CODED_INTERVAL,
        )
        if (fast != codedFast) {
            codedFast = fast
            Log.i(
                TAG,
                if (fast) "bt coded advert fast (${codedPace.wanted(now)}, every ${tuning.fastAdvertMs}ms)" else "bt coded advert slow",
            )
        }
        val end = codedPace.endsAt()
        if (fast && end != null) schedulePace(end - now)
    }

    /** [applyCodedPace] in [delayMs], replacing whatever change was scheduled. */
    private fun schedulePace(delayMs: Long) {
        paceJob?.cancel()
        paceJob =
            scope.launch {
                delay(delayMs)
                applyCodedPace()
            }
    }

    /**
     * A link with a PHY handle went down. Logs where it was, and when it went on its own at range, on the side its peer
     * dials, makes the Coded advert fast ([CodedPhyPolicy.fastAdvertAfterDrop]) — after the settle, so the change's
     * disable and enable stay out of the stack's own pause and resume around the disconnection (9utz).
     */
    private fun notePhyDrop(
        status: PhyLinkStatus,
        reason: String,
    ) {
        Log.i(TAG, "bt phy ${status.nodeId} link dropped on ${status.phy} rssi=${status.linkRssi} ($reason)")
        val tuning = CodedPhyDiag.tuning
        if (reason != "eof" || !codedOn()) return
        if (!CodedPhyPolicy.fastAdvertAfterDrop(localNodeId, status.nodeId, status.phy, status.linkRssi, tuning)) return
        codedPace.onDrop(status.nodeId, elapsed(), tuning.fastHoldMs)
        schedulePace(AdvertReassertPolicy.SETTLE_MS)
    }

    private fun stopCodedAdvert() {
        codedAdvertiser.stop()
        if (!codedAdvert.startsWith("dark")) codedAdvert = "off"
    }

    /** `…debug.PHY --es txpower high|medium`: re-raises the Coded set at that power. */
    private fun setCodedTxPower(level: String): Boolean {
        val power =
            when (level) {
                "high" -> AdvertisingSetParameters.TX_POWER_HIGH
                "medium" -> AdvertisingSetParameters.TX_POWER_MEDIUM
                else -> return false
            }
        scope.launch {
            stopCodedAdvert()
            codedTxPower = level
            codedAdvertiser = newCodedAdvertiser(power)
            codedFast = false
            applyCodedPace() // before the start, so a fast window carries onto the new set
            readvertise()
        }
        return true
    }

    /**
     * A new mode: the Coded set and every link's PHY handle follow it now, the scan's PHYs at its next window, with no
     * restart. OFF lets the handles go and leaves each link on the PHY it is on — asking a far Coded link back to 1M
     * dropped it (the 2026-10-01 walk) — and keeps [codedCapable], so the next mode re-attaches them at once.
     */
    private fun applyPhyMode(mode: CodedPhyMode) {
        codedMode = mode
        Log.i(TAG, "bt phy mode=${mode.wire} supported=$codedSupported")
        applyCodedPace() // OFF reads slow; a mode back on picks up a window still running
        readvertise()
        if (codedOn()) {
            links.values.forEach(::ensurePhyControl)
            phyControls.values.forEach(BlePhyControl::poke)
        } else {
            phyControls.values.forEach(BlePhyControl::close)
            phyControls.clear()
            codedDeviceFor.clear()
            publishLinkPhys()
        }
        wake()
    }

    /** Republishes the PHY each handled link is on, for Diagnostics' per-link chip ([CodedPhyDiag.linkPhys]). */
    private fun publishLinkPhys() {
        CodedPhyDiag.publishLinkPhys(
            phyControls.values
                .map(BlePhyControl::status)
                .filter { it.attached && it.phy != LinkPhy.UNKNOWN }
                .associate { it.nodeId to it.phy },
        )
    }

    /** Gives [link] a PHY handle if the experiment runs, its peer was heard on Coded, and it has none yet. */
    private fun ensurePhyControl(link: FramedLink) {
        val wanted = codedOn() && codedCapable.containsKey(link.nodeId)
        if (!wanted || phyControls.containsKey(link) || links[link.nodeId] !== link) return
        val device = linkDevices[link] ?: return
        val nodeId = link.nodeId
        val control =
            BlePhyControl(
                context = appContext,
                device = device,
                nodeId = nodeId,
                drives = CodedPhyPolicy.drives(localNodeId, nodeId),
                scope = scope,
                isLive = { links[nodeId] === link },
                mode = { codedMode },
                tuning = { CodedPhyDiag.tuning },
                onStep = metrics::onBlePhyStep,
                onGiveUp = metrics::onBlePhyGiveUp,
                now = SystemClock::elapsedRealtime,
                onPhyKnown = ::publishLinkPhys,
            )
        if (phyControls.putIfAbsent(link, control) == null) control.start()
    }

    private fun codedPhyStatus(): CodedPhyStatus {
        val now = elapsed()
        return CodedPhyStatus(
            mode = codedMode,
            supported = codedSupported,
            advert = codedAdvert,
            txPower = codedTxPower,
            links = phyControls.values.map(BlePhyControl::status),
            peers =
                presence.snapshots(now).map {
                    PhyPeerStatus(it.nodeId, it.smoothedRssi, it.oneMSeenAgoMs, it.codedSeenAgoMs, it.rssi1m, it.rssiCoded)
                },
        )
    }

    // --- Scanning (coordination plane) ---

    private suspend fun scanLoop() {
        while (scope.isActive) {
            val inflight = inFlightSnapshot()
            val canScan = adapter?.isEnabled == true && inflight.isEmpty() && !arbiter.busy.value
            if (!canScan) {
                // A connect is in flight (radio contention) or the adapter is off — wait, don't scan. Log the
                // pause so a scanning gap (which starves presence for the whole mesh) is attributable.
                val adapterOn = adapter?.isEnabled == true
                if (adapterOn) Log.d(TAG, "scan paused: connect in flight $inflight arbiter=${arbiter.busy.value}")
                // Wait for the event that ends the pause — the connect's end (`registerLink`/`failConnect` →
                // `wake()`), the arbiter freeing (its collector → `wakeScan()`), the adapter coming back
                // (`STATE_ON` → `wake()`) — with a timeout sized to that event, not a 2 s poll: an adapter left
                // off used to wake this loop thirty times a minute for as long as it stayed off.
                elapsedWait.receiveWithin(scanWake, if (adapterOn) CONNECT_PAUSE_WAIT_MS else ADAPTER_OFF_WAIT_MS)
                continue
            }
            val power = powerState.state.value
            val duty = PowerPolicy.dutyCycle(power)
            val phys = nextScanPhys()
            scanner.phys = phys
            scanner.start(if (power.interactive || power.charging) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            val windowStart = elapsed()
            val scanned = scanner.isScanning
            elapsedWait.sleep(duty.scanWindowMs)
            scanner.stop()
            if (scanned) noteQuietScan(phys, elapsed() - windowStart)
            val aloneFor = aloneForMs()
            val idle =
                if (floorScan()) {
                    PowerPolicy.settledIdleAfterScan(power, links.size, aloneFor)
                } else {
                    PowerPolicy.idleAfterScan(power, links.size, aloneFor)
                }
            logLonelyTransition(links.isEmpty() && PowerPolicy.lonelyRelaxed(power, aloneFor), idle, aloneFor)
            // The hunting gap keeps wall time through sleep; every other gap is a power budget and stretches (pj9w).
            if (PowerPolicy.hunting(power, links.size, aloneFor)) {
                elapsedWait.receiveWithin(scanWake, idle)
            } else {
                withTimeoutOrNull(idle) { scanWake.receive() }
            }
        }
    }

    /**
     * The next presence window's PHYs ([CodedPhyPolicy.scanPhys]): every other one Coded-only while this phone has no
     * link or an unlinked peer is heard on Coded alone. Logs one line per edge of those windows.
     */
    private fun nextScanPhys(): ScanPhys {
        val on = codedOn()
        val far =
            if (on) {
                presence
                    .snapshots(elapsed())
                    .filter { it.nodeId !in links.keys && CodedPhyPolicy.codedOnly(it.oneMSeenAgoMs, it.codedSeenAgoMs) }
                    .map { it.nodeId }
            } else {
                emptyList()
            }
        val alone = links.isEmpty()
        val phys = CodedPhyPolicy.scanPhys(on, alone, far.isNotEmpty(), lastScanCoded)
        lastScanCoded = phys == ScanPhys.CODED
        val windows = on && (alone || far.isNotEmpty())
        if (windows != codedWindows) {
            codedWindows = windows
            val why = if (alone) "alone" else "codedOnly=$far"
            Log.i(TAG, if (windows) "bt scan coded windows on ($why)" else "bt scan coded windows off")
        }
        return phys
    }

    /** A window of [ms] ran on [phys]: GATT-payload quiet time, unless it was Coded-only — which cannot hear an iPhone. */
    private fun noteQuietScan(
        phys: ScanPhys,
        ms: Long,
    ) {
        if (gattPeers && phys != ScanPhys.CODED) forgetQuietGattPayloads(ms)
    }

    /** One line per edge of the lonely cadence, so a device trial can grep when the scan relaxed and why. */
    private fun logLonelyTransition(
        relaxed: Boolean,
        idleMs: Long,
        lonelyForMs: Long,
    ) {
        if (relaxed == lonelyRelaxedLogged) return
        lonelyRelaxedLogged = relaxed
        if (relaxed) {
            Log.i(TAG, "bt scan lonely: relaxed idle=${idleMs}ms (alone ${lonelyForMs}ms)")
        } else {
            Log.i(TAG, "bt scan lonely: aggressive again")
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

    /**
     * A scan hit, on the main thread. An advert with our service data is sighted as it always was. One that only lists
     * the `0xFE30` UUID (a foreground iPhone) is sighted with the payload its GATT characteristic served, once read;
     * until then it launches a read on [scope] when [GattPayloads] says one is due, and wakes nothing — the read's own
     * [sight] is what counts as a boost trigger (companion change A3).
     */
    private fun onScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getServiceData(BleConstants.SERVICE_UUID)
        if (data != null) {
            val coded = result.primaryPhy == BluetoothDevice.PHY_LE_CODED
            BleAdvertPayload.parse(data)?.let { sight(it, result.device, result.rssi, coded) }
            return
        }
        if (!gattPeers || record.serviceUuids?.contains(BleConstants.SERVICE_UUID) != true) return
        val read = gattPayloads.heard(result.device.address)
        if (read != null) sight(read, result.device, result.rssi) else maybeReadGatt(result.device, result.rssi)
    }

    /** The peer [parsed] names is here, on [device] at [rssi]: presence, the side channel's audience, and the loops. */
    private fun sight(
        parsed: BleAdvertPayload.Parsed,
        device: BluetoothDevice,
        rssi: Int,
        coded: Boolean = false,
    ) {
        if (parsed.nodeId == localNodeIdOrEmpty()) return
        if (coded) {
            // Its Coded set's address is not its presence advert's: kept apart, so a dial picks the PHY it opens on.
            codedDeviceFor[parsed.nodeId] = device
            codedCapable[parsed.nodeId] = elapsed()
            metrics.onBleCodedSighting()
        } else {
            deviceFor[parsed.nodeId] = device
        }
        if (codedOn() && parsed.nodeId !in links.keys) {
            codedTallies.compute(parsed.nodeId) { _, t -> (t ?: CodedTally()).also { it.note(rssi, coded) } }
        }
        links[parsed.nodeId]?.let {
            neverSighted.remove(it)
            if (coded) ensurePhyControl(it) // heard on Coded only after its link came up
        }
        presence.onSighting(
            BlePresenceTracker.Sighting(
                nodeId = parsed.nodeId,
                rssiDbm = rssi,
                protoVersion = Protocol.VERSION, // implied by the matched (versioned) service UUID
                capabilities = parsed.capabilities,
                psm = parsed.psm,
                digestCue = parsed.digestCue,
                coded = coded,
            ),
            elapsed(),
        )
        publishReachable()
        if (sideChannel != null) {
            // The audience, not anyCapable: a flagged peer appearing beside an all-linked clique must wake the loop.
            val before = sideCapable.audience(elapsed(), links.keys)
            sideCapable.note(parsed.nodeId, parsed.sideChannel, elapsed())
            if (sideCapable.audience(elapsed(), links.keys) != before) wakeSide()
        }
        healSignal.trySend(Unit) // connectLoop: react to every sighting to drive promotion
        // scanLoop: wake ONLY for a genuine boost trigger. Waking on every sighting (incl. already-linked peers)
        // is what keeps a settled clique scanning continuously — gating here is what lets the floor engage.
        if (isBoostTrigger(parsed.nodeId)) scanWake.trySend(Unit)
    }

    /**
     * Reads [device]'s GATT payload when [GattPayloads] says one is due, and sights the peer it names. Never an address
     * a link holds, and never while a dial is in flight or another connect holds the arbiter: the read is a connection
     * the controller must make, as a dial is. The read is launched, never run on the scan callback's main thread.
     */
    private fun maybeReadGatt(
        device: BluetoothDevice,
        rssi: Int,
    ) {
        val address = device.address
        if (!::localNodeId.isInitialized || address in linkAddresses.values) return
        if (inFlightSnapshot().isNotEmpty() || arbiter.busy.value) return
        if (!gattPayloads.begin(address, elapsed())) return
        gattReads.incrementAndGet()
        Log.d(TAG, "bt gatt reading $address")
        gattJob =
            scope.launch(Dispatchers.IO) {
                // Whatever the read threw, it ends as a failure: an unfinished read would hold [GattPayloads] for good.
                val result =
                    runCatching { gattReader.read(device) }.getOrElse {
                        if (it is CancellationException) throw it
                        BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "error: ${it.message}")
                    }
                gattPayloads.finish(address, result.outcome, elapsed())
                when (val outcome = result.outcome) {
                    is GattPayloads.Outcome.Read -> {
                        Log.i(TAG, "bt gatt read $address → ${outcome.payload.nodeId} (psm ${outcome.payload.psm})")
                        sight(outcome.payload, device, rssi)
                    }

                    GattPayloads.Outcome.Failed -> {
                        Log.i(TAG, "bt gatt read $address failed (${result.phase})")
                    }

                    GattPayloads.Outcome.Stranger -> {
                        Log.i(TAG, "bt gatt read $address stranger (${result.phase})")
                    }
                }
                wake() // the arbiter is free again, and a dial held back for the read may go
                advertEdge() // the read's connection has closed (#112: it opened at a 15 ms interval)
            }
    }

    /** Gives up the read in progress without a wait: the radio went down under it. */
    private fun cancelGattRead() {
        gattJob?.cancel()
        gattJob = null
        gattPayloads.cancel()
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
            if (adapter?.isEnabled == true) driveConnections()
            // Every event that can change a promotion decision pokes healSignal (a sighting, a link up or down,
            // a failed connect, power, the adapter, heal()). The one thing nothing signals is a backed-off peer
            // becoming eligible again, so the wait runs to the earliest backoff deadline — never a flat 5 s
            // tick, which woke this loop twelve times a minute on a settled or empty mesh — with a ceiling so a
            // lost wake costs at most a minute.
            elapsedWait.receiveWithin(healSignal, nextConnectWaitMs())
        }
    }

    private fun nextConnectWaitMs(): Long {
        val now = elapsed()
        val snaps = presence.snapshots(now)
        val backoffDue = synchronized(lock) { backoffs.values.filter { it.nextAt > now }.minOfOrNull { it.nextAt } }
        // The lonely dial comes due on the clock too — the window closing, a candidate's dwell ripening — and on a
        // screen-off phone the next sighting would restart that dwell rather than ripen it (ADR 2026-09.hj4a).
        val lonelyDue = lonelyDialDueMs(snaps)?.let { now + it }
        // So does the ordinary dial, for the same reason: a candidate's dwell ripens between scan windows (ADR 2026-10.pj9w).
        val ripeDue = ordinaryDialDueMs(snaps, now)?.let { now + it }
        val nextDue = listOfNotNull(backoffDue, lonelyDue, ripeDue).minOrNull()
        return ConnectBackoffPolicy.nextDueWaitMs(now, nextDue, CONNECT_WAIT_MIN_MS, CONNECT_WAIT_MAX_MS)
    }

    /** The sighted peers the tie-break has us dial: a smaller id, not linked, no dial to it in flight. */
    private fun ordinaryCandidates(snaps: List<BlePresenceTracker.Snapshot>): List<BlePresenceTracker.Snapshot> =
        snaps.filter { localNodeId > it.nodeId && it.nodeId !in links.keys && it.nodeId !in inFlightSnapshot() }

    private fun ordinaryDialDueMs(
        snaps: List<BlePresenceTracker.Snapshot>,
        now: Long,
    ): Long? =
        if (!::localNodeId.isInitialized || adapter?.isEnabled != true) {
            null
        } else {
            PromotionPolicy.msUntilDue(ordinaryCandidates(snaps), activeBackoff(now), promotionConfig())
        }

    /** The promotion tunables, with the debug link cap (if any) as the budget. */
    private fun promotionConfig() = PromotionConfig(maxLinks = debugCap ?: PromotionConfig.DEFAULT_MAX_LINKS)

    /**
     * The sighted peers [LonelyDialPolicy] may pick from: dialable at all (device and PSM known), not linked, with the
     * ordinary backoff read without [driveConnections]' prune. The policy applies the id order and the other gates.
     */
    private fun lonelyCandidates(snaps: List<BlePresenceTracker.Snapshot>): List<LonelyDialPolicy.Candidate> {
        val backoff = activeBackoff(elapsed())
        return snaps
            .filter { it.nodeId !in links.keys && it.psm != 0 && dialable(it.nodeId) }
            .map { LonelyDialPolicy.Candidate(it.nodeId, it.smoothedRssi, it.dwellMs, it.nodeId in backoff) }
    }

    /** Whether a dial has an address to go to: the peer's 1M advert's, or its Coded set's. */
    private fun dialable(nodeId: String): Boolean = deviceFor.containsKey(nodeId) || codedDeviceFor.containsKey(nodeId)

    /**
     * The address a dial to [snap]'s peer goes to, and whether it is the Coded one: a peer heard on Coded alone is
     * dialed at its Coded set, and the link opens on Coded (the 2026-10-01 spike: a plain L2CAP connect to a
     * Coded-only advert lands on Coded, no initiating-PHY mask needed). Null when neither address is known.
     */
    private fun dialTarget(snap: BlePresenceTracker.Snapshot): Pair<BluetoothDevice, Boolean>? {
        val viaCoded = codedOn() && CodedPhyPolicy.dialCoded(snap.oneMSeenAgoMs, snap.codedSeenAgoMs)
        val device = if (viaCoded) codedDeviceFor[snap.nodeId] else deviceFor[snap.nodeId] ?: codedDeviceFor[snap.nodeId]
        return device?.let { it to viaCoded }
    }

    /** Counts a Coded dial, and returns the `via=` the initiating line carries while the experiment runs. */
    private fun noteDial(viaCoded: Boolean): String {
        if (viaCoded) metrics.onBleCodedDial()
        return when {
            !codedOn() -> ""
            viaCoded -> " via=coded"
            else -> " via=1m"
        }
    }

    /** Whether a dial to a larger id is open: the tie-break never dials one, so it can only be a lonely dial. */
    private fun lonelyDialInFlight(): Boolean = inFlightSnapshot().any { it > localNodeId }

    private fun lonelyDialDueMs(snaps: List<BlePresenceTracker.Snapshot>): Long? =
        if (!::localNodeId.isInitialized || adapter?.isEnabled != true) {
            null
        } else {
            LonelyDialPolicy.msUntilDue(localNodeId, links.size, aloneForMs(), lonelyCandidates(snaps), lonelyDialInFlight())
        }

    private fun driveConnections() {
        val now = elapsed()
        val snaps = presence.snapshots(now)
        publishReachable(now)
        val rssiByNode = snaps.associate { it.nodeId to it.smoothedRssi }
        // Candidates: peers we're the initiator for (tie-break: larger id initiates), not linked, not in flight.
        val candidates = ordinaryCandidates(snaps)
        // The links this decision scores: an eviction closes the link it scored, never one that replaced it since.
        val scored = links.values.associateBy { it.nodeId }
        val linkSnaps =
            scored.values.map { fl ->
                PromotionPolicy.LinkSnapshot(
                    nodeId = fl.nodeId,
                    smoothedRssi = BleAdmissionPolicy.linkRssi(rssiByNode[fl.nodeId], neverSighted = fl in neverSighted),
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
        val cap = debugCap
        val config = promotionConfig()
        val ordinary = PromotionPolicy.decide(candidates, linkSnaps, backoff, config)
        // Alone past the window: one larger-id peer too, which the responder admits unless it has sighted us (hj4a).
        val lonely = LonelyDialPolicy.pick(localNodeId, links.size, aloneForMs(), lonelyCandidates(snaps), lonelyDialInFlight(), config)
        val decided = if (lonely == null) ordinary else ordinary.copy(promote = ordinary.promote + lonely.nodeId)
        // Under a debug cap, a dial still in flight holds a slot too, so back-to-back ticks can't overshoot it.
        val decision =
            if (cap == null) {
                decided
            } else {
                val room = cap - linkSnaps.size - inFlightSnapshot().size + decided.evict.size
                decided.copy(promote = decided.promote.take(room.coerceAtLeast(0)))
            }
        if (decision.promote.isNotEmpty() || decision.evict.isNotEmpty()) {
            Log.i(TAG, "promote=${decision.promote} evict=${decision.evict} backoff=$backoff a2dp=${audioMonitor.state.value}")
        }
        decision.evict.forEach { id -> scored[id]?.let { teardownLink(id, "evicted", only = it) } }
        // A GATT read is a connection too; a dial waits for it (the read's end wakes this loop), as a read waits for a dial.
        if (gattPayloads.current == null) dial(decision.promote, lonely)
    }

    /** Dials each of [promote]; the one that is [lonely]'s is announced first (ADR 2026-09.hj4a). */
    private fun dial(
        promote: List<String>,
        lonely: LonelyDialPolicy.Candidate?,
    ) {
        promote.forEach { id ->
            // Straight before its own `bt initiating to`: the iOS port's interop harness keys off the pair.
            if (id == lonely?.nodeId) {
                Log.i(TAG, "bt lonely dial $id (alone=${aloneForMs()}ms rssi=${lonely.smoothedRssi.toInt()} dwell=${lonely.dwellMs}ms)")
            }
            initiateTo(id)
        }
    }

    @Suppress("LongMethod") // the connect + watchdog + two-way HELLO is one linear flow; splitting it obscures it
    private fun initiateTo(nodeId: String) {
        val snap = presence.snapshots(elapsed()).firstOrNull { it.nodeId == nodeId } ?: return
        val (device, viaCoded) = dialTarget(snap) ?: return
        val psm = presence.psmFor(nodeId) ?: return
        if (!beginConnect(nodeId)) return
        val rssi = snap.smoothedRssi.toInt()
        Log.i(
            TAG,
            "bt initiating to $nodeId (psm $psm rssi=$rssi a2dp=${audioMonitor.state.value} links=${links.size}${noteDial(viaCoded)})",
        )
        scope.launch(Dispatchers.IO) {
            val startedAt = elapsed()
            val socket =
                runCatching { device.createInsecureL2capChannel(psm) }
                    .getOrElse { t -> return@launch failConnect(nodeId, classify(t), startedAt, t, device) }
            // Time-box the blocking connect(): on stall, give up this peer's inFlight slot at the timeout so the
            // scan (paused while any connect is in flight) resumes promptly — instead of after connect() finally
            // unwinds. Closing the socket is best-effort only: on some stacks (the API-30 device's Qualcomm
            // cherokee) close() can't abort an in-progress L2CAP connect and itself blocks until the *native*
            // connect timeout (~21s ≫ our 12s), which is exactly what used to pin inFlight and blind the scan for
            // that whole window. [settled] lets whichever fires first — the watchdog or connect() returning — own
            // the single failConnect; the loser just tidies up the socket. A dial to a Coded address gets longer
            // (`CodedPhyPolicy.connectTimeoutMs`): Android listens on Coded a quarter of the time while it connects.
            val settled = AtomicBoolean(false)
            val timeoutMs = CodedPhyPolicy.connectTimeoutMs(viaCoded)
            val watchdog =
                scope.launch(Dispatchers.IO) {
                    elapsedWait.sleep(timeoutMs)
                    if (settled.compareAndSet(false, true)) {
                        val cause = TimeoutException("connect watchdog ${timeoutMs}ms")
                        failConnect(nodeId, ConnectFailReason.TIMEOUT, startedAt, cause, device)
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
                return@launch failConnect(nodeId, classify(connectErr), startedAt, connectErr, device)
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
                    elapsedWait.sleep(ACCEPT_HELLO_TIMEOUT_MS)
                    runCatching { socket.close() }
                }
            val reply = runCatching { LinkHandshake.readHello(link.input) }.getOrNull()
            replyWatchdog.cancel()
            if (reply == null || reply.nodeId != nodeId) {
                // A reply naming another node: a new node took the address our payload came from. No reply keeps it.
                if (reply != null) forgetGattPayloadIfOther(device.address, reply.nodeId)
                link.close()
                return@launch failConnect(
                    nodeId,
                    ConnectFailReason.HANDSHAKE,
                    startedAt,
                    IllegalStateException("hello reply ${reply?.nodeId ?: "absent"} != expected $nodeId"),
                )
            }
            Log.i(TAG, "bt connect ok $nodeId durMs=${elapsed() - startedAt}")
            // The reply's full-width capabilities, as an accepted link takes the dialer's: the advert carries only
            // their low byte, which never holds CAP_DOORBELL, so a dialed iPhone would never be rung (A3, ADR 2026-09.dqvb).
            registerLink(nodeId, reply, link, device = device)
        }
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (scope.isActive && serverSocket === ss) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { superviseAccepted(socket) }
        }
    }

    /**
     * A client connected to our L2CAP responder: read its identity (HELLO, watchdog-bounded), then keep it or
     * close it by [BleAdmissionPolicy] — which admits a dialer the scan never saw whatever the id order.
     */
    private suspend fun superviseAccepted(socket: BluetoothSocket) {
        val link = BluetoothSocketLink(socket)
        // BluetoothSocket has no soTimeout, so bound the HELLO read by closing the socket if it stalls.
        val watchdog =
            scope.launch {
                elapsedWait.sleep(ACCEPT_HELLO_TIMEOUT_MS)
                runCatching { socket.close() }
            }
        val advert = runCatching { LinkHandshake.readHello(link.input) }.getOrNull()
        watchdog.cancel()
        val clientNodeId = advert?.nodeId
        if (advert == null || clientNodeId == null) {
            link.close()
            return
        }
        // A dialer at an address whose payload names another node took that address: forget the payload before the
        // verdict, so the ghost it names is not what this dialer is judged by (the contract's payload lifetime, rule 2).
        forgetGattPayloadIfOther(socket.remoteDevice.address, clientNodeId)
        val snap = presence.snapshots(elapsed()).firstOrNull { it.nodeId == clientNodeId }
        val sighted = snap != null
        // A dialer heard on Coded alone is judged as unsighted while the experiment runs (ADR 2026-10.yvn6): its sparse
        // Coded hits hold it in presence but rarely promote it, so "we dial it" left a far pair each waiting.
        val admitSighted = CodedPhyPolicy.sightedForAdmission(snap, codedOn())
        val heldAgeMs = links[clientNodeId]?.let { elapsed() - it.linkStartedAt }
        val atCap = debugCap?.let { links.size + inFlightSnapshot().size >= it } ?: false
        val verdict = BleAdmissionPolicy.decide(localNodeId, clientNodeId, admitSighted, heldAgeMs, atCap)
        if (verdict == BleAdmissionPolicy.Verdict.Refuse) {
            Log.i(
                TAG,
                "bt refused client $clientNodeId (sighted=$sighted codedOnly=${sighted && !admitSighted} " +
                    "heldAgeMs=$heldAgeMs atCap=$atCap)",
            )
            link.close()
            return
        }
        // Reply with our identity so the initiator can confirm it reached us (two-way HELLO; the initiator
        // validates this before registering). We read first, then reply — no mutual block.
        if (runCatching { LinkHandshake.replyHello(link.output, localNodeId) }.isFailure) {
            link.close()
            return
        }
        Log.i(TAG, "bt accepted client $clientNodeId ($verdict, sighted=$sighted codedOnly=${sighted && !admitSighted})")
        registerLink(clientNodeId, advert, link, sighted, socket.remoteDevice)
    }

    private fun registerLink(
        nodeId: String,
        advert: Protocol.PeerWire,
        link: app.getknit.knit.mesh.link.LinkSocket,
        sighted: Boolean = true, // an initiator dials only a peer presence holds
        // The peer's device, for its doorbell and its address. [advert] is the peer's HELLO either way — the dialer's
        // on an accepted link, the reply on a dialed one — so its capabilities are full width and carry CAP_DOORBELL.
        device: BluetoothDevice? = null,
    ) {
        val events = LinkEvents()
        val framed =
            FramedLink(
                nodeId = nodeId,
                peer = Peer(nodeId, advert.protoVersion, advert.capabilities),
                socket = link,
                scope = scope,
                cacheDir = appContext.cacheDir,
                metrics = metrics,
                callbacks = events,
                now = SystemClock::elapsedRealtime,
                paceBytesPerSec = BLE_PACE_BYTES_PER_SEC,
                log = { msg -> Log.d(TAG, msg) },
            )
        events.link = framed
        crossings.forget(nodeId) // a fresh stream starts clean: the peer may have restarted with an empty SeenSet
        if (!sighted) neverSighted.add(framed)
        device?.let {
            linkAddresses[framed] = it.address
            linkDevices[framed] = it
        }
        val prev = links.put(nodeId, framed)
        if (prev != null) {
            neverSighted.remove(prev)
            linkAddresses.remove(prev)
            linkDevices.remove(prev)
            phyControls.remove(prev)?.close()
            publishLinkPhys()
            doorbells.remove(prev)?.close() // teardownLink(only = prev) will find it replaced and release nothing
            prev.close() // a stale link to the same peer — never leak it; its own end releases nothing now
        }
        // Before framed.start() and the neighbor refresh, so the link-up frames already ring it.
        if (device != null && DoorbellPolicy.serves(advert.capabilities)) {
            doorbells[framed] =
                BleDoorbell(
                    context = appContext,
                    device = device,
                    nodeId = nodeId,
                    scope = scope,
                    isLive = { links[nodeId] === framed },
                    onRang = { rings.incrementAndGet() },
                    now = SystemClock::elapsedRealtime,
                ).also { it.start() }
        }
        ensurePhyControl(framed)
        codedPace.onLinkUp(nodeId)
        if (codedFast) schedulePace(AdvertReassertPolicy.SETTLE_MS) // its peer is back: slow again, after the settle
        synchronized(lock) {
            inFlight.remove(nodeId)
            backoffs.remove(nodeId)
        } // success resets the peer's streak
        metrics.onBtLinkEstablished()
        framed.start()
        refreshNeighbors()
        publishReachable() // a live link ⇒ reachable, even for an inbound peer we never scan-sighted
        wake() // inFlight cleared + link count changed → resume connectLoop and re-evaluate scan demand
        advertEdge()
        Log.i(TAG, "bt link up: $nodeId (${links.size} live)")
    }

    /** Closes [nodeId]'s link — only if it is still [only], when given — and says whether one was released. */
    private fun teardownLink(
        nodeId: String,
        reason: String,
        only: FramedLink? = null,
    ): Boolean {
        val fl = (if (only == null) links.remove(nodeId) else only.takeIf { links.remove(nodeId, it) }) ?: return false
        if (links.isEmpty()) noLinkSince = elapsed() // the lonely dial's clock runs from the last link's end (hj4a)
        neverSighted.remove(fl)
        linkAddresses.remove(fl)
        linkDevices.remove(fl)
        phyControls.remove(fl)?.let { control ->
            notePhyDrop(control.status(), reason)
            control.close()
        }
        publishLinkPhys()
        doorbells.remove(fl)?.close()
        fl.close()
        crossings.forget(nodeId)
        // The peer was here until now: its side-channel flag lingers from the link's end, not from a sighting the
        // floored scan may have made hours ago — a dropped or evicted flagged peer is exactly whom a page reaches.
        sideCapable.touch(nodeId, elapsed())
        refreshNeighbors()
        publishReachable() // drop the peer from reachable too, unless it's still being scan-sighted
        wakeSide() // the audience may have gone unlinked (the eviction path has no other side wake)
        advertEdge()
        Log.i(TAG, "bt link down: $nodeId ($reason)")
        return true
    }

    /** Per-peer connect backoff: consecutive-failure streak + the elapsed-time deadline before the next attempt. */
    private class ConnectBackoffEntry(
        var streak: Int = 0,
        var nextAt: Long = 0L,
    )

    private fun beginConnect(nodeId: String): Boolean {
        val begun =
            synchronized(lock) {
                if (nodeId in links.keys || nodeId in inFlight) return false
                backoffs[nodeId]?.let { if (elapsed() < it.nextAt) return false }
                inFlight.add(nodeId)
                true
            }
        if (begun) wakeSide() // the side scan stops for the connect window (scanning starves connects)
        return begun
    }

    private fun failConnect(
        nodeId: String,
        reason: ConnectFailReason,
        startedAt: Long,
        cause: Throwable,
        device: BluetoothDevice? = null,
    ) {
        // A dial that failed before its channel opened may have used a PSM the peer no longer listens on (an iPhone
        // that restarted), so its GATT payload is read again at the next advert. A HELLO refusal says nothing of the
        // PSM (GattPayloads, companion change A3).
        if (gattPeers && device != null && reason != ConnectFailReason.HANDSHAKE) forgetGattPayload(device.address, "dial")
        val (streak, nextAt) =
            synchronized(lock) {
                inFlight.remove(nodeId)
                // A dial the peer refused because its own dial to us linked first (a lonely dial crossing the
                // ordinary one, ADR 2026-09.hj4a) is no failure to back off from: that link reset the streak already.
                if (nodeId in links.keys) 0 to elapsed() else bumpBackoffLocked(nodeId)
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

    /**
     * Forgets [address]'s GATT payload and any wait before its next read, so its next advert reads it afresh (the
     * contract's payload lifetime). Logged only when a payload went: `bt gatt forget <addr> (dial|hello <id>|quiet)`.
     */
    private fun forgetGattPayload(
        address: String,
        reason: String,
    ) {
        if (gattPayloads.payload(of = address) != null) Log.i(TAG, "bt gatt forget $address ($reason)")
        gattPayloads.forget(address)
    }

    /** Rule 2: a HELLO on a link to [address] named [nodeId], and the payload held for the address names another. */
    private fun forgetGattPayloadIfOther(
        address: String?,
        nodeId: String,
    ) {
        if (!gattPeers || address == null) return
        val held = gattPayloads.payload(of = address) ?: return
        if (held.nodeId != nodeId) forgetGattPayload(address, "hello $nodeId")
    }

    /**
     * Rule 3: the scan ran [ms] more, and a payload whose address went [GattPayloads.Configuration.quietMs] of scanning
     * unheard, with no link to it up, is forgotten — a node that left, whose address another may take up later.
     */
    private fun forgetQuietGattPayloads(ms: Long) {
        gattPayloads.scanned(ms, linkAddresses.values.toSet()).forEach { Log.i(TAG, "bt gatt forget $it (quiet)") }
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
                            cancelGattRead()
                            presence.clear()
                            deviceFor.clear()
                            codedDeviceFor.clear()
                            sideCapable.clear()
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

    /**
     * Subscribe to adapter on/off. Same hardening as the Wi-Fi Aware twin: the export flag is explicit rather
     * than relying on Android 14+'s system-broadcast exemption, and the registration is guarded so a ROM that
     * doesn't mark `ACTION_STATE_CHANGED` a `<protected-broadcast>` degrades this plane instead of throwing
     * `SecurityException` out of [start] and taking the process with it.
     *
     * The degradation bites harder here than on the Aware side, which is why it is logged: [bringUp] runs
     * only from [start] and from this receiver's `STATE_ON`, so with no receiver a Bluetooth adapter that is
     * off at [start] — or toggled off and back on — leaves the plane down for the rest of the process. The
     * Wi-Fi Aware twin re-checks `isAvailable` on every attach retry and recovers on its own.
     */
    private fun registerAvailability() {
        if (availabilityRegistered) return
        availabilityRegistered =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    availabilityReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "adapter-state receiver registration failed", it) }.isSuccess
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        runCatching { appContext.unregisterReceiver(availabilityReceiver) }
        availabilityRegistered = false
    }

    // Every ACL that opens or closes on the adapter — a mesh link, our GATT read, a bonded watch, a Meshtastic board,
    // another app's — is a connection the stack re-enables its advertising sets around (#112). Most never reach this
    // transport any other way. The receiver only marks the edge; [advertLoop] does the work.
    private val aclEdgeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                Log.d(TAG, "bt acl edge ${intent.action?.substringAfterLast('.')}")
                advertEdge()
            }
        }

    /**
     * Subscribes to ACL edges, guarded and degrading like [registerAvailability]: without it only the net runs.
     *
     * **Exported, unlike the adapter-state receiver, and it must stay so.** `ACTION_ACL_*` is sent by the Bluetooth
     * app (`com.android.bluetooth`, uid 1002), not by `system_server`, and a `RECEIVER_NOT_EXPORTED` receiver hears only
     * root, `system_server` and its own app: registered that way it never fired once on the Pixel 7 (Android 17, #112's
     * trial), while `ACTION_STATE_CHANGED` comes from `system_server` and reaches the other. Both actions are protected
     * broadcasts, so no app can forge one — and a forged edge would only bring an enable forward.
     */
    private fun registerAclEdges() {
        if (aclEdgesRegistered) return
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        aclEdgesRegistered =
            runCatching {
                ContextCompat.registerReceiver(appContext, aclEdgeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }.onFailure { Log.w(TAG, "ACL edge receiver registration failed", it) }.isSuccess
    }

    private fun unregisterAclEdges() {
        if (!aclEdgesRegistered) return
        runCatching { appContext.unregisterReceiver(aclEdgeReceiver) }
        aclEdgesRegistered = false
    }

    // --- Helpers ---

    private fun elapsed() = SystemClock.elapsedRealtime()

    /**
     * How long this node has held no link at all; 0 while one is held. The clock of the scan's lonely cadence (ADR
     * 2026-09.w3xk) and of the lonely dial (ADR 2026-09.hj4a).
     */
    private fun aloneForMs(): Long = if (links.isEmpty()) elapsed() - noLinkSince else 0L

    private fun inFlightSnapshot(): Set<String> = synchronized(lock) { inFlight.toSet() }

    /** Read-only view of peers currently in connect backoff (no [driveConnections] prune side effect). */
    private fun activeBackoff(now: Long): Set<String> = synchronized(lock) { backoffs.filterValues { now < it.nextAt }.keys.toSet() }

    /** Wake both loops — a demand-relevant event (link up/down, connect fail, power change, adapter on, heal). */
    private fun wake() {
        healSignal.trySend(Unit)
        scanWake.trySend(Unit)
        sideWake.trySend(Unit)
    }

    /** Wake only the scan loop — a scan-cadence event (a boost trigger sighted, a foreign peer, an audio change). */
    private fun wakeScan() {
        scanWake.trySend(Unit)
        sideWake.trySend(Unit)
    }

    /** Wake the side channel's loop — its inputs changed (a flagged peer came or went, availability flipped). */
    private fun wakeSide() {
        sideWake.trySend(Unit)
    }

    /**
     * Keeps the side channel's scan at the tier [SideScanPolicy] wants. Its own loop, not [scanLoop]'s: that
     * one sleeps for minutes at the floor, while this one must react to a connect starting (scanning starves
     * connects) and to a flagged peer appearing. Re-asks every [SIDE_TICK_MS] regardless, which is also how a
     * start deferred by the shared scan budget gets retried and the periodic restart lands — and how a file
     * starting to stream on a link is noticed: [FramedLink]'s in-progress flags raise no event, and a BLE-paced
     * blob runs tens of seconds, so the tick is soon enough (a few-KB avatar is over before it and blocks nothing).
     */
    private suspend fun sideScanLoop(side: BleSideChannel) {
        while (scope.isActive) {
            val inputs =
                SideScanPolicy.Inputs(
                    live = side.live && adapter?.isEnabled == true,
                    audience = sideCapable.audience(elapsed(), links.keys),
                    streamInFlight = links.values.any { it.txInProgress || it.rxInProgress },
                    connectBusy = inFlightSnapshot().isNotEmpty() || arbiter.busy.value,
                    audioContended = audioMonitor.contended.value,
                    power = powerState.state.value,
                )
            val tier = SideScanPolicy.decide(inputs)
            side.applyScanTier(tier)
            val decision = "want=$tier audience=${inputs.audience} stream=${inputs.streamInFlight} busy=${inputs.connectBusy}"
            if (sideDecision != decision) {
                sideDecision = decision
                Log.d(TAG, "ble-side $decision applied=${side.scanTier}")
            }
            withTimeoutOrNull(SIDE_TICK_MS) { sideWake.receive() }
        }
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
            // R8 strips the Log.d in release, not the string this builds under the lock: debug only.
            if (BuildConfig.DEBUG) logState()
            // Not debug-gated: a release-shaped `-PbleCodedPhy=true` build is walked too.
            if (codedOn()) logCodedHeard() else codedTallies.clear()
        }
    }

    /**
     * One `bt coded heard` line per unlinked peer heard on Coded this minute: its Coded hits and their raw RSSI span,
     * its 1M hits, and what promotion makes of it (effective RSSI, dwell, whether it clears both, whether we dial it).
     */
    private fun logCodedHeard() {
        val now = elapsed()
        val snaps = presence.snapshots(now).associateBy { it.nodeId }
        val dwellNeeded = PromotionConfig().dwellThresholdMs
        codedTallies.keys.toList().forEach { id ->
            val t = codedTallies.remove(id) ?: return@forEach
            if (t.hits == 0 || id in links.keys) return@forEach
            val snap = snaps[id]
            val promotable = snap != null && snap.smoothedRssi >= PROMOTE_RSSI_FLOOR && snap.dwellMs >= dwellNeeded
            val backoffS = synchronized(lock) { backoffs[id]?.let { ((it.nextAt - now) / MS_PER_S).coerceAtLeast(0) } ?: 0 }
            Log.i(
                TAG,
                "bt coded heard $id hits=${t.hits} rssi=${t.min}..${t.max} last=${t.last} 1m=${t.oneM} " +
                    "eff=${snap?.smoothedRssi?.toInt()} dwell=${snap?.dwellMs}ms promotable=$promotable " +
                    "dials=${localNodeId > id} backoff=${backoffS}s",
            )
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
                "inFlight=${inFlightSnapshot()} backoff=[$backoffStr] a2dp=${audioMonitor.state.value} " +
                "alone=${aloneForMs()}ms psm=$currentPsm advert=$presenceAdvert " +
                "doorbells=${doorbells.size} rings=${rings.get()}" +
                (if (gattPeers) " gattPayloads=${gattPayloads.payloadCount} gattReads=${gattReads.get()}" else "") +
                (if (codedOn()) " phy=${codedMode.wire} coded=$codedAdvert phyLinks=${phyControls.size}" else "") +
                (sideChannel?.let { " ${it.diag()} $sideDecision" } ?: ""),
        )
    }

    companion object {
        const val TAG = "BluetoothMeshTransport"

        /**
         * Whether this device has a Bluetooth adapter + BLE. The one probe of the adapter, kept here because
         * nothing outside `mesh/bluetooth/` imports `android.bluetooth.*` (rules/mesh.md); `RadioSupport.probe`
         * reads it so the Diagnostics row and this gate can never disagree.
         */
        fun support(context: Context): PlaneSupport =
            bleSupport(
                hasHardware =
                    context.getSystemService(BluetoothManager::class.java)?.adapter != null &&
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            )

        /** True on a device with a Bluetooth adapter + BLE — the composite includes this plane only if so. */
        fun isSupported(context: Context): Boolean = support(context) == PlaneSupport.Supported

        // Connection-engine wait bounds: the loop sleeps to the earliest connect backoff deadline (woken early by
        // healSignal), never less than the floor — so a deadline that just passed can't spin it — and never more
        // than the ceiling, the price of a wake that got lost.
        private const val CONNECT_WAIT_MIN_MS = 1_000L
        private const val CONNECT_WAIT_MAX_MS = 60_000L

        // Scan-pause waits: a connect in flight ends within its watchdog (the longest is a Coded dial's) and wakes
        // the loop itself; an adapter that is off wakes it from the STATE_ON receiver. Each is a safety net, not a
        // cadence.
        private const val CONNECT_PAUSE_WAIT_MS = CodedPhyPolicy.CODED_CONNECT_TIMEOUT_MS + 3_000L
        private const val ADAPTER_OFF_WAIT_MS = 60_000L

        // [codedKeeper] has no net of its own: the presence turns are it, so its period never comes due.
        private const val NO_NET_MS = Long.MAX_VALUE / 2

        // A Coded start the stack failed internally (ADVERTISE_FAILED_INTERNAL_ERROR) is retried this many times per
        // bring-up, on [codedKeeper]'s doubling wait; [CODED_RETRYING] marks a retry in flight, so its failure is quiet.
        private const val MAX_CODED_START_RETRIES = 5
        private val CODED_DARK_INTERNAL = "dark ${AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR}"
        private const val CODED_RETRYING = "retrying"

        // Bound the responder's HELLO read (BluetoothSocket has no soTimeout) — close the socket if it stalls.
        private const val ACCEPT_HELLO_TIMEOUT_MS = 5_000L

        // Backoff base after the first failed connect; escalates geometrically per peer (see ConnectBackoffPolicy),
        // capped at MAX_CONNECT_BACKOFF_MS. Replaces the old flat retry — a link-up resets the peer's streak.
        private const val CONNECT_BACKOFF_MS = 10_000L

        // Ceiling the per-peer connect backoff saturates at, so a persistently-failing peer is retried rarely
        // (radio busy / unreachable) instead of on a tight loop that blacks out scanning every attempt.
        private const val MAX_CONNECT_BACKOFF_MS = 180_000L

        // Cadence of the A2DP re-check and (debug) the diagnostic state line, mirroring NAN. A missed playing
        // edge delays the scan floor by at most this; the two AudioManager binder calls used to run every 12 s.
        private const val DIAG_INTERVAL_MS = 60_000L

        // Quiet window to coalesce a burst of digest-cue changes into ONE re-advertise. The cue is only a coarse
        // "my custody set changed, come sync" hint (the on-link DIGEST id-diff is authoritative), and the legacy
        // advert must stop→restart to swap its service data, so settling ~1.5s before republishing is imperceptible
        // to sync latency (the BLE scan floors at minutes) while it removes the per-change advertiser churn.
        private const val CUE_READVERTISE_DEBOUNCE_MS = 1_500L

        private const val MS_PER_S = 1_000L

        // How often the side channel's loop re-asks its scan policy when nothing woke it (also the retry cadence
        // for a start the shared scan budget deferred).
        private const val SIDE_TICK_MS = 10_000L

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

/** One unlinked peer's adverts over a minute, for `bt coded heard` (ADR 2026-10.yvn6). Guarded by the map's compute. */
private class CodedTally {
    var hits = 0
    var min = 0
    var max = 0
    var last = 0
    var oneM = 0

    fun note(
        rssi: Int,
        coded: Boolean,
    ) {
        if (!coded) {
            oneM++
            return
        }
        min = if (hits == 0) rssi else minOf(min, rssi)
        max = if (hits == 0) rssi else maxOf(max, rssi)
        last = rssi
        hits++
    }
}
