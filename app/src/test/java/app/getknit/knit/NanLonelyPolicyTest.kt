package app.getknit.knit

import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState
import app.getknit.knit.mesh.wifiaware.NanLonelyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanLonelyPolicy] — the Wi-Fi Aware loop's cadence with nobody to cue. */
class NanLonelyPolicyTest {
    private val tick = 8_000L
    private val cooldown = 15_000L
    private val onBattery = PowerState(interactive = false, charging = false)
    private val lowBattery = PowerState(interactive = false, charging = false, batteryLow = true)
    private val window = PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS

    private fun cadence(
        power: PowerState,
        lonelyForMs: Long,
    ) = NanLonelyPolicy.cadence(power, lonelyForMs, tick, cooldown)

    @Test
    fun justLonelyIsTheOldCadence() {
        // Screen off on battery doubled the tick and kept the 15 s cooldown; screen on did not double it.
        assertEquals(NanLonelyPolicy.Cadence(16_000L, 15_000L, relaxed = false), cadence(onBattery, lonelyForMs = 0L))
        assertEquals(NanLonelyPolicy.Cadence(8_000L, 15_000L, relaxed = false), cadence(PowerState(interactive = true), 0L))
    }

    @Test
    fun interactiveOrChargingNeverRelaxes() {
        val hour = 60 * 60_000L
        assertEquals(NanLonelyPolicy.Cadence(8_000L, 15_000L, relaxed = false), cadence(PowerState(interactive = true), hour))
        assertEquals(
            NanLonelyPolicy.Cadence(8_000L, 15_000L, relaxed = false),
            cadence(PowerState(interactive = false, charging = true), hour),
        )
    }

    @Test
    fun onBatteryPastTheWindowRelaxesToTheDutyCycle() {
        // ICM is lit for the 30 s after each re-arm: a quarter of the time at 120 s, a tenth at 300 s.
        assertEquals(NanLonelyPolicy.Cadence(120_000L, 105_000L, relaxed = true), cadence(onBattery, window))
        assertEquals(NanLonelyPolicy.Cadence(300_000L, 285_000L, relaxed = true), cadence(lowBattery, window + 1))
    }

    @Test
    fun theWindowBoundaryIsExact() {
        assertFalse(cadence(onBattery, window - 1).relaxed)
        assertTrue(cadence(onBattery, window).relaxed)
    }

    @Test
    fun relaxedInvariantsHold() {
        for ((power, age) in listOf(onBattery to window, lowBattery to window, onBattery to 10 * window)) {
            val c = cadence(power, age)
            assertTrue("cooldown under the tick so a timed tick always clears the gate", c.rearmCooldownMs < c.tickMs)
            assertTrue("never a shorter cooldown than the aggressive one", c.rearmCooldownMs >= cooldown)
            assertTrue("ICM duty at most a quarter", 30_000L * 100 / c.tickMs <= 25)
        }
    }

    @Test
    fun theLonelinessClockStampsOnceHoldsAndClears() {
        assertEquals(0L, NanLonelyPolicy.lonelySince(cueTargetsEmpty = false, prev = 0L, now = 1_000L))
        assertEquals(1_000L, NanLonelyPolicy.lonelySince(cueTargetsEmpty = true, prev = 0L, now = 1_000L))
        assertEquals(1_000L, NanLonelyPolicy.lonelySince(cueTargetsEmpty = true, prev = 1_000L, now = 5_000L))
        assertEquals(0L, NanLonelyPolicy.lonelySince(cueTargetsEmpty = false, prev = 1_000L, now = 5_000L))
    }
}
