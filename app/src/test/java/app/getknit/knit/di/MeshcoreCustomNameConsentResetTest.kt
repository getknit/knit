package app.getknit.knit.di

import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.MeshcoreBoardFace
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The custom-name post consent lasts until the bound MeshCore board is seen under its `Knit abcd` default again (ADR
 * 2026-11.f4d3, security-reviewer): "Use Knit abcd", a setup that names a new board, or another app's rename back each end
 * it, so the next name away from the default asks again. One clear per arrival at the default, none while it is away or
 * custom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreCustomNameConsentResetTest {
    private fun named(
        name: String,
        default: Boolean,
    ) = LoraStatus(meshcore = MeshcoreBoardFace(handle = MeshcoreHandle(name.encodeToByteArray()), namedDefault = default))

    @Test
    fun eachArrivalAtTheDefaultEndsTheCustomNameConsentOnce() =
        runTest {
            val status = MutableStateFlow(named("Sam", default = false))
            var clears = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                meshcoreCustomNameConsentReset(status) { clears++ }
            }
            assertEquals("a chosen name: the consent stands", 0, clears)

            status.value = named("Knit 312b", default = true)
            assertEquals("back at the default: ended", 1, clears)
            status.value = named("Knit 312b", default = true).copy(lastRssi = -90)
            assertEquals("still the default: no second write", 1, clears)

            status.value = LoraStatus()
            status.value = named("Robert", default = false)
            assertEquals("away, then renamed elsewhere: nothing to end", 1, clears)

            status.value = named("Knit 312b", default = true)
            assertEquals("the default again: ended again", 2, clears)
        }

    @Test
    fun aBoardFirstSeenUnderItsDefaultEndsItAtOnce() =
        runTest {
            var clears = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                meshcoreCustomNameConsentReset(MutableStateFlow(named("Knit 312b", default = true))) { clears++ }
            }
            assertEquals(1, clears)
        }
}
