package app.getknit.knit.di

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.room3.withWriteTransaction
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.badge.BadgeIssuer
import app.getknit.knit.badge.BadgeKeys
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.AwareSwitch
import app.getknit.knit.data.settings.BadgeStore
import app.getknit.knit.data.settings.DirectHostStore
import app.getknit.knit.data.settings.HomeStore
import app.getknit.knit.data.settings.LoraPlaneStateStore
import app.getknit.knit.data.settings.LowPowerSwitch
import app.getknit.knit.data.settings.NanJournalStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.device.DeviceImage
import app.getknit.knit.fieldlog.FIELD_LOG_SHARE_DIR
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldJournal
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldLogExport
import app.getknit.knit.fieldlog.batteryEdges
import app.getknit.knit.fieldlog.netEdges
import app.getknit.knit.fieldlog.readBattery
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BridgeFrameSource
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.FarPeerFrameSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPostSink
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.NOT_CONTENDED
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.ProfileFrameSource
import app.getknit.knit.mesh.PublicChannelSink
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.SwitchableTransport
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BleSideChannel
import app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.meshtastic.BondedBoardDirectory
import app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt
import app.getknit.knit.mesh.bluetooth.wear.WearStatusServer
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.SessionTransactor
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.lora.MeshtasticGattDialer
import app.getknit.knit.mesh.lora.MeshtasticLink
import app.getknit.knit.mesh.lora.MeshtasticSession
import app.getknit.knit.mesh.lora.fieldSample
import app.getknit.knit.mesh.lora.loraFieldEdges
import app.getknit.knit.mesh.meshExceptionHandler
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.power.BatterySaverState
import app.getknit.knit.mesh.power.LowPowerSource
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.power.awareSwitchedOff
import app.getknit.knit.mesh.power.awareWontRun
import app.getknit.knit.mesh.spool.AccountIssuer
import app.getknit.knit.mesh.spool.FreeIssuers
import app.getknit.knit.mesh.spool.OkHttpSpoolDialer
import app.getknit.knit.mesh.spool.OkHttpTicketIssuer
import app.getknit.knit.mesh.spool.SpoolDialer
import app.getknit.knit.mesh.spool.TicketIssuer
import app.getknit.knit.mesh.wear.WearStatusSource
import app.getknit.knit.mesh.wifiaware.WifiAwareTransport
import app.getknit.knit.moderation.modelGuardStamp
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.AndroidPublicSpoolDirectory
import app.getknit.knit.net.InternetGate
import app.getknit.knit.transfer.AndroidDirectWifi
import app.getknit.knit.transfer.AndroidTransferFiles
import app.getknit.knit.transfer.DirectHostGuard
import app.getknit.knit.transfer.DirectHostJournal
import app.getknit.knit.transfer.DirectWifi
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.transfer.TransferFiles
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferSignals
import app.getknit.knit.ui.chat.notificationLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/** Qualifier for the single shared ratchet [Mutex] (DM + group session services). */
private val ratchetMutex = named("ratchetMutex")

val meshModule =
    module {
        // Application-lifetime scope for the mesh engine. The shared exception handler is the process-level
        // backstop for an uncaught throw in a top-level child (e.g. a FramedLink writer coroutine).
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default + meshExceptionHandler) }
        single { MeshMetrics() }
        // What this phone does for other people's messages (the Your mesh screen's lifetime numbers): credited
        // at the router's relay fan-out and the custody re-serve, persisted through SettingsStore's journal slice.
        single { ContributionLedger(journal = get<SettingsStore>(), selfId = { get<Identity>().nodeId() }) }
        // Whether a MeshService.start the system refused is still owed a retry (work item #32).
        single { MeshStartGate() }
        // Content digest of this node's syncable state; shared between the forward-store impl (maintains the
        // message set), MeshManager (folds in profile changes), and WifiAwareTransport (cues it to neighbors) —
        // a singleton so none has to construct the other (MeshManager already depends on the transport).
        single { StoreDigest() }
        // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
        single { PowerStateSource() }
        // Whether Knit's UI is on screen (KnitApplication attaches it to the process lifecycle); the BLE scan hunts
        // harder while Knit is in use.
        single { AppVisibility() }
        // Lets the Meshtastic board GATT dial pause the mesh BLE scan for its connect window (shared singleton).
        single { BleConnectArbiter() }
        // Low-power mode's switch, and Battery Saver, which PowerMonitor writes and only low-power mode reads (ADR
        // 2026-10.d44b).
        single { LowPowerSwitch(get()) }
        // The person's Settings › Wi-Fi Aware switch (GitHub #44). Its one-time move from low-power mode is written at
        // the first resolve; until it lands, the switch already reads that answer, so nothing attaches only to stop.
        single { AwareSwitch(get()).also { switch -> get<CoroutineScope>().launch { runCatching { switch.migrateFromLowPower() } } } }
        // Built with the platform's answer, so the posture's first reading on a Battery Saver phone is already on.
        single { BatterySaverState(androidContext().getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) }
        single { PowerMonitor(androidContext(), get(), get()) }
        // Low-power mode's one reading: the user's switch or Battery Saver, lifted on the charger (ADR 2026-10.d44b).
        // SwitchableTransport switches Wi-Fi Aware off on it and MeshService drops the motion heal.
        single {
            LowPowerSource(
                switchOn = get<LowPowerSwitch>().on,
                batterySaver = get<BatterySaverState>().on,
                power = get<PowerStateSource>().state,
                scope = get<CoroutineScope>(),
                log = { msg -> Log.i("LowPower", msg) },
            )
        }
        // The field log (ADR 2026-10.a239): how this phone's radios behaved, kept under noBackupFilesDir and handed
        // over only by the user from Diagnostics. MeshManager drives its session half; the radios record into it.
        single {
            FieldLog(
                journal = FieldJournal(File(androidContext().noBackupFilesDir, FieldJournal.DIR)),
                metrics = get<MeshMetrics>()::snapshot,
                power = get<PowerStateSource>().state,
                enabled = get<SettingsStore>().fieldLogEnabled,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                staged = File(androidContext().cacheDir, FIELD_LOG_SHARE_DIR),
                lowPower = get<LowPowerSource>().posture,
            ).also { log ->
                if (BuildConfig.LORA_PLANE) {
                    // Resolved at each sample, never here: the plane's status is built from the transport, which this precedes.
                    log.sampler("lora") { get<LoraPlaneStatus>().status.value.fieldSample() }
                    // Its link edges, resolved at each session's start, on the board session's clock: a retry's due time is on it.
                    log.follower("lora") { loraFieldEdges(get<LoraPlaneStatus>().status, SystemClock::elapsedRealtime) }
                }
                // The Internet route every spool line reads against, followed for each mesh session.
                log.follower("net") { netEdges(get<InternetGate>()) }
                // The person's Wi-Fi Aware switch (GitHub #44): with it off, the Aware lines stop on purpose.
                log.follower("aware_switch") { get<AwareSwitch>().on.map { FieldEvent.AwareSwitch(it) } }
                // How Android treats the app's background battery use, at each session's start and on change.
                log.follower("battery") { batteryEdges({ readBattery(androidContext()) }) }
            }
        }
        single { FieldLogExport(androidContext(), get(), get(), get()) }
        // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
        single { MeshBlobStore(get(), get(), get(), File(androidContext().cacheDir, "blobtx")) }
        // The prefixes of attachments a link drop cut off, one store for both radios and BlobExchange (#116):
        // session-scoped (MeshManager.start purges it beside the blobtx copies), so no plaintext outlives a session.
        single { PartialBlobs(File(androidContext().cacheDir, "blob-partials"), log = { Log.d("PartialBlobs", it) }) }
        // Demo-screenshot builds (debug-only, `-PseedDemo=true`) swap in a no-op transport that just reports a
        // few connected neighbors (so the UI looks "connected" against the seeded data); the seam returns null
        // in release, where the demo classes don't ship (see the per-variant di/DemoWiring). Production wraps
        // every hardware-supported plane in a CompositeMeshTransport behind the single-transport seam —
        // Bluetooth LE and Wi-Fi Aware, in descending send-preference. Each plane is gated on isSupported() so
        // an unsupported one is simply absent (a device with neither yields an inert, Degraded composite).
        single<MeshTransport> {
            demoTransportOrNull() ?: run {
                val ctx = androidContext()
                // WifiAwareTransport is @RequiresApi(31) (its NDP accept-any responder is API 31): isSupported()'s
                // verdict, with the literal SDK_INT comparison lint reads repeated at the constructor's line below.
                val awareSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WifiAwareTransport.isSupported(ctx)
                // Knit's switch for the Wi-Fi Aware plane: the person's (Settings › Wi-Fi Aware), a debug build's own
                // (Diagnostics / the bridge's NANOFF; debugNanOff reads false in release) and low-power mode's (never,
                // while it is not offered). Read by the plane's SwitchableTransport and by the advert's flag below.
                val awareOff = awareSwitchedOff(get<AwareSwitch>().on, get<SettingsStore>().debugNanOff, get<LowPowerSource>().posture)
                // The plane's own half of "won't run here" (the radio off by evidence, attaching given up), written by
                // the plane once it is built below; the Bluetooth plane is built first, so it reads it through here.
                val awarePlaneWontRun = MutableStateFlow(false)

                // "This phone won't run Wi-Fi Aware" (GitHub #44), the Bluetooth advert's FLAG_NO_AWARE: switched off,
                // or the plane says so — never for a plane that is merely dormant or lent to a Wi-Fi Direct group.
                // Constant true without the plane. Starts true: the advert sets the bit at once and clears it only
                // once it has held, so an early true only delays a peer's wake.
                // The Bluetooth plane's constructor takes it, for the advert's flag and its debounce.
                val awareWontRunHere: StateFlow<Boolean> =
                    if (awareSupported) {
                        awareWontRun(awareOff, awarePlaneWontRun).stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, true)
                    } else {
                        MutableStateFlow(true)
                    }
                val children =
                    buildList {
                        // Held for Wi-Fi Aware's ICM rule (GitHub #44): its audio monitor is the one A2DP signal we have.
                        var bluetooth: BluetoothMeshTransport? = null
                        // Descending send-preference: Bluetooth (persistent links) first, then Wi-Fi Aware (ephemeral).
                        if (BluetoothMeshTransport.isSupported(ctx)) {
                            // The side channel is the one seam its flag gates: null keeps every page dark.
                            val sideChannel =
                                if (BuildConfig.BLE_SIDE_PLANE) {
                                    BleSideChannel(ctx, get(), get(), log = { msg -> Log.d("BleSideChannel", msg) })
                                } else {
                                    null
                                }
                            // The debug link cap reads null in release (gated in the store's flow).
                            val linkCap = get<SettingsStore>().debugBleLinkCap
                            // The Coded PHY experiment's mode reads null while BLE_CODED_PHY keeps it dark: OFF here, the one seam.
                            val phyMode = get<SettingsStore>().debugBlePhyMode.map { it ?: CodedPhyMode.OFF }
                            // BLE_GATT_PEERS gates the iPhone reader, its scan filter and the advert flag together (A3).
                            val bt =
                                BluetoothMeshTransport(
                                    ctx,
                                    get(),
                                    get(),
                                    get(),
                                    get(),
                                    get(),
                                    get(),
                                    sideChannel,
                                    linkCap,
                                    gattPeers = BuildConfig.BLE_GATT_PEERS,
                                    phyMode = phyMode,
                                    appVisible = get<AppVisibility>().visible,
                                    // Cold far sightings start nothing under the low-power posture (ADR 2026-10.yvn6's
                                    // 2026-10-20 amendment, ADR 2026-10.d44b): read at each decision, never cached.
                                    lowPower = get<LowPowerSource>().let { source -> { source.current.active } },
                                    partials = get(),
                                    field = get<FieldLog>(),
                                    awareWontRun = awareWontRunHere,
                                )
                            bluetooth = bt
                            add(bt)
                        }
                        // The explicit SDK_INT guard — redundant with awareSupported — is what lint reads to clear the
                        // @RequiresApi constructor call on this pre-31-reachable line.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && awareSupported) {
                            // One store is both journals (the attach give-up and the initiator hold, ADR 055 / 2026-09.m8kc).
                            val journals = NanJournalStore(get())
                            val nan =
                                WifiAwareTransport(
                                    ctx,
                                    get(),
                                    get(),
                                    get(),
                                    get(),
                                    get(),
                                    journals,
                                    journals,
                                    get(),
                                    appVisible = get<AppVisibility>().visible,
                                    // A call running (live or walkie) holds the watchdog's Tier-2 kill, which would end it
                                    // (ADR 2026-09.9dnk's 2026-10-20 amendment). Late-bound like onCallSignal below, and
                                    // never true in a build that wires no calls.
                                    callLive = {
                                        val call =
                                            if (CALLS_WIRED) {
                                                runCatching {
                                                    getKoin().getOrNull<CallManager>()?.state?.value
                                                }.getOrNull()
                                            } else {
                                                null
                                            }
                                        call != null && call.phase != CallPhase.Ended
                                    },
                                    field = get<FieldLog>(),
                                    // No ICM while Bluetooth audio streams (A2DP, or a Bluetooth / LE Audio output with
                                    // music active); a phone without the Bluetooth plane has no such signal.
                                    audioStreaming = bluetooth?.radioContended ?: NOT_CONTENDED,
                                    // The 5 GHz ICM picks stay debug-only until a cross-band delivery trial passes.
                                    fiveGhzRules = BuildConfig.DEBUG,
                                    wontRunHere = awarePlaneWontRun,
                                )
                            // The switch stops the plane live in every build. Log.i: release keeps it, so a bugreport
                            // shows the plane stopped for it.
                            add(SwitchableTransport(nan, awareOff, get(), { msg -> Log.i("WifiAwareTransport", msg) }))
                        }
                        // The LoRa (Meshtastic) plane rides LAST — lowest send-preference, fast-plane only.
                        // Gated on the flag; the classes stay in the APK but are never resolved when off.
                        if (BuildConfig.LORA_PLANE) add(get<LoraMeshTransport>())
                    }
                CompositeMeshTransport(children, get(), get()) { msg ->
                    Log.d("CompositeMeshTransport", msg)
                }
            }
        }
        // The E2E message cipher, built from this device's identity private keysets.
        single {
            val keys = get<IdentityKeyStore>().keys()
            MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        }
        // THE ratchet lock, shared by the DM and group session services: group seed adoption runs inside
        // a DM commit, so one instance makes the lock-order question vanish by construction.
        single(ratchetMutex) { Mutex() }
        // The other half of that contract: the transaction both services open BEFORE taking the lock, so
        // every critical section acquires the DB and the mutex in one global order. Room's withWriteTransaction
        // is reentrant per coroutine, so the decrypt path (which already wraps its commit) just joins.
        single<SessionTransactor> {
            val db = get<KnitDatabase>()
            object : SessionTransactor {
                override suspend fun <T> transact(block: suspend () -> T): T = db.withWriteTransaction { block() }
            }
        }
        // The DM epoch-ratchet session service (crypto scheme v2). Identity access is lambda-mediated so
        // the service itself stays Android-free and plain-JVM-testable; the store is the Room-backed
        // RatchetStore bound in appModule.
        single {
            val identityKeys = get<IdentityKeyStore>()
            RatchetSessions(
                store = get(),
                dhIdentityPriv = identityKeys::dhIdentityPrivate,
                spkPrivFor = identityKeys::prekeyPrivFor,
                mutex = get(ratchetMutex),
                transact = get(),
                log = { Log.w("RatchetSessions", it) },
            )
        }
        // The group sender-key session service (crypto scheme v2's group form, docs/GROUP_FORWARD_SECRECY.md).
        single { GroupRatchetSessions(store = get(), mutex = get(ratchetMutex), transact = get()) }
        // The LoRa (Meshtastic-over-BLE) plane. The GATT dialer is the only android.bluetooth importer for
        // the feature; the session is pure over it, and the transport is a fast-plane-only composite child.
        // All lazy — resolved only when BuildConfig.LORA_PLANE adds the child, so release never instantiates them.
        single<MeshtasticGattDialer> { MeshtasticGatt(androidContext(), get()) }
        single<MeshtasticLink> {
            MeshtasticSession(dialer = get(), scope = get(), now = SystemClock::elapsedRealtime, log = { Log.d("MeshtasticLink", it) })
        }
        // Demo builds fake the bonded list (no adapter on an emulator); production reads the real one.
        single<BoardDirectory> { demoBoardDirectoryOrNull() ?: BondedBoardDirectory(androidContext()) }
        // MeshManager supplies the signed profile frame for the LoRa key-bootstrap beacon (a third alias),
        // the carried DM-form frames the plane re-offers to a peer it first hears (a fourth), and the custody
        // window the bridge gossips about and serves from (a fifth, ADR 044).
        single<ProfileFrameSource> { get<MeshManager>() }
        single<FarPeerFrameSource> { get<MeshManager>() }
        single<BridgeFrameSource> { get<MeshManager>() }
        single<MeshPostSink> { get<MeshManager>() }
        // The Meshtastic room's outbound half. Bound to the LoRa transport, and reached from MeshManager
        // through a lambda for the same reason MeshPostSink is reached from the transport through one: the
        // two ends construct each other, so exactly one of the two edges has to resolve late.
        single<PublicChannelSink> { get<LoraMeshTransport>() }
        single {
            val settings = get<SettingsStore>()
            LoraMeshTransport(
                selfId = { get<Identity>().nodeId() },
                link = get(),
                // The three per-plane switches are folded first so the whole config stays inside `combine`'s
                // typed five-flow arity — the same fold ChatListViewModel/ChatViewModel make for the same reason.
                config =
                    combine(
                        settings.loraEnabled,
                        settings.loraDeviceAddress,
                        settings.loraChannelIndex,
                        combine(settings.loraDmEnabled, settings.loraBridgeEnabled, settings.loraRoomEnabled, ::Triple),
                    ) { on, addr, ch, (dms, bridge, room) ->
                        if (on && addr != null) LoraConfig(addr, ch, dms, bridge, room) else null
                    },
                selfProfile = { get<ProfileFrameSource>().signedProfile() },
                farFrames = { get<FarPeerFrameSource>().framesFor(it) },
                offerPrefixes = { get<BridgeFrameSource>().offerPrefixes(it) },
                framesMissing = { prefixes, limit, dms -> get<BridgeFrameSource>().framesMissing(prefixes, limit, dms) },
                onPublicPost = { get<MeshPostSink>().onPublicPostHeard(it) },
                // The bound board's node number and signing key, persisted so the profile can advertise them
                // (ProfileContent.loraNode / loraKey) in one write — one profile republish, not two.
                onBoardBound = { settings.setLoraBoard(it.nodeNum.toLong(), it.signingKey) },
                scope = get(),
                metrics = get(),
                // The plane's rate limiters, kept across process death: without it every launch begins with a
                // fresh airtime allowance, which in a lab that reinstalls all day is no allowance at all.
                state = LoraPlaneStateStore(settings),
                clock = SystemClock::elapsedRealtime,
                wallClock = System::currentTimeMillis,
                log = { Log.d("LoraMeshTransport", it) },
            )
        }
        // The Internet (spool) plane's socket factory. Cleartext `ws://` is a debug-build affordance for a
        // LAN daemon (which terminates no TLS of its own); release and staging accept `wss://` only, so a
        // shipped build cannot be pointed at a plaintext relay. The plane itself stays dark until the user
        // opts in AND configures a spool — see SettingsStore.spoolEnabled.
        // The socket's keepalive follows the route: a 25 s ping on Wi-Fi, four minutes on cellular, chosen per
        // dial off the gate's snapshot (net/AndroidInternetGate is the one reader of NetworkCapabilities).
        single<SpoolDialer> {
            OkHttpSpoolDialer(allowCleartext = BuildConfig.DEBUG, clientFor = OkHttpSpoolDialer.clientFor(get<InternetGate>()::routeKind))
        }
        // The ticket key swap (experimental, ADR 2026-10.3sk7): one POST to the issuer an invite named, bound to the
        // validated default network as the link-preview fetch is. Defined only where Knit Anywhere is (ANYWHERE_WIRED).
        if (ANYWHERE_WIRED) {
            single<TicketIssuer> {
                val gate = get<AndroidInternetGate>()
                val base = OkHttpTicketIssuer.baseClient()
                OkHttpTicketIssuer(
                    clientFor = { gate.currentNetwork()?.let { OkHttpTicketIssuer.bound(base, it) } },
                    allowCleartext = BuildConfig.DEBUG,
                )
            }
        }
        // Constructor order: transport, messages, receipts, groups, reactions, peers, metPeers, identity,
        // settings, blobs, imageScreening, blobStore, partials, forwardStore, notifier, textModeration,
        // messageCrypto, ratchet, groupRatchet, groupRoots, scope, metrics, ledger, db, spoolDialer.
        single {
            MeshManager(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                // The commons store is the feature's one seam into the mesh: null while the build hides the
                // feature (`BuildConfig.COMMONS`), so no room is derived into the scope table, subscribed,
                // posted to, or swept for members — the plane behaves exactly as it did before §7.4.
                commons = if (BuildConfig.COMMONS) get() else null,
                // The platform's validated-default-network stream (net/AndroidInternetGate): a new route
                // re-dials a relay the plane is backing off from at once (work item 50).
                routeChanges = get<InternetGate>().routeChanges,
                // ...and the snapshot the plane reads before each dial: no validated route, no dial.
                online = get<InternetGate>()::isOnline,
                publicChannel = { body -> get<PublicChannelSink>().postToPublicChannel(body) },
                onTransferSignal = { sender, payload, sentAt -> get<TransferManager>().onSignal(sender, payload, sentAt) },
                // Voice calls (experimental, `CALLS_WIRED` — di/CallWiring): resolved late like the transfer hook, and
                // never allowed to throw into the inbound path (rules/mesh.md). Nothing in a build that wires no calls.
                onCallSignal = { sender, payload, sentAt ->
                    if (CALLS_WIRED) {
                        runCatching { getKoin().getOrNull<CallManager>()?.onSignal(sender, payload, sentAt) }
                            .onFailure { Log.w("KnitCall", "call signal dropped: ${it.javaClass.simpleName}") }
                    }
                },
                // A walkie chunk (§3.11) goes the same late-bound, never-throwing way, where the walkie is wired.
                onCallTalk = { sender, chunk, sentAt, frameBytes, viaRelay ->
                    if (WALKIE_WIRED) {
                        runCatching { getKoin().getOrNull<CallManager>()?.onTalk(sender, chunk, sentAt, frameBytes, viaRelay) }
                            .onFailure { Log.w("KnitCall", "walkie chunk dropped: ${it.javaClass.simpleName}") }
                    }
                },
                extraCapabilities = CALL_CAPABILITIES,
                // Getting tickets is Knit Anywhere's (ANYWHERE_WIRED): without it this phone holds no key and asks no issuer.
                ticketKeys = if (ANYWHERE_WIRED) get<TicketKeyStore>() else null,
                ticketIssuer = if (ANYWHERE_WIRED) get<TicketIssuer>() else null,
                freeKeys = if (ANYWHERE_WIRED) get<TicketKeyStore>() else null,
                accountIssuer = if (ANYWHERE_WIRED) get<TicketIssuer>() as? AccountIssuer else null,
                deviceKeys = if (ANYWHERE_WIRED) get<TicketKeyStore>().devices else null,
                allowCleartext = BuildConfig.DEBUG,
                // The supporter badge (ADR 2026-10.r99s): the journal in every build, the issuer beside the ticket swap's.
                badgeJournal = get<BadgeStore>(),
                badgeIssuer = if (ANYWHERE_WIRED) get<TicketIssuer>() as? BadgeIssuer else null,
                badgePins = BadgeKeys.forBuild(BuildConfig.DEBUG),
                identities = get(),
                quotes = get(),
                notificationLabels = { notificationLabels(androidContext()) },
                // Ask the best holder of a blob rather than the likeliest (ADR 2026-10.6e5t).
                blobHolderQuery = BlobExchange.HolderQuery(enabled = BuildConfig.BLOB_QUERY),
                fieldLog = get(),
                // The home relay (ADR 2026-10.8kfx); the directory is read only where the build says it is live.
                homes = get<HomeStore>(),
                directory = if (BuildConfig.SPOOL_DIRECTORY) AndroidPublicSpoolDirectory(get()) else null,
            )
        }
        // UI ViewModels, MeshService, and the notification/debug entry points bind this narrow facade (not
        // the concrete orchestrator) so they can be tested against a fake; the same singleton backs both keys.
        single<MeshController> { get<MeshManager>() }
        // Direct Wi-Fi file transfer (transfer/): the radio and storage seams, the sealed-DM signaling seam
        // (MeshManager) and the one state machine. Its inbound half reaches it through MeshManager's
        // onTransferSignal hook above, resolved late so neither singleton constructs the other. The radio is
        // shared with a call between neighbours (call/DirectCallPath) through one lease; the transfer holds its view.
        // A phone where hosting a group takes the phone down never hosts one (ADR 2026-10.w3dw): a generic system image
        // over an older vendor, known at start, or a latch after it died hosting twice. The lease asks it before every
        // group of ours goes up; KnitApplication settles what the previous process left.
        single<DirectHostJournal> { DirectHostStore(get()) }
        single {
            DirectHostGuard(
                journal = get(),
                exits = get<ProcessExitReasons>()::lastExit,
                stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
                genericImage = get<DeviceImage>().genericOnOlderVendor,
                elapsed = SystemClock::elapsedRealtime,
                log = { Log.i("KnitTransfer", it) },
            )
        }
        single { DirectWifiLease(AndroidDirectWifi(androidContext(), get<MeshTransport>()), gate = get<DirectHostGuard>()) }
        single<DirectWifi> { get<DirectWifiLease>().leasedTo(DirectWifiLease.TRANSFER) }
        single<TransferFiles> { AndroidTransferFiles(androidContext()) }
        single<TransferSignals> { get<MeshManager>() }
        single {
            TransferManager(
                messages = get(),
                signals = get(),
                wifi = get(),
                files = get(),
                scope = get<CoroutineScope>(),
                selfId = { get<Identity>().nodeId() },
                peerNearby = { id -> get<MeshController>().neighbors.value.any { it.nodeId == id } },
                log = { Log.i("KnitTransfer", it) },
            )
        }
        // One place that turns settings + `spoolStatus()` into the relay facts the chat indicator, the
        // relay settings screen and Diagnostics all read.
        single { RelayStatusRepository(get(), get()) }
        // Free accounts' notice and switch (docs/VOICE_CALLS.md C-7.6-34): defined only where Knit Anywhere is.
        if (ANYWHERE_WIRED) {
            single {
                FreeUploads(
                    get<RelayStatusRepository>().statuses,
                    get<TicketKeyStore>(),
                    get<TicketKeyStore>(),
                    get<SettingsStore>(),
                    FreeIssuers.expectedAm(androidContext().resources.getStringArray(R.array.free_issuer_am)),
                )
            }
        }
        // The LoRa plane's UI face: the transport itself when the build ships the plane, a dark stand-in
        // otherwise — so the chat header's repository (resolved by every open chat) never instantiates the
        // board session in release. Its facts feed the header glyph, the DM notice and the composer hint.
        // A demo build reports a connected, Knit-provisioned board without dialling one (see DemoWiring);
        // otherwise the transport itself when the build ships the plane, and a dark stand-in when it doesn't.
        single<LoraPlaneStatus> {
            demoLoraPlaneOrNull()
                ?: if (BuildConfig.LORA_PLANE) get<LoraMeshTransport>() else LoraPlaneStatus.Dark
        }
        single { LoraStatusRepository(get(), get()) }
        // The Wear OS status service (prototype, `BuildConfig.WEAR_STATUS`): defined only while lit, so a dark
        // build has nothing for `MeshService` to resolve — its `getOrNull` is the one seam. It reads the same
        // repositories the header does, and nothing it serves is framed onto the mesh.
        if (BuildConfig.WEAR_STATUS) {
            single { WearStatusSource(get(), get(), get(), get(), get(), get<ForwardRepository>(), get()) }
            single { WearStatusServer(androidContext(), get(), get<CoroutineScope>()) }
        }
    }
