package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.NoAwareFlag
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
 * [NoAwareFlag] (GitHub #44): FLAG_NO_AWARE goes up at once and comes down only after "will run Aware" has held for
 * [NoAwareFlag.CLEAR_AFTER_MS], so a Doze maintenance window's flicker never wakes a dormant neighbour. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoAwareFlagTest {
    private val wontRun = MutableStateFlow(true)
    private var advertised = true
    private val applied = mutableListOf<Boolean>()

    private fun apply(on: Boolean) {
        advertised = on
        applied += on
    }

    @Test
    fun theBitComesDownOnlyAfterItHeldClearAMinute() =
        runTest {
            val job = launch { NoAwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            assertTrue("an unchanged value applies nothing", applied.isEmpty())
            wontRun.value = false
            advanceTimeBy(NoAwareFlag.CLEAR_AFTER_MS - 1)
            runCurrent()
            assertTrue(advertised)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(false), applied)
            job.cancel()
        }

    @Test
    fun theBitGoesUpAtOnce() =
        runTest {
            advertised = false
            wontRun.value = false
            val job = launch { NoAwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            wontRun.value = true
            runCurrent()
            assertEquals(listOf(true), applied)
            job.cancel()
        }

    @Test
    fun aFlickerInsideTheMinuteKeepsTheBitUp() =
        runTest {
            val job = launch { NoAwareFlag.follow(wontRun, { advertised }, ::apply, { delay(it) }) }
            runCurrent()
            wontRun.value = false // a Doze maintenance window lets the radio back…
            advanceTimeBy(NoAwareFlag.CLEAR_AFTER_MS / 2)
            runCurrent()
            wontRun.value = true // …and Doze takes it again
            runCurrent()
            wontRun.value = false
            advanceTimeBy(NoAwareFlag.CLEAR_AFTER_MS - 1)
            runCurrent()
            assertTrue("the clock starts again at each clear", applied.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertFalse(advertised)
            assertEquals(listOf(false), applied)
            job.cancel()
        }
}
