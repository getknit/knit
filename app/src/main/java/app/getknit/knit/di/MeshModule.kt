package app.getknit.knit.di

import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.nearby.NearbyTransport
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
    // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
    single { MeshBlobStore(get(), File(androidContext().cacheDir, "blobtx")) }
    single<MeshTransport> { NearbyTransport(androidContext(), get(), get(), get()) }
    single { MeshManager(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
