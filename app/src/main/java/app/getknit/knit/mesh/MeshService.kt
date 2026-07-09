package app.getknit.knit.mesh

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.notifications.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the mesh alive while the app is backgrounded. Hosts the singleton
 * [MeshManager] and adds the background-survival machinery: a periodic heartbeat alarm and a
 * significant-motion trigger (new location → likely new peers), both of which nudge the transport to
 * rediscover/reconnect. (Wi-Fi Aware availability changes are handled inside the transport itself.)
 * The UI controls the mesh by starting/stopping it.
 */
class MeshService : LifecycleService() {
    private val meshManager: MeshController by inject()
    private val powerMonitor: PowerMonitor by inject()
    private val settings: SettingsStore by inject()
    private val scope: CoroutineScope by inject()

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var significantMotion: Sensor? = null

    private val motionListener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                meshManager.heal()
                armSignificantMotion() // one-shot sensor; re-arm for the next move
            }
        }

    override fun onCreate() {
        super.onCreate()
        // Channels are normally created at app startup (KnitApplication); ensure defensively in case
        // the process is started straight into the service.
        NotificationChannels.ensure(this)
        startForeground()
        observeStatus()
        powerMonitor.start() // seed power state before the discovery loop first reads it
        meshManager.start()
        // Remember the mesh is running so BootReceiver restores it after a reboot; a later manual Stop
        // flips this off. Guarded to skip the redundant write on the common already-enabled start.
        scope.launch { if (!settings.meshEnabled.first()) settings.setMeshEnabled(true) }
        scheduleHeartbeat()
        armSignificantMotion()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                // User tapped Stop on the ongoing notification: remember it so we don't auto-restart on
                // the next reboot. On the app-lifetime scope so the write outlives stopSelf()/onDestroy().
                scope.launch { settings.setMeshEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_HEAL -> {
                meshManager.heal()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        powerMonitor.stop()
        significantMotion?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
        cancelHeartbeat()
        meshManager.stop()
        super.onDestroy()
    }

    private fun armSignificantMotion() {
        significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        significantMotion?.let { sensorManager.requestTriggerSensor(motionListener, it) }
    }

    private fun heartbeatIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            2,
            Intent(this, MeshService::class.java).setAction(ACTION_HEAL),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleHeartbeat() {
        getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            heartbeatIntent(),
        )
    }

    private fun cancelHeartbeat() {
        getSystemService(AlarmManager::class.java).cancel(heartbeatIntent())
    }

    /** Post the initial ongoing notification synchronously, seeded from the current mesh state. */
    private fun startForeground() {
        postForeground(
            buildNotification(
                meshManager.neighborCount.value,
                meshManager.transportHealth.value,
            ),
        )
    }

    /**
     * Keep the ongoing notification's text in step with live connectivity: the reachable-peer count and
     * radio health. [MeshManager.neighborCount] is already smoothed (it rides the lingered `reachable`
     * set), so the text won't thrash as ephemeral links flap. Cancelled with the service via
     * [lifecycleScope].
     */
    private fun observeStatus() {
        lifecycleScope.launch {
            combine(meshManager.neighborCount, meshManager.transportHealth) { count, health ->
                count to health
            }.distinctUntilChanged()
                .collect { (count, health) -> postForeground(buildNotification(count, health)) }
        }
    }

    private fun buildNotification(
        count: Int,
        health: TransportHealth,
    ): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, MeshService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(contentText(count, health))
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.mesh_notification_stop), stop)
            .build()
    }

    /**
     * The ongoing notification's status line — the non-Compose twin of the chat screens'
     * `connectionLabel`, sharing the same string resources so the shade and the in-app row stay in step.
     */
    private fun contentText(
        count: Int,
        health: TransportHealth,
    ): CharSequence =
        when (health) {
            TransportHealth.Unavailable -> {
                if (isAirplaneModeOn()) {
                    getString(R.string.chat_connection_airplane)
                } else {
                    getString(R.string.chat_connection_radio_off)
                }
            }

            TransportHealth.Degraded -> {
                getString(R.string.chat_connection_degraded)
            }

            TransportHealth.Healthy -> {
                if (count == 0) {
                    getString(R.string.mesh_notification_searching)
                } else {
                    resources.getQuantityString(R.plurals.mesh_notification_connected, count, count)
                }
            }
        }

    private fun isAirplaneModeOn(): Boolean = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /**
     * (Re-)post the foreground notification. Calling [ServiceCompat.startForeground] again with the same
     * id is the supported way to update it, and — unlike `NotificationManagerCompat.notify` — needs no
     * `POST_NOTIFICATIONS` permission.
     */
    private fun postForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Wi-Fi Aware needs no location, so the service is connectedDevice-only (the runtime type
                // must match the manifest's foregroundServiceType — see AndroidManifest.xml).
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    companion object {
        private val CHANNEL_ID = NotificationChannels.STATUS
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.getknit.knit.STOP_MESH"
        private const val ACTION_HEAL = "app.getknit.knit.HEAL_MESH"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MeshService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
