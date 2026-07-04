package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ConnectBackoffPolicy] — the pure escalating per-peer connect backoff curve. */
class ConnectBackoffPolicyTest {
    private val cfg = BackoffConfig(baseMs = 10_000, maxMs = 180_000, jitterFraction = 0.2)

    // rand()=0.5 → the offset is zero, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun doublesPerStreak() {
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(1, cfg, noJitter))
        assertEquals(20_000, ConnectBackoffPolicy.nextDelayMs(2, cfg, noJitter))
        assertEquals(40_000, ConnectBackoffPolicy.nextDelayMs(3, cfg, noJitter))
        assertEquals(80_000, ConnectBackoffPolicy.nextDelayMs(4, cfg, noJitter))
        assertEquals(160_000, ConnectBackoffPolicy.nextDelayMs(5, cfg, noJitter))
    }

    @Test
    fun saturatesAtMax() {
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(6, cfg, noJitter))
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(7, cfg, noJitter))
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(50, cfg, noJitter)) // no Long overflow on a big streak
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstFailure() {
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(0, cfg, noJitter))
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(-5, cfg, noJitter))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered delay.
        assertEquals(8_000, ConnectBackoffPolicy.nextDelayMs(1, cfg) { 0.0 })
        assertEquals(12_000, ConnectBackoffPolicy.nextDelayMs(1, cfg) { 1.0 })
    }

    @Test
    fun jitterStaysWithinBoundsAcrossTheRange() {
        for (r in listOf(0.0, 0.1, 0.5, 0.9, 0.999)) {
            val d = ConnectBackoffPolicy.nextDelayMs(3, cfg) { r }
            assertTrue("delay $d within ±20% of 40s", d in 32_000..48_000)
        }
    }
}
