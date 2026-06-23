package app.getknit.knit.mesh

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the mesh alive while the app is backgrounded. Hosts the singleton
 * [MeshManager] and adds the background-survival machinery: a periodic heartbeat alarm, a
 * significant-motion trigger (new location → likely new peers), and Bluetooth-recovery — all of
 * which nudge the transport to rescan/reconnect. The UI controls the mesh by starting/stopping it.
 */
class MeshService : LifecycleService() {

    private val meshManager: MeshManager by inject()

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var significantMotion: Sensor? = null

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            meshManager.heal()
            armSignificantMotion() // one-shot sensor; re-arm for the next move
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                meshManager.restart()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground()
        meshManager.start()
        scheduleHeartbeat()
        armSignificantMotion()
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HEAL -> meshManager.heal()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothReceiver) }
        significantMotion?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
        cancelHeartbeat()
        meshManager.stop()
        super.onDestroy()
    }

    private fun armSignificantMotion() {
        significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        significantMotion?.let { sensorManager.requestTriggerSensor(motionListener, it) }
    }

    private fun heartbeatIntent(): PendingIntent = PendingIntent.getService(
        this, 2, Intent(this, MeshService::class.java).setAction(ACTION_HEAL),
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

    private fun startForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(getString(R.string.mesh_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.mesh_notification_stop), stop)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mesh_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "knit_mesh"
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
