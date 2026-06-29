package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.demo.DemoSeeder
import app.getknit.knit.mesh.DemoTransport
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.nearby.NearbyTransport
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val meshModule = module {
    // Application-lifetime scope for the mesh engine.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { MeshMetrics() }
    // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
    single { PowerStateSource() }
    single { PowerMonitor(androidContext(), get()) }
    // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
    single { MeshBlobStore(get(), File(androidContext().cacheDir, "blobtx")) }
    // Demo-screenshot builds swap in a no-op transport that just reports a few connected neighbors
    // (so the UI looks "connected" against the seeded data); production always uses NearbyTransport.
    single<MeshTransport> {
        if (BuildConfig.SEED_DEMO) DemoTransport(DemoSeeder.ONLINE_NODE_IDS)
        else NearbyTransport(androidContext(), get(), get(), get(), get())
    }
    // The E2E message cipher, built from this device's identity private keysets.
    single {
        val keys = get<IdentityKeyStore>().keys()
        MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
    }
    // Constructor order: transport, messages, groups, reactions, peers, identity, settings, blobs,
    // blobStore, forwardStore, notifier, textModeration, messageCrypto, scope, metrics.
    single {
        MeshManager(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(),
        )
    }
}
