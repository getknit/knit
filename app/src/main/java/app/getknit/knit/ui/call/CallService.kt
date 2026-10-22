package app.getknit.knit.ui.call

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.R
import app.getknit.knit.call.CallAudioPolicy
import app.getknit.knit.call.CallAudioRoute
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallRouting
import app.getknit.knit.call.CallState
import app.getknit.knit.call.SystemCallSync
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the microphone while a call is live and Knit is not on screen (experimental, `CALLS_WIRED`): a foreground
 * service of types `microphone` and `phoneCall` (the call is a Telecom one, §3.13), started once RECORD_AUDIO is
 * granted by [CallActivity] while it is in the foreground, or by [startAnswered] from inside Telecom's answer when a
 * headset, a watch or a car took the call — the two places Android 14+ lets a microphone service start. Stops itself
 * when the call ends.
 *
 * Its notification is the way back to a call the user left: the ongoing CallStyle with the peer's face, where the call
 * stands (the timer once connected), Hang up, Mute (a live call's only) and one route action — onto the loudspeaker,
 * or off it to the headset or the earpiece ([CallAudioPolicy.notificationTarget]) — each naming what a tap will do. It
 * is posted again on every change of [CallManager.state] and of the route, so those always show the call as it is.
 * While the call is on the earpiece it also holds the [ProximityLock], so the screen goes dark at the ear wherever the
 * user left the call screen.
 */
class CallService :
    LifecycleService(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val system: SystemCallSync by inject()
    private val faces: CallFaces by inject()
    private val proximity by lazy { ProximityLock(this) }
    private var watching = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        // First, always: a started foreground service that stops before claiming the state is a crash.
        claim(calls.state.value, null, system.routing.value)
        if (!watching) {
            watching = true
            lifecycleScope.launch {
                combine(calls.state, system.routing, ::Pair).collect { (state, routing) ->
                    if (state == null || state.phase == CallPhase.Ended) {
                        proximity.end()
                        ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        // The screen goes dark at the ear while the call is on the earpiece (C-3.13-11).
                        proximity.hold(CallAudioPolicy.screenOffNearEar(state, routing))
                        claim(state, faces.of(state.peerId), routing)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        proximity.end()
        super.onDestroy()
    }

    private fun claim(
        state: CallState?,
        face: CallFace?,
        routing: CallRouting?,
    ) {
        val id = state?.id.orEmpty()
        val person = face?.person ?: Person.Builder().setName(getString(R.string.call_notif_unnamed)).build()
        val notification =
            NotificationCompat
                .Builder(this, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(face?.name ?: getString(R.string.call_notif_unnamed))
                .setContentText(textOf(state))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setColor(ONGOING_GREEN)
                .setColorized(true)
                .setContentIntent(PendingIntent.getActivity(this, 2, CallActivity.intent(this, id), CallNotifications.FLAGS))
                .setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        person,
                        CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANGUP, id),
                    ),
                ).apply {
                    state?.connectedAt?.let {
                        setWhen(it)
                        setShowWhen(true)
                        setUsesChronometer(true)
                    }
                    // A walkie session has no live microphone to mute (§3.11); its audio has a route as a call's does.
                    if (state != null && !state.walkie) {
                        addAction(
                            action(
                                if (state.muted) R.drawable.ic_call_mic else R.drawable.ic_call_mic_off,
                                if (state.muted) "Unmute" else "Mute",
                                CallActionReceiver.ACTION_MUTE,
                                id,
                                !state.muted,
                            ),
                        )
                    }
                    routing?.let(CallAudioPolicy::notificationTarget)?.let { addAction(routeAction(it, id)) }
                }.build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            } else {
                0
            }
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID + 1, notification, type)
        } catch (e: SecurityException) {
            // RECORD_AUDIO revoked between the check and here, or started from the background: nothing to hold.
            Log.w(TAG, "call service refused: ${e.message}")
        }
    }

    // Each names what a tap does, with the icon of where it leads, and carries the state it sets (not a toggle), so a
    // tap on a notification a beat behind the call can't flip it the wrong way.
    private fun action(
        icon: Int,
        label: String,
        kind: String,
        id: String,
        on: Boolean,
    ): NotificationCompat.Action =
        NotificationCompat.Action
            .Builder(IconCompat.createWithResource(this, icon), label, CallActionReceiver.pending(this, kind, id, on))
            .build()

    // Named and drawn by where it leads, and carrying that route's key, so a stale notification cannot send the call
    // anywhere the user did not see.
    private fun routeAction(
        target: CallAudioRoute,
        id: String,
    ): NotificationCompat.Action {
        val icon =
            when (target.kind) {
                CallAudioRoute.Kind.SPEAKER -> R.drawable.ic_call_speaker
                CallAudioRoute.Kind.EARPIECE -> R.drawable.ic_call_earpiece
                CallAudioRoute.Kind.BLUETOOTH -> R.drawable.ic_call_bluetooth
                CallAudioRoute.Kind.WIRED, CallAudioRoute.Kind.OTHER -> R.drawable.ic_call_headset
            }
        return NotificationCompat.Action
            .Builder(
                IconCompat.createWithResource(this, icon),
                routeLabel(this, target),
                CallActionReceiver.pending(this, CallActionReceiver.ACTION_ROUTE, id, route = target.key),
            ).build()
    }

    private fun textOf(state: CallState?): String =
        getString(
            when (state?.phase) {
                CallPhase.Calling -> callingText(state)
                CallPhase.Ringing -> R.string.call_status_ringing
                CallPhase.Connecting -> R.string.call_status_connecting
                CallPhase.Connected -> connectedText(state)
                CallPhase.Reconnecting -> R.string.call_status_reconnecting
                else -> R.string.call_notif_unnamed
            },
        )

    /** An outgoing call with no RINGING back: whether anything reaches the peer (C-3.3-5), and whether it answered (C-3.3-6). */
    @StringRes
    private fun callingText(state: CallState): Int =
        when {
            state.unreachable -> R.string.call_notif_unreachable
            state.unproven -> R.string.call_notif_unproven
            else -> R.string.call_status_calling
        }

    @StringRes
    private fun connectedText(state: CallState): Int =
        when {
            state.walkie -> R.string.call_notif_walkie
            state.muted -> R.string.call_notif_voice_muted
            else -> R.string.call_notif_voice
        }

    companion object {
        private const val TAG = "KnitCall"

        /**
         * Starts the service for a call the system answered — a headset's button, a watch, a car — where the call screen
         * may never open (§3.13, C-3.13-10). Called from inside Telecom's answer, which lets an app in the background
         * start it with the microphone (device-observed on a Pixel 9, API 37: `allowWiu` ACTIVITY_STARTER). Where Android
         * refuses, the call runs unheard until the user opens it, and the log says so.
         */
        fun startAnswered(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "call service not started: no microphone grant; the call screen asks for it")
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: this phone does not count the answer as a reason.
                Log.w(TAG, "call service not started: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // The ongoing-call green the system's own call chip uses, dark enough for white text.
        private const val ONGOING_GREEN = 0xFF137333.toInt()
    }
}
