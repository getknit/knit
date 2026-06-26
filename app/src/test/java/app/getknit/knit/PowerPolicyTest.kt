package app.getknit.knit

import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPolicyTest {

    @Test
    fun interactiveScansAggressively() {
        val duty = PowerPolicy.dutyCycle(PowerState(interactive = true))
        assertEquals(12_000L, duty.scanWindowMs)
        assertEquals(30_000L, duty.baseIntervalMs)
    }

    @Test
    fun chargingScansAggressivelyEvenWhenScreenOff() {
        val duty = PowerPolicy.dutyCycle(PowerState(interactive = false, charging = true))
        assertEquals(12_000L, duty.scanWindowMs)
        assertEquals(30_000L, duty.baseIntervalMs)
    }

    @Test
    fun screenOffOnBatteryBacksOff() {
        val duty = PowerPolicy.dutyCycle(PowerState(interactive = false, charging = false))
        assertEquals(8_000L, duty.scanWindowMs)
        assertEquals(120_000L, duty.baseIntervalMs)
    }

    @Test
    fun screenOffAndBatteryLowBacksOffFurthest() {
        val duty = PowerPolicy.dutyCycle(
            PowerState(interactive = false, charging = false, batteryLow = true),
        )
        assertEquals(8_000L, duty.scanWindowMs)
        assertEquals(300_000L, duty.baseIntervalMs)
    }

    @Test
    fun interactiveOverridesBatteryLow() {
        val duty = PowerPolicy.dutyCycle(PowerState(interactive = true, batteryLow = true))
        assertEquals(30_000L, duty.baseIntervalMs)
    }

    @Test
    fun chargingOverridesBatteryLow() {
        val duty = PowerPolicy.dutyCycle(
            PowerState(interactive = false, charging = true, batteryLow = true),
        )
        assertEquals(30_000L, duty.baseIntervalMs)
    }
}
