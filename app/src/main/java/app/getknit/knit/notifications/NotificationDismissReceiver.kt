package app.getknit.knit.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Resets the message notification's accumulated state when the user swipes the notification away, so
 * already-seen messages don't reappear in the next post. Wired as the notification's deleteIntent.
 */
class NotificationDismissReceiver : BroadcastReceiver(), KoinComponent {

    private val notifier: Notifier by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MessageNotifier.ACTION_DISMISS) return
        val notificationId = intent.getIntExtra(MessageNotifier.EXTRA_NOTIF_ID, -1)
        if (notificationId != -1) notifier.onDismissed(notificationId)
    }
}
