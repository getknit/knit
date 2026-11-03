// Each seam returns a constant here because it is a stand-in for src/debug's function of the same signature.
@file:Suppress("FunctionOnlyReturningConstant")

package app.getknit.knit.di

import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSignals
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import org.koin.core.Koin

/**
 * Release-variant demo wiring — no-ops. Demo-screenshot mode is a debug-only affordance, so the seeder,
 * the no-op `DemoTransport` and the fake LoRa board are not compiled into release (they live in
 * `src/debug`). `src/main` calls these seams; the debug variant supplies the real implementations.
 */
fun demoTransportOrNull(): MeshTransport? = null

internal fun demoLoraPlaneOrNull(): LoraPlaneStatus? = null

internal fun demoBoardDirectoryOrNull(): BoardDirectory? = null

fun demoCallSignalsOrNull(): CallSignals? = null

fun demoCallMediaOrNull(): CallMedia? = null

fun demoTalkSendOrNull(): (suspend (String, ByteArray, Boolean, Boolean) -> Int?)? = null

@Suppress("UNUSED_PARAMETER")
fun demoSceneRequested(scene: String?) {
    // No-op: the trailer director is a debug-only affordance (see the debug variant's DemoWiring).
}

@Suppress("UNUSED_PARAMETER")
fun seedDemoIfEnabled(koin: Koin) {
    // No-op: demo seeding is a debug-only affordance (see the debug variant's DemoWiring).
}

@Suppress("UNUSED_PARAMETER")
fun startDemoDirectorIfEnabled(koin: Koin) {
    // No-op: the trailer director is a debug-only affordance (see the debug variant's DemoWiring).
}
