package app.getknit.knit.mesh.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Low-power mode's one reading (ADR 2026-10.d44b): the switch or Battery Saver on battery, lifted by a charger that
 * has held for a minute, and silent until the switch has been read, so Wi-Fi Aware never attaches at launch only to
 * be dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LowPowerTest {
    @Test
    fun thePolicyTable() {
        val cases =
            listOf(
                // switch, saver, charging -> reason
                Triple(false, false, false) to null,
                Triple(true, false, false) to LowPowerReason.SWITCH,
                Triple(false, true, false) to LowPowerReason.BATTERY_SAVER,
                Triple(true, true, false) to LowPowerReason.SWITCH,
                Triple(true, false, true) to null,
                Triple(false, true, true) to null,
                Triple(true, true, true) to null,
                Triple(false, false, true) to null,
            )
        for ((input, reason) in cases) {
            val (on, saver, charging) = input
            assertEquals("$input", reason, LowPowerPolicy.posture(on, saver, charging, offered = true).reason)
        }
    }

    @Test
    fun aModeNotOfferedIsOffWhateverItsInputsSay() {
        // GitHub #44: low-power mode is not offered; the switch's key, Battery Saver and the charger all read off.
        assertFalse(LOW_POWER_OFFERED)
        for (on in listOf(false, true)) {
            for (saver in listOf(false, true)) {
                for (charging in listOf(false, true)) {
                    assertEquals("$on $saver $charging", LowPowerPosture.OFF, LowPowerPolicy.posture(on, saver, charging))
                }
            }
        }
    }

    @Test
    fun aSourceNotOfferedNeverComesOn() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            val source = LowPowerSource(inputs.switch, inputs.saver, inputs.power, backgroundScope)
            inputs.saver.value = true
            inputs.switch.emit(true)
            assertEquals(LowPowerPosture.OFF, source.posture.replayCache.single())
            assertFalse(source.current.active)
        }

    @Test
    fun thePostureGivesUpAwareAndTheMotionHealTogether() {
        val on = LowPowerPosture(LowPowerReason.SWITCH)
        assertTrue(on.active)
        assertTrue(on.awareOff)
        assertFalse(on.motionHeal)
        assertFalse(LowPowerPosture.OFF.active)
        assertFalse(LowPowerPosture.OFF.awareOff)
        assertTrue(LowPowerPosture.OFF.motionHeal)
    }

    private class Inputs {
        val switch = MutableSharedFlow<Boolean>(replay = 1)
        val saver = MutableStateFlow(false)
        val power = MutableStateFlow(PowerState(charging = false))
    }

    // Offered, so the mode's own rules stay pinned while it is not ([LOW_POWER_OFFERED]).
    private fun TestScope.source(inputs: Inputs) =
        LowPowerSource(inputs.switch, inputs.saver, inputs.power, backgroundScope, offered = true)

    @Test
    fun nothingIsSaidBeforeTheSwitchIsRead() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            val source = source(inputs)
            assertTrue(source.posture.replayCache.isEmpty())
            assertEquals(LowPowerPosture.OFF, source.current)

            inputs.switch.emit(true)
            assertEquals(LowPowerReason.SWITCH, source.posture.first().reason)
        }

    @Test
    fun batterySaverHoldsItOnWithTheSwitchOff() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            val source = source(inputs)
            inputs.switch.emit(false)
            assertNull(source.current.reason)

            inputs.saver.value = true
            assertEquals(LowPowerReason.BATTERY_SAVER, source.current.reason)
            inputs.saver.value = false
            assertEquals("a lift the user did not make settles first", LowPowerReason.BATTERY_SAVER, source.current.reason)
            advanceTimeBy(LowPowerPolicy.LIFT_SETTLE_MS + 1)
            assertNull(source.current.reason)
        }

    @Test
    fun aChargerLiftsItOnlyAfterHoldingAndUnpluggingAppliesAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            val source = source(inputs)
            inputs.switch.emit(true)
            assertTrue(source.current.active)

            inputs.power.value = PowerState(charging = true)
            advanceTimeBy(LowPowerPolicy.CHARGE_HOLD_MS - 1)
            assertTrue("still on inside the hold", source.current.active)
            advanceTimeBy(2)
            runCurrent()
            assertFalse("lifted once the charger held", source.current.active)

            inputs.power.value = PowerState(charging = false)
            assertTrue("unplugging applies at once", source.current.active)
        }

    @Test
    fun aFlappingChargerNeverLiftsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            val source = source(inputs)
            inputs.switch.emit(true)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { source.posture.collect { seen += it.active } }

            repeat(10) {
                inputs.power.value = PowerState(charging = true)
                advanceTimeBy(LowPowerPolicy.CHARGE_HOLD_MS / 2)
                inputs.power.value = PowerState(charging = false)
                advanceTimeBy(1_000)
            }
            assertEquals("one reading, never lifted", listOf(true), seen)
        }

    @Test
    fun batterySaverTurningItselfOffOnAPlugInIsHeldLikeTheCharger() =
        runTest(UnconfinedTestDispatcher()) {
            // AOSP turns Battery Saver off at a plug-in and a sticky one back on at the unplug: with the switch off, that
            // lift must wait out the hold too, or a flapping cable cycles Wi-Fi Aware (the advisory review).
            val inputs = Inputs()
            inputs.saver.value = true
            val source = source(inputs)
            inputs.switch.emit(false)
            assertEquals(LowPowerReason.BATTERY_SAVER, source.current.reason)

            val seen = mutableListOf<LowPowerReason?>()
            backgroundScope.launch { source.posture.collect { seen += it.reason } }
            inputs.power.value = PowerState(charging = true)
            inputs.saver.value = false
            advanceTimeBy(LowPowerPolicy.CHARGE_HOLD_MS / 2)
            // The unplug's broadcast first, the sticky Battery Saver a moment after: the instant between is not a lift.
            inputs.power.value = PowerState(charging = false)
            advanceTimeBy(100)
            inputs.saver.value = true
            advanceTimeBy(LowPowerPolicy.LIFT_SETTLE_MS * 2)
            assertEquals("never lifted", listOf<LowPowerReason?>(LowPowerReason.BATTERY_SAVER), seen)

            inputs.power.value = PowerState(charging = true)
            inputs.saver.value = false
            advanceTimeBy(LowPowerPolicy.CHARGE_HOLD_MS + 1)
            assertNull("lifted once the charger held", source.current.reason)
        }

    @Test
    fun theUsersOwnSwitchLiftsItAtOnceEvenOnTheCharger() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            inputs.saver.value = true
            val source = source(inputs)
            inputs.switch.emit(true)
            inputs.power.value = PowerState(charging = true)
            inputs.saver.value = false
            advanceTimeBy(1_000)
            assertTrue("held on the charger", source.current.active)

            inputs.switch.emit(false)
            assertFalse("the switch is the user's own lift", source.current.active)
        }

    @Test
    fun aPhoneThatStartsOnTheChargerIsNotHeld() =
        runTest(UnconfinedTestDispatcher()) {
            val inputs = Inputs()
            inputs.power.value = PowerState(charging = true)
            val source = source(inputs)
            inputs.switch.emit(true)
            assertFalse("the first reading is not an edge", source.current.active)
        }

    @Test
    fun awareIsOffWhileAnySwitchSaysSoAndWaitsForAll() =
        runTest(UnconfinedTestDispatcher()) {
            val userOn = MutableSharedFlow<Boolean>(replay = 1)
            val debugOff = MutableStateFlow(false)
            val posture = MutableSharedFlow<LowPowerPosture>(replay = 1)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { awareSwitchedOff(userOn, debugOff, posture).collect { seen += it } }
            posture.emit(LowPowerPosture.OFF)
            assertEquals("silent until the person's switch is read", emptyList<Boolean>(), seen)

            userOn.emit(true)
            debugOff.value = true
            posture.emit(LowPowerPosture(LowPowerReason.BATTERY_SAVER))
            debugOff.value = false
            posture.emit(LowPowerPosture.OFF)
            userOn.emit(false) // Settings › Wi-Fi Aware off (GitHub #44)
            userOn.emit(true)
            assertEquals(listOf(false, true, false, true, false), seen)
        }

    @Test
    fun awareWontRunIsTheSwitchOrThePlane() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val plane = MutableStateFlow(false)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { awareWontRun(off, plane).collect { seen += it } }
            plane.value = true // the radio off by evidence, or attaching given up
            plane.value = false
            off.value = true // switched off
            plane.value = true // still true: no repeat
            off.value = false
            plane.value = false
            assertEquals(listOf(false, true, false, true, false), seen)
        }
}
