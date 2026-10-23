package app.getknit.knit.ui.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.MissedCall
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.di.STT_WIRED
import app.getknit.knit.notifications.NotificationChannels
import app.getknit.knit.ui.fullScreenCallSettingsIntent
import app.getknit.knit.ui.fullScreenCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The Android half of a call's alerting (experimental, `CALLS_WIRED`): rings an incoming call with a CallStyle
 * notification and a full-screen intent to [CallActivity], opens [CallActivity] for an outgoing one, and notifies a
 * missed call. Collects [CallManager] from process start ([app.getknit.knit.di.startCallsIfWired]), so a call rings
 * whether or not the UI was ever opened; the mesh service keeps the process (and its spool connection) alive.
 */
internal class CallController(
    private val context: Context,
    // [CallManager.state] and [CallManager.missed].
    private val calls: Flow<CallState?>,
    private val missedCalls: Flow<MissedCall>,
    private val scope: CoroutineScope,
    // [CallFaces.of].
    private val faceOf: suspend (peerId: String) -> CallFace,
    private val alertMs: Long,
    private val canRingFullScreen: () -> Boolean? = { fullScreenCalls(context, callsWired = true) },
) {
    @Volatile
    private var ringing: String? = null

    @Volatile
    private var opened: String? = null

    fun start() {
        CallNotifications.ensureChannels(context)
        scope.launch { calls.collect { onState(it) } }
        scope.launch { missedCalls.collect { onMissed(it) } }
    }

    private suspend fun onState(state: CallState?) {
        state ?: return
        when {
            state.phase == CallPhase.Alerting -> {
                if (ringing != state.id) {
                    ringing = state.id
                    ring(state)
                }
            }

            state.outgoing && state.phase == CallPhase.Calling -> {
                // The user just tapped Call, so Knit is in the foreground and may start an activity.
                if (opened != state.id) {
                    opened = state.id
                    context.startActivity(CallActivity.intent(context, state.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            ringing == state.id -> {
                ringing = null
                NotificationManagerCompat.from(context).cancel(CallNotifications.TAG_RING, CallNotifications.ID)
            }
        }
    }

    private suspend fun ring(state: CallState) {
        val face = faceOf(state.peerId)
        val screen = PendingIntent.getActivity(context, 0, CallActivity.intent(context, state.id), CallNotifications.FLAGS)
        val answer =
            PendingIntent.getActivity(
                context,
                1,
                CallActivity.intent(context, state.id).setAction(CallActivity.ACTION_ANSWER),
                CallNotifications.FLAGS,
            )
        val decline = CallActionReceiver.pending(context, CallActionReceiver.ACTION_DECLINE, state.id)
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_RING)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(face.name)
                .setContentText(context.getString(if (state.walkie) R.string.call_notif_ring_walkie else R.string.call_notif_ring_voice))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                // A lost CANCEL and a dead process must not leave the phone ringing (the manager's alert timeout).
                .setTimeoutAfter(alertMs)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(face.person, decline, answer))
                .build()
                .apply { flags = flags or Notification.FLAG_INSISTENT }
        CallNotifications.post(context, CallNotifications.TAG_RING, notification)
        if (canRingFullScreen() == false) askForFullScreen()
    }

    /**
     * Android 14+ grants a sideloaded build no full-screen intents, and nothing an app does can grant it: without it an
     * incoming call is a heads-up the locked phone never shows. Onboarding's optional row is the one place Knit asks
     * unprompted, and the Calls screen shows the state; this says so only when a call has just rung without it, never at
     * process start, which re-asked on every reboot whatever the person had answered. Its tap opens the one system page
     * that can allow it (or `adb shell appops set app.getknit.knit USE_FULL_SCREEN_INTENT allow`).
     */
    private fun askForFullScreen() {
        val settings = fullScreenCallSettingsIntent(context)
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(context.getString(R.string.call_notif_full_screen_title))
                .setContentText(context.getString(R.string.call_notif_full_screen_body))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 3, settings, CallNotifications.FLAGS))
                .build()
        CallNotifications.post(context, CallNotifications.TAG_FULL_SCREEN, notification)
    }

    private suspend fun onMissed(missed: MissedCall) {
        val face = faceOf(missed.peerId)
        val open =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/${missed.peerId}")
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(context.getString(R.string.call_notif_missed))
                .setContentText(if (missed.noRelay) context.getString(R.string.call_notif_missed_no_relay, face.name) else face.name)
                .setLargeIcon(face.icon.toIcon(context))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setWhen(missed.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, missed.peerId.hashCode(), open, CallNotifications.FLAGS))
                .build()
        CallNotifications.post(context, "${CallNotifications.TAG_MISSED}${missed.peerId}", notification)
    }
}

/** The display name a call surface shows for [peerId], disambiguated like every other label in the app. */
internal suspend fun callerName(
    peers: PeerRepository,
    peerId: String,
): String = peers.labelIndex().labelFor(peerId, peers.find(peerId)?.name).text

/** The calls' notification channels and the one guarded post (`CALLS_WIRED`). */
internal object CallNotifications {
    // Bumped to _v2 when the ring became Knit's own (ADR 2026-10.xukf): a channel's sound is immutable once
    // created, so the `knit_calls` channel that rang the phone's default ringtone is deleted in [ensureChannels].
    const val CHANNEL_RING = "knit_calls_v2"
    private const val LEGACY_RING = "knit_calls"
    const val CHANNEL_STATUS = "knit_call_status"

    // The speech model's download (`SttDownloadService`, where `STT_WIRED`): progress only, never a sound.
    const val CHANNEL_DOWNLOAD = "knit_stt_download"
    const val TAG_RING = "call:ring"
    const val TAG_MISSED = "call:missed:"
    const val TAG_FULL_SCREEN = "call:fullscreen"
    const val ID = 4_100
    const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_RING, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.call_notif_channel_ring))
                .setSound(
                    // One loop with its pause inside; the ring's FLAG_INSISTENT plays it until the call is answered.
                    NotificationChannels.rawSound(context, R.raw.knit_ring),
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setVibrationEnabled(true)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.call_notif_channel_status))
                .build(),
        )
        if (STT_WIRED) {
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_DOWNLOAD, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.call_notif_channel_download))
                    .build(),
            )
        }
        manager.deleteNotificationChannel(LEGACY_RING)
    }

    fun post(
        context: Context,
        tag: String,
        notification: Notification,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching { manager.notify(tag, ID, notification) }
    }
}
