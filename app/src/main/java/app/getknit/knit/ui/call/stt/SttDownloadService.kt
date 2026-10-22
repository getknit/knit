package app.getknit.knit.ui.call.stt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.R
import app.getknit.knit.call.SttModelState
import app.getknit.knit.ui.call.CallNotifications
import app.getknit.knit.ui.call.mb
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Holds the process while the speech model (walkie transcripts, live captions) downloads (docs/VOICE_CALLS.md C-3.11-9;
 * experimental, `STT_WIRED`): a `dataSync` foreground service, started by [SttModelStore] as a download begins, so a
 * hang-up, a screen left or the cached-app freezer does not stall a fetch of 142 MB half way. It does none of the work — the
 * download is the store's job on the app scope — and stops itself as soon as the store's state is anything but
 * [SttModelState.Downloading].
 *
 * Its notification is the download's progress and its Cancel, for a user who left the screen they started it on; a tap
 * opens the app. On Android 15's `dataSync` time limit it cancels the download (what was fetched stays, and the next
 * Download picks up from there) and stops.
 */
class SttDownloadService :
    LifecycleService(),
    KoinComponent {
    private val store: SttModelStore by inject()
    private var watching = false

    // The last progress posted and when: the store reports every 256 KiB, more than the shade will take.
    private var shownPercent = -1
    private var shownAt = 0L

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        // First, always: a started foreground service that stops before claiming the state is a crash.
        claim(store.state.value)
        if (intent?.action == ACTION_CANCEL) store.cancel()
        if (!watching) {
            watching = true
            lifecycleScope.launch {
                store.state.collect { state ->
                    if (state is SttModelState.Downloading) update(state) else stop()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.i(TAG, "speech model download out of foreground time; cancelling")
        store.cancel()
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Posts [state] when its whole percent moved and a second has passed since the last post.
    private fun update(state: SttModelState.Downloading) {
        val now = SystemClock.elapsedRealtime()
        if (percentOf(state) == shownPercent || now - shownAt < UPDATE_MS) return
        claim(state)
    }

    private fun percentOf(state: SttModelState.Downloading): Int =
        if (state.total > 0) (state.done * PROGRESS_MAX / state.total).toInt() else 0

    private fun claim(state: SttModelState) {
        CallNotifications.ensureChannels(this)
        val downloading = state as? SttModelState.Downloading
        val notification =
            NotificationCompat
                .Builder(this, CallNotifications.CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(getString(R.string.stt_notif_title))
                .setContentText(
                    downloading?.let { getString(R.string.stt_notif_progress, mb(this, it.done), mb(this, it.total)) }
                        ?: getString(R.string.stt_notif_starting),
                ).setProgress(PROGRESS_MAX, downloading?.let(::percentOf) ?: 0, downloading == null)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .apply { openApp()?.let(::setContentIntent) }
                .addAction(
                    0,
                    getString(R.string.call_settings_speech_model_cancel),
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, SttDownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
        shownPercent = downloading?.let(::percentOf) ?: -1
        shownAt = SystemClock.elapsedRealtime()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: IllegalStateException) {
            // Out of foreground time, or a start the platform refused: the download goes on as long as the process does.
            Log.w(TAG, "speech model download service refused: ${e.message}")
        }
    }

    private fun openApp(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    companion object {
        private const val TAG = "KnitCall"
        private const val ACTION_CANCEL = "app.getknit.knit.stt.CANCEL"
        private const val NOTIFICATION_ID = CallNotifications.ID + 2
        private const val PROGRESS_MAX = 100
        private const val UPDATE_MS = 1_000L

        /**
         * Starts the service for a download under way. Throws where the platform refuses a foreground start (Android 12+,
         * from the background: only the debug bridge starts a download there); [SttModelStore] logs that and goes on.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SttDownloadService::class.java))
        }
    }
}
