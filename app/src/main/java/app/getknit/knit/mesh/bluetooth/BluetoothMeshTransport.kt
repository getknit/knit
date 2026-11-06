package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.getknit.knit.BuildConfig
import app.getknit.knit.fieldlog.BtDownReason
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.BleAdvertWrite
import app.getknit.knit.mesh.BleBlindDrop
import app.getknit.knit.mesh.BleRotation
import app.getknit.knit.mesh.BleSideDrop
import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.FileFeed
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.FileProgress
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.bleSupport
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.furthestByKey
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LandingFiles
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkClaims
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.power.ElapsedWait
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
 *   random address is transparent. A blinding build airs a rotating tag in the id's place instead ([AdvertWriter],
 *   [AdvertRotation], ADR 2026-11.xp6d): fresh sets, a fresh PSM and fresh side slots at every slot edge, key change and
 *   bring-up, from one writer, never the id.
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
class BluetoothMeshTransport internal constructor(
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
    // Whether Knit's UI is on screen (`AppVisibility`): with the screen lit, the person is using Knit, and the scan hunts
    // while the Coded experiment runs ([CodedPhyPolicy.hunts]). False, always, in a process with no UI.
    private val appVisible: StateFlow<Boolean> = MutableStateFlow(false),
    // Whether the low-power posture is on (`LowPowerSource.current`, ADR 2026-10.d44b): a cold far sighting then starts
    // no hunt and no fast advert ([CodedPhyPolicy.coldHunt]). Read at each decision; false in a test or a build without it.
    private val lowPower: () -> Boolean = { false },
    // Where a link keeps an attachment cut mid-stream and resumes it from (#116, ADR 2026-10.wtyc): one store
    // shared with the Wi-Fi Aware plane and BlobExchange, so any holder on either radio can finish the transfer.
    private val partials: PartialBlobs,
    // The field log (ADR 2026-10.a239): each link's up, down and failed dial, as edges this transport already logs.
    private val field: FieldRecorder = FieldRecorder.NONE,
    // "This phone won't run Wi-Fi Aware" (GitHub #44, built in `di/MeshModule`): the advert's FLAG_AWARE is its
    // complement, through [AwareFlag]'s asymmetric debounce. True — the bit clear — for a phone without the plane, and by
    // default: a phone that cannot say it runs Aware never makes a peer wake its own for it.
    private val awareWontRun: StateFlow<Boolean> = MutableStateFlow(true),
    // Who must prove the node id its HELLO claims (R1–R4), and this phone's half of a proof (ADR 2026-11.264w, #232):
    // shared with the Wi-Fi Aware plane and the inbound pipeline, which keep the counters and the refusal lines.
    private val claims: LinkClaims,
    private val prover: LinkProver,
    // The blinded advert's writer (ADR 2026-11.xp6d, step 5b): `BuildConfig.BLIND_ADVERT`, the one seam, passed in so
    // nothing under mesh/bluetooth/ reads BuildConfig for it. It writes only while the runtime switch ([blindSwitch],
    // `SettingsStore.debugBleBlindAdvert`, on unless a debug build stored it off) is on and the controller has extended
    // advertising (its sets may share the host's address across a restart otherwise), and only with [tagMarks], where the
    // tag schedule's per-generation marks persist (`PresenceKeyRepository.markStore`).
    private val blindAdvert: Boolean = false,
    private val blindSwitch: Flow<Boolean> = flowOf(true),
    tagMarks: TagSchedule.MarkStore? = null,
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
    //
    // The presence and Coded sets live in [writer]'s one snapshot (ADR 2026-11.xp6d's writer, step 5b's W3): every write
    // goes through it, and a blinding rotation replaces both with fresh instances. [newPresenceAdvertiser] and
    // [newCodedAdvertiser] make them; their callbacks act only while their instance is the snapshot's.
    private val writer = AdvertWriter<BleAdvertiser> { localNodeId }

    // When the presence set is enabled again (#112): the stack's own re-enable around a connection can be refused, and
    // only sometimes says so. [presenceAdvert] is how it last fared — off, on, refused <status> or disabled.
    private val advertKeeper = AdvertReassertPolicy.Keeper()
    private val advertWake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var presenceAdvert = "off"
    private val scanner =
        BleScanner({ adapter?.bluetoothLeScanner }, ::onScanResult, log = { Log.d(TAG, it) }, matchesServiceUuid = gattPeers)

    // The Coded PHY experiment (ADR 2026-10.yvn6). [codedMode] is the mode last read from [phyMode], [codedSupported]
    // the controller probe [bringUp] makes; [writer]'s Coded set carries the presence payload again on the Coded PHY, and
    // [codedAdvert] says how it fared (a refused start leaves it dark until the next bring-up, as the side channel's).
    @Volatile private var codedMode = CodedPhyMode.OFF

    @Volatile private var codedSupported = false

    @Volatile private var codedTxPower = "high"

    @Volatile private var codedAdvert = "off"

    @Volatile private var codedPower = AdvertisingSetParameters.TX_POWER_HIGH

    // A peer's Coded address (its Coded set's own random address), beside [deviceFor]'s 1M one; and when each peer was
    // last heard on Coded, which is how a link knows its peer can step down — no advert flag is spent on it.
    private val codedDeviceFor = ConcurrentHashMap<String, HeardDevice>()
    private val codedCapable = ConcurrentHashMap<String, Long>()

    // Each link's PHY handle, keyed by link like [doorbells], and its peer's device, which a late handle needs.
    private val phyControls = ConcurrentHashMap<FramedLink, BlePhyControl>()

    // The PHY each link was last read on, kept past its handle: `mode off` lets every handle go but leaves the link on
    // its PHY (BlePhyControl.close), and a file fed to a link still on Coded must keep Coded's pace (#114). Dropped
    // with the link.
    private val linkPhy = ConcurrentHashMap<FramedLink, LinkPhy>()

    // The feeding count and earned flag [paceFor] last logged for each link, so `bt file pace` is written when a link's
    // split or budget changes, not every chunk. Dropped with the link.
    private val lastPace = ConcurrentHashMap<FramedLink, Pair<Int, Boolean>>()

    // This phone's LE connections as [leLinkCount] last counted them, and when: the boosted budget depends on how many
    // share the controller's buffers, and the count costs a binder call, so a feed reads it at most every 10 s.
    @Volatile private var leLinks = 1

    @Volatile private var leLinksAt = Long.MIN_VALUE / 2
    private val linkDevices = ConcurrentHashMap<FramedLink, BluetoothDevice>()

    // What each unlinked peer's adverts did this minute, logged by [diagLoop] as `bt coded heard` while the experiment
    // runs — the walk test's record of whether a far peer was heard, how loud, and why it did or did not promote.
    private val codedTallies = ConcurrentHashMap<String, CodedTally>()

    // When the Coded set advertises fast: after a link at range drops, until its peer is back or the hold runs out (ADR
    // 2026-10.yvn6, amendment 3). [paceJob] makes the next change — a drop's settle, a link's return, the hold's end.
    private val codedPace = CodedAdvertPace()

    // The dialing side's twin of [codedPace]: the peers a link of ours dropped at range, hunted by the scan until they
    // link again or the hold runs out (ADR 2026-10.yvn6, the 2026-10-04 amendment). [hunting] and [huntWhy] are the
    // last window's verdict, for one log line per change and the state line.
    private val rangeHunt = RangeHunt()

    // What cold far sightings may still spend (ADR 2026-10.yvn6, the 2026-10-20 amendment): each peer's cooldown, one
    // cold hunt and one cold fast window at a time, and the phone-wide budget. Its grants are [rangeHunt]'s and
    // [codedPace]'s cold holds.
    private val coldSightings = ColdSightings()

    @Volatile private var hunting = false

    private var huntWhy = ""

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

    // When the last presence window's full length would have ended. A dial ends a hunting window early, and the next
    // one still waits for this: windows then start at least a window apart, as they always did, which keeps the app
    // under Android's five scan starts per 30 s however fast dials begin and end. Touched by [scanLoop] alone.
    private var nextWindowAt = 0L

    // The waits a relink waits on — the scan's window, its pause behind a dial and its hunting gap, the connect loop,
    // the dial and HELLO watchdogs, the GATT payload read — run on the elapsed clock, so a phone that suspends between
    // events keeps them in wall time instead of stretching them by however long it slept (ADR 2026-10.pj9w). The
    // scan's other gaps are power budgets and keep plain timeouts, as do the waits elsewhere in this file.
    private val elapsedWait = ElapsedWait(::elapsed)

    // Whose GATT payload to read and what each read found (companion change A3), and the reader; [gattReads] counts
    // the reads begun, for the debug state line. [gattJob] is the read in progress, cancelled with the radio.
    private val gattPayloads = GattPayloads()
    private val gattReader = BleGattPayloadReader(appContext, arbiter, elapsedWait)
    private val gattReads = AtomicInteger()

    @Volatile private var gattJob: Job? = null

    // Live L2CAP links, keyed by peer nodeId (many, unlike NAN's ≤1).
    private val links = ConcurrentHashMap<String, FramedLink>()

    // Each link's silence verdict and the stamps of its socket's bytes (#203, ADR 2026-10.697m), keyed by link like
    // [doorbells]: one loop ([silenceLoop]) reads them all, woken by [silenceWake] when a keepalive comes or goes, a PHY
    // handle reports, an edge lands or a link comes up.
    private val silences = ConcurrentHashMap<FramedLink, SilentLink>()
    private val silenceWake = Channel<Unit>(Channel.CONFLATED)

    private class SilentLink(
        val policy: LinkSilence,
        val stamps: SocketStamps,
    )

    // Links this transport closed for silence: their reader's end is our close, not the peer's, so it bumps no streak and
    // logs no `(eof)` line (#203). Weak, as a reader or a blocked writer may outlive the link by any length of time.
    private val closedSilent: MutableSet<FramedLink> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // Unproven links this transport closed because their id can prove itself now (R3/R4, ADR 2026-11.264w): their end is
    // our close too, so it bumps no streak — the id's streak is the real peer's, which just proved itself. Weak, as above.
    private val closedForClaim: MutableSet<FramedLink> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // The R3/R4 hook on [claims], registered at start and closed at stop.
    @Volatile private var claimsHook: AutoCloseable? = null

    // The addresses of links closed on this phone's own controller's word (#203), until [checkOrphans] reports their ACL:
    // kept with this phone holding more LE connections than links, the stack is suspect (`bt stack suspect`, for #204).
    private val localCloses = ConcurrentHashMap<String, LocalClose>()

    private class LocalClose(
        val nodeId: String,
        val why: LinkSilence.Why,
        val at: Long,
    )

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

    // Each link's tuner (#120): a GATT client on the link's ACL that asks for a priority, an MTU or a PHY. Keyed by link
    // like [doorbells], made on first use by the link's [boosts] entry or by `…debug.LINKTUNE`, one per link for both.
    private val tuners = ConcurrentHashMap<FramedLink, BleLinkTuner>()

    // Each link's boost (#120): made at the link's first file either way when the build allows it and the peer is not an
    // iPhone, it asks for a short interval while this side feeds and says whether the link earned the larger budget.
    private val boosts = ConcurrentHashMap<FramedLink, BleLinkBoost>()

    // The LE connections closed channels left up (#142, ADR 2026-10.b8yf): fed by the ACL edges and the channels' ends,
    // read on [diagLoop]'s minute. [aclArms] counts the arms begun ([armAcl]: at link-up, at the end of a channel that never
    // became a link, and at stop) and [aclArmsAttached] those that attached, for the debug state line; a release is
    // counted by [orphans] alone (`released=`).
    private val orphans = OrphanAclPolicy<BluetoothDevice>()
    private val aclArms = AtomicInteger()
    private val aclArmsAttached = AtomicInteger()

    // Addresses whose unlinked channel's arm is settling ([armUnlinked], #150): one settle per address, so a dialer that
    // opens and closes channels many times a second spawns one sleeping coroutine, not one per channel. Each holds the
    // latest node id a channel's end on the address named, so a named end during a HELLO-less settle is not lost.
    private val unlinkedSettling = ConcurrentHashMap<String, UnlinkedSettle>()

    private class UnlinkedSettle(
        @Volatile var nodeId: String?,
    )

    // Which frames already crossed which link, either way: the router's flood copy and the fast path's link
    // copy are the same frame twice for the same stream, and a relayed frame's fast copy would go straight
    // back over the link it arrived on. One write per link per frame; the far end's SeenSet would have
    // dropped the rest (see [LinkCrossings] for why that makes it a byte saving and not a delivery change).
    private val crossings = LinkCrossings(clock = SystemClock::elapsedRealtime)

    // Freshest BluetoothDevice per per-peer key (updated every sighting, so a rotated random address re-associates), with
    // the form it was heard under: a dial uses it only while that form is the peer's current one (ADR 2026-11.xp6d).
    private val deviceFor = ConcurrentHashMap<String, HeardDevice>()

    private class HeardDevice(
        val device: BluetoothDevice,
        val advertised: String,
    )

    // The node ids whose latest sighting came through a GATT payload (an iPhone, kwq2) rather than service data: this
    // phone's own evidence that a dialer claiming CAP_DOORBELL in its unauthenticated HELLO is one (AND-3). Kept with
    // [deviceFor], not the payload, which the quiet rule can forget inside presence's 90 s linger.
    private val gattSighted = ConcurrentHashMap.newKeySet<String>()

    // Presence model (smoothed RSSI + dwell + linger) fed by scan sightings; drives promotion + `reachable`.
    private val presence =
        BlePresenceTracker(
            codedCreditDb = { CodedPhyDiag.tuning.codedCreditDb },
            oneMWeakDbm = { CodedPhyDiag.tuning.oneMWeakDbm },
        )

    // Connection bookkeeping guarded by [lock]: in-flight initiator connects + per-peer escalating backoff
    // (streak + next-eligible deadline), reset the moment a link comes up.
    private val lock = Any()

    // Each dial in flight and its kind, recorded when it starts: a lonely dial is never inferred from the id order
    // (ADR 2026-11.xp6d).
    // Keyed by the dial key (DialKeys.dialKey: a clear advert's id, a blinded one's handle and address).
    private val inFlight = HashMap<String, DialPlan.InFlight>()
    private val backoffs = HashMap<String, ConnectBackoffEntry>()
    private val backoffConfig = BackoffConfig(baseMs = CONNECT_BACKOFF_MS, maxMs = MAX_CONNECT_BACKOFF_MS)

    // Observes A2DP audio so a connect failure can be *attributed* to a busy radio (instrumentation only — see
    // [BluetoothAudioMonitor]); read at failure time and exposed via [radioContended] + the periodic diag line.
    private val audioMonitor = BluetoothAudioMonitor(appContext, adapter) { Log.d(TAG, it) }

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
    override val reachable = _reachable.asStateFlow()

    private val _near = MutableStateFlow<Set<Peer>>(emptySet())
    override val near = _near.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Healthy)
    override val health = _health.asStateFlow()

    // The peers this phone has positive evidence run Wi-Fi Aware (GitHub #44, [AwareCandidateTracker]): near (wakes a
    // dormant Aware plane) and heard (keeps it attached), published with [reachable] and [near] from one snapshot.
    // [seesNewcomers]: the last presence window's scan really ran, with the adapter on — false at once on its end.
    private val awareCandidates = AwareCandidateTracker()
    private val _awareCandidatesNear = MutableStateFlow<Set<String>>(emptySet())
    override val awareCandidatesNear = _awareCandidatesNear.asStateFlow()
    private val _awareCandidatesHeard = MutableStateFlow<Set<String>>(emptySet())
    override val awareCandidatesHeard = _awareCandidatesHeard.asStateFlow()

    // [seesNewcomers]' one writer: an off edge (adapter off, `stop()`) has the last word over any window that began
    // before it, so a late window end never undoes one ([NewcomerWatch]). Each change is logged, under its lock.
    private val newcomers =
        NewcomerWatch { sees, why -> Log.i(TAG, if (sees) "bt newcomers watched" else "bt newcomers unwatched ($why)") }
    override val seesNewcomers = newcomers.sees

    // Whether the advert carries FLAG_AWARE now ([AwareFlag] moves it); read by [readvertise] at each encode.
    @Volatile private var awareAdvertised = false

    // [publishReachable]'s snapshot and its writes, as one step: a sighting on the main thread and a window's end on
    // the scan loop's would otherwise write their sets in the other order. The sets last logged, for one line per change.
    private val publishLock = Any()
    private var awareLogged = AwareCandidateTracker.EMPTY

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 32)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    // Files a link finished that the consumer has not taken yet (#138): every link lands into it, none clears it.
    private val landing = LandingFiles()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 32)
    override val incomingDigests = _incomingDigests.asSharedFlow()

    override val kind = TransportKind.Bluetooth

    // The fast path is routed here (not turned into a plain send by the composite): [fastFanout]/[fastSend]
    // replicate the link copy the composite sent and add the side-channel page where it applies.
    override val hasFastPlane = true

    // Diagnostic-only: reflects A2DP-audio activity so the Diagnostics BLE row can flag a contended radio.
    override val radioContended = audioMonitor.contended

    private lateinit var localNodeId: String

    // My advertised forms (ADR 2026-11.xp6d), one snapshot every pre-link role reads ([judge], the dial plan): replaced
    // whole, never field by field. Clear while the writer airs the id; a blinding rotation swaps (current, previous) as one
    // snapshot when its fresh sets are asked to start ([rotate]).
    private val mine = AtomicReference<AdvertOrder.Mine?>(null)

    /** [mine], or the clear form of [localNodeId] before [start] has set it. */
    private fun mine(): AdvertOrder.Mine = mine.get() ?: AdvertOrder.Mine.clear(localNodeId)

    // The blinded advert's reader (ADR 2026-11.xp6d). [aliases] resolves a heard tag and takes every handshake event; it
    // and the key feed run on [worker] alone, one task at a time, never the main looper and never under [lock]: a call
    // can list a window's 140 HMACs per key. A blinded sighting hops there through [blindQueue] (latest per tag, bounded)
    // stamped at capture and with the radio generation it was heard in, and comes back to [sight] keyed by its resolved
    // id or, unresolved, by its form's handle ([formKeys]). [linkStamps] keeps a late resolve from undoing a link event.
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val aliases = AdvertAliases<FramedLink>(nowMs = SystemClock::elapsedRealtime, wallMs = System::currentTimeMillis)
    private val formKeys = FormKeys(onEvict = ::forgetHandle)
    private val blindQueue = BlindQueue<BlindSighting>()
    private val linkStamps = LinkEventStamps()
    private val replays = ReplayMarks()
    private val keyLimiter = KeyChurnLimiter()
    private val radioGeneration = AtomicInteger()

    @Volatile private var keyRelease: Job? = null

    @Volatile private var keyReleaseAt = Long.MAX_VALUE

    private var timeRegistered = false

    // The form each live link's peer advertised (a sighting while linked, or the HELLO's honoured `t`), so who dials back
    // after a drop at range reads it when presence has long lost the peer (bluetooth's part-1 review). Dropped with the link.
    private val linkForms = ConcurrentHashMap<FramedLink, AdvertOrder.Form>()

    private class BlindSighting(
        val parsed: BleAdvertPayload.Parsed,
        val device: BluetoothDevice,
        val rssi: Int,
        val coded: Boolean,
        // Capture time on the elapsed clock (`ScanResult.timestampNanos`), and the radio generation it was heard in.
        val at: Long,
        val generation: Int,
    )

    // The L2CAP responder's servers: the one the advert names, and while blinding the one a rotation just replaced, still
    // accepting for a dial timeout ([PsmRotation], step 5b's W4). Guarded by its own monitor; taken inside [writer]'s
    // lock by a rotation's install, never the other way round. [acceptJobs] holds each live server's accept loop.
    private val servers = PsmRotation<BluetoothServerSocket>(::listenL2cap, ::closeServerSocket)
    private val acceptJobs = ConcurrentHashMap<BluetoothServerSocket, Job>()
    private val psmDraw = SecureRandom()

    // The blinded advert's writer (ADR 2026-11.xp6d, step 5b). [tagSchedule] is the one per transport, so per process,
    // never per bring-up (it caches each generation's mark); [rotation] times the slot turn, [staleLatch] asks for one key
    // pass per generation on a stale mark. All three, [workerKey], [sessionHadKey] and [airedGeneration] are the worker's.
    private val tagSchedule: TagSchedule? = tagMarks?.let { TagSchedule(it) { localNodeId } }
    private val rotation = AdvertRotation()
    private val staleLatch = StaleLatch()
    private var workerKey: OwnPresenceKey? = null
    private var sessionHadKey = false
    private var airedGeneration: Long? = null

    // The own key as last handed in ([onOwnPresenceKey]), read by a bring-up's rotation; and the engine's own value,
    // read synchronously just before a tag airs ([onOwnPresenceKeySource]).
    @Volatile private var ownKey: OwnPresenceKey? = null

    @Volatile private var ownKeySource: () -> OwnPresenceKey? = { null }

    // The runtime switch's last value, and whether the controller has extended advertising ([probeCoded], adapter on):
    // null until read, sticky once true (the debug bridge's `extendedAdvertising` reports all three states).
    @Volatile private var blindSwitchOn = true

    @Volatile private var extAdvSupported: Boolean? = null

    // When the last ACL edge landed: a slot rotation runs at least SETTLE_MS after one (9utz), within its hold's bound.
    @Volatile private var lastAclEdgeAt = Long.MIN_VALUE / 2

    // `TAG_NEXT` sent (step 5b's W5): who may hear it ([tagNextHears], `MeshTransport.onTagNextRecipients`, read at each
    // send), the per-link throttle ([tagNextSends], the worker's) and the tag the standing announcement named
    // ([announcedTag], the worker's; null when none stands), sent to a link that comes up before the rotation.
    @Volatile private var tagNextHears: (String) -> Boolean = { false }
    private val tagNextSends = TagNextSends<FramedLink>()
    private var announcedTag: ByteArray? = null

    // A peer's accepted announcement waiting for its edge to become its link's form: one note and one wait per link, the
    // worker's ([LinkFormWaits], [onTagNext]).
    private val linkFormWaits =
        LinkFormWaits<FramedLink>(
            launch = { block -> scope.launch(worker) { block() } },
            wait = { ms -> wallWait.sleep(ms) },
            wallMs = System::currentTimeMillis,
        )

    // The live links this phone dialed (the dial path registers with `dialStartedAt`, the accept path without), keyed by
    // link identity and dropped wherever [linkForms] drops the link, so a replaced link never reports its successor's role.
    // Read by the debug bridge's `ADVERT` (`links[].dialedByMe`).
    private val dialedByMe: MutableSet<FramedLink> = ConcurrentHashMap.newKeySet()

    // When the last rotation installed (elapsed clock, the worker's): `ADVERT_ROTATE`'s grace floor. [lastInstall]: what
    // the last install was (its cause, its tag's source), for the debug bridge's `ADVERT` alone; the worker's.
    private var lastInstallAt: Long? = null
    private var lastInstall: AdvertStatus.Install? = null

    // How a stale mark asks the key engine for a pass (`MeshTransport.onPresenceKeyPassRequester`).
    @Volatile private var keyPassRequester: () -> Unit = {}

    // The blinding-only row's scan boost, bounded phone-wide (step 5b's W6), and the boost it bought (elapsed clock).
    private val legacyBoost = LegacyDialerBoost()

    @Volatile private var legacyBoostUntil = Long.MIN_VALUE

    // While blinding, when each unresolved handle was first sighted (capture stamp), and when a chase last ended early on
    // one (step 5b's W6). [chaseLock] makes each change of [chase] one step, from the composite's thread or the worker.
    private val handleFirstSeen = ConcurrentHashMap<String, Long>()

    @Volatile private var chaseEarlyEndAt: Long? = null
    private val chaseLock = Any()

    // The slot turn's wake: a key change, a clock change, a bring-up, a flip. Its wait runs on the wall clock, elapsed-
    // aware (a privacy bound: a tag must not outlive its slot by however long the CPU slept).
    private val rotationWake = Channel<Unit>(Channel.CONFLATED)
    private val wallWait = ElapsedWait(System::currentTimeMillis)

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

    // A dial began ([beginConnect]): it ends a hunting window that is running, so the dial the hunt exists for does not
    // contend with our own scan for the radio (scanning starves connects). Conflated, and drained as each window
    // starts, so a dial that began before the window cannot end it.
    private val dialStarted = Channel<Unit>(Channel.CONFLATED)

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

    // A debug build's scan hold (`…debug.SCAN`, #285): the hunt's gap after every window until it is released, expires or
    // the transport stops. [lastGap] is the gap the loop last took, for the bridge's read.
    private val scanHold = ScanHold()

    @Volatile private var lastGap: ScanPlan.Gap? = null

    // The GATT read budget's last logged edge ([logGattBudget]); written on the scan callback's main thread only.
    private var gattBudgetSpentLogged = false

    // Side channel: which sighted/linked peers advertise the flag, the loop that keeps its scan tier right,
    // and the tier last logged. [sideWake] is its own conflated channel for the same reason [scanWake] is.
    private val sideCapable = SideCapableTracker()
    private val sideWake = Channel<Unit>(Channel.CONFLATED)

    // The last decision and why, as one string: logged on change (a new reason for Off counts), shown on the state line.
    @Volatile private var sideDecision: String = "-"

    private var rotationJob: Job? = null
    private var blindJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var cueJob: Job? = null
    private var awareJob: Job? = null
    private var powerJob: Job? = null
    private var diagJob: Job? = null
    private var audioJob: Job? = null
    private var arbiterJob: Job? = null
    private var visibleJob: Job? = null
    private var sideJob: Job? = null
    private var capJob: Job? = null
    private var phyJob: Job? = null
    private var advertJob: Job? = null
    private var silenceJob: Job? = null

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

            override fun onAdvertRefused() {
                advertWake.trySend(Unit) // [advertLoop] re-reads the slot's retry wait
            }
        }

    // Forwards a live link's decoded records into our flows and its teardown into [teardownLink]. One per link:
    // a link replaced under the same node id still reports its own end, and that must release only its own slot,
    // never the replacement's (ADR 2026-09.shzv).
    private inner class LinkEvents(
        // When this link's success may reset its peer's streak: a dialed proven link waits for the responder's first
        // record (ADR 2026-11.264w); every other link settled at registration.
        val dialStreak: ProvenDialStreak = ProvenDialStreak(pending = false),
        // When our dial for a pending link began, for its failed handshake's `durMs=`.
        private val dialStartedAt: Long = 0L,
        // The dial key our dial was booked under (DialKeys.dialKey), null for a link the peer dialed: a tag dial's
        // backoff is the tag's and address's, beside the proven id's.
        private val dialKey: String? = null,
    ) : LinkCallbacks {
        lateinit var link: FramedLink
        private val down = LinkDownOnce() // both loops report the end; only the first may tear down and bump (#205)

        /** On the reader, at the link's first record: a pending dialed proven link's responder kept our proof. */
        fun onFirstRecord() {
            if (dialStreak.confirm()) resetStreak(link.nodeId, dialKey) // success resets the peer's streak
        }

        override fun onInbound(frame: InboundFrame) {
            // In counts as a crossing too: nothing we later hand this link may be the frame it just gave us.
            crossings.firstCrossing(frame.fromNodeId, FrameKey.of(frame.wire, frame.envelope))
            _inbound.tryEmit(frame)
        }

        override fun onDigest(digest: ReceivedDigest) {
            _incomingDigests.tryEmit(digest)
        }

        override fun onFile(file: ReceivedFile) {
            // Dropped — a full buffer, or no collector yet (a value with no subscriber is discarded): nothing will take it.
            if (_incomingFiles.subscriptionCount.value == 0 || !_incomingFiles.tryEmit(file)) landing.take(file.fromNodeId, file.key)
            wakeSide() // the one stream edge a link reports: rxInProgress just cleared, the side scan may go Off
        }

        override fun onLinkDown(nodeId: String) {
            // Only the first of the two loops' reports tears down (#205). Closed for silence (#203): the end its reader or
            // writer reports is our close, not a flap. Replaced by a fresh link from the same peer: that link holds the
            // slot, and the peer did not flap.
            // Read before the teardown, so a first record racing this end counts once, one way or the other.
            val ending = dialStreak.end(if (link.linkStartedAt > 0) elapsed() - link.linkStartedAt else 0L)
            val flapped =
                down.flapped(
                    teardown = { teardownLink(nodeId, "eof", only = link) },
                    replaced = { links[nodeId] != null },
                    ownClose = { link in closedSilent || link in closedForClaim },
                )
            if (ending == ProvenDialStreak.End.RESET_THEN_FLAP) resetStreak(nodeId, dialKey)
            if (!flapped) return
            if (ending == ProvenDialStreak.End.HANDSHAKE) {
                // Our dialed proven link ended before the responder said a word: it refused our PROOF, or went. A failed
                // dial on the streak the dial found, never a reset plus a flap (ADR 2026-11.264w).
                val cause = IllegalStateException("link ended before the responder's first record: proof unconfirmed")
                return failConnect(dialKey ?: nodeId, ConnectFailReason.HANDSHAKE, dialStartedAt, cause, releaseSlot = false, peer = nodeId)
            }
            // A flapping link escalates on the same per-peer streak as a failed connect, so a peer that keeps
            // dropping isn't reconnected on a tight loop (which would black out scanning each attempt).
            val (streak, nextAt) = synchronized(lock) { bumpBackoffLocked(nodeId) }
            wake() // link count changed → connectLoop retries and scanLoop re-evaluates demand
            Log.i(TAG, "bt link down $nodeId (eof) streak=$streak retryMs=${nextAt - elapsed()}")
        }
    }

    override fun start() {
        landing.clear() // nothing landed before this session is one it will take
        if (!hasHardware) {
            _health.value = TransportHealth.Unavailable
            Log.w(TAG, "Bluetooth LE unsupported on this device; BT plane disabled")
            return
        }
        // R3/R4 (ADR 2026-11.264w): an id that can prove itself now closes the unproven link claiming it. On the
        // notifier's thread, so it only schedules.
        claimsHook = claims.onCapable { id -> scope.launch { closeClaimed(id) } }
        scope.launch {
            localNodeId = identity.nodeId()
            mine.set(AdvertOrder.Mine.clear(localNodeId))
            noLinkSince = elapsed()
            registerAvailability()
            registerAclEdges()
            registerTimeChanges()
            audioMonitor.start()
            codedMode = phyMode.first() // before bringUp, so the first advert and scan already follow it
            blindSwitchOn = blindSwitch.first() // before bringUp, so the first advert is already the switch's form
            withContext(worker) { sessionHadKey = false } // a session's first own key completes its bring-up
            advertKeeper.start(elapsed()) // before bringUp, so a refused first start is kept and retried
            codedKeeper.start(elapsed())
            // Before bringUp, so the first advert already says it; the collector below moves it from here.
            awareAdvertised = !awareWontRun.value
            if (adapter?.isEnabled == true) bringUp() else _health.value = TransportHealth.Unavailable
            scanJob = scope.launch { scanLoop() }
            connectJob = scope.launch { connectLoop() }
            // Re-advertise our epoch once the carried set settles so a peer that now wants our data links to pull it.
            // Debounced via collectLatest: a newer cue cancels the pending delay, so the advert's data is swapped once
            // per burst, not once per store-and-forward frame.
            cueJob =
                scope.launch {
                    storeDigest.version.drop(1).collectLatest {
                        // stretches: a 1.5 s debounce; a late re-advertise only delays a pull cue.
                        delay(CUE_READVERTISE_DEBOUNCE_MS)
                        readvertise()
                    }
                }
            // FLAG_AWARE (GitHub #44): cleared at once, set after a wall-clock minute of "will run"; each move re-advertises in place.
            awareJob = scope.launch { AwareFlag.follow(awareWontRun, { awareAdvertised }, ::setAware, elapsedWait::sleep) }
            powerJob = scope.launch { powerState.state.drop(1).collect { onPowerChange() } }
            diagJob = scope.launch { diagLoop() }
            advertJob = scope.launch { advertLoop() }
            silenceJob = scope.launch { silenceLoop() }
            startWriterTurns()
            // A2DP forces the scan to its floor (audio contends the radio); wake the scan loop on any change so
            // the falling edge (audio stopped) resumes the normal cadence promptly instead of after the floor gap.
            audioJob = scope.launch { audioMonitor.contended.drop(1).collect { onAudioChange() } }
            // The board dial holds an arbiter slot; wake the scan on release so it resumes without waiting out the gap.
            arbiterJob = scope.launch { arbiter.busy.drop(1).collect { wakeScan() } }
            // Knit coming on screen starts a hunt now (CodedPhyPolicy.hunts); leaving it ends one at the next window, 12 s
            // on, so the falling edge wakes nothing. Nothing at all while the experiment is off: release never hunts.
            visibleJob = scope.launch { appVisible.drop(1).filter { it }.collect { if (codedOn()) wakeScan() } }
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
            field.sampler(FIELD_SAMPLER, ::fieldSample)
            if (BuildConfig.DEBUG) bindDebugDiags()
            sideChannel?.let {
                it.bind(sideListener)
                sideJob = scope.launch { sideScanLoop(it) }
            }
        }
    }

    /**
     * The blinded advert writer's two loops (step 5b): the slot turn on the worker, and the runtime switch, each of whose
     * flips is a rotation turn (fresh sets either way, the old form kept as `previous`).
     */
    private fun startWriterTurns() {
        if (!blindAdvert || tagSchedule == null) return // a build that never blinds runs neither
        rotationJob = scope.launch(worker) { rotationLoop() }
        blindJob =
            scope.launch {
                blindSwitch.distinctUntilChanged().collect {
                    blindSwitchOn = it
                    scope.launch(worker) { flipIfWanted() }
                }
            }
    }

    /**
     * A debug build's bridge levers: the drain trial's per-link tuning (#120), the blinded advert's view (step 6) and the
     * scan hold (#285).
     */
    private fun bindDebugDiags() {
        // A hold a bridge call raced past the last stop's unbind must not carry into this session, nor its `on` go unclosed.
        if (scanHold.end()) onScanHoldEnd("stop")
        LinkTuneDiag.tune = ::tuneLinks
        AdvertDiag.status = ::advertStatus
        AdvertDiag.rotate = ::forceRotate
        AdvertDiag.awaitSwitch = ::awaitAdvertSwitch
        ScanDiag.hold = ::scanDiag
    }

    /** [bindDebugDiags] undone at stop (a no-op on a release build, which bound nothing). */
    private fun unbindDebugDiags() {
        LinkTuneDiag.tune = null
        AdvertDiag.status = null
        AdvertDiag.rotate = null
        AdvertDiag.awaitSwitch = null
        ScanDiag.hold = null
    }

    /** The power state moved: the scan's cadence may change, and a dark screen lets a boost go at once. */
    private fun onPowerChange() {
        wake()
        boosts.values.forEach(BleLinkBoost::poke)
    }

    /** Bluetooth audio started or stopped: the scan's floor moves, and audio starting lets a boost go. */
    private fun onAudioChange() {
        wakeScan()
        boosts.values.forEach(BleLinkBoost::poke)
    }

    override fun stop() {
        newcomersOff("stop") // at once: a stopped plane watches for nobody, and a window ending late cannot undo it
        claimsHook?.close()
        claimsHook = null
        scanJob?.cancel()
        connectJob?.cancel()
        cueJob?.cancel()
        awareJob?.cancel()
        powerJob?.cancel()
        diagJob?.cancel()
        audioJob?.cancel()
        arbiterJob?.cancel()
        visibleJob?.cancel()
        capJob?.cancel()
        phyJob?.cancel()
        advertJob?.cancel()
        silenceJob?.cancel()
        listOf(rotationJob, blindJob, paceJob).forEach { it?.cancel() }
        CodedPhyDiag.status = null
        CodedPhyDiag.setTxPower = null
        field.sampler(FIELD_SAMPLER, null)
        unbindDebugDiags()
        endScanHoldAtStop()
        CodedPhyDiag.publishLinkPhys(emptyMap())
        sideJob?.cancel()
        cancelGattRead()
        sideCapable.clear()
        awareCandidates.clear()
        unregisterAvailability()
        unregisterAclEdges()
        unregisterTimeChanges()
        dropBlindSightings()
        audioMonitor.stop()
        tearDownRadio()
        links.keys.toList().forEach { teardownLink(it, "stop") }
        landing.clear() // after the links: a reader still running could land a file the next session never takes
        presence.clear()
        deviceFor.clear()
        gattSighted.clear()
        codedDeviceFor.clear()
        codedCapable.clear()
        codedTallies.clear()
        rangeHunt.clear()
        coldSightings.clear()
        linkStamps.clear()
        hunting = false
        huntWhy = ""
        codedWindows = false
        lastScanCoded = false
        synchronized(lock) {
            inFlight.clear()
            backoffs.clear()
        }
        _neighbors.value = emptySet()
        _reachable.value = emptySet()
        _near.value = emptySet()
        _awareCandidatesNear.value = emptySet()
        _awareCandidatesHeard.value = emptySet()
        disarmAtStop() // after the links' ends: nothing watches an ACL once the transport has stopped
    }

    /**
     * Hands each watched ACL still up whose arm never attached one more arm, and records it (#142): with the edge
     * receiver gone and the tick cancelled, nothing would release it, and a Pause would keep it for its length. Its
     * `isLive` is true — the edges stopped with the receiver, and these channels closed a moment ago. The arm runs on the
     * app's scope, which a stop does not cancel; its `finally` closes the client whatever ends it.
     */
    private fun disarmAtStop() {
        orphans.stop(elapsed()).forEach { u ->
            Log.i(TAG, "bt acl orphan ${u.nodeId} ${u.address} unarmed at stop (ended ${u.endedAgoMs}ms ago)")
            armAcl(u.device, u.nodeId, isLive = { true }, AclArm.STOP)
            field.record(FieldEvent.BtOrphan(u.nodeId, u.linked, armed = false, tries = 0, cleared = false, ageMs = u.endedAgoMs))
        }
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
        val initiable = if (::localNodeId.isInitialized) DialPlan.initiable(localNodeId, mine(), peers) else emptySet()
        if (initiable == foreignReachable) return
        val rising = initiable.any { it !in foreignReachable }
        foreignReachable = initiable
        synchronized(chaseLock) { chase = ScanDemandPolicy.onForeign(chase, initiable, elapsed(), PROMOTE_CHASE_MS) }
        if (rising) wakeScan() // a new foreign peer appeared → re-evaluate demand now (don't wait out the floor)
    }

    /**
     * The direct contacts' presence keys (ADR 2026-11.xp6d's key feed): node id → up to three keys, newest first, the
     * whole listing. On the worker, through [keyLimiter]'s churn bound, into [aliases]; a held change applies when its
     * window frees.
     */
    override fun onPresenceKeys(keys: Map<String, List<ByteArray>>) {
        scope.launch(worker) {
            val held = keyLimiter.held
            val coalesced = keyLimiter.coalesced
            applyKeys(keyLimiter.offer(keys, elapsed()))
            countChurn(held, coalesced)
        }
    }

    /**
     * This phone's own presence key (ADR 2026-11.xp6d, step 5b's W2), the engine's published value. Held at once, so a
     * bring-up's rotation reads the newest; then, on the worker, every reservation is dropped and a change of the
     * generation on the air restarts the advert at once ([onOwnKeyChanged]). Never logged beyond its generation.
     */
    override fun onOwnPresenceKey(key: OwnPresenceKey?) {
        ownKey = key
        scope.launch(worker) { onOwnKeyChanged(key) }
    }

    /** Who may be sent `TAG_NEXT` (step 5b's W5): read at each send, never cached at link-up. */
    override fun onTagNextRecipients(hears: (nodeId: String) -> Boolean) {
        tagNextHears = hears
    }

    /** How a stale mark asks the key engine for a pass ([requestKeyPass]). */
    override fun onPresenceKeyPassRequester(request: () -> Unit) {
        keyPassRequester = request
    }

    /** The engine's own value, read just before a tag airs ([TagChoice]): a fresh pair inside the feed's hop airs nothing. */
    override fun onOwnPresenceKeySource(read: () -> OwnPresenceKey?) {
        ownKeySource = read
    }

    /** Hands [listing] to the resolver (null: nothing changed), then schedules the next held listing. On the worker. */
    private fun applyKeys(listing: Map<String, List<ByteArray>>?) {
        if (listing != null) aliases.setKeys(listing.mapValues { AdvertAliases.ContactKey(it.value) })
        val due = keyLimiter.nextDueAt() ?: return
        if (keyRelease?.isActive == true && keyReleaseAt <= due) return
        keyRelease?.cancel()
        keyReleaseAt = due
        keyRelease =
            scope.launch(worker) {
                // stretches: a contact's held key listing (its churn bound) applies late while the CPU sleeps; no relink waits on it.
                delay((due - elapsed()).coerceAtLeast(0L))
                keyRelease = null
                keyReleaseAt = Long.MAX_VALUE
                applyKeys(keyLimiter.release(elapsed()))
            }
    }

    /** The key listings [keyLimiter] held or coalesced since [held] and [coalesced] were read. */
    private fun countChurn(
        held: Long,
        coalesced: Long,
    ) {
        repeat((keyLimiter.held - held).toInt()) { metrics.onBleBlindDropped(BleBlindDrop.KEY_LISTING_HELD) }
        repeat((keyLimiter.coalesced - coalesced).toInt()) { metrics.onBleBlindDropped(BleBlindDrop.KEY_LISTING_COALESCED) }
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
        carryProbe(link, "frame")
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
        val link = links[to.nodeId] ?: return false
        val accepted = link.sendFile(file, meta)
        if (accepted) {
            metrics.onFileSent(TransportKind.Bluetooth)
            carryProbe(link, "file")
        }
        return accepted
    }

    // Streaming, then landing: a file ends by joining the second before it leaves the first, so this order never
    // reads it in neither (#138).
    override fun arrivingFiles(): Map<String, ArrivingFile> = (links.values.mapNotNull { it.rxFile } + landing.all).furthestByKey()

    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) = landing.take(fromNodeId, key)

    override fun fileArrivingFrom(nodeId: String): Boolean = links[nodeId]?.rxInProgress ?: false

    override fun fileArrivingEtaMs(nodeId: String): Long? = links[nodeId]?.rxEtaMs()

    override fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.cancelQueued(key) ?: false

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.hasPendingFile(key) ?: false

    // A Coded link's feed keeps its own pace outside the split ([paceFor]), so only a 1M/2M one counts. Its ETA comes
    // from the receiver's progress reports once one has come (ADR 2026-10.nsr7). Before that, a feed whose link may
    // still earn the boost is read at the boosted share until its second chunk is paced, or while it is still earning,
    // up to FramedLink's bound: its first chunk is paced at the base before the link reports its interval (~0.6 s on
    // 2026-10-06, 10 s to a dark receiver on 2026-10-07), and a second ask lands within ms of the first, since both
    // receivers act on the author's HAVE.
    override fun fileFeed(
        nodeId: String,
        key: String,
    ): FileFeed? {
        val link = links[nodeId]?.takeIf { linkPhy[it] != LinkPhy.CODED } ?: return null
        // A windowed feed runs at the drain its reports measure, not at a pace a boost may raise: no settle to read.
        if (windowed(link)) return link.feed(key)
        val settling = boostedShare(link, joining = false, settling = true)
        return link.feed(key, settlingPace = settling, earning = settling > 0 && !earned(link))
    }

    /**
     * How fast a file to [nodeId] would go now (ADR 2026-10.nsr7): the share its pace would give it beside the feeds
     * already going — boosted when the link will earn the boost ([willEarn]) — times the share of its pace the link's
     * last measured stream drained ([FramedLink.drainRatio]). Coded keeps its own pace. 0 with no link.
     */
    override fun expectedFileRate(nodeId: String): Int {
        val link = links[nodeId] ?: return 0
        val phy = linkPhy[link] ?: LinkPhy.UNKNOWN
        // A windowed feed takes what the air gives it, so the last stream's drain is the best guess, shared only with the
        // other windowed feeds going now on this phone's one controller; the last stream measured whatever sharing it had.
        return BleFeedPolicy.expectedRate(
            codedPace = if (phy == LinkPhy.CODED) CodedPhyPolicy.pace(phy, CodedPhyDiag.tuning).bytesPerSec else null,
            drained = link.lastDrain,
            windowed = windowed(link),
            otherWindowedFeeds = { feedingLinks().count { it !== link && it.txWindowed } },
            drainRatio = link.drainRatio,
            boosted = { boostedShare(link, joining = !link.txInProgress) },
        ) {
            val feeding = pacedFeedingLinks().size + if (link.txInProgress) 0 else 1
            CodedPhyPolicy.pace(phy, CodedPhyDiag.tuning, feeding, earned = false, leLinks = links.size).bytesPerSec
        }
    }

    /**
     * Whether a feed on [link] will earn the boost (ADR 2026-10.2da4, 2026-10.nsr7): it can (an Android peer, the flag
     * and the trial's switch on, a tuner not given up, no audio), and someone will ask for the short interval — it has
     * earned now, or within [EARNED_RECENTLY_MS] (its peer asks: a lit receiver), or this side is lit and asks itself.
     * A dark pair never asks, and scoring one at the boosted share sent it beside an iPhone's feed (the reviewer's
     * finding on the first draft).
     */
    private fun willEarn(link: FramedLink): Boolean =
        BleFeedPolicy.willEarn(
            canEarn(link),
            earned(link),
            earnedRecently = { boosts[link]?.earnedWithin(EARNED_RECENTLY_MS) == true },
            interactive = powerState.state.value.interactive,
        )

    // Whether a feed on [link] can earn the boost at all: an Android peer, the flag and the trial's switch on, a tuner not
    // given up, no audio. Whether anyone will ask for the interval is [willEarn]'s.
    private fun canEarn(link: FramedLink): Boolean =
        BleFeedPolicy.canEarn(
            buildFlag = BuildConfig.BLE_LINK_BOOST,
            capabilities = link.peer.capabilities,
            boostOn = LinkTuneDiag.boostOn,
            tunerAlive = tuners[link]?.alive != false,
            audioContended = audioMonitor.contended.value,
        )

    override fun onFileProgress(
        nodeId: String,
        key: String,
        held: Long,
        ms: Long,
    ) {
        links[nodeId]?.onProgress(key, held, ms)
    }

    // Every link's receive-side reports (FramedLink's onRxProgress), for BlobExchange to send back. Lossy past a few: a
    // dropped one is the next one's sample, one report interval later.
    private val rxProgress =
        MutableSharedFlow<FileProgress>(extraBufferCapacity = PROGRESS_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val fileProgress: Flow<FileProgress> = rxProgress.asSharedFlow()

    /**
     * The share [link] feeds at once it has earned the boost, while it will earn it ([willEarn]): else 0. [joining]
     * counts a file it is not feeding yet into the split. Read as a feed's [settling] pace earned or not yet, and
     * whenever it can earn ([canEarn]), because a chunk's pace moves only at the next chunk, and the boost earns inside
     * the first: a dark holder's link earns when its lit receiver asks, which a link idle for [EARNED_RECENTLY_MS] has
     * not shown yet (the bluetooth-reviewer's findings: a park poll in that gap read the base and unparked). A link
     * that has not earned it by its second chunk is read at its own pace from then.
     */
    private fun boostedShare(
        link: FramedLink,
        joining: Boolean,
        settling: Boolean = false,
    ): Int {
        if (!(if (settling) canEarn(link) else willEarn(link))) return 0
        val feeding = (pacedFeedingLinks().size + if (joining) 1 else 0).coerceAtLeast(1)
        val phy = linkPhy[link] ?: LinkPhy.UNKNOWN
        return CodedPhyPolicy.pace(phy, CodedPhyDiag.tuning, feeding, earned = true, leLinks = leLinkCount()).bytesPerSec
    }

    // Every link's file-feed changes — an end, a settle or a pace move, a first measurement — poked by its FramedLink
    // (onFeedChanged). Lossy past one: a reader re-reads the
    // links, so one pending poke says everything a second would.
    private val feedChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val fileFeedsChanged: Flow<Unit> = feedChanges.asSharedFlow()

    // [paceFor]'s divisor: the links splitting the 1M/2M budget right now. A Coded feed keeps its own pace outside it.
    override fun feedingLoad(nodeId: String): Int = links.values.count { it.txInProgress && linkPhy[it] != LinkPhy.CODED }

    override fun slowLinkTo(nodeId: String): Boolean = links[nodeId]?.let { linkPhy[it] == LinkPhy.CODED } ?: false

    /**
     * Whether the link to [nodeId] is near its floor, by its PHY handle's link-RSSI reads ([LinkFade], ADR 2026-10.79yb's
     * ninth amendment). Asked per walkie chunk on the send path, so it only reads: two short monitors, no I/O. A link
     * with no PHY handle (the experiment off, or a peer never heard on Coded, an iPhone among them) cannot tell: false.
     */
    override fun liveLinkFading(nodeId: String): Boolean = links[nodeId]?.let { phyControls[it]?.fading() } ?: false

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        val link = links[to.nodeId] ?: return
        link.sendDigest(ids)
        carryProbe(link, "digest") // the 60 s re-offer: every link's carrier (#203)
    }

    // --- Radio bring-up / teardown ---

    /**
     * The radio came up (transport start, adapter on). Blinding ([blinding]), the bring-up **is** a rotation (step 5b's
     * W3): the writer holds every push from now until the worker has chosen the tag, opened a fresh server behind its
     * throwaways and installed fresh presence, Coded and side sets with it ([rotate]), so no set starts clear and then
     * switches in place. Otherwise the clear advert goes up now, as it always has.
     */
    private fun bringUp() {
        sideChannel?.bringUp() // probes the controller first, so the advert below already carries its flag
        probeCoded()
        if (blinding()) {
            val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
            scope.launch(worker) { runRotation(AdvertRotation.Cause.BRINGUP, announced = false, token = token) }
        } else {
            upClear()
        }
        _health.value = TransportHealth.Healthy
        wakeSide()
    }

    /**
     * The clear advert's bring-up: a fresh server (no throwaways: a clear advert names the id anyway) and the clear
     * snapshot, its instances reused when they last carried the clear form, fresh when they carried a tag (no set ever
     * carries two forms).
     */
    private fun upClear() {
        closeServers()
        val server = synchronized(servers) { servers.rotate(elapsed(), throwaways = 0) } ?: return
        startAccepting(server)
        Log.i(TAG, "L2CAP responder listening on psm ${server.psm}")
        val reuse = writer.form()?.blinded == false
        val presence = writer.presence()?.takeIf { reuse } ?: newPresenceAdvertiser()
        val coded = writer.coded()?.takeIf { reuse } ?: newCodedAdvertiser().also { it.setInterval(codedInterval()) }
        val clear = AdvertOrder.Form.clear(localNodeId)
        var stamped = 0L
        val aired = AdvertWriter.Aired(presence, coded, clear, server.psm)
        // `mine` is set under the writer's lock with the stamp, as `rotate` does, so two installs set it in the order they
        // stamped, whichever threads they ran on (bluetooth-reviewer, 2026-11-04).
        writer.up(pendingTag = false, clear = aired, ::encode, ::write, onStamped = {
            stamped = it
            mine.set(AdvertOrder.Mine.clear(localNodeId))
        })
        // On the worker, after this (main-thread) install: a worker rotation may have installed and recorded a tag in
        // between, and this late task must not stop its turn, clear its own tags or overwrite its facts.
        scope.launch(worker) {
            if (stamped == 0L || !AdvertStatus.Install.mayRecord(lastInstall, stamped)) return@launch
            aliases.setOwnTags(null, null)
            rotation.onStopped()
            airedGeneration = null
            lastInstall = AdvertStatus.Install(stamped, AdvertRotation.Cause.BRINGUP, null, null, null, null)
        }
    }

    /** A new L2CAP responder server, or null when the stack would not open one. */
    private fun listenL2cap(): BluetoothServerSocket? {
        val a = adapter ?: return null
        return runCatching { a.listenUsingInsecureL2capChannel() }
            .onFailure { Log.w(TAG, "L2CAP listen failed", it) }
            .getOrNull()
    }

    /** Closes [ss] (a throwaway, a server whose grace ended, every server at teardown) and ends its accept loop. */
    private fun closeServerSocket(ss: BluetoothServerSocket) {
        acceptJobs.remove(ss)?.cancel()
        runCatching { ss.close() } // what ends a blocked accept(): a cancel does not
    }

    private fun startAccepting(ss: BluetoothServerSocket) {
        acceptJobs[ss] = scope.launch(Dispatchers.IO) { acceptLoop(ss) }
    }

    /** Every server, current and in its grace, closed. */
    private fun closeServers() {
        synchronized(servers) { servers.closeAll() }.forEach(::closeServerSocket)
    }

    /**
     * Closes the retired servers whose grace ended (step 5b's W4): one dial timeout after the rotation that replaced each,
     * on the elapsed clock so a sleeping phone does not hold a server for however long it slept.
     */
    private fun closeRetiredServersAfterGrace() {
        scope.launch {
            elapsedWait.sleep(PsmRotation.GRACE_MS + SERVER_GRACE_SLACK_MS)
            synchronized(servers) { servers.due(elapsed()) }.forEach(::closeServerSocket)
        }
    }

    private fun tearDownRadio() {
        writer.down() // stops the presence and Coded sets, and makes any rotation begun before now stale
        presenceAdvert = "off"
        if (!codedAdvert.startsWith("dark")) codedAdvert = "off"
        // The next bring-up starts the Coded set slow; a link that drops after it makes it fast again.
        codedFast = false
        writer.coded()?.setInterval(BleAdvertiser.CODED_INTERVAL)
        scanner.stop()
        sideChannel?.tearDown()
        closeServers()
        // At adapter OFF (and before a bring-up): no reservation outlives the radio, and the slot turn has nothing on air.
        scope.launch(worker) {
            tagSchedule?.dropReservations()
            rotation.onStopped()
            announcedTag = null
            tagNextSends.clear()
            linkFormWaits.clear()
        }
    }

    /**
     * Pushes the advert again (a cue, a flag, the Coded set's state moved): through [writer], built from the form on the
     * air and the PSM it names, never from the node id while blinding; nothing while a rotation's tag is pending.
     */
    private fun readvertise() {
        if (adapter?.isEnabled != true || !::localNodeId.isInitialized) return
        writer.push(::encode, ::write)
    }

    /**
     * The presence payload for [form] at [psm]. FLAG_DIALS_GATT_PEERS rides the same gate as the reader, so it never
     * claims a read this phone cannot make: a lower-id iPhone waits to be dialed only by a phone that can find it
     * (companion change A3). A blinded form sets FLAG_BLINDED and zeroes the digest cue. Under [writer]'s lock.
     */
    private fun encode(
        form: AdvertOrder.Form,
        psm: Int,
    ): ByteArray =
        BleAdvertPayload.advert(
            form.advertised,
            Protocol.LOCAL_CAPABILITIES,
            storeDigest.current(),
            psm,
            sideLive = sideChannel?.live == true,
            gattPeers = gattPeers,
            aware = awareAdvertised,
            blinded = form.blinded,
        )

    /** Writes [bytes] to [aired]'s presence set and, while the experiment runs, the same bytes to its Coded set. */
    private fun write(
        aired: AdvertWriter.Aired<BleAdvertiser>,
        bytes: ByteArray,
    ) {
        aired.presence.update(bytes)
        // The same bytes on the Coded set, so its PSM and cue can never lag the presence advert's.
        if (codedOn() && !codedAdvert.startsWith("dark")) {
            if (codedAdvert == "off") codedAdvert = "starting"
            aired.coded?.update(bytes)
        } else if (!codedOn()) {
            stopCodedAdvert()
        }
    }

    // --- The blinded advert's writer (ADR 2026-11.xp6d, step 5b) ---

    /**
     * Whether the advert should name a tag now: the build writes tags, the runtime switch is on, the tag schedule has its
     * store, and the controller has extended advertising (read with the adapter on, [probeCoded]).
     */
    private fun blinding(): Boolean = blindAdvert && tagSchedule != null && blindSwitchOn && extAdvSupported == true

    /** A presence set; its callbacks act only while it is [writer]'s current one (a late start of a retired one never). */
    private fun newPresenceAdvertiser(): BleAdvertiser {
        lateinit var set: BleAdvertiser
        set =
            BleAdvertiser(
                { adapter?.bluetoothLeAdvertiser },
                log = { Log.d(TAG, it) },
                onStartStatus = { if (writer.isCurrent(set)) onPresenceEnabled(true, it) },
                onEnableStatus = { enabled, status -> if (writer.isCurrent(set)) onPresenceEnabled(enabled, status) },
            )
        return set
    }

    /**
     * One rotation of the presence advert, on the worker, never held once called (step 5b's W2–W4): the tag ([TagChoice]:
     * the reserved one, the slot's next, or a random one, never the id), a fresh server behind its throwaways, fresh
     * presence and Coded sets installed through [writer] (the old ones retired first), the side channel's fresh slots, and
     * my forms swapped as one snapshot. A switch turned off makes the clear form the new one, the same way. [token] is a
     * bring-up's; other causes begin their own. Returns whether it installed; one that did not hands its token back. A
     * throw costs this rotation alone (the slot turn's round, or [runRotation]).
     */
    private fun rotate(
        cause: AdvertRotation.Cause,
        announced: Boolean,
        token: Int = writer.begin(),
    ): Boolean {
        var installed = false
        try {
            if (!::localNodeId.isInitialized) return false
            val wall = System.currentTimeMillis()
            val old = writer.form()
            val choice = chooseTag(announced, wall)
            val form = choice?.form ?: AdvertOrder.Form.clear(localNodeId)
            var stamped = 0L
            val previous = old?.takeIf { it != form }
            // `mine` is set under the writer's lock with the stamp, as `upClear` does, so two installs set it in the order
            // they stamped, whichever threads they ran on (bluetooth-reviewer, 2026-11-04).
            installed =
                installFresh(token, form) {
                    stamped = it
                    mine.set(AdvertOrder.Mine(form, previous))
                }
            if (!installed) {
                // The radio went down first, or no server would open: nothing new airs, and the writer goes back to the
                // snapshot on the air (none after a teardown: a bring-up's then stays dark until the next one).
                metrics.onBleAdvertWrite(BleAdvertWrite.INSTALL_STALE)
                return false
            }
            afterInstall(cause, old, previous, form, choice, wall, stamped)
            return true
        } finally {
            // Never a pending writer left behind: a rotation that did not install (or threw before it) hands pushes back.
            if (!installed) writer.abandon(token)
        }
    }

    /** What follows a rotation's install, on the worker: my forms, the resolver's own tags, the side slots, the counts. */
    @Suppress("LongParameterList") // the rotation's facts
    private fun afterInstall(
        cause: AdvertRotation.Cause,
        old: AdvertOrder.Form?,
        previous: AdvertOrder.Form?,
        form: AdvertOrder.Form,
        choice: TagChoice.Choice?,
        wall: Long,
        stamped: Long,
    ) {
        aliases.setOwnTags(form.takeIf { it.blinded }?.advertised, previous?.takeIf { it.blinded }?.advertised)
        if (form.blinded || old?.blinded == true) sideChannel?.rotate()
        airedGeneration = choice?.generation
        lastInstallAt = elapsed()
        lastInstall = AdvertStatus.Install(stamped, cause, choice?.generation, choice?.source, choice?.resolvable, choice?.stale)
        if (form.blinded) rotation.onRotated(wall) else rotation.onStopped()
        announcedTag = null // aired now, or passed by
        metrics.onBleRotation(BleRotation.valueOf(cause.name))
        if (choice?.source != TagChoice.Source.ANNOUNCED) {
            metrics.onBleAdvertWrite(BleAdvertWrite.UNANNOUNCED)
            if (links.isNotEmpty()) sendCurrentFormOnce()
        }
        // A switch flip that came while this rotation's tag was pending was refused then: it runs now.
        if (blinding() != form.blinded) scope.launch(worker) { flipIfWanted() }
        rotationWake.trySend(Unit)
    }

    /** The tag a rotation airs while blinding ([TagChoice]), counted; null when the advert should be clear. */
    private fun chooseTag(
        announced: Boolean,
        wall: Long,
    ): TagChoice.Choice? {
        val schedule = tagSchedule ?: return null
        if (!blinding()) return null
        return TagChoice.choose(schedule, ownKey, ownKeySource(), announced, wall).also(::countChoice)
    }

    /**
     * Installs fresh presence and Coded sets carrying [form] through [writer], with a fresh server behind its throwaways
     * opened first (under the writer's lock, so a teardown that comes first leaves nothing open, and one that comes after
     * closes it). False when the radio went down first or no server is open at all.
     */
    private fun installFresh(
        token: Int,
        form: AdvertOrder.Form,
        onStamped: (Long) -> Unit,
    ): Boolean {
        var fresh: BluetoothServerSocket? = null
        val installed =
            writer.install(token, prepare = {
                // The new server opens before the old sets stop, and accepts from now; the old one keeps its grace.
                fresh = synchronized(servers) { servers.rotate(elapsed(), psmDraw.nextInt(PsmRotation.MAX_THROWAWAYS + 1)) }
                fresh?.let(::startAccepting) ?: metrics.onBleAdvertWrite(BleAdvertWrite.PSM_KEPT)
                val server = fresh ?: synchronized(servers) { servers.current }
                // Before the start: a Coded set comes up on the interval a fast window wants, at the power asked for.
                if (!codedAdvert.startsWith("dark")) codedAdvert = "off"
                val coded = newCodedAdvertiser().also { it.setInterval(codedInterval()) }
                server?.let { AdvertWriter.Aired(newPresenceAdvertiser(), coded, form, it.psm) }
            }, ::encode, ::write, onStamped)
        if (fresh != null) closeRetiredServersAfterGrace()
        return installed
    }

    /** Counts what [choice] aired, and asks the key engine for one pass per generation on a stale mark. */
    private fun countChoice(choice: TagChoice.Choice) {
        val event =
            when (choice.source) {
                TagChoice.Source.ANNOUNCED -> BleAdvertWrite.ANNOUNCED
                TagChoice.Source.NO_KEY -> BleAdvertWrite.RANDOM_NO_KEY
                TagChoice.Source.KEY_MOVED -> BleAdvertWrite.RANDOM_KEY_MOVED
                TagChoice.Source.MARK_FAILED -> BleAdvertWrite.RANDOM_MARK_FAILED
                TagChoice.Source.SLOTTED -> if (choice.resolvable) null else BleAdvertWrite.RANDOM_SCHEDULED
            }
        event?.let(metrics::onBleAdvertWrite)
        if (choice.source == TagChoice.Source.SLOTTED || choice.source == TagChoice.Source.ANNOUNCED) {
            metrics.onBleTagScheduled(unresolvable = !choice.resolvable, stale = choice.stale)
        }
        // `Next.stale` and `resolvable` reach nothing aired or synced: counts only, here and in STATE.
        val generation = choice.generation ?: return
        if (staleLatch.offer(generation, choice.stale)) {
            metrics.onBleAdvertWrite(BleAdvertWrite.STALE_MARK)
            requestKeyPass()
        }
    }

    /**
     * After a rotation nobody was told of (a late wake, a key change, a flip), the form now on the air once to each
     * eligible link ([sendForm]), so linked strangers do not dial the new tag. On the worker. Nothing for a clear form.
     */
    private fun sendCurrentFormOnce() {
        val form = writer.form()?.takeIf { it.blinded } ?: return
        val tag = NodeId.toBytes(form.advertised)
        links.values.toList().forEach { sendForm(it, TagNextSends.Kind.CURRENT, tag) }
    }

    /**
     * The lead announcement (step 5b's W5): [next]'s tag in `TAG_NEXT` to each eligible link, as a link record outside
     * `DoorbellPolicy.rings` (a rotation never rings a suspended iPhone). It stands until the rotation airs it, for a link
     * that comes up meanwhile ([announcedTag]). On the worker.
     */
    private fun announceTag(next: TagSchedule.Next) {
        val tag = next.tag.copyOf()
        announcedTag = tag
        links.values.toList().forEach { sendForm(it, TagNextSends.Kind.ANNOUNCE, tag) }
    }

    /**
     * One `TAG_NEXT` of [kind] naming [tag] to [link], if [TagNextSends] lets it go: a proven tag-aware link, the session's
     * predicate true for its peer now, an own key held, the receiver's per-link limit kept. Counted either way; the line
     * names the recipient, never the tag. On the worker.
     */
    private fun sendForm(
        link: FramedLink,
        kind: TagNextSends.Kind,
        tag: ByteArray,
    ) {
        if (links[link.nodeId] !== link) return
        val hears = runCatching { tagNextHears(link.nodeId) }.getOrDefault(false)
        val eligible = TagNextSends.eligible(link.proven, link.peer.capabilities and Protocol.CAP_BLIND_ADVERT != 0L, hears)
        val outcome =
            tagNextSends.decide(
                link,
                kind,
                eligible,
                ownKey != null,
                System.currentTimeMillis(),
                rotation.announceDueAt(),
                announcing = announcedTag != null,
            )
        metrics.onBleAdvertWrite(
            when (outcome) {
                TagNextSends.Outcome.NO_KEY -> {
                    BleAdvertWrite.TAG_NEXT_NO_KEY
                }

                TagNextSends.Outcome.INELIGIBLE -> {
                    BleAdvertWrite.TAG_NEXT_INELIGIBLE
                }

                TagNextSends.Outcome.THROTTLED -> {
                    BleAdvertWrite.TAG_NEXT_THROTTLED
                }

                TagNextSends.Outcome.BEFORE_ANNOUNCE -> {
                    BleAdvertWrite.TAG_NEXT_BEFORE_ANNOUNCE
                }

                TagNextSends.Outcome.SENT -> {
                    when (kind) {
                        TagNextSends.Kind.ANNOUNCE -> BleAdvertWrite.TAG_NEXT_ANNOUNCED
                        TagNextSends.Kind.CURRENT -> BleAdvertWrite.TAG_NEXT_CURRENT
                        TagNextSends.Kind.RESEND -> BleAdvertWrite.TAG_NEXT_RESENT
                    }
                }
            },
        )
        if (outcome != TagNextSends.Outcome.SENT) return
        if (link.sendTagNext(tag)) Log.i(TAG, "bt tag next → ${link.nodeId}")
    }

    /**
     * A stale advertiser mark asks the key engine for a pass (a MARK advance), once per generation ([staleLatch]), through
     * the session's requester (`MeshTransport.onPresenceKeyPassRequester`), which launches it on the session: never on
     * the worker, never under the engine's lock from here, never a mint of ours.
     */
    private fun requestKeyPass() {
        runCatching { keyPassRequester() }
    }

    /** The Coded set's interval now: fast while a fast window runs ([codedFast]), else the resting one. */
    private fun codedInterval(): Int =
        if (codedFast) CodedPhyPolicy.advertIntervalUnits(CodedPhyDiag.tuning.fastAdvertMs) else BleAdvertiser.CODED_INTERVAL

    /** The slot turn's announcement, on the worker: reserve the coming slot's tag under the key held. */
    private fun announce() {
        val wall = System.currentTimeMillis()
        val key = ownKey
        val schedule = tagSchedule
        // Nothing to reserve with no key: the rotation then airs a random tag, unannounced. The turn marks it announced.
        if (key == null || schedule == null) return
        val next = runCatching { schedule.reserve(key.key, key.generation, wall) }.getOrNull()
        if (next != null && next.resolvable) announceTag(next)
    }

    /** Whether a slot rotation due now waits (bounded from its due time): a fast Coded window, a hunt, a dial of mine. */
    private fun rotationHeld(): Boolean = codedFast || hunting || synchronized(lock) { inFlight.isNotEmpty() }

    /** How long the last ACL edge still settles (9utz's 2.5 s), on the elapsed clock. */
    private fun settleLeftMs(): Long = (lastAclEdgeAt + AdvertReassertPolicy.SETTLE_MS - elapsed()).coerceAtLeast(0L)

    /**
     * The slot turn (step 5b's W4), on the worker: [AdvertRotation] says when and [RotationTurn] waits (on the wall clock
     * through an elapsed-aware wait, a privacy bound; with nothing on the air, for a wake alone), woken by a key change, a
     * clock change, a bring-up or a flip. Every pass suspends, and a rotation that does not install never repeats at once.
     */
    private suspend fun rotationLoop() {
        RotationTurn(
            rotation,
            rotationWake,
            wallWait,
            wallMs = System::currentTimeMillis,
            holding = ::rotationHeld,
            settleLeftMs = ::settleLeftMs,
            holdMs = { CodedPhyDiag.tuning.fastHoldMs },
            radioUp = writer::isUp,
            announce = ::announce,
            rotate = { step ->
                if (step.held) metrics.onBleAdvertWrite(BleAdvertWrite.HELD)
                if (step.bounded) metrics.onBleAdvertWrite(BleAdvertWrite.HOLD_BOUNDED)
                rotate(AdvertRotation.Cause.SLOT, step.announced)
            },
            onFailure = { Log.w(TAG, "bt advert rotation failed: ${it.javaClass.simpleName}") },
        ).run()
    }

    /**
     * The own key changed (a [onOwnPresenceKey] call), on the worker: every reservation goes, and a change of the
     * generation on the air restarts the advert at once, never held ([AdvertRotation.causeOfKeyChange]).
     */
    private fun onOwnKeyChanged(key: OwnPresenceKey?) {
        val held = workerKey
        workerKey = key
        if (key == held) return
        tagSchedule?.dropReservations()
        rotation.dropAnnouncement()
        announcedTag = null
        val cause = AdvertRotation.causeOfKeyChange(held, key, airedGeneration, sessionHadKey)
        if (key != null) sessionHadKey = true
        if (cause != null && airsTagNow()) rotate(cause, announced = false)
    }

    /** Whether the radio is up with a blinded form on the air and no rotation's tag pending (one would read the new key). */
    private fun airsTagNow(): Boolean = writer.isUp() && !writer.pending() && writer.form()?.blinded == true

    /**
     * The runtime switch moved [blinding]: a rotation to the wanted form, fresh sets either way. Not a key change, so it
     * stays out of an ACL edge's settle (9utz) and comes back once it ends.
     */
    private fun flipIfWanted() {
        if (!writer.isUp() || writer.pending() || !::localNodeId.isInitialized) return
        val on = writer.form()?.blinded == true
        if (blinding() == on) return
        val settle = settleLeftMs()
        if (settle > 0) {
            scope.launch(worker) {
                elapsedWait.sleep(settle)
                flipIfWanted()
            }
            return
        }
        rotate(AdvertRotation.Cause.FLIP, announced = false)
    }

    // --- The debug bridge's view of the writer (step 6; bound in a debug build only, AdvertDiag) ---

    /**
     * `…debug.ADVERT`'s status, read in one task on the worker (the turn's fields, the generations, the last install and
     * [aliases], confined to it, are the worker's; the writer's snapshot is one reference). Never a key or a tag
     * ([AdvertStatus]).
     */
    private suspend fun advertStatus(): AdvertStatus =
        withContext(worker) {
            val linked = links.values.toList()
            AdvertStatus.of(
                aired = writer.aired(),
                radioUp = writer.isUp(),
                pending = writer.pending(),
                blinding = blinding(),
                extendedAdvertising = extAdvSupported,
                keyHeld = ownKey != null,
                keyGeneration = ownKey?.generation,
                install = lastInstall,
                turn = rotation.view(),
                mine = mine().current,
                aliases = aliases.counters(),
                formHandles = formKeys.size,
                sightings = presence.snapshots(elapsed()),
                links =
                    linked.map {
                        AdvertStatus.Link(
                            nodeId = it.nodeId,
                            proven = it.proven,
                            dialedByMe = it in dialedByMe,
                            tagAware = it.peer.capabilities and Protocol.CAP_BLIND_ADVERT != 0L,
                            form = linkForms[it],
                        )
                    },
            )
        }

    /**
     * `…debug.ADVERT_ROTATE`: one unannounced rotation now through [rotate] ([AdvertRotation.Cause.FORCED]), on the worker,
     * refused in the same task by [ForcedRotation.refusal] when an ordinary rotation would not run now or would cost the
     * plane a server, a reservation or a settle. A refused call rotates nothing.
     */
    private suspend fun forceRotate(): ForcedRotation.Result =
        withContext(worker) {
            val before = writer.aired()?.installs ?: 0L
            val refused =
                ForcedRotation.refusal(
                    radioUp = writer.isUp(),
                    pending = writer.pending(),
                    blinding = blinding(),
                    airedBlinded = writer.aired()?.form?.blinded == true,
                    settleLeftMs = settleLeftMs(),
                    announced = rotation.view().announced || announcedTag != null,
                    holding = rotationHeld(),
                    retiredServerOpen = synchronized(servers) { servers.nextDueAt() } != null,
                    sinceInstallMs = lastInstallAt?.let { elapsed() - it },
                    formSentRecently = tagNextSends.sentWithin(System.currentTimeMillis()),
                )
            val rotated = refused == null && ::localNodeId.isInitialized && rotate(AdvertRotation.Cause.FORCED, announced = false)
            ForcedRotation.Result(rotated, refused, before, writer.aired()?.installs ?: 0L)
        }

    /**
     * `…debug.ADVERT --ez blind|clear`: after the bridge stored the switch as [want], waits until this transport has seen it
     * and the form on the air matches [blinding] ([AdvertSwitch.verdict]), at most [ADVERT_SWITCH_WAIT_MS] on the elapsed
     * clock: a flip waits out an ACL edge's 2.5 s settle, then a rotation's install. Polls; a debug bridge's wait only.
     */
    private suspend fun awaitAdvertSwitch(want: Boolean): AdvertSwitch.Result {
        val poll = ElapsedWait(::elapsed, ADVERT_SWITCH_POLL_MS)
        val deadline = elapsed() + ADVERT_SWITCH_WAIT_MS
        while (true) {
            val timedOut = elapsed() >= deadline
            val verdict =
                withContext(worker) {
                    AdvertSwitch.verdict(
                        markStore = tagSchedule != null,
                        want = want,
                        seen = blindSwitchOn == want,
                        radioUp = writer.isUp(),
                        pending = writer.pending(),
                        airedBlinded = writer.aired()?.form?.blinded,
                        blinding = blinding(),
                        extendedAdvertising = extAdvSupported,
                        timedOut = timedOut,
                    )
                }
            if (verdict != null) return verdict
            poll.sleep(ADVERT_SWITCH_POLL_MS)
        }
    }

    /** [rotate] for a bring-up, from the worker. */
    private fun runRotation(
        cause: AdvertRotation.Cause,
        announced: Boolean,
        token: Int,
    ) {
        runCatching { rotate(cause, announced, token) }.onFailure {
            // Never the id: the snapshot left from before the bring-up is stopped and stays so (the writer's stale rule).
            Log.w(TAG, "bt advert bring-up rotation failed: ${it.javaClass.simpleName}")
            writer.abandon(token)
        }
    }

    /**
     * [AwareFlag]'s move of FLAG_AWARE (GitHub #44): the advert changes in place, once. A write the stack refuses is
     * written again on the next re-assert turn ([BleAdvertiser.reassert]), since nothing else would push it.
     */
    private fun setAware(on: Boolean) {
        if (awareAdvertised == on) return
        awareAdvertised = on
        Log.i(TAG, "bt advert aware=$on")
        readvertise()
    }

    // --- Keeping the presence set on the air (#112) ---

    /**
     * Enables the presence set again whenever [advertKeeper] says it is due — after a connection edge, a reported
     * refusal, a heal, or the net — and the Coded set with it while that one is up, then the side channel's slots with a
     * page on the air (9utz, amendment 2026-10-09), each set its own enable so one refused set never takes another down.
     * The Coded set and the slots ride these turns; [codedKeeper] and the side channel's own keepers add retries for a
     * refusal the stack reported, on the doubling wait. Every wait is a timeout, so a lost wake costs latency, never
     * liveness.
     */
    private suspend fun advertLoop() {
        while (scope.isActive) {
            if (adapter?.isEnabled != true) {
                // Nothing to enable; bringUp starts the set afresh when the adapter comes back.
                // stretches: a 60 s net while the adapter is off; STATE_ON's bringUp raises the set, but wake() never pokes this loop.
                withTimeoutOrNull(ADAPTER_OFF_WAIT_MS) { advertWake.receive() }
                continue
            }
            val now = elapsed()
            val linked = links.isNotEmpty()
            val side = sideChannel
            if (advertKeeper.isDue(now, linked)) {
                writer.presence()?.reassert()
                if (codedUp()) writer.coded()?.reassert()
                side?.reassert()
                advertKeeper.reasserted(now)
                codedKeeper.reasserted(now)
            }
            if (codedKeeper.isDue(now, linked)) {
                if (codedUp()) {
                    writer.coded()?.reassert()
                } else if (codedAdvert == CODED_DARK_INTERNAL) {
                    retryCodedStart()
                }
                codedKeeper.reasserted(now)
            }
            side?.retryRefused()
            val sideWait = side?.retryWaitMs() ?: Long.MAX_VALUE
            val wait = minOf(advertKeeper.waitMs(elapsed(), linked), codedKeeper.waitMs(elapsed(), linked), sideWait)
            // stretches: the re-assert turns (9utz): settle, refusal retry, net. pj9w kept the net monotonic; the no-link retry is open.
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
        lastAclEdgeAt = elapsed()
        advertKeeper.onConnectionEdge(elapsed())
        advertWake.trySend(Unit)
    }

    // --- Coded PHY (the experiment, ADR 2026-10.yvn6) ---

    /** Whether the experiment runs here: a mode other than OFF, on a controller that has the Coded PHY. */
    private fun codedOn(): Boolean = codedMode != CodedPhyMode.OFF && codedSupported

    /** Reads the controller's Coded and extended-advertising support (adapter on), and clears a refused advert. */
    private fun probeCoded() {
        val a = adapter
        // Extended advertising also gates the blinded writer: without it a controller's sets may share the host's address
        // across a restart, which would tie one tag to the next (ADR 2026-11.xp6d). False while the adapter is off.
        val read = if (a != null && a.isEnabled) runCatching { a.isLeExtendedAdvertisingSupported }.getOrNull() else null
        val extended = read == true
        // Sticky for the process: the controller's feature does not change, and one transient false read at a bring-up
        // must not leave a whole session airing its id. A false read only replaces "not read yet".
        if (extended) {
            extAdvSupported = true
        } else if (read == false && extAdvSupported == null) {
            extAdvSupported = false
        }
        codedSupported = extended && a != null && runCatching { a.isLeCodedPhySupported }.getOrDefault(false)
        if (codedAdvert.startsWith("dark")) codedAdvert = "off"
        codedStartRetries = 0
    }

    /** A Coded set at the power asked for ([codedPower]); its callbacks act only while it is [writer]'s current one. */
    private fun newCodedAdvertiser(): BleAdvertiser {
        lateinit var set: BleAdvertiser
        set =
            BleAdvertiser(
                { adapter?.bluetoothLeAdvertiser },
                log = { Log.d(TAG, "coded $it") },
                params = BleAdvertiser.codedParams(codedPower),
                onStartStatus = { if (writer.isCurrent(set)) onCodedAdvertStatus(it) },
                onEnableStatus = { enabled, status -> if (writer.isCurrent(set)) onCodedEnabled(enabled, status) },
                onIntervalRefused = { interval, status -> if (writer.isCurrent(set)) onCodedIntervalRefused(interval, status) },
            )
        return set
    }

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
        writer.coded()?.setInterval(
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
                // stretches: the fast Coded advert's 2.5 s start (≈ CHECK_MS on either clock) and 3 min end (battery only).
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
        form: AdvertOrder.Form?,
    ) {
        Log.i(TAG, "bt phy ${status.nodeId} link dropped on ${status.phy} rssi=${status.linkRssi} ($reason)")
        val tuning = CodedPhyDiag.tuning
        if (reason != "eof" || !codedOn()) return
        // Who dials back is a dial role, on advertised forms; who stepped the link's PHY stays on the real ids (xp6d).
        val dialsBack = dialsBack(status.nodeId, form)
        if (CodedPhyPolicy.huntAfterDrop(localNodeId, status.nodeId, status.phy, status.linkRssi, tuning, dialsBack)) {
            rangeHunt.onDrop(status.nodeId, elapsed())
            Log.i(TAG, "bt hunt ${status.nodeId}: lost at range, for ${tuning.fastHoldMs}ms or ${tuning.huntInUseHoldMs}ms in use")
            wakeScan()
            return
        }
        if (!CodedPhyPolicy.fastAdvertAfterDrop(localNodeId, status.nodeId, status.phy, status.linkRssi, tuning, dialsBack)) return
        codedPace.onDrop(status.nodeId, elapsed(), tuning.fastHoldMs)
        schedulePace(AdvertReassertPolicy.SETTLE_MS)
    }

    /**
     * Whether I dial [nodeId] back after its link dropped ([CodedPhyPolicy.dialsBackAfterDrop]): on my form against the
     * form the link recorded for it ([linkForm]), else the one presence still holds, else the classic order on the real
     * ids (a never-sighted link). At a drop at range presence has usually lost the peer.
     */
    private fun dialsBack(
        nodeId: String,
        linkForm: AdvertOrder.Form?,
    ): Boolean {
        val heard = if (linkForm == null) presence.snapshots(elapsed()).firstOrNull { it.nodeId == nodeId }?.form else null
        return CodedPhyPolicy.dialsBackAfterDrop(mine(), linkForm, heard, localNodeId, nodeId)
    }

    private fun stopCodedAdvert() {
        writer.coded()?.stop()
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
            codedTxPower = level
            codedPower = power
            codedFast = false
            if (!codedAdvert.startsWith("dark")) codedAdvert = "off"
            // A fresh Coded set through the one writer, under the form on the air; a fast window moves it in place after.
            writer.replaceCoded(newCodedAdvertiser(), ::encode, ::write)
            applyCodedPace()
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

    /**
     * Republishes the PHY each handled link is on, for Diagnostics' per-link chip ([CodedPhyDiag.linkPhys]), and notes
     * it in [linkPhy] for the link's file pace.
     */
    private fun publishLinkPhys() {
        val known =
            phyControls.mapNotNull { (link, control) ->
                control.status().takeIf { it.attached && it.phy != LinkPhy.UNKNOWN }?.let { link to it }
            }
        known.forEach { (link, status) ->
            if (links[link.nodeId] !== link) return@forEach
            // A PHY step makes what the link's last stream measured meaningless for the next (ADR 2026-10.nsr7); a step to
            // or from Coded, the drain of the one streaming now too (ADR 2026-10.5buf's Coded amendment).
            val was = linkPhy.put(link, status.phy)
            if (was != null && was != status.phy) link.forgetDrain(newAir = was == LinkPhy.CODED || status.phy == LinkPhy.CODED)
        }
        CodedPhyDiag.publishLinkPhys(known.associate { (_, status) -> status.nodeId to status.phy })
        boosts.values.forEach(BleLinkBoost::poke) // a link on Coded is never boosted
        publishReachable() // a step to or from Coded moves the link in or out of the Aware candidate sets (GitHub #44)
    }

    /**
     * The pace a file is fed to [link] at, by the PHY it was last read on (#114, ADR 2026-10.yvn6), and on 1M/2M by how
     * many links share the budget right now (#117, ADR 2026-10.8jwn): every link here streaming a file and not on Coded, this one included.
     * The budget is [link]'s own (ADR 2026-10.g24x): the boosted one while it has earned the boost (#120), else the base. A link that
     * has not earned it, or has no boost (an iPhone, the flag off), holds only itself to the base — it once held every
     * feeding link there, and one iPhone pulling a photo cut three boosted links to a quarter of their pace. Read before
     * each chunk, so a feed that starts or ends, or a link that earns or loses the boost, re-splits at its next chunk. A
     * feed whose writes stall keeps its share until its write fails.
     */
    private fun paceFor(link: FramedLink): PaceConfig {
        val phy = linkPhy[link] ?: LinkPhy.UNKNOWN
        val feeding = pacedFeedingLinks().size
        val earned = earned(link)
        val le = if (earned) leLinkCount() else links.size
        val tuning = CodedPhyDiag.tuning
        val base = CodedPhyPolicy.pace(phy, tuning, feeding, earned, le)
        // A windowed feed is clocked by its receiver's reports; the pace above is its fallback while the receiver is silent.
        val pace = BleFeedPolicy.pace(base, windowed(link), phy, tuning)
        // Recorded only for the live link: a writer still finishing a chunk on a link torn down meanwhile would put it
        // back into the map after its teardown removed it, and pin it there.
        if (phy != LinkPhy.CODED && links[link.nodeId] === link && lastPace.put(link, feeding to earned) != feeding to earned) {
            // `earned` and `le` ride after the split, and the link last, so every parse of the line before them holds.
            Log.i(
                TAG,
                "bt file pace budget=${CodedPhyPolicy.budget(tuning, earned, le)} feeding=$feeding " +
                    "share=${pace.bytesPerSec} chunk=${pace.chunkBytes} earned=$earned le=$le → ${link.nodeId}",
            )
        }
        return pace
    }

    /**
     * This phone's LE connections: the mesh's links, and every other device `getConnectedDevices(GATT)` names (a ring, a
     * watch's LE fallback, a LoRa board, a link's orphaned ACL), which take a share of the controller's buffers too. That
     * list is every app's GATT clients' connections and, while any app has a GATT server registered (GMS does on the
     * Pixels), every LE connection on the phone, since the stack tells each server of each one (#142). So a mesh link may
     * be there or not, and its address is never counted twice; a phone with no server registered undercounts what no
     * client holds. Counted at most every [LE_COUNT_MS].
     */
    private fun leLinkCount(): Int {
        val now = elapsed()
        if (now - leLinksAt < LE_COUNT_MS) return leLinks
        val connected =
            runCatching { bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) }
                .getOrNull()
                .orEmpty()
                .map { it.address }
        leLinks = BleFeedPolicy.leLinks(links.size, connected, linkAddresses.values.toSet())
        leLinksAt = now
        return leLinks
    }

    /** The links sharing the 1M/2M file budget right now: every one streaming a file and not on Coded. */
    private fun feedingLinks(): List<FramedLink> = links.values.filter { it.txInProgress && linkPhy[it] != LinkPhy.CODED }

    // The feeding links that share the 1M/2M budget: a windowed feed takes none of it (ADR 2026-10.5buf), and counted
    // there it halved an iPhone's pull beside it (the bluetooth-reviewer's finding).
    private fun pacedFeedingLinks(): List<FramedLink> = feedingLinks().filter { !it.txWindowed }

    /**
     * Whether a file to [link] is clocked by its receiver's reports (ADR 2026-10.nsr7's phase 2): the switch on and a
     * peer that reports — a build carrying [Protocol.CAP_BLOB_HAVE], an iPhone included — on any PHY.
     */
    private fun windowed(link: FramedLink): Boolean = BleFeedPolicy.windowed(LinkTuneDiag.feedWindow, link.peer.capabilities)

    /**
     * Whether every one of [feedingLinks] has earned the boosted budget (none feeding earns nothing): the debug bridge's
     * summary. Each feed is paced by its own link's [earned].
     */
    private fun earned(feedingLinks: List<FramedLink>): Boolean = feedingLinks.isNotEmpty() && feedingLinks.all { earned(it) }

    /**
     * Whether [link] has earned the boosted budget (#120, ADR 2026-10.g24x). Never while Bluetooth audio streams: the budget was
     * measured with none, and A2DP takes the air a 15 ms link drains through.
     */
    private fun earned(link: FramedLink): Boolean = !audioMonitor.contended.value && boosts[link]?.earned == true

    /**
     * Makes [link]'s boost at its first file either way, and pokes it on every file edge after (FramedLink's `onBusy`).
     * None for an iPhone (its doorbell owns the parameters, ADR 2026-09.dqvb), none with the build flag off, and none for
     * a link already torn down or replaced: its own close pokes this too.
     */
    private fun boostPoke(link: FramedLink) {
        if (!BuildConfig.BLE_LINK_BOOST || DoorbellPolicy.serves(link.peer.capabilities)) return
        val nodeId = link.nodeId
        if (links[nodeId] !== link) return
        val boost =
            boosts[link] ?: BleLinkBoost(
                nodeId = nodeId,
                feeding = { link.feeding },
                receiving = { link.rxInProgress },
                observes = {
                    LinkBoostPolicy.observes(
                        enabled = LinkTuneDiag.boostOn,
                        doorbell = false,
                        coded = linkPhy[link] == LinkPhy.CODED,
                    )
                },
                eligible = {
                    LinkBoostPolicy.eligible(
                        enabled = LinkTuneDiag.boostOn,
                        doorbell = false,
                        coded = linkPhy[link] == LinkPhy.CODED,
                        interactive = powerState.state.value.interactive,
                        audio = audioMonitor.contended.value,
                    )
                },
                tuner = { make -> tunerFor(link, make) },
                scope = scope,
                wait = elapsedWait,
                now = SystemClock::elapsedRealtime,
            ).let { fresh ->
                boosts.putIfAbsent(link, fresh) ?: fresh.also {
                    it.start()
                    if (links[nodeId] !== link) boosts.remove(link)?.close() // torn down meanwhile: its teardown missed this
                }
            }
        boost.poke()
    }

    /**
     * [link]'s tuner: the live one, or — when [make] — a new one, its reports routed to the link's boost. Null for a link
     * gone or without a device, or when none is live and none may be made.
     */
    private fun tunerFor(
        link: FramedLink,
        make: Boolean,
    ): BleLinkTuner? {
        val live = tuners[link]?.takeIf { it.alive }
        val device = linkDevices[link]
        if (live != null || !make || device == null) return live
        val nodeId = link.nodeId
        var made: BleLinkTuner? = null
        val tuner =
            tuners.compute(link) { _, current ->
                current?.takeIf { it.alive } ?: BleLinkTuner(
                    context = appContext,
                    device = device,
                    nodeId = nodeId,
                    scope = scope,
                    isLive = { links[nodeId] === link },
                    now = SystemClock::elapsedRealtime,
                    onParams = { params, ok -> boosts[link]?.onParams(params, ok) },
                    onEnded = { attached -> boosts[link]?.onTunerEnded(attached) },
                ).also {
                    current?.close()
                    made = it
                }
            }
        val fresh = made ?: return tuner
        if (links[nodeId] !== link) { // torn down meanwhile: its teardown missed this
            tuners.remove(link, fresh)
            fresh.close()
            return null
        }
        fresh.start()
        return fresh
    }

    /**
     * Arms [device]'s ACL to idle out once its last channel closes (#142, ADR 2026-10.b8yf): a GATT client attaches to
     * the ACL and leaves at once, asking nothing. An LE ACL can outlive Knit's CoC. Android 12's ATT channel never
     * idles out unless a local client held it and left, and on Android 16/17 the side that dialed never gets the no-app
     * idle timeout (`gatt_set_idle_timeout … status=false` at connect). A client that held it and left sets that timeout
     * (4 s on Android 16/17, 1 s by source on Android 12), which runs once no dynamic channel remains, so the stack drops
     * the ACL about 4 s after the link's channel closes (1–4 s on Android 12). Either end's disconnect ends it on both
     * phones. Never in [tuners], so the boost and
     * `…debug.LINKTUNE close` cannot take it over; it ends by itself.
     *
     * [isLive] is checked just before `connectGatt`, which on a gone ACL would dial instead. A `false` from
     * `LinkTuneDiag.aclArm` (the debug A/B switch) arms nothing. An [AclArm.ARM] (link-up) begins in [orphans] here; an
     * [AclArm.UNLINKED] (through `tryArm`) and an [AclArm.RELEASE] (through `check`) began there already and bring their
     * [token]. Each tells [orphans] how it fared by that token (the record's `armed`, and the slots of #150); a stop's arm
     * is outside it. A release logs as `bt acl release`, so `bt acl arm` lines and `arms=` count arms alone.
     */
    private fun armAcl(
        device: BluetoothDevice,
        nodeId: String,
        isLive: () -> Boolean,
        kind: AclArm = AclArm.ARM,
        token: Long? = null,
    ) {
        if (!LinkTuneDiag.aclArm) return
        val address = device.address
        if (kind != AclArm.RELEASE) aclArms.incrementAndGet()
        val flight = if (kind == AclArm.ARM) orphans.onArming(address, elapsed()) else token
        BleLinkTuner(
            context = appContext,
            device = device,
            nodeId = nodeId,
            scope = scope,
            isLive = isLive,
            now = SystemClock::elapsedRealtime,
            onEnded = { attached ->
                if (kind != AclArm.RELEASE && attached) aclArmsAttached.incrementAndGet()
                flight?.let { orphans.onArmed(it, attached, elapsed()) }
            },
            // A flight that never made a client frees its slot at once (#150); a stop's arm has none to free.
            onNeverRegistered = flight?.let { { orphans.onNeverRegistered(it) } },
            label = if (kind == AclArm.RELEASE) "bt acl release" else "bt acl arm",
        ).apply {
            start()
            finish(LinkTuneRequest(node = nodeId)) // asks nothing: attach, then disconnect() and close() in its finally
        }
    }

    /**
     * One round of [orphans] (#142): an ACL a closed channel left up past the grace is released by a fresh [armAcl], and
     * each orphan is logged and recorded in the field log once. Runs on [diagLoop]'s minute, whose wait stretches while
     * the CPU sleeps: a release is the net under the arms, and no relink waits on it. An address a live link or a dial in
     * flight uses is left alone, at the tick and again at the attach ([releasable]), so a release never lands on an ACL
     * our own L2CAP connect is opening.
     */
    private fun checkOrphans() {
        val busy = linkAddresses.values.toSet() + dialingAddresses()
        orphans.check(elapsed(), busy, LinkTuneDiag.aclArm).forEach { action ->
            when (action) {
                is OrphanAclPolicy.Release -> {
                    Log.i(
                        TAG,
                        "bt acl orphan ${action.nodeId} ${action.address} release try=${action.tries} " +
                            "(link ended ${action.endedAgoMs}ms ago)",
                    )
                    val address = action.address
                    // Read again at the attach, not only at the tick: a dial of ours may have started on it since.
                    armAcl(action.device, action.nodeId, isLive = { releasable(address) }, AclArm.RELEASE, action.token)
                }

                is OrphanAclPolicy.Report -> {
                    Log.i(
                        TAG,
                        "bt acl orphan ${action.nodeId} ${action.address} ${if (action.cleared) "cleared" else "kept"} " +
                            "tries=${action.tries} armed=${action.armed} age=${action.ageMs}ms",
                    )
                    suspectStack(action)
                    field.record(
                        FieldEvent.BtOrphan(action.nodeId, action.linked, action.armed, action.tries, action.cleared, action.ageMs),
                    )
                }
            }
        }
    }

    /**
     * A link this phone closed on its own controller's word (#203) whose ACL the arm could not clear, while this phone
     * still holds more LE connections than links: the stack lost a disconnect and keeps a connection nothing owns.
     * Logged as `bt stack suspect`, which #204's verdict will read; nothing acts on it here. Entries past
     * [LOCAL_CLOSE_KEEP_MS] go unreported.
     */
    private fun suspectStack(report: OrphanAclPolicy.Report) {
        val now = elapsed()
        localCloses.entries.removeIf { now - it.value.at > LOCAL_CLOSE_KEEP_MS }
        val close = localCloses.remove(report.address) ?: return
        if (report.cleared) return
        val le = leLinkCount()
        if (le <= links.size) return
        Log.w(
            TAG,
            "bt stack suspect ${report.address} (${close.nodeId} closed on ${close.why.wire} ${now - close.at}ms ago, " +
                "le=$le links=${links.size})",
        )
    }

    /** The addresses our dials in flight target, on either PHY. */
    private fun dialingAddresses(): Set<String> =
        synchronized(lock) { inFlight.values.toList() }
            .flatMap { listOfNotNull(it.address, deviceFor[it.peerKey]?.device?.address, codedDeviceFor[it.peerKey]?.device?.address) }
            .toSet()

    /**
     * Whether an attach to [address]'s ACL may go now: its connect edge was seen and no disconnect since (else
     * `connectGatt` would dial), and no live link or dial of ours is on it — a release or an unlinked arm must not land
     * on an ACL our own L2CAP connect is opening.
     */
    private fun releasable(address: String): Boolean =
        orphans.aclUp(address) && address !in linkAddresses.values && address !in dialingAddresses()

    /**
     * A channel on [device]'s ACL closed without becoming a link (#142): a dialer refused or whose HELLO failed, or a
     * dial of ours that failed after its connect returned. Nothing arms that ACL at link-up, so it is armed here and
     * watched as an unlinked channel's end under [nodeId] (unknown for a HELLO that never came: armed, not watched).
     *
     * It waits [UNLINKED_SETTLE_MS] first, on the elapsed clock: the channel is ms old, and the ACL's connect edge comes
     * from another process and can trail it, while an ACL that never came up must arm nothing and dial nothing. A link
     * that came up on the address meanwhile (a cross-dial over the same ACL) takes it over. A cross-dial that starts
     * after the arm stays safe: the arm only arms the idle timer, and when it fires `l2c_link_timeout` disconnects only
     * while the link holds no channel (`if (!p_lcb->ccb_queue.p_first_ccb)`, android12 and android16 alike).
     *
     * Bounded (#150, the ADR's amendment): each arm is a GATT client registration, of 32 per device.
     * - A channel's end on an address already settling returns at once, leaving its node id for the settle to use.
     * - An ACL whose edges say it is gone, or that a link or dial of ours uses, begins nothing.
     * - `OrphanAclPolicy.tryArm` refuses an arm pending or attached on the address (that ACL is covered), one failed too
     *   recently, and one past the in-flight cap. On the last two the settle waits, on the elapsed clock, as long as
     *   `msUntilOpen` says and asks again, at most [UNLINKED_RETRIES] times: a refused lonely dial's ACL must not fall
     *   through to the minute's net, whose wait stretches. A HELLO-less channel's arm holds at most two of the three
     *   slots, and one whose ACL was gone at the attach frees its slot at once (`BleLinkTuner`'s `onNeverRegistered`).
     * - Refused for good, a HELLO-less channel is watched under "(no hello)" (a named one already is), so the minute's
     *   net still releases its ACL.
     * - No log line per refusal (a flood would flood logcat): `skipped=` on the debug `bt state` line counts them.
     */
    private fun armUnlinked(
        device: BluetoothDevice,
        nodeId: String?,
    ) {
        val address = device.address
        val mine = UnlinkedSettle(nodeId)
        val held = unlinkedSettling.compute(address) { _, old -> old?.also { if (nodeId != null) it.nodeId = nodeId } ?: mine }
        if (held !== mine) return
        val endedAt = elapsed()
        scope
            .launch(Dispatchers.IO) {
                try {
                    settleUnlinked(device, mine, endedAt)
                } finally {
                    unlinkedSettling.remove(address, mine)
                }
            }.invokeOnCompletion { unlinkedSettling.remove(address, mine) } // a launch cancelled before it ran
    }

    private suspend fun settleUnlinked(
        device: BluetoothDevice,
        settle: UnlinkedSettle,
        endedAt: Long,
    ) {
        val address = device.address
        elapsedWait.sleep(UNLINKED_SETTLE_MS)
        if (address in linkAddresses.values) return
        val nodeId = settle.nodeId
        if (nodeId != null) orphans.onLinkEnded(address, nodeId, device, endedAt, linked = false)
        val gate = gateUnlinked(address, settle)
        // Out of the map before the last read of its node id: an end after this starts a settle of its own.
        unlinkedSettling.remove(address, settle)
        if (gate == null) return
        val named = settle.nodeId
        if (nodeId == null && named != null) orphans.onLinkEnded(address, named, device, endedAt, linked = false) // named meanwhile
        if (gate is OrphanAclPolicy.ArmGate.Open) {
            armAcl(device, named ?: NO_HELLO, isLive = { releasable(address) }, AclArm.UNLINKED, gate.token)
        } else if (named == null && gate.passesLater) {
            orphans.onLinkEnded(address, NO_HELLO, device, endedAt, linked = false, named = false) // no arm stands on it
        }
    }

    /**
     * Asks `tryArm` for an unlinked arm on [address], waiting out a retry or a full set of slots on the elapsed clock at
     * most [UNLINKED_RETRIES] times. Null when nothing may arm it any more: the switch is off, or the ACL is gone or ours
     * (read again after every wait).
     */
    private suspend fun gateUnlinked(
        address: String,
        settle: UnlinkedSettle,
    ): OrphanAclPolicy.ArmGate? {
        if (!armable(address)) return null
        // A HELLO-less channel's arm finds one slot kept for releases and named arms; a name may arrive while it waits.
        var gate = orphans.tryArm(address, elapsed(), named = settle.nodeId != null)
        var retries = 0
        while (gate.passesLater && retries < UNLINKED_RETRIES) {
            retries++
            // Null: an arm began on the address meanwhile, and it covers the ACL.
            val wait =
                orphans.msUntilOpen(address, elapsed(), named = settle.nodeId != null)
                    ?: return OrphanAclPolicy.ArmGate.Pending
            elapsedWait.sleep(wait)
            if (!armable(address)) return null
            gate = orphans.tryArm(address, elapsed(), named = settle.nodeId != null)
        }
        return gate
    }

    // Whether an unlinked arm may attach to [address] now: the A/B switch is on, and nothing to arm means nothing to dial.
    private fun armable(address: String): Boolean = LinkTuneDiag.aclArm && releasable(address)

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
                onStep = { toCoded ->
                    metrics.onBlePhyStep(toCoded)
                    field.record(FieldEvent.BtPhy(nodeId, toCoded, phyControls[link]?.status()?.linkRssi))
                },
                onGiveUp = metrics::onBlePhyGiveUp,
                now = SystemClock::elapsedRealtime,
                onPhyKnown = ::publishLinkPhys,
                onLost = { onLinkEdge(link, "phy lost") },
            )
        if (phyControls.putIfAbsent(link, control) == null) control.start()
    }

    /**
     * `…debug.LINKTUNE` (#120, debug builds only): asks each link [req] names for what it names, through a tuner made
     * on first use, or lets their tuners go. An iPhone link is left alone — its doorbell owns the parameters (ADR
     * 2026-09.dqvb) — and no PHY is asked while the Coded experiment runs here: a stepper on either side reads a
     * foreign answer as a refusal and gives up, and a 1M or 2M mask lasts the link's life, so it would also block the
     * peer's step to Coded. The peer's own mode is not visible from here; the trial sets both phones' mode off.
     */
    private suspend fun tuneLinks(req: LinkTuneRequest): LinkTuneReport {
        boosts.values.forEach(BleLinkBoost::poke) // `--ez boost` may have just flipped
        val named = links.values.filter { req.node == "all" || it.nodeId.startsWith(req.node) }
        // Side by side, so `all` answers within the bridge's 30 s however many links it names.
        val statuses =
            coroutineScope {
                named
                    .map { link ->
                        async {
                            val address = linkDevices[link]?.address
                            when {
                                req.close -> {
                                    tuners.remove(link)?.close()
                                    boosts[link]?.forget() // nothing hears the link now
                                    LinkTuneStatus(link.nodeId, address, attached = false)
                                }

                                !req.asks -> {
                                    withBoost(link, tuners[link]?.status() ?: LinkTuneStatus(link.nodeId, address, attached = false))
                                }

                                DoorbellPolicy.serves(link.peer.capabilities) -> {
                                    LinkTuneStatus(link.nodeId, address, attached = false, refused = "iPhone link: the doorbell owns it")
                                }

                                else -> {
                                    tuneLink(link, req)
                                        ?: LinkTuneStatus(link.nodeId, address, attached = false, refused = "no device")
                                }
                            }
                        }
                    }.awaitAll()
            }
        val feedingLinks = feedingLinks()
        return LinkTuneReport(feedingLinks.size, statuses, earned(feedingLinks), leLinkCount())
    }

    private suspend fun tuneLink(
        link: FramedLink,
        req: LinkTuneRequest,
    ): LinkTuneStatus? {
        val tuner = tunerFor(link, make = true) ?: return null
        val codedRuns = codedOn()
        val status = tuner.tune(if (codedRuns) req.copy(phy = null) else req)
        val refused = if (codedRuns && req.phy != null) "the Coded experiment runs: …debug.PHY --es mode off on both" else null
        return withBoost(link, status.copy(codedOwned = phyControls.containsKey(link), refused = refused))
    }

    /**
     * Lets [link]'s boost and tuner go with the link. A link replaced by a fresh one from the same peer ([sharedAcl]) may
     * keep its ACL, so a boost still holding the short interval asks for BALANCED as the tuner's last ask, served without
     * waiting for a report: nothing else would put that ACL back. A link torn down lets its client go at once — an open
     * client holds the ACL, and the ACL takes the interval with it when it goes.
     */
    private fun closeTuning(
        link: FramedLink,
        sharedAcl: Boolean,
    ) {
        val boosting = boosts.remove(link)?.let { boost -> boost.boosting.also { boost.close() } } == true
        val tuner = tuners.remove(link) ?: return
        if (sharedAcl && boosting) {
            Log.i(TAG, "bt boost ${link.nodeId} release (link replaced)")
            tuner.finish(LinkTuneRequest(node = link.nodeId, priority = TunePriority.BALANCED))
        } else {
            tuner.close()
        }
    }

    private fun withBoost(
        link: FramedLink,
        status: LinkTuneStatus,
    ): LinkTuneStatus = boosts[link]?.let { status.copy(boosting = it.boosting, earned = it.earned) } ?: status

    /**
     * The field log's Bluetooth sample (ADR 2026-10.a239): the plane, each live link as its handles last heard it, and
     * the strongest few sighted peers. A read of what the transport already holds — it asks no link handle for
     * anything; the LE connection count may refresh its cache with one `getConnectedDevices` ([leLinkCount]).
     */
    private fun fieldSample(): List<FieldEvent> {
        val now = elapsed()
        val sighted = presence.snapshots(now)
        val live =
            // A link registered but not yet started has no age; it shows at the next sample.
            links.values.filter { it.linkStartedAt > 0 }.map { link ->
                val phy = phyControls[link]?.status()
                val tune = tuners[link]?.status()
                FieldEvent.BtLink(
                    peer = link.nodeId,
                    ageMs = now - link.linkStartedAt,
                    feeding = link.txInProgress,
                    receiving = link.rxInProgress,
                    phy = phy?.phy ?: tune?.txPhy ?: linkPhy[link],
                    rssi = phy?.linkRssi ?: tune?.rssi,
                    intervalUs = tune?.params?.intervalUs,
                    mtu = tune?.mtu,
                    earned = boosts[link]?.earned,
                )
            }
        val peers =
            // Resolved peers only: an unresolved form would add a pseudonym per heard tag (ADR 2026-11.xp6d).
            sighted.filter { !FormKeys.isForm(it.nodeId) }.sortedByDescending { it.smoothedRssi }.take(FIELD_SAMPLE_PEERS).map {
                FieldEvent.BtPeer(
                    peer = it.nodeId,
                    linked = links.containsKey(it.nodeId),
                    rssi = it.smoothedRssi.toInt(),
                    rssi1m = it.rssi1m?.toInt(),
                    rssiCoded = it.rssiCoded?.toInt(),
                    codedLagMs = it.codedLagMs,
                )
            }
        val plane = FieldEvent.BtScan(links.size, leLinkCount(), sighted.size, hunting, codedMode)
        return listOf(plane) + live + peers
    }

    private fun codedPhyStatus(): CodedPhyStatus {
        val now = elapsed()
        val tuning = CodedPhyDiag.tuning
        return CodedPhyStatus(
            mode = codedMode,
            supported = codedSupported,
            advert = codedAdvert,
            txPower = codedTxPower,
            links = phyControls.values.map(BlePhyControl::status),
            peers =
                presence.snapshots(now).map {
                    PhyPeerStatus(
                        it.nodeId,
                        it.smoothedRssi,
                        it.oneMSeenAgoMs,
                        it.codedSeenAgoMs,
                        it.rssi1m,
                        it.rssiCoded,
                        it.codedLagMs,
                    )
                },
            hunting = hunting,
            hunted = rangeHunt.wanted(now, inUse(powerState.state.value), tuning.fastHoldMs, tuning.huntInUseHoldMs),
        )
    }

    // --- Scanning (coordination plane) ---

    private suspend fun scanLoop() {
        while (scope.isActive) {
            // Drained before the in-flight check: a dial that began before it pauses the scan below, and one that begins
            // after it stays in the channel and ends the hunting window it lands in.
            dialStarted.tryReceive()
            if (waitedBeforeWindow()) continue
            val power = powerState.state.value
            val hunt = huntNow(power)
            scanWindow(power, hunt)
            // A cold hunt this window began (or one it ended) moves the gap after it too: a screen-off phone would
            // otherwise sit out its settled gap before the hunt's first window (the 2026-10-20 amendment).
            val gapHunt = if (noteColdSightings(power)) huntNow(power) else hunt
            val aloneFor = aloneForMs()
            val held = scanHeld()
            val gap = ScanPlan.gapAfterWindow(power, gapHunt, ::floorScan, links.size, aloneFor, held)
            if (held) scanHold.onHeldGap()
            lastGap = gap
            logLonelyTransition(gap.lonelyRelaxed, gap.ms, aloneFor)
            // The hunting gaps keep wall time through sleep; every other gap is a power budget and stretches (pj9w).
            if (gap.keepsWallTime) {
                elapsedWait.receiveWithin(scanWake, gap.ms)
            } else {
                // stretches: a power budget: the settled, linked and relaxed gaps (pj9w).
                withTimeoutOrNull(gap.ms) { scanWake.receive() }
            }
        }
    }

    /**
     * Waits instead of scanning, and says so, while a window may not start: a dial or the board's connect holds the
     * radio, the adapter is off, or a window a dial cut short still holds its slot ([nextWindowAt]).
     */
    private suspend fun waitedBeforeWindow(): Boolean {
        val inflight = inFlightSnapshot()
        val adapterOn = adapter?.isEnabled == true
        val busy = arbiter.busy.value
        // A connect in flight (radio contention) or the adapter off: wait, don't scan, for the event that ends the pause
        // — the connect's end (`registerLink`/`failConnect` → `wake()`), the arbiter freeing (its collector →
        // `wakeScan()`), the adapter coming back (`STATE_ON` → `wake()`) — with a timeout sized to that event, not a 2 s
        // poll: an adapter left off used to wake this loop thirty times a minute for as long as it stayed off. Else a
        // window a dial cut short keeps its slot.
        val wait =
            ScanPlan.beforeWindow(
                adapterOn,
                inflight.isNotEmpty(),
                busy,
                nextWindowAt,
                elapsed(),
                CONNECT_PAUSE_WAIT_MS,
                ADAPTER_OFF_WAIT_MS,
            )
                ?: return false
        // No window runs while the adapter is off, so none would write false: say it here too (GitHub #44).
        if (!adapterOn) newcomersOff("adapter off")
        // The pause is logged so a scanning gap (which starves presence for the whole mesh) is attributable.
        if (adapterOn && (inflight.isNotEmpty() || busy)) Log.d(TAG, "scan paused: connect in flight $inflight arbiter=$busy")
        elapsedWait.receiveWithin(scanWake, wait)
        return true
    }

    /**
     * Whether this window hunts ([CodedPhyPolicy.hunts]): a peer lost at range is wanted back ([rangeHunt]), or Knit is
     * open on a lit screen, and no Bluetooth audio contends the radio. Logs one line each time the answer or its reason
     * changes.
     */
    private fun huntNow(power: PowerState): Boolean {
        val tuning = CodedPhyDiag.tuning
        val inUse = inUse(power)
        val lost = rangeHunt.wanted(elapsed(), inUse, tuning.fastHoldMs, tuning.huntInUseHoldMs)
        // Audio streaming over Bluetooth keeps the scan at its floor, a hunt included: the radio it would take is the
        // one the music and our own dials share (the a2dp floor). The lost peer stays held, so the hunt resumes.
        val wants = CodedPhyPolicy.hunts(codedOn(), lost.isNotEmpty(), inUse)
        val hunt = wants && !audioMonitor.contended.value
        val why = if (wants) "lost=$lost inUse=$inUse" + (if (hunt) "" else " a2dp") else ""
        if (why != huntWhy) {
            huntWhy = why
            Log.i(
                TAG,
                when {
                    hunt -> "bt scan → hunt ($why)"
                    wants -> "bt scan hunt held ($why)"
                    else -> "bt scan hunt over"
                },
            )
        }
        hunting = hunt
        return hunt
    }

    /**
     * Treats each cold far sighting the window left — an unlinked peer heard on Coded alone ([ScanPlan.far]) — as a
     * drop at range, bounded ([CodedPhyPolicy.coldHunt], [ColdSightings]; the 2026-10-20 amendment): the side that dials
     * holds it in [rangeHunt] for the hold granted, the dialed side makes the Coded advert fast for it. First it ends a
     * cold hunt whose windows would end in no dial ([endColdHunts]). Returns whether the hunted set moved, so the gap
     * after this window is judged again.
     */
    private fun noteColdSightings(power: PowerState): Boolean {
        if (!codedOn() || !::localNodeId.isInitialized) return false
        val now = elapsed()
        val tuning = CodedPhyDiag.tuning
        val snaps = presence.snapshots(now)
        val backoff = activeBackoff(now, snaps)
        val lowPower = lowPower()
        val ended = endColdHunts(snaps, backoff, off = lowPower || !tuning.coldHunt, now)
        val far = ScanPlan.far(snaps, links.keys).toSet()
        if (far.isEmpty()) return ended
        val inflight = inFlightPeers()
        val held = rangeHunt.wanted(now, inUse(power), tuning.fastHoldMs, tuning.huntInUseHoldMs) + codedPace.wanted(now)
        val linkSnaps = linkSnapshots(links.values, snaps, now)
        val config = promotionConfig()
        var started = false
        snaps.filter { it.nodeId in far }.forEach { snap ->
            val id = snap.nodeId
            val dialsBack = CodedPhyPolicy.dialsBack(mine(), snap.form, localNodeId, id)
            val sighting =
                ColdSighting(
                    peerNodeId = id,
                    smoothedRssi = snap.smoothedRssi,
                    linked = id in links.keys || id in inflight,
                    held = id in held,
                    backingOff = id in backoff,
                    promotes = dialsBack && DialPlan.promotes(snap, linkSnaps, backoff, config),
                )
            val action =
                CodedPhyPolicy.coldHunt(localNodeId, sighting, codedOn = true, lowPower = lowPower, tuning = tuning, dialsBack = dialsBack)
            if (action != ColdAction.NONE) started = startCold(id, action, snap.smoothedRssi.toInt(), now, tuning) || started
        }
        return ended || started
    }

    /**
     * Ends each cold hunt whose peer linked (a link-up on the link's own thread can land between [noteColdSightings]'
     * read of [links] and its start), is in connect backoff ([backoff]) or gone from presence ([snaps]), or that low
     * power or the switch now refuses ([off]): its windows would end in no dial ([ColdSightings.endReason]). A drop's
     * hunt is left alone. Returns whether any ended.
     */
    private fun endColdHunts(
        snaps: List<BlePresenceTracker.Snapshot>,
        backoff: Set<String>,
        off: Boolean,
        now: Long,
    ): Boolean {
        val present = snaps.mapTo(HashSet()) { it.nodeId }
        var ended = false
        rangeHunt.cold(now).forEach { id ->
            val why =
                ColdSightings.endReason(
                    linked = links.containsKey(id),
                    backingOff = id in backoff,
                    present = id in present,
                    off = off,
                ) ?: return@forEach
            if (rangeHunt.cancelCold(id)) {
                coldSightings.end(id, now)
                Log.i(TAG, BtLines.coldHuntOver(id, why))
                ended = true
            }
        }
        return ended
    }

    /**
     * Takes [action] for [id]'s cold sighting if [ColdSightings] grants it — the last gate, so a cooldown or budget is
     * spent only on an action taken. Returns whether a hunt began.
     */
    private fun startCold(
        id: String,
        action: ColdAction,
        rssi: Int,
        now: Long,
        tuning: PhyTuning,
    ): Boolean {
        val grant = coldSightings.tryStart(id, action, now, tuning.fastHoldMs, tuning.coldHuntCooldownMs) ?: return false
        if (action == ColdAction.HUNT) {
            rangeHunt.onCold(id, now, grant)
            // Read after the hold is in: a link-up on the link's own thread since [noteColdSightings] read [links] cleared
            // nothing yet held, so it is undone here, as that link-up would have (a later one clears it itself).
            if (links.containsKey(id)) {
                rangeHunt.onLinkUp(id)
                coldSightings.end(id, now)
                return false
            }
            Log.i(TAG, BtLines.coldHunt(id, grant, rssi))
            return true
        }
        codedPace.onDrop(id, now, grant)
        if (links.containsKey(id)) {
            codedPace.onLinkUp(id)
            coldSightings.end(id, now)
            return false
        }
        Log.i(TAG, BtLines.coldFast(id, grant, rssi))
        // After the settle, as a drop's (9utz); a change already pending reads the new hold when it runs.
        if (paceJob?.isActive != true) schedulePace(AdvertReassertPolicy.SETTLE_MS)
        return false
    }

    /** Whether the person is using Knit now: its UI on screen and the screen lit. */
    private fun inUse(power: PowerState): Boolean = appVisible.value && power.interactive

    /** One presence scan window, on the PHYs [nextScanPhys] picks, for the window [power] gives. */
    private suspend fun scanWindow(
        power: PowerState,
        hunt: Boolean,
    ) {
        val phys = nextScanPhys(hunt)
        scanner.phys = phys
        scanner.start(if (power.interactive || power.charging) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
        val windowStart = elapsed()
        val window = newcomers.begin()
        val scanned = scanner.isScanning
        // The clock codedOnly is measured on runs only while 1M is heard (ADR 2026-10.yvn6).
        presence.onOneMListening(scanned && phys != ScanPhys.CODED, windowStart)
        val windowMs = PowerPolicy.dutyCycle(power).scanWindowMs
        nextWindowAt = windowStart + windowMs
        // Whether the window ran its whole length: a dial cut it short (a hunt's only) may have kept it from hearing a peer.
        val full: Boolean
        try {
            // A hunt's own dial ripens inside its windows, so a dial ends one; any other window runs its length (pj9w).
            full =
                if (hunt) {
                    elapsedWait.receiveWithin(dialStarted, windowMs) == null
                } else {
                    elapsedWait.sleep(windowMs)
                    true
                }
        } finally {
            presence.onOneMListening(false, elapsed())
        }
        // Still registered at the end: no onScanFailed in the window, and no adapter going off under it.
        val ranClean = scanned && scanner.isScanning && adapter?.isEnabled == true
        scanner.stop()
        if (scanned) noteQuietScan(phys, elapsed() - windowStart)
        endWindow(windowStart, ranClean, phys, full, window)
    }

    /**
     * A presence window that began at [startedAt] ended (GitHub #44): whether the plane watches for newcomers follows
     * whether its scan [ranClean], a whole window on 1M counts as a miss for every Aware candidate it did not hear, and
     * the sets are published again, so a peer that left drops out of `reachable`, `near` and the candidate sets within
     * the presence linger plus one gap, with no sighting to republish them (the plan's [B] finding).
     */
    private fun endWindow(
        startedAt: Long,
        ranClean: Boolean,
        phys: ScanPhys,
        full: Boolean,
        window: NewcomerWatch.Window,
    ) {
        // An off edge since the window began has the last word: the adapter is off or the plane stopped.
        newcomers.end(window, ranClean)
        awareCandidates.onWindow(startedAt, countsAsMiss = ranClean && full && phys != ScanPhys.CODED)
        publishReachable()
    }

    /** An off edge for [seesNewcomers] ([why]): false now, and no window that began before it may write true. */
    private fun newcomersOff(why: String) {
        newcomers.off(why)
    }

    /**
     * The next presence window's PHYs ([CodedPhyPolicy.scanPhys]): every other one Coded-only while this phone has no
     * link, an unlinked peer is heard on Coded alone, it [hunt]s, or another plane sees a peer it would dial that
     * Bluetooth has not sighted (the chase). Logs one line per edge of those windows.
     */
    private fun nextScanPhys(hunt: Boolean): ScanPhys {
        val on = codedOn()
        val now = elapsed()
        val sighted = if (on) presence.snapshots(now) else emptyList()
        val far = ScanPlan.far(sighted, links.keys)
        // Only a chased peer this phone has heard on Coded: a Coded-only window cannot hear one without it (a Pixel 3, a
        // release build, a phone in mode OFF), and the chase's one wake would spend its whole window deaf to it.
        val codedChase = ScanDemandPolicy.ChaseState(chase.deadlines.filterKeys { codedCapable.containsKey(it) })
        val chased = on && ScanDemandPolicy.chasing(codedChase, sighted.mapTo(HashSet()) { it.nodeId }, links.keys.toSet(), now)
        val alone = links.isEmpty()
        val phys = CodedPhyPolicy.scanPhys(on, alone, far.isNotEmpty(), lastScanCoded, farWanted = hunt || chased)
        lastScanCoded = phys == ScanPhys.CODED
        val windows = ScanPlan.codedWindows(on, alone, far, hunt, chased)
        if (windows != codedWindows) {
            codedWindows = windows
            val why = codedWindowsWhy(alone, hunt, far)
            Log.i(TAG, if (windows) "bt scan coded windows on ($why)" else "bt scan coded windows off")
        }
        return phys
    }

    /** Why Coded-only windows came on, for their edge's log line: the first of the reasons [nextScanPhys] reads. */
    private fun codedWindowsWhy(
        alone: Boolean,
        hunt: Boolean,
        far: List<String>,
    ): String = ScanPlan.codedWindowsWhy(alone, hunt, far) { chase.deadlines.keys.filter { codedCapable.containsKey(it) } }

    /** A window of [ms] ran on [phys]: GATT-payload quiet time, unless it was Coded-only — which cannot hear an iPhone. */
    private fun noteQuietScan(
        phys: ScanPhys,
        ms: Long,
    ) {
        if (gattPeers && phys != ScanPhys.CODED) forgetQuietGattPayloads(ms)
    }

    /** The transport stopped: a debug scan hold ends with it (`bt scan hold off (stop)`), and the last gap is forgotten. */
    private fun endScanHoldAtStop() {
        if (scanHold.end()) onScanHoldEnd("stop")
        lastGap = null
    }

    /** Whether the debug scan hold stands now; one that ran out ends here, logged once (`bt scan hold off (expired)`). */
    private fun scanHeld(): Boolean {
        val now = elapsed()
        if (scanHold.expire(now)) onScanHoldEnd("expired")
        return scanHold.held(now)
    }

    /**
     * The scan hold ended ([why]: `expired`, `released` or `stop`). The floor's edge is forgotten, so the next window logs
     * the tier the policy picks again and a trial log shows the scan back on it.
     */
    private fun onScanHoldEnd(why: String) {
        scanFloored = null
        Log.i(TAG, "bt scan hold off ($why)")
    }

    /**
     * `…debug.SCAN` (#285, a debug build only, through [ScanDiag]): [hold] true starts or renews the hold for [minutes]
     * ([ScanHold.minutes]), and a new one wakes the scan, so a long gap ends now; false releases it; null only reads. The hold moves
     * the gap alone ([ScanPlan.gapAfterWindow]), and costs battery while it stands.
     */
    private fun scanDiag(
        hold: Boolean?,
        minutes: Int?,
    ): ScanStatus {
        var granted: Int? = null
        if (hold == true) {
            val started = scanHold.start(elapsed(), minutes)
            granted = started.minutes
            Log.i(TAG, "bt scan hold on (debug, $granted min)")
            // A renewal only moves the expiry: the held gap already stands, and each wake would add a back-to-back window.
            if (!started.renewed) wakeScan()
        } else if (hold == false && scanHold.end()) {
            onScanHoldEnd("released")
        }
        val held = scanHeld()
        val power = powerState.state.value
        val gap = lastGap
        return ScanStatus(
            held = held,
            expiresInMs = scanHold.remainingMs(elapsed()),
            minutes = granted,
            posture = gap?.posture?.wire,
            gapMs = gap?.ms,
            wallTime = gap?.keepsWallTime,
            windowMs = PowerPolicy.dutyCycle(power).scanWindowMs,
            mode = if (power.interactive || power.charging) "balanced" else "low_power",
            heldGaps = scanHold.heldGaps(),
            links = links.size,
            hunting = hunting,
        )
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
        val backoff = activeBackoff(now, sighted)
        val candidates = DialPlan.scanCandidates(mine(), sighted, links.keys, backoff, PROMOTE_RSSI_FLOOR, provenLinks(), links::get)
        val demand =
            ScanDemandPolicy.decide(
                linkCount = links.size,
                promotableCandidates = candidates,
                chase = chase,
                bleSighted = sighted.mapTo(HashSet()) { it.nodeId },
                bleLinked = links.keys.toSet(),
                now = now,
                boostUntil = legacyBoostUntil,
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
            // A blinded advert's bytes 1–16 are a tag, never an id (ADR 2026-11.xp6d): a malformed one is dropped here,
            // before anything is computed; a well-formed one hops to the resolver's worker. A clear one is sighted here,
            // as it always was, and never waits on the worker.
            when {
                BleAdvertPayload.isMalformedBlinded(data) -> {
                    metrics.onBleBlindDropped(BleBlindDrop.MALFORMED_ADVERT)
                }

                else -> {
                    BleAdvertPayload.parse(data)?.let {
                        if (it.blinded) offerBlind(it, result, coded) else sight(it, result.device, result.rssi, coded)
                    }
                }
            }
            return
        }
        if (!gattPeers || record.serviceUuids?.contains(BleConstants.SERVICE_UUID) != true) return
        val read = gattPayloads.heard(result.device.address)
        if (read != null) sight(read, result.device, result.rssi, viaGatt = true) else maybeReadGatt(result.device, result.rssi)
    }

    /**
     * Queues a blinded sighting for the worker, stamped at capture (`timestampNanos`, the `elapsedRealtimeNanos` base
     * [elapsed] reads) and with the radio generation it was heard in. On the main thread: a map write, no hash.
     */
    private fun offerBlind(
        parsed: BleAdvertPayload.Parsed,
        result: ScanResult,
        coded: Boolean,
    ) {
        val item = BlindSighting(parsed, result.device, result.rssi, coded, result.timestampNanos / NANOS_PER_MS, radioGeneration.get())
        // One slot per tag and PHY: a peer heard on both keeps its Coded hearing while the worker is busy.
        when (blindQueue.offer(if (coded) parsed.nodeId + CODED_SLOT else parsed.nodeId, item)) {
            BlindQueue.Offer.START -> {
                scope.launch(worker) { drainBlind() }
            }

            BlindQueue.Offer.CONFLATED -> {
                metrics.onBleBlindDropped(BleBlindDrop.QUEUE_CONFLATED)
            }

            BlindQueue.Offer.DROPPED -> {
                metrics.onBleBlindDropped(BleBlindDrop.QUEUE_FULL)
            }

            BlindQueue.Offer.QUEUED -> {}
        }
    }

    /** On the worker: resolves every queued blinded sighting, until the queue is empty. */
    private fun drainBlind() {
        while (true) {
            val batch = blindQueue.take()
            if (batch.isEmpty()) return
            // One bad sighting must not end the drain: the queue would stay marked as draining and read nothing again.
            batch.forEach { runCatching { readBlind(it) }.onFailure { e -> Log.w(TAG, "bt blind read failed", e) } }
        }
    }

    /**
     * One blinded sighting, on the worker ([BlindSightings]): resolved, its form's history carried to the id or dropped,
     * then sighted under the key it is attributed to. One heard in an older radio generation (queued before the adapter
     * went off) is dropped, as [cancelGattRead] drops a read, so it cannot put a phantom back after the clear. One
     * captured before its peer's last link event ([linkStamps]) feeds presence and its RSSI only.
     */
    private fun readBlind(item: BlindSighting) {
        if (item.generation != radioGeneration.get()) return metrics.onBleBlindDropped(BleBlindDrop.STALE_RADIO)
        val tag = item.parsed.nodeId
        val resolution = aliases.resolve(item.parsed.form)
        val formKey = formKeys.keyFor(tag)
        val replayed = replays.replayed(tag, item.device.address)
        val attribution =
            BlindSightings.attribute(resolution, formKey, replayed) ?: return metrics.onBleBlindDropped(
                if (resolution == AdvertAliases.Resolution.Own) BleBlindDrop.OWN_ADVERT else BleBlindDrop.REPLAYED_ADVERT,
            )
        when (attribution.history) {
            BlindSightings.History.CARRY -> {
                rekeyForm(tag, attribution.key, carry = true, attribution.suppressor)
            }

            BlindSightings.History.DROP -> {
                rekeyForm(tag, attribution.key, carry = false)
            }

            BlindSightings.History.NONE -> {}
        }
        if (FormKeys.isForm(attribution.key)) noteHandleSighted(attribution.key, item.at)
        val onTime = !linkStamps.late(attribution.key, item.at)
        if (item.generation != radioGeneration.get()) return metrics.onBleBlindDropped(BleBlindDrop.STALE_RADIO)
        // A hint's sighting still notes its own link (the one whose `t` it was): its PHY control, its sighted mark, its form.
        val ownLink = links[attribution.key]?.let { linkForms[it]?.advertised == tag } == true
        val reach = BlindSightings.reach(attribution.full, onTime, ownLink)
        sight(
            item.parsed,
            item.device,
            item.rssi,
            item.coded,
            key = attribution.key,
            suppressor = attribution.suppressor,
            at = item.at,
            devices = reach.devices,
            reach = reach.reach,
            linkNotes = reach.linkNotes,
        )
    }

    /**
     * An unresolved [handle] sighted, captured at [at] (elapsed): its first sighting may end a chase early while this phone
     * blinds (step 5b's W6, [ScanDemandPolicy.endEarly]), at most once per [CHASE_EARLY_END_COOLDOWN_MS], counted. On
     * the worker.
     */
    private fun noteHandleSighted(
        handle: String,
        at: Long,
    ) {
        if (handleFirstSeen.size >= HANDLE_FIRST_SEEN_MAX) handleFirstSeen.clear() // bounded; a stale entry only delays an end
        if (handleFirstSeen.putIfAbsent(handle, at) != null || !mine().blinded) return
        val now = elapsed()
        synchronized(chaseLock) {
            chase =
                ScanDemandPolicy.endEarly(chase, PROMOTE_CHASE_MS, at, chaseEarlyEndAt, CHASE_EARLY_END_COOLDOWN_MS, now)
                    ?: return
            chaseEarlyEndAt = now
        }
        metrics.onBleBlindDropped(BleBlindDrop.CHASE_ENDED_BY_HANDLE)
    }

    /**
     * [FormKeys] let [handle] go (a tag flood past its cap): its entries go with it, so a form is never held under two
     * handles, and the eviction is counted. Under the handle table's monitor; touches only thread-safe maps.
     */
    private fun forgetHandle(handle: String) {
        presence.forget(handle)
        deviceFor.remove(handle)
        codedDeviceFor.remove(handle)
        codedCapable.remove(handle)
        codedTallies.remove(handle)
        gattSighted.remove(handle)
        awareCandidates.forget(handle)
        sideCapable.forget(handle)
        handleFirstSeen.remove(handle)
        metrics.onBleBlindDropped(BleBlindDrop.HANDLE_EVICTED)
    }

    /**
     * Moves what [tag]'s handle holds to [id] when it first resolves by a key or by our own dial's proof ([carry]): its
     * dwell, admission sighting and RSSI (presence), its addresses and its Coded hearing. Otherwise (a claim, a hint)
     * drops them: the id is attributed from now on, never backwards. Either way the handle's Aware candidacy, side
     * channel flag and tally go: the id's own sightings note them. A cold hunt or fast window held for the handle ends by
     * its own hold. On the worker, in the same task as the resolution, never at the next sighting.
     */
    private fun rekeyForm(
        tag: String,
        id: String,
        carry: Boolean,
        suppressor: BlePresenceTracker.Suppressor? = null,
    ) {
        val from = formKeys.existing(tag) ?: return
        if (from == id) return
        if (carry) {
            presence.rekey(from, id, suppressor)
            deviceFor.remove(from)?.let { deviceFor.putIfAbsent(id, it) }
            codedDeviceFor.remove(from)?.let { codedDeviceFor.putIfAbsent(id, it) }
            codedCapable.remove(from)?.let { at -> codedCapable.merge(id, at, ::maxOf) }
        } else {
            presence.forget(from)
            deviceFor.remove(from)
            codedDeviceFor.remove(from)
            codedCapable.remove(from)
        }
        gattSighted.remove(from)
        codedTallies.remove(from)
        awareCandidates.forget(from)
        sideCapable.forget(from)
    }

    /**
     * The peer [parsed] names is here, on [device] at [rssi]: presence, the side channel's audience, and the loops. A
     * blinded sighting comes from the worker with the [key] it is attributed to (the resolved id, or its form's handle),
     * what may suppress a dial on that answer ([suppressor], read when the dial is decided), its capture stamp [at], and how
     * far it reaches: [devices] (the addresses a dial would use), [reach] (the boost, the tallies, the Aware candidates
     * and the side audience) and [linkNotes] (the held link's PHY control, its sighted mark and its recorded form). A
     * clear one is its own key, stamped now, reaching everything, as it always was.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // one sighting's facts and reach; each gate one line
    private fun sight(
        parsed: BleAdvertPayload.Parsed,
        device: BluetoothDevice,
        rssi: Int,
        coded: Boolean = false,
        viaGatt: Boolean = false,
        key: String = parsed.nodeId,
        suppressor: BlePresenceTracker.Suppressor = BlePresenceTracker.Suppressor.Linked,
        at: Long = elapsed(),
        devices: Boolean = true,
        reach: Boolean = true,
        linkNotes: Boolean = reach,
    ) {
        if (key == localNodeIdOrEmpty()) return
        val form = parsed.form
        if (coded) metrics.onBleCodedSighting()
        if (devices) {
            if (viaGatt) gattSighted.add(key) else gattSighted.remove(key)
            val heard = HeardDevice(device, form.advertised)
            if (coded) {
                // Its Coded set's address is not its presence advert's: kept apart, so a dial picks the PHY it opens on.
                codedDeviceFor[key] = heard
                codedCapable.merge(key, at, ::maxOf)
            } else {
                deviceFor[key] = heard
            }
        }
        if (reach && codedOn() && key !in links.keys) {
            codedTallies.compute(key) { _, t -> (t ?: CodedTally()).also { it.note(rssi, coded) } }
        }
        if (linkNotes) {
            links[key]?.let {
                neverSighted.remove(it)
                linkForms[it] = form
                // A put racing the link's teardown would keep the dead link here for good: undone if it lost.
                if (links[key] !== it) linkForms.remove(it)
                if (coded) ensurePhyControl(it) // heard on Coded only after its link came up
            }
        }
        presence.onSighting(
            BlePresenceTracker.Sighting(
                nodeId = key,
                rssiDbm = rssi,
                protoVersion = Protocol.VERSION, // implied by the matched (versioned) service UUID
                capabilities = parsed.capabilities,
                psm = parsed.psm,
                digestCue = parsed.digestCue,
                coded = coded,
                advertised = form.advertised,
                blinded = form.blinded,
                suppressor = suppressor,
            ),
            at,
        )
        // Before the publish, which reads it: only service data carries the bit; a GATT payload's is no evidence.
        if (reach) {
            if (viaGatt) awareCandidates.forget(key) else awareCandidates.note(key, parsed.aware, !coded, at)
        }
        publishReachable()
        if (reach && sideChannel != null) {
            // The audience, not anyCapable: a flagged peer appearing beside an all-linked clique must wake the loop.
            val before = sideCapable.audience(elapsed(), links.keys)
            sideCapable.note(key, parsed.sideChannel, elapsed())
            if (sideCapable.audience(elapsed(), links.keys) != before) wakeSide()
        }
        healSignal.trySend(Unit) // connectLoop: react to every sighting to drive promotion
        // scanLoop: wake ONLY for a genuine boost trigger. Waking on every sighting (incl. already-linked peers)
        // is what keeps a settled clique scanning continuously — gating here is what lets the floor engage.
        if (reach && isBoostTrigger(form, key, suppressor)) scanWake.trySend(Unit)
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
        if (!::localNodeId.isInitialized) return
        if (!DialPlan.mayReadGatt(address, linkAddresses.values, arbiter.busy.value) { inFlightSnapshot().isNotEmpty() }) return
        val now = elapsed()
        logGattBudget(gattPayloads.budgetSpent(now))
        if (!gattPayloads.begin(address, now)) return
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
                        sight(outcome.payload, device, rssi, viaGatt = true)
                    }

                    GattPayloads.Outcome.Failed -> {
                        Log.i(TAG, "bt gatt read $address failed (${result.phase})")
                    }

                    GattPayloads.Outcome.Stranger -> {
                        Log.i(TAG, "bt gatt read $address stranger (${result.phase})")
                        if (result.phase == BleGattPayloadReader.PHASE_BLINDED) metrics.onBleBlindDropped(BleBlindDrop.GATT_BLINDED)
                    }
                }
                wake() // the arbiter is free again, and a dial held back for the read may go
                advertEdge() // the read's connection has closed (#112: it opened at a 15 ms interval)
            }
    }

    /**
     * One line per edge of the GATT read budget (#154), so a device trial can grep when strangers spent it and when it
     * came back. Read at a due-looking advert, never logged per advert.
     */
    private fun logGattBudget(spent: Boolean) {
        if (spent == gattBudgetSpentLogged) return
        gattBudgetSpentLogged = spent
        if (spent) {
            val config = gattPayloads.configuration
            Log.i(TAG, "bt gatt budget spent (${config.budgetReads} in ${config.budgetWindowMs}ms)")
        } else {
            Log.i(TAG, "bt gatt budget free")
        }
    }

    /** Gives up the read in progress without a wait: the radio went down under it. */
    private fun cancelGattRead() {
        gattJob?.cancel()
        gattJob = null
        gattPayloads.cancel()
    }

    /** A just-sighted peer worth boosting the scan for: one we'd initiate to ([AdvertOrder.iDial] on its advertised
     *  [form]), not held off by a link its answer's [suppressor] counts, above the RSSI floor (so it can
     *  actually promote), and off connect backoff by its dial key. Mirrors the [floorScan] candidate gate. */
    private fun isBoostTrigger(
        form: AdvertOrder.Form,
        key: String,
        suppressor: BlePresenceTracker.Suppressor,
    ): Boolean {
        if (!::localNodeId.isInitialized) return false
        val forms = mine()
        // Before the RSSI read, which takes the presence lock: a scan result for a linked or an order-exempt peer stops here.
        val linked = links.keys
        return AdvertOrder.iDial(forms.current, form) && !DialPlan.suppressed(suppressor, key, linked, provenLinks(), links::get) &&
            DialPlan.promotable(
                forms,
                form,
                key,
                presence.smoothedRssiFor(key),
                linked,
                PROMOTE_RSSI_FLOOR,
                suppressor,
                provenLinks(),
                links::get,
            ) {
                backingOff(it, form.blinded)
            }
    }

    /**
     * Whether [key] backs off now ([activeBackoff]'s rule for one peer): on the scan callback, so the presence read a
     * blinded peer's dial key needs is made only when some backoff is active and the key's own is not.
     */
    private fun backingOff(
        key: String,
        blinded: Boolean,
    ): Boolean {
        val now = elapsed()
        val active = synchronized(lock) { ConnectBackoffs.active(backoffs, now) }
        if (key in active) return true
        if (!blinded || active.isEmpty()) return false
        return key in activeBackoff(now)
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
        val backoffDue = synchronized(lock) { ConnectBackoffs.nextDueAt(backoffs, now) }
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
        DialPlan.ordinaryCandidates(mine(), snaps, links.keys, inFlightPeers(), provenLinks(), links::get)

    private fun ordinaryDialDueMs(
        snaps: List<BlePresenceTracker.Snapshot>,
        now: Long,
    ): Long? =
        if (!::localNodeId.isInitialized || adapter?.isEnabled != true) {
            null
        } else {
            PromotionPolicy.msUntilDue(ordinaryCandidates(snaps), activeBackoff(now, snaps), promotionConfig())
        }

    /** The promotion tunables, with the debug link cap (if any) as the budget. */
    private fun promotionConfig() = PromotionConfig(maxLinks = debugCap ?: PromotionConfig.DEFAULT_MAX_LINKS)

    /**
     * The sighted peers [LonelyDialPolicy] may pick from: dialable at all (device and PSM known), not linked, with the
     * ordinary backoff read without [driveConnections]' prune. The policy applies the id order and the other gates.
     */
    private fun lonelyCandidates(snaps: List<BlePresenceTracker.Snapshot>): List<LonelyDialPolicy.Candidate> =
        DialPlan.lonelyCandidates(snaps, links.keys, activeBackoff(elapsed(), snaps), provenLinks(), links::get) {
            dialTarget(it) != null
        }

    /**
     * The address a dial to [snap]'s peer goes to, and whether it is the Coded one: a peer heard on Coded alone is
     * dialed at its Coded set, and the link opens on Coded (the 2026-10-01 spike: a plain L2CAP connect to a
     * Coded-only advert lands on Coded, no initiating-PHY mask needed). A peer with no 1M address is dialed at its
     * Coded set as a Coded dial too, even before its lag ripens (#148). Null when neither address is known.
     */
    private fun dialTarget(snap: BlePresenceTracker.Snapshot): Pair<BluetoothDevice, Boolean>? =
        DialPlan.target(codedOn() && CodedPhyPolicy.dialCoded(snap.codedLagMs), heardAs(deviceFor, snap), heardAs(codedDeviceFor, snap))

    /** [snap]'s address in [cache], only while it was heard under the form [snap] airs now (ADR 2026-11.xp6d). */
    private fun heardAs(
        cache: Map<String, HeardDevice>,
        snap: BlePresenceTracker.Snapshot,
    ): BluetoothDevice? = cache[snap.nodeId]?.takeIf { it.advertised == snap.advertised }?.device

    /** [snap]'s dial key ([DialKeys.dialKey]) at the address a dial would go to now. */
    private fun dialKeyOf(snap: BlePresenceTracker.Snapshot): String =
        DialKeys.dialKey(snap, formKeys::keyFor, if (snap.blinded) dialTarget(snap)?.first?.address else null)

    /** Counts a Coded dial, and returns the `via=` the initiating line carries while the experiment runs. */
    private fun noteDial(viaCoded: Boolean): String {
        if (viaCoded) metrics.onBleCodedDial()
        return DialPlan.via(codedOn(), viaCoded)
    }

    /** Whether a lonely dial is open, by the kind its dial recorded when it began. */
    private fun lonelyDialInFlight(): Boolean = DialPlan.lonelyDialInFlight(synchronized(lock) { inFlight.mapValues { it.value.kind } })

    private fun lonelyDialDueMs(snaps: List<BlePresenceTracker.Snapshot>): Long? =
        if (!::localNodeId.isInitialized || adapter?.isEnabled != true) {
            null
        } else {
            LonelyDialPolicy.msUntilDue(mine(), links.size, aloneForMs(), lonelyCandidates(snaps), lonelyDialInFlight())
        }

    /**
     * What [fl] scores for eviction ([BleAdmissionPolicy.linkRssi]). The doorbell bit is the HELLO's, which
     * [registerLink] keeps in `peer` on a dialed link and an accepted one alike: a doorbell link gone from presence is
     * a backgrounded iPhone still linked, scored the floor (interop audit AND-3).
     */
    private fun linkScore(
        fl: FramedLink,
        rssiByNode: Map<String, Double>,
    ): Double = BleAdmissionPolicy.linkRssi(rssiByNode[fl.nodeId], fl in neverSighted, DoorbellPolicy.serves(fl.peer.capabilities))

    /** What each of [held] scores in the promotion: its eviction score ([linkScore]), age and idle time at [now]. */
    private fun linkSnapshots(
        held: Collection<FramedLink>,
        snaps: List<BlePresenceTracker.Snapshot>,
        now: Long,
    ): List<PromotionPolicy.LinkSnapshot> {
        val rssiByNode = snaps.associate { it.nodeId to it.smoothedRssi }
        return held.map { fl ->
            PromotionPolicy.LinkSnapshot(
                nodeId = fl.nodeId,
                smoothedRssi = linkScore(fl, rssiByNode),
                ageMs = now - fl.linkStartedAt,
                idleMs = now - fl.lastActivityAt,
            )
        }
    }

    private fun driveConnections() {
        val now = elapsed()
        val snaps = presence.snapshots(now)
        publishReachable(now)
        // Candidates: peers we're the initiator for (tie-break: larger id initiates), not linked, not in flight.
        val candidates = ordinaryCandidates(snaps)
        // The links this decision scores: an eviction closes the link it scored, never one that replaced it since.
        val scored = links.values.associateBy { it.nodeId }
        val linkSnaps = linkSnapshots(scored.values, snaps, now)
        // Present by per-peer key or by dial key (a tag's at its address): what the backoff book keeps.
        val present =
            snaps.mapTo(HashSet()) { it.nodeId } +
                snaps.filter { it.blinded }.flatMap { snap -> dialKeyOf(snap).let { listOfNotNull(it, DialKeys.addressKey(it)) } }
        val active =
            synchronized(lock) {
                // Drop backoff state for a peer that has left and whose window has passed, so a returning peer
                // starts fresh instead of inheriting a maxed-out streak.
                ConnectBackoffs.prune(backoffs, now) { id -> id in present || id in links.keys || id in inFlight.keys }
                ConnectBackoffs.active(backoffs, now)
            }
        val backoff = DialKeys.backedOff(snaps, active, ::dialKeyOf)
        val cap = debugCap
        val config = promotionConfig()
        val ordinary = PromotionPolicy.decide(candidates, linkSnaps, backoff, config)
        // Alone past the window: one larger-id peer too, which the responder admits unless it has sighted us (hj4a).
        val lonely = LonelyDialPolicy.pick(mine(), links.size, aloneForMs(), lonelyCandidates(snaps), lonelyDialInFlight(), config)
        // Under a debug cap, a dial still in flight holds a slot too, so back-to-back ticks can't overshoot it.
        val decision = DialPlan.decision(ordinary, lonely, cap, linkSnaps.size) { inFlightSnapshot().size }
        if (decision.promote.isNotEmpty() || decision.evict.isNotEmpty()) {
            Log.i(TAG, BtLines.promote(decision.promote, decision.evict, backoff, audioMonitor.state.value))
        }
        decision.evict.forEach { id ->
            val fl = scored[id] ?: return@forEach
            val score = linkSnaps.firstOrNull { it.nodeId == id }?.smoothedRssi?.toInt()
            Log.i(TAG, "bt evicting $id (score=$score doorbell=${DoorbellPolicy.serves(fl.peer.capabilities)})")
            teardownLink(id, "evicted", only = fl)
        }
        // A GATT read is a connection too; a dial waits for it (the read's end wakes this loop), as a read waits for a dial.
        // Said before the gate, so no read starts between the end of the one in flight and the next pass (#154).
        gattPayloads.yieldTo(DialPlan.dialWaiting(decision.promote) { id -> snaps.any { it.nodeId == id && dialTarget(it) != null } })
        if (gattPayloads.current == null) dial(decision.promote, lonely)
    }

    /** Dials each of [promote]; the one that is [lonely]'s is announced first (ADR 2026-09.hj4a). */
    private fun dial(
        promote: List<String>,
        lonely: LonelyDialPolicy.Candidate?,
    ) {
        promote.forEach { id ->
            // Straight before its own `bt initiating to`: the iOS port's interop harness keys off the pair.
            val isLonely = id == lonely?.nodeId
            if (isLonely) {
                Log.i(TAG, BtLines.lonelyDial(id, aloneForMs(), lonely.smoothedRssi.toInt(), lonely.dwellMs))
            }
            initiateTo(id, if (isLonely) DialPlan.DialKind.LONELY else DialPlan.DialKind.ORDINARY)
        }
    }

    /**
     * Dials the peer presence holds under [key] (ADR 2026-11.xp6d: a resolved id, or an unresolved form's handle). A
     * clear advert is dialed by id ([LinkHandshake.dial], `a` = the id). A blinded one is dialed by its tag
     * ([LinkHandshake.dialTag]: no id expected, a proof required, `t` absent while this phone airs its id), booked under
     * its dial key (the tag's handle and the address), so a failure backs off that pair and never the unproven id a
     * reply names; the link registers under the id the proof proved, and the outcome feeds [aliases] on the worker.
     */
    @Suppress("LongMethod") // the connect + watchdog + two-way HELLO is one linear flow; splitting it obscures it
    private fun initiateTo(
        key: String,
        kind: DialPlan.DialKind,
    ) {
        val snap = presence.snapshots(elapsed()).firstOrNull { it.nodeId == key } ?: return
        val (device, viaCoded) = dialTarget(snap) ?: return
        val psm = presence.psmFor(key) ?: return
        val form = snap.form
        val dialKey = DialKeys.dialKey(snap, formKeys::keyFor, device.address)
        if (!beginConnect(dialKey, DialPlan.InFlight(kind, key, device.address))) return
        val rssi = snap.smoothedRssi.toInt()
        Log.i(TAG, BtLines.initiating(key, psm, rssi, audioMonitor.state.value, links.size, noteDial(viaCoded)))
        // A tag dial forgets no GATT payload and arms its channel unnamed: the reply's id is unproven (`dialTag`'s KDoc).
        val gattDevice = device.takeIf { !form.blinded }
        val named = key.takeIf { !form.blinded }
        scope.launch(Dispatchers.IO) {
            val startedAt = elapsed()
            val socket =
                runCatching { device.createInsecureL2capChannel(psm) }
                    .getOrElse { t -> return@launch failConnect(dialKey, ConnectFailClassifier.classify(t), startedAt, t, gattDevice) }
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
                        failConnect(dialKey, ConnectFailReason.TIMEOUT, startedAt, cause, gattDevice)
                    }
                    runCatching { socket.close() } // may block until the native connect unwinds; the slot is already freed
                }
            val connectErr = runCatching { socket.connect() }.exceptionOrNull()
            if (!settled.compareAndSet(false, true)) {
                // The watchdog already timed this attempt out and released the slot; just tidy up and stop.
                runCatching { socket.close() }
                armUnlinked(device, named) // only if its ACL came up after all
                return@launch
            }
            watchdog.cancel()
            if (connectErr != null) {
                runCatching { socket.close() }
                failConnect(dialKey, ConnectFailClassifier.classify(connectErr), startedAt, connectErr, gattDevice)
                return@launch armUnlinked(device, named) // only if its ACL came up: a refused channel
            }
            val link = BluetoothSocketLink(socket)
            // Two-way HELLO: send our identity first, then read the responder's reply and require it to match the peer
            // we dialed, so the link's identity is confirmed by the peer over the socket, not taken from the
            // (unauthenticated) scanned advert. When the reply carries CAP_LINK_PROOF the responder's PROOF follows it,
            // and ours goes back once it verifies (ADR 2026-11.264w). Writes-then-reads while the responder reads-then-
            // replies, so neither blocks. A BluetoothSocket has no soTimeout, so one 5 s watchdog bounds the whole
            // exchange, the reply and the PROOF read alike, by closing the socket.
            val replyCut = cutAfter(socket, ACCEPT_HELLO_TIMEOUT_MS)
            // `a` = the form dialed (ADR 2026-11.xp6d), so the responder judges order on what we dialed; `t` absent while
            // this phone airs its id (absent reads as `t` = id, and saves 34 B a HELLO).
            val outcome = runCatching { handshake(link, form, key) }
            val inTime = replyCut.finish()
            val dialed = outcome.getOrNull()?.takeIf { inTime } ?: LinkHandshake.Dialed.NoReply
            val error = outcome.exceptionOrNull()
            val refusal = if (form.blinded) tagDialRefusal(dialed, error, inTime) else dialRefusal(key, device, dialed, error, inTime)
            if (refusal != null || dialed !is LinkHandshake.Dialed.Linked) {
                link.close()
                failConnect(dialKey, ConnectFailReason.HANDSHAKE, startedAt, refusal ?: IllegalStateException("handshake"))
                return@launch armUnlinked(device, named) // a channel that never became a link: a refusal, a failed proof (#142)
            }
            // A clear dial's reply named the id dialed; a tag dial's proved the id it registers under (never the tag).
            val provenId = dialed.reply.nodeId
            Log.i(TAG, BtLines.connectOk(provenId, elapsed() - startedAt))
            // The reply's full-width capabilities, as an accepted link takes the dialer's: the advert carries only
            // their low byte, which never holds CAP_DOORBELL, so a dialed iPhone would never be rung (A3, ADR 2026-09.dqvb).
            val framed =
                registerLink(
                    provenId,
                    dialed.reply,
                    link,
                    device = device,
                    dialMs = elapsed() - startedAt,
                    proven = dialed.proven,
                    dialStartedAt = startedAt,
                    dialKey = dialKey,
                    form = form,
                )
            if (form.blinded) scope.launch(worker) { onTagDialed(framed, form, dialed.reply.told, provenId, key, device.address) }
        }
    }

    /**
     * The dialer's handshake on [link] to [form]: by id ([LinkHandshake.dial], `a` = the id) for a clear advert, by tag
     * ([LinkHandshake.dialTag], no id expected, a proof required) for a blinded one.
     */
    private fun handshake(
        link: BluetoothSocketLink,
        form: AdvertOrder.Form,
        key: String,
    ): LinkHandshake.Dialed {
        val dialed = NodeId.toBytes(form.advertised)
        val told = toldForm()
        return if (form.blinded) {
            LinkHandshake.dialTag(link.input, link.output, prover, dialed, told = told)
        } else {
            LinkHandshake.dial(link.input, link.output, prover, key, told = told, addressed = dialed)
        }
    }

    /**
     * The HELLO's `t`: the tag I air now while blinding (ADR 2026-11.xp6d, "The handshake"), so a peer judges order on the
     * form it could have heard; null while I air my id (a clear HELLO carries `a`, never `t`).
     */
    private fun toldForm(): ByteArray? = mine().current.takeIf { it.blinded }?.let { NodeId.toBytes(it.advertised) }

    /**
     * Why a tag dial whose handshake ended as [dialed] is not a link, or null when it is one. Nothing here names, counts
     * or forgets anything for the id a reply names: on a tag dial it is unproven until the proof ([LinkHandshake.dialTag]).
     */
    private fun tagDialRefusal(
        dialed: LinkHandshake.Dialed,
        error: Throwable?,
        inTime: Boolean,
    ): Throwable? =
        when (dialed) {
            LinkHandshake.Dialed.NoReply -> {
                error
                    ?: if (inTime) {
                        IllegalStateException(
                            "tag dial: no reply",
                        )
                    } else {
                        TimeoutException("hello watchdog ${ACCEPT_HELLO_TIMEOUT_MS}ms")
                    }
            }

            is LinkHandshake.Dialed.WrongPeer -> {
                IllegalStateException("tag dial: the reply names this phone")
            }

            is LinkHandshake.Dialed.ProofFailed -> {
                IllegalStateException("tag dial: proof failed (${dialed.why})")
            }

            is LinkHandshake.Dialed.Linked -> {
                null
            }
        }

    /**
     * Our tag dial to [form] proved [provenId] on [link] (the responder's HELLO told [told]), on the worker: the dial feeds
     * [aliases] ([AdvertAliases.onDialed], which runs [AdvertAliases.onDialProved]). A claim it records carries the form's
     * history to the id ([rekeyForm]). A proof of another id than a contact's key names for the tag is a replay: the
     * (tag, [address]) pair attributes nothing from now on, and an address of it held for the key's id ([attributed]) is
     * forgotten, so it is never dialed as that contact.
     */
    @Suppress("LongParameterList") // the dial's facts
    private fun onTagDialed(
        link: FramedLink,
        form: AdvertOrder.Form,
        told: String?,
        provenId: String,
        attributed: String,
        address: String,
    ) {
        val mismatches = aliases.keyProofMismatchCount
        // A link that already ended stores nothing: its proof still settles the tag's rivals, and a replay is still marked.
        val outcome =
            if (BlindSightings.current(links[provenId], link)) {
                aliases.onDialed(link, form, told, provenId)
            } else {
                aliases.onDialProved(form, provenId)
                null
            }
        if (aliases.keyProofMismatchCount > mismatches) {
            replays.mark(form.advertised, address)
            if (!FormKeys.isForm(attributed)) {
                deviceFor.computeIfPresent(attributed) { _, held -> held.takeIf { it.device.address != address } }
                codedDeviceFor.computeIfPresent(attributed) { _, held -> held.takeIf { it.device.address != address } }
            }
        }
        if (outcome == AdvertAliases.ClaimOutcome.Recorded) {
            rekeyForm(form.advertised, provenId, carry = true, BlePresenceTracker.Suppressor.Claim(link))
        }
    }

    /**
     * Why a dial to [nodeId] whose handshake ended as [dialed] is not a link, or null when it is one. [error] is what the
     * handshake threw, and [inTime] false when the watchdog cut it. Counts and logs the proof cases through [claims].
     */
    private fun dialRefusal(
        nodeId: String,
        device: BluetoothDevice,
        dialed: LinkHandshake.Dialed,
        error: Throwable?,
        inTime: Boolean,
    ): Throwable? =
        when (dialed) {
            // Most often a refusal: the responder closed the channel (shzv, hj4a).
            LinkHandshake.Dialed.NoReply -> {
                error
                    ?: if (inTime) {
                        IllegalStateException("hello reply absent != expected $nodeId")
                    } else {
                        TimeoutException("hello watchdog ${ACCEPT_HELLO_TIMEOUT_MS}ms")
                    }
            }

            is LinkHandshake.Dialed.WrongPeer -> {
                // A reply naming another node: a new node took the address our payload came from. No reply keeps it.
                forgetGattPayloadIfOther(device.address, dialed.reply.nodeId)
                IllegalStateException("hello reply ${dialed.reply.nodeId} != expected $nodeId")
            }

            is LinkHandshake.Dialed.ProofFailed -> {
                claims.proofFailed(nodeId, PLANE, dialed.why)
                IllegalStateException("link proof failed: ${dialed.why}")
            }

            is LinkHandshake.Dialed.Linked -> {
                // R1: a reply without the bit claiming an id that can prove itself is not that peer.
                if (!dialed.proven && claims.isProofCapable(nodeId)) {
                    claims.refused(nodeId, PLANE, "unproven reply")
                    IllegalStateException("unproven reply claims a proof-capable id")
                } else {
                    null
                }
            }
        }

    /**
     * A watchdog that closes [socket] after [ms] on the elapsed clock (a BluetoothSocket has no soTimeout, and a relink
     * waits on this). [Cut.finish] cancels it and says whether the handshake beat it: exactly one of the two wins, so a
     * handshake that returned as the watchdog fired is never registered on a socket the watchdog closed.
     */
    private fun cutAfter(
        socket: BluetoothSocket,
        ms: Long,
    ): Cut {
        val settled = AtomicBoolean(false)
        val job =
            scope.launch {
                elapsedWait.sleep(ms)
                if (settled.compareAndSet(false, true)) runCatching { socket.close() }
            }
        return Cut(settled, job)
    }

    private class Cut(
        private val settled: AtomicBoolean,
        private val job: Job,
    ) {
        /** Stops the watchdog; true when the handshake finished first (the socket is still ours). */
        fun finish(): Boolean = settled.compareAndSet(false, true).also { job.cancel() }
    }

    // Per server: a rotation's retired server keeps accepting through its grace (step 5b's W4); closing a server is what
    // ends its loop (a blocked accept() throws), and a closed one is forgotten.
    private fun acceptLoop(ss: BluetoothServerSocket) {
        try {
            while (scope.isActive) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                scope.launch(Dispatchers.IO) { superviseAccepted(socket) }
            }
        } finally {
            acceptJobs.remove(ss)
        }
    }

    /**
     * A client connected to our L2CAP responder: read its identity (HELLO, watchdog-bounded), then keep it or
     * close it by [BleAdmissionPolicy] — which admits a dialer the scan never saw whatever the id order.
     *
     * Link proofs (ADR 2026-11.264w, #232): a HELLO with `CAP_LINK_PROOF` and no nonce, and an unproven HELLO claiming
     * an id that can prove itself (R1), are closed with no reply before any other row. A kept dialer that offers a proof
     * gets our reply and PROOF in one write, and is registered only once its own PROOF verifies, under a second 5 s
     * watchdog; the link it displaces (R3) closes only then, and stays if the proof fails. Every close before
     * registration hands the channel to [armUnlinked], as the old refusals did (#142).
     */
    @Suppress("LongMethod", "ReturnCount") // read, refuse, answer, re-judge, register: one linear flow of early exits
    private fun superviseAccepted(socket: BluetoothSocket) {
        val link = BluetoothSocketLink(socket)
        // BluetoothSocket has no soTimeout, so bound the HELLO read by closing the socket if it stalls.
        val helloCut = cutAfter(socket, ACCEPT_HELLO_TIMEOUT_MS)
        val read = runCatching { LinkHandshake.receiveHello(link.input) }.getOrNull()
        val helloInTime = helloCut.finish()
        val hello = read?.takeIf { helloInTime }
        val device = socket.remoteDevice
        if (hello == null) {
            link.close()
            armUnlinked(device, null)
            return
        }
        val clientNodeId = hello.wire.nodeId
        if (hello.malformedOffer) {
            // A HELLO that offers a proof with no nonce to sign over: a failed proof, closed with no reply, no backoff.
            claims.proofFailed(clientNodeId, PLANE, "no nonce")
            Log.i(TAG, "bt refused client $clientNodeId (proof=no-nonce)")
            link.close()
            armUnlinked(device, clientNodeId)
            return
        }
        if (refusesClaim(hello)) {
            Log.i(TAG, "bt refused client $clientNodeId (claim=unproven)")
            link.close()
            armUnlinked(device, clientNodeId)
            return
        }
        // A dialer at an address whose payload names another node took that address: forget the payload before the
        // verdict, so the ghost it names is not what this dialer is judged by (the contract's payload lifetime, rule 2).
        forgetGattPayloadIfOther(device.address, clientNodeId)
        val first = judge(hello, honoured = BleAdmissionPolicy.honours(hello.offersProof, proven = false, late = false))
        if (first.verdict.refuses) {
            Log.i(TAG, "bt refused client $clientNodeId (${first.fields})")
            noteHeldRefusal(first.verdict, clientNodeId)
            link.close()
            armUnlinked(device, clientNodeId) // most often a sighted lonely dialer (hj4a)
            return
        }
        // Reply with our identity so the initiator can confirm it reached us (two-way HELLO; the initiator validates this
        // before registering), our PROOF behind it when the dialer offers one, then read the dialer's PROOF. We read
        // first, then reply: no mutual block. Its own watchdog: the HELLO's was cancelled at the read.
        val answerCut = cutAfter(socket, ACCEPT_HELLO_TIMEOUT_MS)
        val outcome = runCatching { LinkHandshake.answer(link.input, link.output, prover, hello, told = toldForm()) }
        val inTime = answerCut.finish()
        val answered =
            outcome.getOrNull()?.takeIf { inTime }
                ?: LinkHandshake.Answered.ProofFailed(if (inTime) "unreadable" else "timeout")
        when (answered) {
            LinkHandshake.Answered.WriteFailed -> {
                link.close()
                armUnlinked(device, clientNodeId)
                return
            }

            is LinkHandshake.Answered.ProofFailed -> {
                // The held link (if any) is untouched: R3 displaces only on a verified proof.
                claims.proofFailed(clientNodeId, PLANE, answered.why)
                Log.i(TAG, "bt refused client $clientNodeId (proof=${answered.why.replace(' ', '-')})")
                link.close()
                armUnlinked(device, clientNodeId)
                return
            }

            is LinkHandshake.Answered.Linked -> {}
        }
        val proven = answered.proven
        // Up to 5 s went by: the held link may have gone, been replaced or proven, and the id may have become
        // proof-capable. Judge again against what is held now; an unproven link re-checks R1.
        if (!proven && refusesClaim(hello)) {
            Log.i(TAG, "bt refused client $clientNodeId (claim=unproven late=true)")
            link.close()
            armUnlinked(device, clientNodeId)
            return
        }
        val late = judge(hello, honoured = BleAdmissionPolicy.honours(hello.offersProof, proven, late = true))
        if (late.verdict.refuses) {
            Log.i(TAG, "bt refused client $clientNodeId (${late.fields} late=true)")
            noteHeldRefusal(late.verdict, clientNodeId)
            link.close()
            armUnlinked(device, clientNodeId)
            return
        }
        Log.i(TAG, "bt accepted client $clientNodeId (${late.verdict}, ${late.acceptFields})")
        val form = toldForm(hello, proven)
        val framed = registerLink(clientNodeId, hello.wire, link, late.sighted, device, proven = proven, form = form)
        noteAnswered(framed, form, clientNodeId)
    }

    /**
     * A proven dialer's `t` ([form]) is a hint (presence and RSSI only, never a dial suppressed), on the worker, and only
     * while [framed] is still the link held under [id] ([BlindSightings.current]).
     */
    private fun noteAnswered(
        framed: FramedLink,
        form: AdvertOrder.Form?,
        id: String,
    ) {
        if (form?.blinded != true) return
        scope.launch(worker) { if (BlindSightings.current(links[id], framed)) aliases.onAnswered(framed, form.advertised, id) }
    }

    /**
     * The form an accepted dialer's HELLO `t` says it airs (ADR 2026-11.xp6d): honoured on a [proven] link only, and a
     * `t` other than the id only with the tag prefix; null when absent or not honoured.
     */
    private fun toldForm(
        hello: LinkHandshake.ReceivedHello,
        proven: Boolean,
    ): AdvertOrder.Form? {
        val id = hello.wire.nodeId
        val told = hello.wire.told?.takeIf { proven && (it == id || NodeId.toBytesOrNull(it)?.let(BlindTag::hasPrefix) == true) }
        return told?.let { AdvertOrder.Form.fromHello(id, it) }
    }

    /** R1 on a dialer's [hello]: it offers no proof for an id that can prove itself. Counted and logged by [claims]. */
    private fun refusesClaim(hello: LinkHandshake.ReceivedHello): Boolean {
        val id = hello.wire.nodeId
        if (!BleAdmissionPolicy.refusesClaim(hello.offersProof, claims.isProofCapable(id))) return false
        claims.refused(id, PLANE, "unproven hello")
        return true
    }

    /** One admission verdict on a dialer, with the fields its `bt refused client` / `bt accepted client` line carries. */
    private class Judgement(
        val verdict: BleAdmissionPolicy.Verdict,
        val sighted: Boolean,
        // `sighted=… codedOnly=… heldAgeMs=… atCap=…[ doorbell=…]`: the refusal's fields, harness-read (interop.py).
        val fields: String,
        // `sighted=… codedOnly=…[ doorbell=…]`: the acceptance's, after its verdict token.
        val acceptFields: String,
    )

    /**
     * The blinding rows' follow-ups (ADR 2026-11.xp6d, step 5b's W5/W6), on the dialer's IO thread:
     *  - the held-link row ([BleAdmissionPolicy.Verdict.RefuseHeld]): my current form goes over the held link as a resend
     *    (the same predicate as every `TAG_NEXT`, throttled per link and kept out of the window before a due
     *    announcement: an excluded peer's re-dials get no resend), and the held link's silence is probed now
     *    ([LinkSilence.onHeldDial]: never on a `CAP_DOORBELL` link, one probe per answer wait), so a dead held link
     *    closes within its own probes and the dialer's next try is admitted. Never a shorter wait of its own: a forged
     *    dial must not get a live link closed behind a full stack buffer;
     *  - the blinding-only row ([BleAdmissionPolicy.Verdict.RefuseLegacy]): counted, and a scan boost to find that
     *    dialer's clear advert, at most one per cooldown phone-wide ([LegacyDialerBoost]).
     */
    private fun noteHeldRefusal(
        verdict: BleAdmissionPolicy.Verdict,
        dialerId: String,
    ) {
        when (verdict) {
            BleAdmissionPolicy.Verdict.RefuseHeld -> {
                metrics.onBleBlindDropped(BleBlindDrop.HELD_LINK_REFUSED)
                val held = links[dialerId] ?: return
                // Counted only when it starts a probe: forged dials at any rate cost one probe per answer wait.
                if (silences[held]?.policy?.onHeldDial(elapsed()) == true) {
                    metrics.onBleAdvertWrite(BleAdvertWrite.HELD_PROBED)
                    Log.i(TAG, "bt link edge $dialerId held dial")
                    silenceWake.trySend(Unit)
                }
                scope.launch(worker) {
                    val form = writer.form()?.takeIf { it.blinded } ?: return@launch
                    sendForm(held, TagNextSends.Kind.RESEND, NodeId.toBytes(form.advertised))
                }
            }

            BleAdmissionPolicy.Verdict.RefuseLegacy -> {
                metrics.onBleBlindDropped(BleBlindDrop.LEGACY_DIALER_REFUSED)
                val now = elapsed()
                if (!legacyBoost.take(now, dialerId)) return
                legacyBoostUntil = now + LegacyDialerBoost.BOOST_MS
                metrics.onBleAdvertWrite(BleAdvertWrite.LEGACY_BOOST)
                wakeScan()
            }

            else -> {}
        }
    }

    /**
     * [BleAdmissionPolicy.decide] on [hello] against the link held to its id now and what presence holds now. The HELLO's
     * `t` and `a` count in the order check and the held-link row only when [honoured]: the HELLO offers a proof (the
     * first pass) or proved it (the late pass), as the security design pass of ADR 2026-11.xp6d requires.
     */
    private fun judge(
        hello: LinkHandshake.ReceivedHello,
        honoured: Boolean,
    ): Judgement {
        val advert = hello.wire
        val clientNodeId = advert.nodeId
        val snap = presence.snapshots(elapsed()).firstOrNull { it.nodeId == clientNodeId }
        val sighted = snap != null
        // A dialer heard on Coded alone is judged as unsighted while the experiment runs (ADR 2026-10.yvn6): its sparse
        // Coded hits hold it in presence but rarely promote it, so "we dial it" left a far pair each waiting.
        val admitSighted = CodedPhyPolicy.sightedForAdmission(snap, codedOn())
        val held = links[clientNodeId]
        val heldAgeMs = held?.let { elapsed() - it.linkStartedAt }
        val atCap = debugCap?.let { links.size + inFlightSnapshot().size >= it } ?: false
        // A doorbell dialer (an iPhone) counts as sighted only if heard within 12 s: backgrounded, it is unheard while
        // the linger still holds it (interop audit AND-3). The HELLO's bit is taken only with our own evidence behind it,
        // so a claimed id can't pull a sighted Android peer into the unsighted rows. Logged for a claim alone.
        val claimed = DoorbellPolicy.serves(advert.capabilities)
        val heldDoorbell = held?.let { DoorbellPolicy.serves(it.peer.capabilities) }
        val doorbell = BleAdmissionPolicy.doorbellTrusted(claimed, heldDoorbell, sighted, clientNodeId in gattSighted)
        val heardAgoMs = snap?.lastSeenAgoMs
        val trust = if (doorbell) "" else " doorbellTrusted=false"
        val why = if (claimed) " doorbell=true heardAgoMs=$heardAgoMs inPresence=$sighted$trust" else ""
        // The order on advertised forms, and the held-link row (ADR 2026-11.xp6d): both read one snapshot of mine. For a
        // phone that airs its id, the first is the classic `local > dialer` and the second never fires.
        val forms = mine()
        val outOfOrder = BleAdmissionPolicy.outOfOrder(forms, clientNodeId, advert.told, advert.addressed, honoured)
        val heldRow =
            held != null &&
                BleAdmissionPolicy.heldLinkRow(
                    forms,
                    advert.addressed,
                    honoured,
                    heldProven = held.proven,
                    heldQuiet = silences[held]?.let { elapsed() - it.stamps.lastByteAt >= LinkSilence.QUIET_MS } ?: true,
                    heldDoorbell = heldDoorbell == true,
                )
        // The proof rows (ADR 2026-11.264w): R1 was asked already; R3 and R2 read whether the held link is proven.
        val verdict =
            BleAdmissionPolicy.decide(
                localNodeId,
                clientNodeId,
                admitSighted,
                heldAgeMs,
                atCap,
                doorbell,
                heardAgoMs,
                heldProven = held?.proven == true,
                offersProof = hello.offersProof,
                proofCapable = claims.isProofCapable(clientNodeId),
                outOfOrder = outOfOrder,
                heldRow = heldRow,
                blinding = forms.blinded,
                readsTags = advert.readsTags,
            )
        val coded = "sighted=$sighted codedOnly=${sighted && !admitSighted}"
        val proof = if (held != null) " heldProven=${held.proven} offersProof=${hello.offersProof}" else ""
        // After every existing field, so a harness that reads them keeps matching (interop.py).
        val heldTag = blindRowTag(verdict)
        return Judgement(verdict, sighted, "$coded heldAgeMs=$heldAgeMs atCap=$atCap$why$proof$heldTag", "$coded$why")
    }

    /** The refusal line's last field for a blinding row: `held=resend` (the held-link row) or `blind=legacy`. */
    private fun blindRowTag(verdict: BleAdmissionPolicy.Verdict): String =
        when (verdict) {
            BleAdmissionPolicy.Verdict.RefuseHeld -> " held=resend"
            BleAdmissionPolicy.Verdict.RefuseLegacy -> " blind=legacy"
            else -> ""
        }

    // One registration: the link, its doorbell, its PHY control and the neighbor set, in order; the proof's verdict
    // (ADR 2026-11.264w) adds its displacement, its deferred streak reset and the claim it reports.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun registerLink(
        nodeId: String,
        advert: Protocol.PeerWire,
        link: BluetoothSocketLink,
        sighted: Boolean = true, // an initiator dials only a peer presence holds
        // The peer's device, for its doorbell and its address. [advert] is the peer's HELLO either way — the dialer's
        // on an accepted link, the reply on a dialed one — so its capabilities are full width and carry CAP_DOORBELL.
        device: BluetoothDevice? = null,
        // How long our dial took, for the field log; null for a link the peer dialed.
        dialMs: Long? = null,
        // Whether the peer proved the id its HELLO claims (ADR 2026-11.264w): the handshake's verdict, never a default.
        proven: Boolean,
        // When our dial began, for a link we dialed: a proven one keeps the peer's streak until the responder's first
        // record says it kept our proof ([ProvenDialStreak]). Null for a link the peer dialed.
        dialStartedAt: Long? = null,
        // The dial key our dial was booked under (DialKeys.dialKey); null for a link the peer dialed.
        dialKey: String? = null,
        // The form the peer advertises, when known here: the one we dialed, or an accepted dialer's honoured `t`.
        form: AdvertOrder.Form? = null,
    ): FramedLink {
        val events = LinkEvents(ProvenDialStreak(pending = proven && dialStartedAt != null), dialStartedAt ?: 0L, dialKey)
        val framed =
            FramedLink(
                nodeId = nodeId,
                peer = Peer(nodeId, advert.protoVersion, advert.capabilities),
                socket = link,
                scope = scope,
                cacheDir = appContext.cacheDir,
                partials = partials,
                metrics = metrics,
                callbacks = events,
                now = SystemClock::elapsedRealtime,
                pace = { paceFor(events.link) }, // set just below; the writer starts only at framed.start()
                log = { msg -> Log.d(TAG, msg) },
                landing = landing,
                onBusy = { boostPoke(events.link) },
                onFeedChanged = { feedChanges.tryEmit(Unit) },
                onRxProgress = { key, held, ms -> rxProgress.tryEmit(FileProgress(nodeId, key, held, ms)) },
                // A sender of ours windows only while our own switch is on: the lab's builds share it (ADR 2026-10.5buf).
                windowedReports = { LinkTuneDiag.feedWindow },
                onKeepalive = { onKeepaliveIn(events.link) },
                onKeepaliveWritten = { onKeepaliveWritten(events.link) },
                proven = proven,
                onFirstRecord = events::onFirstRecord,
                onTagNext = { bytes -> onTagNext(events.link, proven, advert, bytes) },
            )
        events.link = framed
        // Before framed.start(), so the peer's announce finds it. An iPhone's is exempt from the whole check (#203).
        val silence = LinkSilence(exempt = DoorbellPolicy.serves(advert.capabilities), uptime = SystemClock::uptimeMillis)
        silences[framed] = SilentLink(silence, link.stamps)
        crossings.forget(nodeId) // a fresh stream starts clean: the peer may have restarted with an empty SeenSet
        if (!sighted) neverSighted.add(framed)
        device?.let {
            linkAddresses[framed] = it.address
            linkDevices[framed] = it
            confirmGattPayload(it.address, nodeId)
        }
        (form ?: presence.snapshots(elapsed()).firstOrNull { it.nodeId == nodeId }?.form)?.let { linkForms[framed] = it }
        if (dialStartedAt != null) dialedByMe.add(framed) // the dial path passes it; the accept path never does
        linkStamps.onLinkEvent(nodeId, elapsed())
        val prev = links.put(nodeId, framed)
        // R3 on this plane: a proven link took the place of an unproven one claiming the id (the prev close below).
        val displaced = proven && prev != null && !prev.proven
        if (prev != null) {
            neverSighted.remove(prev)
            // Usually a different ACL from the replacement's (#142: handle 3 against 5), so its own may be left up.
            val prevAddress = linkAddresses.remove(prev)
            val prevDevice = linkDevices.remove(prev)
            if (prevAddress != null && prevDevice != null) orphans.onLinkEnded(prevAddress, nodeId, prevDevice, elapsed())
            linkPhy.remove(prev)
            lastPace.remove(prev)
            linkForms.remove(prev)
            dialedByMe.remove(prev)
            scope.launch(worker) {
                aliases.onLinkEnded(prev)
                tagNextSends.forget(prev)
                linkFormWaits.forget(prev)
            }
            phyControls.remove(prev)?.close()
            publishLinkPhys()
            silences.remove(prev)
            doorbells.remove(prev)?.close() // teardownLink(only = prev) will find it replaced and release nothing
            closeTuning(prev, sharedAcl = true)
            prev.close() // a stale link to the same peer — never leak it; its own end releases nothing now
            if (prev.linkStartedAt > 0) {
                field.record(FieldEvent.BtDown(nodeId, BtDownReason.REPLACED, elapsed() - prev.linkStartedAt, links.size - 1))
            }
        }
        device?.let { orphans.onLinkUp(it.address) } // after the replaced link's end: the same ACL is this link's now
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
                    onAttached = { orphans.onDoorbellAttached(device.address, elapsed()) },
                ).also { it.start() }
        } else if (device != null) {
            // An iPhone's doorbell lookup above attaches and so arms its ACL already (it reports that attach to [orphans]).
            // Not cancelled with the link: an attach that lands after the channel closed still lets the ACL go, so it is
            // live while the ACL is up.
            val address = device.address
            armAcl(device, nodeId, isLive = { links[nodeId] === framed || orphans.aclUp(address) })
        }
        ensurePhyControl(framed)
        codedPace.onLinkUp(nodeId)
        rangeHunt.onLinkUp(nodeId)
        coldSightings.end(nodeId, elapsed()) // a cold spell that ends in a link spends only the time it ran
        if (codedFast) schedulePace(AdvertReassertPolicy.SETTLE_MS) // its peer is back: slow again, after the settle
        synchronized(lock) {
            // Both the dial's key and the id it registered under: a dial ends either way (ADR 2026-11.xp6d).
            inFlight.remove(nodeId)
            dialKey?.let { inFlight.remove(it) }
        }
        // Success resets the peer's streak; a dialed proven link's only at the responder's first record (264w).
        if (!events.dialStreak.pending) resetStreak(nodeId, dialKey)
        metrics.onBtLinkEstablished()
        framed.start()
        announce(framed, silence)
        refreshNeighbors()
        publishReachable() // a live link ⇒ reachable, even for an inbound peer we never scan-sighted
        wake() // inFlight cleared + link count changed → resume connectLoop and re-evaluate scan demand
        advertEdge()
        field.record(FieldEvent.BtUp(nodeId, dialMs = dialMs, sighted = sighted, links = links.size))
        Log.i(TAG, "bt link up: $nodeId (${links.size} live)")
        // A link up while an announcement stands is told the reserved tag right after its proof (step 5b's W5).
        if (proven) scope.launch(worker) { announcedTag?.let { sendForm(framed, TagNextSends.Kind.ANNOUNCE, it) } }
        // Once the link is in the table and up, so this plane's own R3/R4 hook finds it proven and leaves it.
        if (proven) {
            claims.proven(nodeId, PLANE, displaced)
        } else if (claims.isProofCapable(nodeId)) {
            // [LinkClaims] tells each id once: a seed or profile that made it proof-capable after this link's last R1
            // check but before its `links.put` found nothing to close, and will not tell again (bluetooth-reviewer).
            scope.launch { closeClaimed(nodeId) }
        }
        return framed
    }

    /** Resets [nodeId]'s connect streak, and the dial key's a tag dial was booked under. */
    private fun resetStreak(
        nodeId: String,
        dialKey: String?,
    ) {
        synchronized(lock) {
            backoffs.remove(nodeId)
            dialKey?.let {
                backoffs.remove(it)
                DialKeys.addressKey(it)?.let(backoffs::remove)
                DialKeys.handleKey(it)?.let(backoffs::remove)
            }
        }
    }

    /**
     * A `TAG_NEXT` ([bytes], 16 exactly: `LinkFraming` refuses any other length) came in on [link], whose HELLO was
     * [advert] (ADR 2026-11.xp6d), on its reader. Only a [proven] link whose HELLO carried `CAP_BLIND_ADVERT` may
     * announce a tag: it goes to [AdvertAliases.onTagNext] on the worker, held pending until heard (one per link per two
     * minutes, the class's rule). Any other is dropped and counted. The tag is never logged.
     */
    private fun onTagNext(
        link: FramedLink,
        proven: Boolean,
        advert: Protocol.PeerWire,
        bytes: ByteArray,
    ) {
        if (!proven || !advert.readsTags) return metrics.onBleBlindDropped(BleBlindDrop.TAG_NEXT_UNPROVEN)
        val tag = NodeId.fromBytes(bytes)
        scope.launch(worker) {
            if (!BlindSightings.current(links[link.nodeId], link)) return@launch
            // Rate-limited, own, contested, voided, contradicted: nothing noted, and nothing scheduled.
            noteLinkForm(link, tag, aliases.onTagNext(link, link.nodeId, tag))
        }
    }

    /**
     * A peer's accepted announcement ([tag]) becomes [link]'s form when it reaches its edge (a resend's at once), as a
     * sighting would make it: both ends then judge `dialsBackAfterDrop` on the same pair of forms, which a settled pair's
     * sparse sightings at range would leave apart (the bluetooth design pass). Only a record the resolver accepted
     * ([outcome] Pending or Redundant), one note and one wait per link ([linkFormWaits]): a later record replaces both, so a
     * fork flooding records costs one coroutine. At the edge the tag must still stand for the link
     * ([AdvertAliases.stillAnnounced]). On the worker; the wait is elapsed-aware on the wall clock.
     */
    private fun noteLinkForm(
        link: FramedLink,
        tag: String,
        outcome: AdvertAliases.ClaimOutcome,
    ) {
        val form = AdvertOrder.Form(tag, blinded = true)
        linkFormWaits.offer(
            link,
            tag,
            outcome,
            stands = { links[link.nodeId] === link && aliases.stillAnnounced(link, link.nodeId, tag) },
            apply = { if (links[link.nodeId] === link) linkForms[link] = form },
        )
    }

    /**
     * R3/R4 (ADR 2026-11.264w): [nodeId] can prove itself now — its profile gained `CAP_LINK_PROOF`, or it proved itself
     * on a link — so the unproven link held claiming it is closed, through the ordinary teardown of that link only. Our
     * own close: it bumps no streak ([closedForClaim]), as the id's streak is the real peer's. A proven link stays.
     */
    private fun closeClaimed(nodeId: String) {
        val fl = links[nodeId]?.takeIf { !it.proven } ?: return
        closedForClaim.add(fl)
        if (!teardownLink(nodeId, "claim", only = fl)) return
        claims.claimClosed(nodeId, PLANE)
        wake() // the connect loop may dial the real peer now
    }

    /**
     * Closes [nodeId]'s link — only if it is still [only], when given — and says whether one was released. With
     * [closeLater] (a silent link's, #203) the socket is closed after the neighbour set and `reachable` have moved, on an
     * IO coroutine nothing waits for: a stack that lost the connection may not return from `close()` (2ec2eb5b).
     */
    private fun teardownLink(
        nodeId: String,
        reason: String,
        only: FramedLink? = null,
        closeLater: Boolean = false,
    ): Boolean {
        val fl = (if (only == null) links.remove(nodeId) else only.takeIf { links.remove(nodeId, it) }) ?: return false
        if (links.isEmpty()) noLinkSince = elapsed() // the lonely dial's clock runs from the last link's end (hj4a)
        linkStamps.onLinkEvent(nodeId, elapsed())
        val form = linkForms.remove(fl)
        dialedByMe.remove(fl)
        scope.launch(worker) {
            aliases.onLinkEnded(fl)
            tagNextSends.forget(fl)
            linkFormWaits.forget(fl)
        }
        neverSighted.remove(fl)
        val address = linkAddresses.remove(fl)
        val device = linkDevices.remove(fl)
        if (address != null && device != null) orphans.onLinkEnded(address, nodeId, device, elapsed()) // its ACL may stay (#142)
        // A link that counted as Aware evidence (on 1M/2M) leaves its peer heard from its end, not from an old sighting.
        awareCandidates.onLinkEnded(nodeId, counted = linkPhyNear(linkPhy.remove(fl)), elapsed())
        lastPace.remove(fl)
        val dropStatus =
            phyControls.remove(fl)?.let { control ->
                control.status().also { notePhyDrop(it, reason, form) }.also { control.close() }
            }
        publishLinkPhys()
        // A link torn down before it started never recorded its up: leave its down out too.
        if (fl.linkStartedAt > 0) {
            field.record(
                FieldEvent.BtDown(
                    nodeId,
                    BtDownReason.of(reason),
                    lifetimeMs = elapsed() - fl.linkStartedAt,
                    links = links.size,
                    phy = dropStatus?.phy,
                    rssi = dropStatus?.linkRssi,
                ),
            )
        }
        silences.remove(fl)
        doorbells.remove(fl)?.close()
        closeTuning(fl, sharedAcl = false)
        if (!closeLater) fl.close()
        crossings.forget(nodeId)
        // The peer was here until now: its side-channel flag lingers from the link's end, not from a sighting the
        // floored scan may have made hours ago — a dropped or evicted flagged peer is exactly whom a page reaches.
        sideCapable.touch(nodeId, elapsed())
        refreshNeighbors()
        publishReachable() // drop the peer from reachable too, unless it's still being scan-sighted
        wakeSide() // the audience may have gone unlinked (the eviction path has no other side wake)
        advertEdge()
        Log.i(TAG, "bt link down: $nodeId ($reason)")
        if (closeLater) closeOffThread(fl)
        return true
    }

    /**
     * Closes [fl] on an IO coroutine nothing waits for, and logs `bt link close slow` if it has not returned within
     * [CLOSE_SLOW_MS] (#203): a writer sitting in a write the stack will never finish holds nothing up but its own thread.
     */
    private fun closeOffThread(fl: FramedLink) {
        val closing = scope.launch(Dispatchers.IO) { fl.close() }
        scope.launch {
            if (elapsedWait.runWithin(CLOSE_SLOW_MS) { closing.join() } == null) Log.w(TAG, "bt link close slow ${fl.nodeId}")
        }
    }

    // --- Silent links (#203, ADR 2026-10.697m) ---

    /**
     * One loop over every link's [LinkSilence]: each is stepped, then the loop sleeps on the elapsed clock until the
     * earliest is due, or until [silenceWake]. No tick per link and no poke per byte: a byte in only moves a link's due
     * time later, which the next look reads. With no link to judge, it waits for one.
     */
    private suspend fun silenceLoop() {
        while (scope.isActive) {
            val now = elapsed()
            var due: Long? = null
            silences.entries.toList().forEach { (link, s) ->
                if (links[link.nodeId] !== link) return@forEach
                val rxAt = s.stamps.lastByteAt
                val blocked = s.stamps.writeBlockedSince
                val local = localReading(link, rxAt)
                when (val action = s.policy.step(now, rxAt, blocked, local)) {
                    is LinkSilence.Action.Probe -> {
                        sendProbe(link, action, now - rxAt, action.via.name.lowercase())
                    }

                    is LinkSilence.Action.Close -> {
                        closeSilent(link, action, s.policy.answers)
                    }

                    LinkSilence.Action.None -> {}
                }
                if (links[link.nodeId] === link) {
                    s.policy.msUntilDue(now, rxAt, blocked, local)?.let { ms -> due = minOf(due ?: ms, ms) }
                }
            }
            val wait = due
            if (wait == null) silenceWake.receive() else elapsedWait.receiveWithin(silenceWake, wait.coerceAtLeast(SILENCE_MIN_LOOK_MS))
        }
    }

    /** The announce (#203): no shipped build sends a `KEEPALIVE`, so the peer's tells this side it answers one. */
    private fun announce(
        link: FramedLink,
        silence: LinkSilence,
    ) {
        if (silence.announce()) link.sendKeepalive()
        silenceWake.trySend(Unit)
    }

    /**
     * This side just queued a [what] (`digest`, `frame` or `file`) to [link]: a due first probe rides right behind it, so a
     * dozing peer that record wakes answers in the same wake (#203). Off every lock; the policy decides at once.
     */
    private fun carryProbe(
        link: FramedLink,
        what: String,
    ) {
        val s = silences[link] ?: return
        val now = elapsed()
        val rxAt = s.stamps.lastByteAt
        val action = s.policy.carry(now, rxAt)
        if (action is LinkSilence.Action.Probe) sendProbe(link, action, now - rxAt, what)
    }

    /** Queues [probe] on [link], [quietMs] after its last byte in, logged with what sent it ([via]). */
    private fun sendProbe(
        link: FramedLink,
        probe: LinkSilence.Action.Probe,
        quietMs: Long,
        via: String,
    ) {
        Log.i(TAG, "bt link probe ${link.nodeId} #${probe.n} (quiet=${quietMs}ms) via=$via")
        link.sendKeepalive()
        silenceWake.trySend(Unit)
    }

    /** What [link]'s PHY handle says since its last byte in at [rxAt] (#203); null for a link with none. */
    private fun localReading(
        link: FramedLink,
        rxAt: Long,
    ): LinkSilence.Local? =
        phyControls[link]?.let { control ->
            // A loss before a byte that came in after it was not this link's connection ending.
            LinkSilence.Local(lost = control.lostAt?.let { it >= rxAt } == true)
        }

    /** A `KEEPALIVE` came in on [link]: answered when its policy says so (#203). On the link's reader. */
    private fun onKeepaliveIn(link: FramedLink) {
        val s = silences[link] ?: return
        val first = !s.policy.answers
        if (s.policy.onKeepaliveIn()) link.sendKeepalive()
        if (first && s.policy.answers) Log.i(TAG, "bt link answers ${link.nodeId}")
        silenceWake.trySend(Unit) // the link's probes may be due from here on
    }

    /** One of our keepalives on [link] went to the socket: a probe's answer is timed from here. On the link's writer. */
    private fun onKeepaliveWritten(link: FramedLink) {
        silences[link]?.policy?.onKeepaliveWritten(elapsed()) ?: return
        silenceWake.trySend(Unit)
    }

    /**
     * The stack hinted that [link]'s connection is gone ([what]: an LE ACL disconnect for its address, or its PHY client's
     * loss): logged, and a link whose peer answers is probed at once. Never closed on the edge alone — it names no handle,
     * and #142 saw two ACLs to one peer. Cheap: the ACL receiver runs on the main thread.
     */
    private fun onLinkEdge(
        link: FramedLink,
        what: String,
    ) {
        val s = silences[link] ?: return
        if (links[link.nodeId] !== link || !s.policy.onEdge(elapsed())) return
        Log.i(TAG, "bt link edge ${link.nodeId} $what (answers=${s.policy.answers})")
        silenceWake.trySend(Unit)
    }

    /**
     * Closes [link] for silence through the ordinary teardown (`only = link`), the neighbour set and `reachable` moved
     * before its socket closes. A close on this phone's own controller's word is remembered by address for
     * `bt stack suspect` ([checkOrphans]).
     */
    private fun closeSilent(
        link: FramedLink,
        close: LinkSilence.Action.Close,
        answers: Boolean,
    ) {
        val nodeId = link.nodeId
        if (links[nodeId] !== link) return
        closedSilent.add(link)
        val address = linkAddresses[link]
        Log.i(TAG, "bt link silent $nodeId (${close.why.wire} quiet=${close.quietMs}ms answers=$answers)")
        if (!teardownLink(nodeId, "silent", only = link, closeLater = true)) return
        wake() // as the eof path does: the connect loop may redial now, and the scan and side scan re-read their demand
        if (close.why.local && address != null) localCloses[address] = LocalClose(nodeId, close.why, elapsed())
    }

    /**
     * Books a dial under [dialKey] ([DialKeys.dialKey]) as [record], unless that key is linked, in flight or backing off,
     * or another dial to the same peer ([DialPlan.InFlight.peerKey]) is in flight.
     */
    private fun beginConnect(
        dialKey: String,
        record: DialPlan.InFlight,
    ): Boolean {
        val begun =
            synchronized(lock) {
                if (!ConnectBackoffs.mayBegin(backoffs, dialKey, links.keys, inFlight.keys, elapsed())) return false
                if (inFlight.values.any { it.peerKey == record.peerKey }) return false
                inFlight[dialKey] = record
                true
            }
        if (begun) {
            wakeSide() // the side scan stops for the connect window (scanning starves connects)
            dialStarted.trySend(Unit) // and so does a hunting presence window
        }
        return begun
    }

    /**
     * A dial booked under [dialKey] failed: its streak advances on that key (a tag dial's is the tag's and address's,
     * never the id a reply names), unless its peer — [peer], or the per-peer key the dial was attributed to — is linked
     * now.
     */
    @Suppress("LongParameterList") // one failure's facts
    private fun failConnect(
        dialKey: String,
        reason: ConnectFailReason,
        startedAt: Long,
        cause: Throwable,
        device: BluetoothDevice? = null,
        // False for a link's end counted as a failed dial (ADR 2026-11.264w): its reader or writer reports it after the
        // teardown freed the id, so the in-flight slot may be a newer dial's by then, and only that dial may release it.
        releaseSlot: Boolean = true,
        // The proven id of a link whose end counts as a failed dial; null reads the in-flight record.
        peer: String? = null,
    ) {
        // A dial that failed before its channel opened may have used a PSM the peer no longer listens on (an iPhone
        // that restarted), so its GATT payload is read again at the next advert. A HELLO refusal says nothing of the
        // PSM (GattPayloads, companion change A3).
        if (gattPeers && device != null && DialPlan.forgetsGattPayload(reason)) forgetGattPayload(device.address, "dial")
        var shown = peer ?: dialKey
        val (streak, nextAt) =
            synchronized(lock) {
                val record = if (releaseSlot) inFlight.remove(dialKey) else null
                if (peer == null && record != null) shown = record.peerKey
                // A dial the peer refused because its own dial to us linked first (a lonely dial crossing the
                // ordinary one, ADR 2026-09.hj4a) is no failure to back off from: that link reset the streak already.
                // A link's end (releaseSlot false) reads no record: [peer] is its id.
                val linkedNow = DialKeys.linkedNow(record, peer ?: dialKey, links.keys)
                // A dial to an unresolved form advances its address's streak and its handle's too (DialKeys.addressKey,
                // handleKey): a new tag at one address, or one tag on its 1M and Coded sets' two addresses, still backs off.
                val unresolved = peer == null && record?.peerKey?.let(FormKeys::isForm) == true
                listOfNotNull(DialKeys.addressKey(dialKey, unresolved), DialKeys.handleKey(dialKey, unresolved)).forEach {
                    ConnectBackoffs.onDialFailed(backoffs, it, linkedNow = linkedNow, now = elapsed(), config = backoffConfig)
                }
                ConnectBackoffs.onDialFailed(backoffs, dialKey, linkedNow = linkedNow, now = elapsed(), config = backoffConfig)
            }
        metrics.onBtConnectFailed(reason)
        field.record(FieldEvent.BtFail(BtLines.fieldPeer(shown), reason, durationMs = elapsed() - startedAt, streak = streak))
        wake()
        // Log the real exception (previously swallowed) + A2DP state, so the intermittent failure is diagnosable.
        val dur = elapsed() - startedAt
        Log.w(TAG, BtLines.connectFailed(shown, reason, dur, audioMonitor.state.value, links.size, streak, nextAt - elapsed()), cause)
    }

    /**
     * Forgets [address]'s GATT payload and any wait before its next read, so its next advert reads it afresh (the
     * contract's payload lifetime). Logged only when a payload went: `bt gatt forget <addr> (dial|hello <id>|quiet)`.
     * Nothing here touches the read budget (#154): the re-read it makes due is charged when it ends.
     */
    private fun forgetGattPayload(
        address: String,
        reason: String,
    ) {
        if (gattPayloads.payload(of = address) != null) Log.i(TAG, "bt gatt forget $address ($reason)")
        gattPayloads.forget(address)
    }

    /**
     * A link's HELLO at [address] named [nodeId], so a payload read there naming that node is vouched for: the read's
     * budget charge is refunded, once (#154). The payload is unauthenticated, and only a link vouches for it.
     */
    private fun confirmGattPayload(
        address: String,
        nodeId: String,
    ) {
        if (gattPeers && gattPayloads.confirmed(address, nodeId, elapsed())) Log.d(TAG, "bt gatt confirmed $address → $nodeId")
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
    private fun bumpBackoffLocked(nodeId: String): Pair<Int, Long> = ConnectBackoffs.bump(backoffs, nodeId, elapsed(), backoffConfig)

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
                            orphans.clear() // every ACL went with the adapter, and its edges may never have come
                            bringUp()
                            wake()
                        }
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "bluetooth adapter off")
                        _health.value = TransportHealth.Unavailable // Bluetooth switched off (or airplane mode)
                        newcomersOff("adapter off") // at once, not at the window's end (GitHub #44)
                        dropBlindSightings() // at once: a sighting queued before the off cannot put a phantom back
                        scope.launch {
                            links.keys.toList().forEach { teardownLink(it, "adapter off") }
                            orphans.clear() // after the links' ends: no ACL outlives the adapter
                            tearDownRadio()
                            cancelGattRead()
                            presence.clear()
                            deviceFor.clear()
                            gattSighted.clear()
                            codedDeviceFor.clear()
                            sideCapable.clear()
                            awareCandidates.clear()
                            synchronized(lock) {
                                inFlight.clear()
                                backoffs.clear()
                            }
                            _reachable.value = emptySet()
                            _near.value = emptySet()
                            _awareCandidatesNear.value = emptySet()
                            _awareCandidatesHeard.value = emptySet()
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
    // transport any other way. The receiver marks the edge for [advertLoop], and tells [orphans] which LE connections
    // are up (#142): the one oracle for an ACL a closed channel left behind, since `getConnectedDevices(GATT)` shows
    // another device's connection only while some app's GATT server is registered. Runs on the main thread.
    private val aclEdgeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val action = intent.action
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                // The transport rides the edge from API 33; below, every edge counts (a mesh link's address is an RPA, never
                // a BR/EDR device's).
                val transport =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
                    } else {
                        BluetoothDevice.TRANSPORT_AUTO
                    }
                val up = action == BluetoothDevice.ACTION_ACL_CONNECTED
                if (device != null && transport != BluetoothDevice.TRANSPORT_BREDR) {
                    orphans.onAcl(device.address, up, elapsed())
                    // A live link's own address went down: probe it (#203), never close on this alone.
                    if (!up) linkAddresses.filterValues { it == device.address }.keys.forEach { onLinkEdge(it, "acl down") }
                }
                Log.d(
                    TAG,
                    "bt acl edge ${action?.substringAfterLast('.')} ${device?.address}" +
                        if (transport == BluetoothDevice.TRANSPORT_BREDR) " bredr" else "",
                )
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

    // The wall clock jumped (ADR 2026-11.xp6d): the expected-tag window slides on the worker, so a big jump's re-list
    // runs there and not inside a scan result's resolve. A time-zone change moves no slot (slots are UTC). Main thread.
    private val timeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                scope.launch(worker) { aliases.slide() }
                rotationWake.trySend(Unit) // the slot turn's edge moved with the clock
            }
        }

    /**
     * Subscribes to `ACTION_TIME_CHANGED`, guarded and degrading like [registerAvailability] (5da56015): a refusal leaves
     * the window to slide at the next resolve, which every entry point of [aliases] does first. Not exported: the
     * broadcast comes from `system_server`.
     */
    private fun registerTimeChanges() {
        if (timeRegistered) return
        timeRegistered =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    timeReceiver,
                    IntentFilter(Intent.ACTION_TIME_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "time-change receiver registration failed", it) }.isSuccess
    }

    private fun unregisterTimeChanges() {
        if (!timeRegistered) return
        runCatching { appContext.unregisterReceiver(timeReceiver) }
        timeRegistered = false
    }

    /**
     * A new radio generation (the adapter went off, or the transport stopped): every blinded sighting queued or being
     * resolved in an older one is dropped at the worker, and the form handles go with presence.
     */
    private fun dropBlindSightings() {
        radioGeneration.incrementAndGet()
        blindQueue.clear()
        formKeys.clear()
        handleFirstSeen.clear()
    }

    // --- Helpers ---

    private fun elapsed() = SystemClock.elapsedRealtime()

    /**
     * How long this node has held no link at all; 0 while one is held. The clock of the scan's lonely cadence (ADR
     * 2026-09.w3xk) and of the lonely dial (ADR 2026-09.hj4a).
     */
    private fun aloneForMs(): Long = if (links.isEmpty()) elapsed() - noLinkSince else 0L

    /** The ids holding a proven link now: what a key's answer suppresses a dial on ([DialPlan.suppressed]). */
    private fun provenLinks(): Set<String> = links.entries.mapNotNullTo(HashSet()) { (id, link) -> id.takeIf { link.proven } }

    /** The dial keys in flight. */
    private fun inFlightSnapshot(): Set<String> = synchronized(lock) { inFlight.keys.toSet() }

    /** The per-peer keys a dial in flight was attributed to when it began. */
    private fun inFlightPeers(): Set<String> = synchronized(lock) { inFlight.values.mapTo(HashSet()) { it.peerKey } }

    /**
     * Read-only view of the per-peer keys of [snaps] in connect backoff, by dial key or by id ([DialKeys.backedOff]; no
     * [driveConnections] prune side effect).
     */
    private fun activeBackoff(
        now: Long,
        snaps: List<BlePresenceTracker.Snapshot> = presence.snapshots(now),
    ): Set<String> = DialKeys.backedOff(snaps, synchronized(lock) { ConnectBackoffs.active(backoffs, now) }, ::dialKeyOf)

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
            // stretches: the side channel's tick (pj9w).
            withTimeoutOrNull(SIDE_TICK_MS) { sideWake.receive() }
        }
    }

    /**
     * Publish [reachable] as presence ∪ live links, so a peer we hold a link to stays "nearby" even when the
     * throttled scan hasn't re-sighted it within the presence linger (the reachable ⊇ neighbors invariant the
     * floor depends on). [near] is published beside it from the same snapshots ([ScanPlan.near]): one
     * [BlePresenceTracker.snapshots] call, since it prunes, so the two sets never read different presences. No
     * freshness gate of its own on the wall clock: nothing would republish when one expired, so a stale hit leaves on
     * the next publish, as it leaves [reachable]. Applies to [_reachable] and [_near] only — never [_neighbors], which
     * routes sends.
     */
    private fun publishReachable(now: Long = elapsed()) {
        synchronized(publishLock) {
            val snaps = presence.snapshots(now)
            val live = links.values.toList()
            // The Aware candidates (GitHub #44) from the same snapshots: a link counts only on a PHY read back as 1M/2M.
            val facts =
                live.associate {
                    it.nodeId to AwareCandidateTracker.LinkFacts(linkPhyNear(linkPhy[it]), DoorbellPolicy.serves(it.peer.capabilities))
                }
            // Reachable and near carry resolved and linked peers only; the Aware sets take unresolved forms too (xp6d).
            val published = ScanPlan.published(snaps, live.map { it.peer }) { awareCandidates.sets(now, it, facts) }
            _reachable.value = published.reachable
            _near.value = published.near
            val sets = published.aware
            _awareCandidatesNear.value = sets.near
            _awareCandidatesHeard.value = sets.heard
            // Log.d: a peer on the −90 line can move near once a burst; a debug build is what the trials run.
            if (sets != awareLogged) {
                awareLogged = sets
                Log.d(TAG, "bt aware candidates near=${sets.near} heard=${sets.heard}")
            }
        }
    }

    // A link's PHY as Aware evidence: read back as 1M or 2M. Coded, or no readback at all, is not (GitHub #44).
    private fun linkPhyNear(phy: LinkPhy?): Boolean = phy == LinkPhy.ONE_M || phy == LinkPhy.TWO_M

    private fun localNodeIdOrEmpty(): String = if (::localNodeId.isInitialized) localNodeId else ""

    // --- Diagnostics ---

    private suspend fun diagLoop() {
        while (scope.isActive) {
            // stretches: the `bt state` line, whose stretch is the measure of how much a phone sleeps (pj9w).
            delay(DIAG_INTERVAL_MS)
            audioMonitor.refresh() // re-evaluate audio vs live AudioManager state (the playing edge can be missed)
            // R8 strips the Log.d in release, not the string this builds under the lock: debug only.
            if (BuildConfig.DEBUG) logState()
            checkOrphans() // every build: the net under the ACL arm (#142)
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
                    "dials=${snap?.let { AdvertOrder.iDial(mine().current, it.form) } ?: (localNodeId > id)} backoff=${backoffS}s",
            )
        }
    }

    /** Periodic one-line state dump (mirrors WifiAwareTransport's), so a device session is greppable end-to-end. */
    private fun logState() {
        val now = elapsed()
        val rssiByNode = presence.snapshots(now).associate { it.nodeId to it.smoothedRssi }
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
                "alone=${aloneForMs()}ms psm=${if (writer.form()?.blinded == true) "rotating" else writer.psm()} advert=$presenceAdvert " +
                "doorbells=${doorbells.size} rings=${rings.get()}" +
                (if (tuners.isNotEmpty()) " tuners=${tuners.size}" else "") +
                " arms=${aclArmsAttached.get()}/${aclArms.get()} orphans=${orphans.watching} skipped=${orphans.armsSkipped} " +
                "released=${orphans.released} reported=${orphans.reported}" +
                (
                    if (boosts.isNotEmpty()) {
                        " boosts=${boosts.size} boosting=${boosts.values.count {
                            it.boosting
                        }} earned=${boosts.values.count { it.earned }}"
                    } else {
                        ""
                    }
                ) +
                (if (gattPeers) gattDiag() else "") +
                (if (codedOn()) " phy=${codedMode.wire} coded=$codedAdvert phyLinks=${phyControls.size} hunt=$hunting" else "") +
                (if (codedOn()) fadeDiag() else "") +
                sideChannel?.let { " ${it.diag()} $sideDecision" }.orEmpty() +
                " linkScores=${links.values.joinToString(",") { "${it.nodeId}:${linkScore(it, rssiByNode).toInt()}" }}" +
                // ble-link-trial.py reads `links=` then `reach=` at its head; near= and the fields after it carry bare ids.
                " near=${_near.value.map { it.nodeId }}" +
                // After near=, bare ids and booleans only: knit-ios interop.py reads every `<id>:<number>` past linkScores=.
                " awareNear=${_awareCandidatesNear.value} awareHeard=${_awareCandidatesHeard.value} aware=$awareAdvertised " +
                "newcomers=${newcomers.sees.value}" +
                // A debug build's scan hold (#285): bare seconds left, no id, so interop.py's `<id>:<number>` read is untouched.
                scanHold.remainingMs(now)?.let { " scanHold=${it / MS_PER_S}s" }.orEmpty(),
        )
    }

    /** The links whose PHY handle calls them fading ([LinkFade]), for the `bt state` line; empty when none is. */
    private fun fadeDiag(): String {
        val fading = phyControls.filter { (_, control) -> control.fading() }.map { (link, _) -> link.nodeId }
        return if (fading.isEmpty()) "" else " fade=$fading"
    }

    /** The GATT payload reads' part of the `bt state` line; `gattPayloads=` / `gattReads=` are read by the iOS harness. */
    private fun gattDiag(): String =
        " gattPayloads=${gattPayloads.payloadCount} gattReads=${gattReads.get()} " +
            "gattBudget=${gattPayloads.budgetUsed(elapsed())}/${gattPayloads.configuration.budgetReads}"

    /**
     * Why [armAcl] attaches: a link-up arm, an unlinked channel's arm `OrphanAclPolicy.tryArm` let begin (both recorded
     * by [orphans]), the stop's last arm, or an orphan's release.
     */
    private enum class AclArm { ARM, UNLINKED, STOP, RELEASE }

    companion object {
        const val TAG = "BluetoothMeshTransport"

        /** This plane's name among the field log's samplers. */
        private const val FIELD_SAMPLER = "bluetooth"

        /** The strongest sighted peers a field-log sample keeps: enough for a walk's partner and its neighbours. */
        private const val FIELD_SAMPLE_PEERS = 4

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

        // A retired L2CAP server's close comes this long after its grace (PsmRotation.GRACE_MS), so the due check finds it.
        private const val SERVER_GRACE_SLACK_MS = 1_000L

        // `…debug.ADVERT`'s wait for a stored switch to reach the air: an ACL edge's 2.5 s settle, then a rotation's
        // install (throwaway servers, a durable mark write), with margin; looked at every 200 ms of the elapsed clock.
        private const val ADVERT_SWITCH_WAIT_MS = 6_000L
        private const val ADVERT_SWITCH_POLL_MS = 200L

        // A chase ends early on a newly sighted handle at most this often (step 5b's W6): a stationary blinded stranger
        // gets a new handle every rotation, and must not end every chase.
        private const val CHASE_EARLY_END_COOLDOWN_MS = 300_000L

        // The first-sighting stamps kept for unresolved handles: about the handle table's own bound.
        private const val HANDLE_FIRST_SEEN_MAX = 1_024

        // A Coded start the stack failed internally (ADVERTISE_FAILED_INTERNAL_ERROR) is retried this many times per
        // bring-up, on [codedKeeper]'s doubling wait; [CODED_RETRYING] marks a retry in flight, so its failure is quiet.
        private const val MAX_CODED_START_RETRIES = 5

        // A link that earned the boost this recently has a peer that asks for it: a file to it will earn it again.
        private const val EARNED_RECENTLY_MS = 5 * 60_000L

        // Receive-side progress reports buffered for BlobExchange: one per incoming file every couple of seconds.
        private const val PROGRESS_BUFFER = 16

        // How often a feed recounts this phone's LE connections for the boosted budget ([leLinkCount]).
        private const val LE_COUNT_MS = 10_000L

        // How long a channel that never became a link waits before its ACL is armed and watched ([armUnlinked]): the
        // ACL's connect edge comes from the Bluetooth app's process and can trail the channel.
        private const val UNLINKED_SETTLE_MS = 2_000L

        // A silent link's close (#203) that has not returned by now is logged `bt link close slow`: it holds nothing up.
        private const val CLOSE_SLOW_MS = 5_000L

        // The silent-link loop's shortest wait: a link due now was just stepped, and the next look is never a busy loop.
        private const val SILENCE_MIN_LOOK_MS = 1_000L

        // How long a close on local evidence waits for its ACL's orphan report before `bt stack suspect` forgets it: past
        // the arm's two releases on the minute's net, stretched by a sleeping CPU.
        private const val LOCAL_CLOSE_KEEP_MS = 30 * 60_000L

        // The node id an unlinked arm and its watch go by when the channel closed before its HELLO came.
        private const val NO_HELLO = "(no hello)"

        // How many times an unlinked arm refused for a recent failure or a full set of slots waits and asks again.
        private const val UNLINKED_RETRIES = 2
        private const val CODED_DARK_INTERNAL = "dark ${AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR}"
        private const val CODED_RETRYING = "retrying"

        // Bound the responder's HELLO read (BluetoothSocket has no soTimeout) — close the socket if it stalls.
        private const val ACCEPT_HELLO_TIMEOUT_MS = 5_000L

        // This plane's name on [LinkClaims]' counters' log lines (ADR 2026-11.264w).
        private const val PLANE = "bt"

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

        // What a Coded sighting's slot in [blindQueue] adds to its tag (never part of a 26-char form).
        private const val CODED_SLOT = "/coded"

        // `ScanResult.timestampNanos` to the elapsed clock's milliseconds.
        private const val NANOS_PER_MS = 1_000_000L

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
