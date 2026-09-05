package app.getknit.knit.di

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room3.withWriteTransaction
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.BridgeFrameSource
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FarPeerFrameSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPostSink
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.ProfileFrameSource
import app.getknit.knit.mesh.PublicChannelSink
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport
import app.getknit.knit.mesh.bluetooth.meshtastic.BondedBoardDirectory
import app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt
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
import app.getknit.knit.mesh.meshExceptionHandler
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.spool.OkHttpSpoolDialer
import app.getknit.knit.mesh.spool.SpoolDialer
import app.getknit.knit.mesh.wifiaware.WifiAwareTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
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
        // Whether a MeshService.start the system refused is still owed a retry (work item #32).
        single { MeshStartGate() }
        // Content digest of this node's syncable state; shared between the forward-store impl (maintains the
        // message set), MeshManager (folds in profile changes), and WifiAwareTransport (cues it to neighbors) —
        // a singleton so none has to construct the other (MeshManager already depends on the transport).
        single { StoreDigest() }
        // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
        single { PowerStateSource() }
        // Lets the Meshtastic board GATT dial pause the mesh BLE scan for its connect window (shared singleton).
        single { BleConnectArbiter() }
        single { PowerMonitor(androidContext(), get()) }
        // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
        single { MeshBlobStore(get(), get(), get(), File(androidContext().cacheDir, "blobtx")) }
        // Demo-screenshot builds (debug-only, `-PseedDemo=true`) swap in a no-op transport that just reports a
        // few connected neighbors (so the UI looks "connected" against the seeded data); the seam returns null
        // in release, where the demo classes don't ship (see the per-variant di/DemoWiring). Production wraps
        // every hardware-supported plane in a CompositeMeshTransport behind the single-transport seam —
        // Bluetooth LE and Wi-Fi Aware, in descending send-preference. Each plane is gated on isSupported() so
        // an unsupported one is simply absent (a device with neither yields an inert, Degraded composite).
        single<MeshTransport> {
            demoTransportOrNull() ?: run {
                val ctx = androidContext()
                val children =
                    buildList {
                        // Descending send-preference: Bluetooth (persistent links) first, then Wi-Fi Aware (ephemeral).
                        if (BluetoothMeshTransport.isSupported(ctx)) {
                            add(BluetoothMeshTransport(ctx, get(), get(), get(), get(), get(), get()))
                        }
                        // WifiAwareTransport is @RequiresApi(31) (its NDP accept-any responder is API 31). The
                        // explicit SDK_INT guard — redundant with isSupported()'s own — is what lint reads to
                        // clear the @RequiresApi companion/constructor calls on this pre-31-reachable line.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WifiAwareTransport.isSupported(ctx)) {
                            add(WifiAwareTransport(ctx, get(), get(), get(), get(), get(), get<SettingsStore>()))
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
                selfId = { get<app.getknit.knit.identity.Identity>().nodeId() },
                link = get(),
                config =
                    combine(
                        settings.loraEnabled,
                        settings.loraDeviceAddress,
                        settings.loraChannelIndex,
                        settings.loraDmEnabled,
                        settings.loraBridgeEnabled,
                    ) { on, addr, ch, dms, bridge -> if (on && addr != null) LoraConfig(addr, ch, dms, bridge) else null },
                selfProfile = { get<ProfileFrameSource>().signedProfile() },
                farFrames = { get<FarPeerFrameSource>().framesFor(it) },
                offerPrefixes = { get<BridgeFrameSource>().offerPrefixes(it) },
                framesMissing = { prefixes, limit, dms -> get<BridgeFrameSource>().framesMissing(prefixes, limit, dms) },
                onPublicPost = { get<MeshPostSink>().onPublicPostHeard(it) },
                scope = get(),
                metrics = get(),
                clock = SystemClock::elapsedRealtime,
                wallClock = System::currentTimeMillis,
                log = { Log.d("LoraMeshTransport", it) },
            )
        }
        // The Internet (spool) plane's socket factory. Cleartext `ws://` is a debug-build affordance for a
        // LAN daemon (which terminates no TLS of its own); release and staging accept `wss://` only, so a
        // shipped build cannot be pointed at a plaintext relay. The plane itself stays dark until the user
        // opts in AND configures a spool — see SettingsStore.spoolEnabled.
        single<SpoolDialer> { OkHttpSpoolDialer(allowCleartext = BuildConfig.DEBUG) }
        // Constructor order: transport, messages, receipts, groups, reactions, peers, identity, settings,
        // blobs, imageScreening, blobStore, forwardStore, notifier, textModeration, messageCrypto, ratchet,
        // groupRatchet, groupRoots, scope, metrics, db, spoolDialer.
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
                publicChannel = { name, body -> get<PublicChannelSink>().postToPublicChannel(name, body) },
            )
        }
        // UI ViewModels, MeshService, and the notification/debug entry points bind this narrow facade (not
        // the concrete orchestrator) so they can be tested against a fake; the same singleton backs both keys.
        single<MeshController> { get<MeshManager>() }
        // One place that turns settings + `spoolStatus()` into the relay facts the chat indicator, the
        // relay settings screen and Diagnostics all read.
        single { RelayStatusRepository(get(), get()) }
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
    }
