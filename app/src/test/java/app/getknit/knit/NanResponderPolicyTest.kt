package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanConnectPolicy
import app.getknit.knit.mesh.wifiaware.NanResponderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NanResponderPolicy] — the pacing that keeps an unfulfillable responder request from being
 * re-filed as fast as the framework can refuse it (work item #77: 174 files in 130 ms on the Pixel 3). The
 * assertions are budget assertions: what a run of refusals *costs*, and when the request is given up on.
 */
class NanResponderPolicyTest {
    // rand()=0.5 → zero offset, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun theFirstRefusalWaitsOutTheFloor() {
        assertEquals(500, NanResponderPolicy.refileDelayMs(1, noJitter))
        assertEquals("a contended verdict is the floor, whatever the streak", 500, NanResponderPolicy.contendedRefileMs(noJitter))
        assertFalse(NanResponderPolicy.cycleSession(1, 0))
    }

    @Test
    fun theFloorIsInvisibleToTheKnocker() {
        // The one legitimate verdict is a peer knocking while our initiator link holds the interface. That peer's
        // first retry is NanConnectPolicy's base backoff out, so a re-file at the floor is filed long before it
        // knocks again — the floor costs the knock nothing.
        assertTrue(NanResponderPolicy.BASE_BACKOFF_MS < NanConnectPolicy.backoffMs(1) { 0.0 })
    }

    @Test
    fun backoffDoublesPerConsecutiveRefusal() {
        assertEquals(1_000, NanResponderPolicy.refileDelayMs(2, noJitter))
        assertEquals(2_000, NanResponderPolicy.refileDelayMs(3, noJitter))
        assertEquals(4_000, NanResponderPolicy.refileDelayMs(4, noJitter))
        assertEquals(8_000, NanResponderPolicy.refileDelayMs(5, noJitter))
        assertEquals(16_000, NanResponderPolicy.refileDelayMs(6, noJitter))
        assertEquals(32_000, NanResponderPolicy.refileDelayMs(7, noJitter))
    }

    @Test
    fun backoffSaturatesAtAMinuteWithoutOverflow() {
        assertEquals("streak 8 saturates", 60_000, NanResponderPolicy.refileDelayMs(8, noJitter))
        assertEquals(60_000, NanResponderPolicy.refileDelayMs(20, noJitter))
        assertEquals(60_000, NanResponderPolicy.refileDelayMs(Int.MAX_VALUE, noJitter)) // no Long overflow
        // Aligned with the connect cap, so a dead responder polls at the rate a dead peer already does.
        assertEquals(NanConnectPolicy.backoffMs(8, noJitter), NanResponderPolicy.refileDelayMs(8, noJitter))
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstRefusal() {
        assertEquals(500, NanResponderPolicy.refileDelayMs(0, noJitter))
        assertEquals(500, NanResponderPolicy.refileDelayMs(-3, noJitter))
        assertFalse(NanResponderPolicy.cycleSession(0, 0))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered 500 ms.
        assertEquals(400, NanResponderPolicy.refileDelayMs(1) { 0.0 })
        val hi = NanResponderPolicy.refileDelayMs(1) { 0.999999 }
        assertTrue("upper bound ≈ +20 %: got $hi", hi in 590..600)
    }

    @Test
    fun theFifthRefusalWithAFreeInterfaceCyclesTheSession() {
        assertFalse("four in a row still re-file", NanResponderPolicy.cycleSession(NanResponderPolicy.CYCLE_AT_STREAK - 1, 0))
        assertTrue(NanResponderPolicy.cycleSession(NanResponderPolicy.CYCLE_AT_STREAK, 0))
        assertTrue("every refusal past it too, while the budget lasts", NanResponderPolicy.cycleSession(CYCLE_AT_STREAK + 7, 0))

        // What those four re-files cost in wall-clock: long past the ~10 ms the framework takes to answer a
        // request it will never fulfil, and well inside the 45 s Tier-1 watchdog window.
        val untilCycle = (1 until NanResponderPolicy.CYCLE_AT_STREAK).sumOf { NanResponderPolicy.refileDelayMs(it, noJitter) }
        assertEquals(7_500, untilCycle)
        assertTrue(untilCycle < 45_000)
    }

    @Test
    fun theCycleBudgetIsThreePerEpisodeAndThenTheCurveContinues() {
        assertTrue(NanResponderPolicy.cycleSession(NanResponderPolicy.CYCLE_AT_STREAK, NanResponderPolicy.MAX_CYCLES - 1))
        assertFalse(NanResponderPolicy.cycleSession(NanResponderPolicy.CYCLE_AT_STREAK, NanResponderPolicy.MAX_CYCLES))
        assertFalse(NanResponderPolicy.cycleSession(NanResponderPolicy.CYCLE_AT_STREAK + 40, NanResponderPolicy.MAX_CYCLES))
        // A responder the framework refuses for good: once the cycles are spent it is retried once a minute, so
        // the request is still there to take if the framework changes its mind, at a cost that is a rounding error.
        assertEquals(1_440, DAY_MS / NanResponderPolicy.MAX_BACKOFF_MS)
    }

    @Test
    fun thePixel3BurstCostsTwoFilesNotAHundredAndSeventyFour() {
        // The field shape: the framework answered every fresh request in ~10 ms, and an unrelated re-attach
        // ended the loop 2.1 s after the capture began. Replay that window against the curve: a verdict
        // arrives 10 ms after each file, and the next file waits out the policy's delay.
        val windowMs = 2_114L
        val verdictLatencyMs = 10L
        var now = 0L
        var files = 0
        var streak = 0
        var cycled = false
        while (now < windowMs) {
            now += verdictLatencyMs // the verdict on the request just filed
            streak++
            if (NanResponderPolicy.cycleSession(streak, 0)) {
                cycled = true
                break
            }
            now += NanResponderPolicy.refileDelayMs(streak, noJitter)
            if (now < windowMs) files++
        }
        assertEquals("the same 2.1 s that held 174 files holds two", 2, files)
        assertFalse("and the give-up is still ahead — 7.5 s of a free interface, not 2", cycled)
    }

    @Test
    fun theStreakEndsInACycleBeforeTheWatchdogWouldNotice() {
        // Left alone, a request refused on a free interface is given up on by the fifth verdict and the session
        // cycled — the whole run, framework latency included, is under ten seconds.
        var now = 0L
        var streak = 0
        while (!NanResponderPolicy.cycleSession(++streak, 0)) {
            now += 10 + NanResponderPolicy.refileDelayMs(streak, noJitter)
        }
        assertEquals(NanResponderPolicy.CYCLE_AT_STREAK, streak)
        assertTrue("gave up at ${now}ms", now < 10_000)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
        const val CYCLE_AT_STREAK = NanResponderPolicy.CYCLE_AT_STREAK
    }
}
