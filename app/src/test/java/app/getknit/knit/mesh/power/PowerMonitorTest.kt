package app.getknit.knit.mesh.power

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The power inputs `PowerPolicy` reads, from the system's own signals: a seed at [PowerMonitor.start] (so the
 * first scan cadence is already right, before any broadcast), then each screen, charger and battery-level
 * transition, and silence once stopped. Charging is what keeps a node off its relaxed cadence (ADR 2026-09.kb68),
 * so the seed must count a full battery on the charger as charging.
 */
@RunWith(AndroidJUnit4::class)
class PowerMonitorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val source = PowerStateSource()
    private val monitor = PowerMonitor(app, source)

    @After
    fun tearDown() = monitor.stop()

    private fun battery(
        status: Int,
        level: Int,
        scale: Int = 100,
    ) {
        @Suppress("DEPRECATION") // the sticky BATTERY_CHANGED is exactly what the seed reads
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, scale),
        )
    }

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theSeedReadsTheScreenAndTheStickyBattery() {
        shadowOf(app.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        battery(BatteryManager.BATTERY_STATUS_FULL, level = 10)

        monitor.start()

        assertEquals(PowerState(interactive = false, charging = true, batteryLow = true), source.state.value)
    }

    @Test
    fun aDischargingBatteryAboveTheThresholdIsNeitherChargingNorLow() {
        battery(BatteryManager.BATTERY_STATUS_DISCHARGING, level = 16)
        monitor.start()
        assertEquals(PowerState(interactive = true, charging = false, batteryLow = false), source.state.value)
    }

    @Test
    fun aBatteryWithNoScaleIsNeverCalledLow() {
        battery(BatteryManager.BATTERY_STATUS_CHARGING, level = 0, scale = 0)
        monitor.start()
        assertEquals(PowerState(interactive = true, charging = true, batteryLow = false), source.state.value)
    }

    @Test
    fun eachTransitionMovesOnlyItsOwnInput() {
        battery(BatteryManager.BATTERY_STATUS_DISCHARGING, level = 80)
        monitor.start()

        broadcast(Intent.ACTION_SCREEN_OFF)
        assertEquals(PowerState(interactive = false), source.state.value)
        broadcast(Intent.ACTION_POWER_CONNECTED)
        assertEquals(PowerState(interactive = false, charging = true), source.state.value)
        broadcast(Intent.ACTION_BATTERY_LOW)
        assertEquals(PowerState(interactive = false, charging = true, batteryLow = true), source.state.value)
        broadcast(Intent.ACTION_SCREEN_ON)
        broadcast(Intent.ACTION_POWER_DISCONNECTED)
        broadcast(Intent.ACTION_BATTERY_OKAY)
        assertEquals(PowerState(), source.state.value)
    }

    @Test
    fun aStoppedMonitorHearsNothing() {
        battery(BatteryManager.BATTERY_STATUS_DISCHARGING, level = 80)
        monitor.start()
        monitor.stop()
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertEquals(PowerState(), source.state.value)
        monitor.stop() // a second stop is harmless
    }
}
