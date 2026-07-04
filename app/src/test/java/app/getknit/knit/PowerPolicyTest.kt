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
        val duty =
            PowerPolicy.dutyCycle(
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
        val duty =
            PowerPolicy.dutyCycle(
                PowerState(interactive = false, charging = true, batteryLow = true),
            )
        assertEquals(30_000L, duty.baseIntervalMs)
    }

    // --- idleAfterScan: a connected mesh backs off, an isolated node scans aggressively ---

    @Test
    fun connectedMeshIdleBacksOffByNeighborCount() {
        // baseIntervalMs * (1 + neighborCount); interactive base = 30_000.
        assertEquals(60_000L, PowerPolicy.idleAfterScan(PowerState(interactive = true), neighborCount = 1, lonelyForMs = 0L))
        assertEquals(90_000L, PowerPolicy.idleAfterScan(PowerState(interactive = true), neighborCount = 2, lonelyForMs = 0L))
        // Screen-off-on-battery base = 120_000.
        assertEquals(
            240_000L,
            PowerPolicy.idleAfterScan(PowerState(interactive = false, charging = false), neighborCount = 1, lonelyForMs = 0L),
        )
    }

    @Test
    fun isolatedNodeScansAggressivelyRegardlessOfPower() {
        // Just became isolated (lonelyForMs = 0) → inside the aggressive window for every power state.
        val everyState =
            listOf(
                PowerState(interactive = true),
                PowerState(interactive = false, charging = true),
                PowerState(interactive = false, charging = false),
                PowerState(interactive = false, charging = false, batteryLow = true),
            )
        everyState.forEach { state ->
            assertEquals(12_000L, PowerPolicy.idleAfterScan(state, neighborCount = 0, lonelyForMs = 0L))
        }
    }

    @Test
    fun isolatedTooLongOnBatteryRelaxesToPowerPolicy() {
        val stale = 5 * 60_000L // past the 3-min aggressive window
        val onBattery = PowerState(interactive = false, charging = false)
        val batteryLow = PowerState(interactive = false, charging = false, batteryLow = true)
        // Relax to the power-policy base interval (120_000 / 300_000) once alone too long.
        assertEquals(120_000L, PowerPolicy.idleAfterScan(onBattery, neighborCount = 0, lonelyForMs = stale))
        assertEquals(300_000L, PowerPolicy.idleAfterScan(batteryLow, neighborCount = 0, lonelyForMs = stale))
    }

    @Test
    fun isolatedWhileInteractiveOrChargingStaysAggressiveEvenWhenStale() {
        val stale = 10 * 60_000L // long past the window, but the cap only applies on battery
        val charging = PowerState(interactive = false, charging = true)
        assertEquals(12_000L, PowerPolicy.idleAfterScan(PowerState(interactive = true), neighborCount = 0, lonelyForMs = stale))
        assertEquals(12_000L, PowerPolicy.idleAfterScan(charging, neighborCount = 0, lonelyForMs = stale))
    }

    // --- settledIdleAfterScan: the discovery floor when a node has nothing to promote ---

    @Test
    fun settledIdleRaisesTheGapToTheFloorWhenActivityIdleIsBelowIt() {
        // Interactive with 1 link: activity idle = 60_000 < 120_000 floor → clamped up to the floor.
        assertEquals(
            120_000L,
            PowerPolicy.settledIdleAfterScan(PowerState(interactive = true), neighborCount = 1, lonelyForMs = 0L),
        )
    }

    @Test
    fun settledIdleNeverGoesBelowTheAlreadyLongerActivityIdle() {
        // Screen-off-on-battery with 1 link: activity idle = 240_000 > 120_000 floor → keep the larger value.
        assertEquals(
            240_000L,
            PowerPolicy.settledIdleAfterScan(PowerState(interactive = false, charging = false), neighborCount = 1, lonelyForMs = 0L),
        )
    }
}
