package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AwareFlag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AwareFlag] (GitHub #44, ADR 2026-10.2t49's 2026-10-27 amendment): FLAG_AWARE comes down at once and goes up only
 * after "will run Aware" has held for [AwareFlag.SET_AFTER_MS], so a Doze maintenance window's flicker never wakes a
 * dormant neighbour. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwareFlagTest {
    private val wontRun = MutableStateFlow(true)
    private var advertised = false
    private val applied = mutableListOf<Boolean>()

    private fun apply(on: Boolean) {
        advertised = on
        applied += on
    }

    @Test
    fun theBitGoesUpOnlyAfterWillRunHeldAMinute() =
        runTest {
            val job = launch { AwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            assertTrue("an unchanged value applies nothing", applied.isEmpty())
            wontRun.value = false
            advanceTimeBy(AwareFlag.SET_AFTER_MS - 1)
            runCurrent()
            assertFalse(advertised)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(true), applied)
            job.cancel()
        }

    @Test
    fun theBitComesDownAtOnce() =
        runTest {
            advertised = true
            wontRun.value = false
            val job = launch { AwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            assertTrue("already up: nothing to apply", applied.isEmpty())
            wontRun.value = true
            runCurrent()
            assertEquals(listOf(false), applied)
            job.cancel()
        }

    @Test
    fun aFlickerInsideTheMinuteKeepsTheBitDown() =
        runTest {
            val job = launch { AwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            wontRun.value = false // a Doze maintenance window lets the radio back…
            advanceTimeBy(AwareFlag.SET_AFTER_MS / 2)
            runCurrent()
            wontRun.value = true // …and Doze takes it again
            runCurrent()
            wontRun.value = false
            advanceTimeBy(AwareFlag.SET_AFTER_MS - 1)
            runCurrent()
            assertTrue("the clock starts again at each will-run edge", applied.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertTrue(advertised)
            assertEquals(listOf(true), applied)
            job.cancel()
        }
}
