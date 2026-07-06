package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.demo.DemoSeeder
import app.getknit.knit.mesh.DemoTransport
import app.getknit.knit.mesh.MeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * Debug-variant demo wiring. Demo-screenshot mode (`-PseedDemo=true`) is a debug-only affordance: the
 * seeder ([DemoSeeder]) and the no-op [DemoTransport] live in `src/debug`, so nothing demo-related
 * compiles into release. `src/main` calls these two seams (`di/MeshModule.kt` for the transport,
 * `KnitApplication` for the seed); `src/release` provides no-op counterparts.
 */
fun demoTransportOrNull(): MeshTransport? = if (BuildConfig.SEED_DEMO) DemoTransport(DemoSeeder.ONLINE_NODE_IDS) else null

fun seedDemoIfEnabled(koin: Koin) {
    if (BuildConfig.SEED_DEMO) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            DemoSeeder(koin).seed()
        }
    }
}
