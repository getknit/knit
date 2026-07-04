package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanConnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanConnectPolicy] — the pure fix-#3 fast-fail streak gate + escalating connect backoff. */
class NanConnectPolicyTest {
    // rand()=0.5 → zero offset, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun onlyTheFirstFastFailDropsTheHandle() {
        assertTrue("1st fast-fail = probable stale handle → drop + re-discover", NanConnectPolicy.dropHandleOnFastFail(1))
        assertFalse("2nd consecutive = fresh handle failed too → keep it (suppress re-arm)", NanConnectPolicy.dropHandleOnFastFail(2))
        assertFalse("3rd+ likewise", NanConnectPolicy.dropHandleOnFastFail(3))
    }

    @Test
    fun backoffDoublesFromAShortBase() {
        assertEquals("short base so a genuine restart retries the fresh handle promptly", 2_000, NanConnectPolicy.backoffMs(1, noJitter))
        assertEquals(4_000, NanConnectPolicy.backoffMs(2, noJitter))
        assertEquals(8_000, NanConnectPolicy.backoffMs(3, noJitter))
        assertEquals(16_000, NanConnectPolicy.backoffMs(4, noJitter))
    }

    @Test
    fun backoffSaturatesAtTheHealAlignedCapWithoutOverflow() {
        assertEquals("streak 5 still below the cap", 32_000, NanConnectPolicy.backoffMs(5, noJitter))
        assertEquals("streak 6 saturates at the ~60 s self-heal-aligned cap", 60_000, NanConnectPolicy.backoffMs(6, noJitter))
        assertEquals(60_000, NanConnectPolicy.backoffMs(8, noJitter))
        assertEquals(60_000, NanConnectPolicy.backoffMs(50, noJitter)) // no Long overflow on a big streak
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstFailure() {
        assertEquals(2_000, NanConnectPolicy.backoffMs(0, noJitter))
        assertEquals(2_000, NanConnectPolicy.backoffMs(-3, noJitter))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered 2_000 ms.
        assertEquals(1_600, NanConnectPolicy.backoffMs(1) { 0.0 })
        val hi = NanConnectPolicy.backoffMs(1) { 0.999999 }
        assertTrue("upper bound ≈ +20 %: got $hi", hi in 2_390..2_400)
    }
}
