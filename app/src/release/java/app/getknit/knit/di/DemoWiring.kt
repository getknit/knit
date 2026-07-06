package app.getknit.knit.di

import app.getknit.knit.mesh.MeshTransport
import org.koin.core.Koin

/**
 * Release-variant demo wiring — no-ops. Demo-screenshot mode is a debug-only affordance, so the seeder
 * and the no-op `DemoTransport` are not compiled into release (they live in `src/debug`). `src/main`
 * calls these two seams; the debug variant supplies the real implementations.
 */
fun demoTransportOrNull(): MeshTransport? = null

@Suppress("UNUSED_PARAMETER")
fun seedDemoIfEnabled(koin: Koin) {
    // No-op: demo seeding is a debug-only affordance (see the debug variant's DemoWiring).
}
