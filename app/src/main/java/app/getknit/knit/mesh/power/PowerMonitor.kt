package app.getknit.knit.mesh.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Feeds [PowerStateSource] from Android system signals: screen on/off
 * ([Intent.ACTION_SCREEN_ON] / [Intent.ACTION_SCREEN_OFF]), charger connect/disconnect, and the
 * coarse battery-low/okay transitions — plus an initial seed of all three so state is correct before
 * the first broadcast arrives. The screen actions cannot be declared in the manifest, so the
 * receiver is registered at runtime; [app.getknit.knit.mesh.MeshService] owns its [start]/[stop]
 * lifecycle (mirroring its Bluetooth receiver).
 */
class PowerMonitor(
    context: Context,
    private val source: PowerStateSource,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> source.update { it.copy(interactive = true) }
                Intent.ACTION_SCREEN_OFF -> source.update { it.copy(interactive = false) }
                Intent.ACTION_POWER_CONNECTED -> source.update { it.copy(charging = true) }
                Intent.ACTION_POWER_DISCONNECTED -> source.update { it.copy(charging = false) }
                Intent.ACTION_BATTERY_LOW -> source.update { it.copy(batteryLow = true) }
                Intent.ACTION_BATTERY_OKAY -> source.update { it.copy(batteryLow = false) }
            }
        }
    }

    fun start() {
        seed()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** Reads current interactivity and a sticky battery snapshot so the state is correct immediately. */
    private fun seed() {
        val interactive = powerManager?.isInteractive ?: true
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { status ->
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val batteryLow = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level >= 0 && scale > 0 && level.toFloat() / scale <= LOW_BATTERY_THRESHOLD
        } ?: false
        source.update { it.copy(interactive = interactive, charging = charging, batteryLow = batteryLow) }
    }

    private companion object {
        const val LOW_BATTERY_THRESHOLD = 0.15f
    }
}
