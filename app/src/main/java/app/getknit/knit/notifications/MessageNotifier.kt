package app.getknit.knit.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations

/**
 * Builds and posts "new message" notifications, one per context channel (Nearby / Group messages /
 * Direct messages / Mentions). Each channel keeps its own in-memory [NotificationHistory] and
 * re-posts one stable [NotificationCompat.MessagingStyle] notification, so multiple senders render as
 * one grouped conversation (avatar + name per line) and — combined with
 * [NotificationCompat.Builder.setOnlyAlertOnce] — a busy channel updates silently instead of buzzing
 * repeatedly. Channels themselves are owned by [NotificationChannels].
 *
 * Suppression is per-conversation: while a conversation is on screen ([setVisibleConversation]) its
 * messages are not notified, and opening it clears its already-posted messages from every channel —
 * but other conversations keep notifying normally. A Koin process singleton; lives as long as
 * MeshService keeps the process alive.
 */
class MessageNotifier(private val context: Context) : Notifier {

    private val manager = NotificationManagerCompat.from(context)

    /** The conversation currently on screen, or null when none is. */
    @Volatile
    private var visibleConversationId: String? = null

    /** Last "me" identity seen on a post, so a clear-driven re-post can rebuild the MessagingStyle. */
    @Volatile
    private var lastSelf: Self? = null

    private class Self(val id: String, val name: String, val avatarBytes: ByteArray?)

    /** One per channel: its target channel, notification id, conversation title, and accumulated history. */
    private class Bucket(
        val channelId: String,
        val notificationId: Int,
        val titleRes: Int,
        val openRequest: Int,
        val dismissRequest: Int,
    ) {
        val history = NotificationHistory()
    }

    private val nearby = Bucket(
        NotificationChannels.NEARBY, ID_NEARBY, R.string.notif_title_nearby, REQ_OPEN_NEARBY, REQ_DISMISS_NEARBY,
    )
    private val groups = Bucket(
        NotificationChannels.GROUPS, ID_GROUPS, R.string.notif_title_groups, REQ_OPEN_GROUPS, REQ_DISMISS_GROUPS,
    )
    private val dms = Bucket(
        NotificationChannels.DMS, ID_DMS, R.string.notif_title_dms, REQ_OPEN_DMS, REQ_DISMISS_DMS,
    )
    private val mentions = Bucket(
        NotificationChannels.MENTIONS, ID_MENTIONS, R.string.notif_title_mentions, REQ_OPEN_MENTIONS, REQ_DISMISS_MENTIONS,
    )
    private val allBuckets = listOf(nearby, groups, dms, mentions)

    private fun bucketFor(kind: ConversationKind): Bucket = when (kind) {
        ConversationKind.NEARBY -> nearby
        ConversationKind.GROUP -> groups
        ConversationKind.DM -> dms
    }

    override fun createChannel() = NotificationChannels.ensure(context)

    override fun notify(incoming: NotifMessage, selfId: String, selfName: String, selfAvatarBytes: ByteArray?) =
        post(bucketFor(Conversations.kindFor(incoming.conversationId)), incoming, selfId, selfName, selfAvatarBytes)

    override fun notifyMention(incoming: NotifMessage, selfId: String, selfName: String, selfAvatarBytes: ByteArray?) =
        post(mentions, incoming, selfId, selfName, selfAvatarBytes)

    /** Records [incoming] in [bucket]'s history and (re)posts its grouped MessagingStyle notification. */
    private fun post(bucket: Bucket, incoming: NotifMessage, selfId: String, selfName: String, selfAvatarBytes: ByteArray?) {
        // The user is already looking at this conversation — nothing to surface.
        if (incoming.conversationId == visibleConversationId) return
        val self = Self(selfId, selfName, selfAvatarBytes).also { lastSelf = it }
        val messages = synchronized(bucket.history) { bucket.history.add(incoming) }
        buildAndPost(bucket, messages, self)
    }

    /** Builds a MessagingStyle notification from [messages] and posts it on [bucket]'s id/channel. */
    private fun buildAndPost(bucket: Bucket, messages: List<NotifMessage>, self: Self) {
        if (!manager.areNotificationsEnabled()) return
        // Explicit POST_NOTIFICATIONS check (runtime permission on API 33+). areNotificationsEnabled()
        // already implies it, but lint's flow analysis needs the explicit check on every path to the
        // manager.notify() call below (post() and setVisibleConversation() both reach it through here).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val me = personOf(self.id, self.name.ifBlank { context.getString(R.string.notif_self_name) }, self.avatarBytes)
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(true)
            .setConversationTitle(context.getString(bucket.titleRes))
        messages.forEach { m ->
            style.addMessage(m.body, m.sentAt, personOf(m.senderId, m.senderName, m.avatarBytes))
        }

        val notification = NotificationCompat.Builder(context, bucket.channelId)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChatIntent(bucket.openRequest))
            .setDeleteIntent(dismissIntent(bucket.notificationId, bucket.dismissRequest))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        // Guarded by the caller; runCatching still defends a permission revoked between the check and here.
        runCatching { manager.notify(bucket.notificationId, notification) }
    }

    override fun setVisibleConversation(conversationId: String?) {
        visibleConversationId = conversationId
        // Leaving a chat (null) just resumes notifying; opening one clears what it already showed.
        if (conversationId == null) return
        val self = lastSelf
        // A conversation's messages can live in its kind channel and (if it @-mentioned us) in Mentions,
        // so scan every bucket and re-post the survivors (or cancel the notification when none remain).
        allBuckets.forEach { bucket ->
            val remaining = synchronized(bucket.history) {
                if (bucket.history.remove(conversationId)) bucket.history.snapshot() else null
            } ?: return@forEach
            if (remaining.isEmpty() || self == null) {
                manager.cancel(bucket.notificationId)
            } else {
                buildAndPost(bucket, remaining, self)
            }
        }
    }

    override fun onDismissed(notificationId: Int) {
        val bucket = allBuckets.firstOrNull { it.notificationId == notificationId } ?: return
        synchronized(bucket.history) { bucket.history.clear() }
    }

    private fun personOf(id: String, name: String, avatarBytes: ByteArray?): Person =
        Person.Builder()
            .setKey(id)
            .setName(name.ifBlank { id })
            .apply { iconFor(avatarBytes)?.let { setIcon(it) } }
            .build()

    /** Decodes avatar [bytes] (from the encrypted blob store) into an adaptive-bitmap icon; null -> no icon. */
    private fun iconFor(bytes: ByteArray?): IconCompat? {
        val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            ?: return null
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    private fun openChatIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dismissIntent(notificationId: Int, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .putExtra(EXTRA_NOTIF_ID, notificationId)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_DISMISS = "app.getknit.knit.MESSAGE_NOTIFICATION_DISMISSED"
        const val EXTRA_NOTIF_ID = "app.getknit.knit.NOTIF_ID"

        // Notification ids — id 1 is MeshService's foreground notification.
        private const val ID_NEARBY = 2
        private const val ID_MENTIONS = 3
        private const val ID_GROUPS = 4
        private const val ID_DMS = 5

        // PendingIntent request codes — distinct per bucket and action so the intents don't collide.
        private const val REQ_OPEN_NEARBY = 10
        private const val REQ_DISMISS_NEARBY = 11
        private const val REQ_OPEN_GROUPS = 12
        private const val REQ_DISMISS_GROUPS = 13
        private const val REQ_OPEN_DMS = 14
        private const val REQ_DISMISS_DMS = 15
        private const val REQ_OPEN_MENTIONS = 16
        private const val REQ_DISMISS_MENTIONS = 17
    }
}
