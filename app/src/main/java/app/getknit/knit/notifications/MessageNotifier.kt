package app.getknit.knit.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R

/**
 * Builds and posts the single proximity-room "new message" notification with
 * [NotificationCompat.MessagingStyle], so multiple senders render as one grouped conversation
 * (avatar + name per line). Holds an in-memory [NotificationHistory] and re-posts one stable
 * notification — combined with [NotificationCompat.Builder.setOnlyAlertOnce] a busy room updates
 * silently instead of buzzing repeatedly. A Koin process singleton; lives as long as MeshService
 * keeps the process alive.
 */
class MessageNotifier(private val context: Context) : Notifier {

    private val history = NotificationHistory()
    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var chatVisible = false

    override fun createChannel() {
        val messages = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.message_channel_name))
            .setDescription(context.getString(R.string.message_channel_description))
            .build()
        val mentions = NotificationChannelCompat.Builder(MENTION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.mention_channel_name))
            .setDescription(context.getString(R.string.mention_channel_description))
            .build()
        manager.createNotificationChannel(messages)
        manager.createNotificationChannel(mentions)
    }

    override fun notify(incoming: NotifMessage, selfId: String, selfName: String, selfAvatarPath: String?) {
        if (chatVisible) return
        if (!manager.areNotificationsEnabled()) return
        // Explicit POST_NOTIFICATIONS check (runtime permission on API 33+; auto-granted below).
        // areNotificationsEnabled() already implies this, but lint's flow analysis needs the
        // explicit check on the path to manager.notify() below.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val messages = synchronized(history) { history.add(incoming) }
        val me = personOf(selfId, selfName.ifBlank { context.getString(R.string.notif_self_name) }, selfAvatarPath)
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(true)
            .setConversationTitle(context.getString(R.string.message_conversation_title))
        messages.forEach { m ->
            style.addMessage(m.body, m.sentAt, personOf(m.senderId, m.senderName, m.avatarPath))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChatIntent())
            .setDeleteIntent(dismissIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        // Guarded above; runCatching still defends a permission revoked between the check and here.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    override fun notifyMention(incoming: NotifMessage, selfId: String, selfName: String, selfAvatarPath: String?) {
        if (chatVisible) return
        if (!manager.areNotificationsEnabled()) return
        // Same explicit POST_NOTIFICATIONS guard as notify() (lint needs it on the path to notify()).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // A mention is a targeted call-out, so it posts its own standalone notification (not the grouped
        // MessagingStyle the room uses) to avoid being buried among other senders.
        val notification = NotificationCompat.Builder(context, MENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(incoming.senderName)
            .setContentText(incoming.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(incoming.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mentionOpenIntent())
            .setAutoCancel(true)
            .build()

        // Guarded above; runCatching still defends a permission revoked between the check and here.
        runCatching { manager.notify(MENTION_NOTIFICATION_ID, notification) }
    }

    override fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) clear()
    }

    override fun clear() {
        synchronized(history) { history.clear() }
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(MENTION_NOTIFICATION_ID)
    }

    override fun onDismissed() {
        synchronized(history) { history.clear() }
    }

    private fun personOf(id: String, name: String, avatarPath: String?): Person =
        Person.Builder()
            .setKey(id)
            .setName(name.ifBlank { id })
            .apply { iconFor(avatarPath)?.let { setIcon(it) } }
            .build()

    /** Decodes a cached avatar file into an adaptive-bitmap icon; null (missing/evicted) -> no icon. */
    private fun iconFor(path: String?): IconCompat? {
        val bitmap = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } ?: return null
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    private fun openChatIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN_CHAT, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dismissIntent(): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_DISMISS)
        return PendingIntent.getBroadcast(context, REQUEST_DISMISS, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun mentionOpenIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN_MENTION, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_DISMISS = "app.getknit.knit.MESSAGE_NOTIFICATION_DISMISSED"

        private const val CHANNEL_ID = "knit_messages"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_OPEN_CHAT = 3
        private const val REQUEST_DISMISS = 4

        private const val MENTION_CHANNEL_ID = "knit_mentions"
        private const val MENTION_NOTIFICATION_ID = 3
        private const val REQUEST_OPEN_MENTION = 5
    }
}
