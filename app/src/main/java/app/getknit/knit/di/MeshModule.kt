package app.getknit.knit.di

import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.nearby.NearbyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val meshModule = module {
    // Application-lifetime scope for the mesh engine.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<MeshTransport> { NearbyTransport(androidContext(), get(), get()) }
    single { MeshManager(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
