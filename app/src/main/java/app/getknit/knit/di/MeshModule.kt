package app.getknit.knit.di

import android.os.Build
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.demo.DemoSeeder
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.DemoTransport
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.meshExceptionHandler
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.wifiaware.WifiAwareTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val meshModule =
    module {
        // Application-lifetime scope for the mesh engine. The shared exception handler is the process-level
        // backstop for an uncaught throw in a top-level child (e.g. a FramedLink writer coroutine).
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default + meshExceptionHandler) }
        single { MeshMetrics() }
        // Content digest of this node's syncable state; shared between the forward-store impl (maintains the
        // message set), MeshManager (folds in profile changes), and WifiAwareTransport (cues it to neighbors) —
        // a singleton so none has to construct the other (MeshManager already depends on the transport).
        single { StoreDigest() }
        // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
        single { PowerStateSource() }
        single { PowerMonitor(androidContext(), get()) }
        // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
        single { MeshBlobStore(get(), File(androidContext().cacheDir, "blobtx")) }
        // Demo-screenshot builds swap in a no-op transport that just reports a few connected neighbors (so the UI
        // looks "connected" against the seeded data). Production wraps every hardware-supported plane in a
        // CompositeMeshTransport behind the single-transport seam — Bluetooth LE and Wi-Fi Aware, in descending
        // send-preference. Each plane is gated on isSupported() so an unsupported one is simply absent (a device
        // with neither yields an inert, Degraded composite).
        single<MeshTransport> {
            if (BuildConfig.SEED_DEMO) {
                DemoTransport(DemoSeeder.ONLINE_NODE_IDS)
            } else {
                val ctx = androidContext()
                val children =
                    buildList {
                        // Descending send-preference: Bluetooth (persistent links) first, then Wi-Fi Aware (ephemeral).
                        if (BluetoothMeshTransport.isSupported(ctx)) {
                            add(BluetoothMeshTransport(ctx, get(), get(), get(), get(), get()))
                        }
                        // WifiAwareTransport is @RequiresApi(31) (its NDP accept-any responder is API 31). The
                        // explicit SDK_INT guard — redundant with isSupported()'s own — is what lint reads to
                        // clear the @RequiresApi companion/constructor calls on this pre-31-reachable line.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WifiAwareTransport.isSupported(ctx)) {
                            add(WifiAwareTransport(ctx, get(), get(), get(), get(), get()))
                        }
                    }
                CompositeMeshTransport(children, get(), get()) { msg ->
                    android.util.Log.d("CompositeMeshTransport", msg)
                }
            }
        }
        // The E2E message cipher, built from this device's identity private keysets.
        single {
            val keys = get<IdentityKeyStore>().keys()
            MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        }
        // Constructor order: transport, messages, groups, reactions, peers, identity, settings, blobs,
        // blobStore, forwardStore, notifier, textModeration, messageCrypto, scope, metrics, db.
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
            )
        }
        // UI ViewModels, MeshService, and the notification/debug entry points bind this narrow facade (not
        // the concrete orchestrator) so they can be tested against a fake; the same singleton backs both keys.
        single<MeshController> { get<MeshManager>() }
    }
