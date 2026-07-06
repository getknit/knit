package app.getknit.knit.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import java.text.BreakIterator

/**
 * Builds and posts "new message" notifications, Signal-style: **one MessagingStyle notification per
 * conversation** (real title + group/peer avatar as the large icon, per-sender lines, and inline
 * **Reply** + **Mark read** actions), all stacked under a single **group summary** ("N messages in M
 * chats"). Each conversation keeps its own in-memory [NotificationHistory] and re-posts under a stable
 * tag ([NotificationManagerCompat.notify] with `tag`=conversation, a shared id), so a busy thread updates
 * its one notification instead of buzzing repeatedly ([NotificationCompat.Builder.setOnlyAlertOnce]).
 * Tapping a notification deep-links straight to that thread (see [openChatIntent] / [MainActivity]).
 * Channels themselves are owned by [NotificationChannels].
 *
 * Mentions post as a **separate** entry (tag `mention:<conversationId>`) on the Mentions channel — still
 * grouped under the same summary — so a thread that both @-mentions you and sends normal messages shows
 * both, matching the app's dedicated-mentions design.
 *
 * Suppression is per-conversation: while a conversation is on screen ([setVisibleConversation]) its
 * messages are not notified, and opening it clears its already-posted notification(s). A Koin process
 * singleton; lives as long as MeshService keeps the process alive.
 */
class MessageNotifier(
    private val context: Context,
) : Notifier {
    private val manager = NotificationManagerCompat.from(context)

    /** The conversation currently on screen, or null when none is. */
    @Volatile
    private var visibleConversationId: String? = null

    /** Last "me" identity seen on a post, so a Mark-read-driven summary refresh can rebuild. */
    @Volatile
    private var lastSelf: Self? = null

    private class Self(
        val id: String,
        val name: String,
        val avatarBytes: ByteArray?,
    )

    /** Per-conversation accumulated state, keyed by notification tag (conversationId or `mention:…`). */
    private class ConvState(
        val tag: String,
        val conversationId: String,
        val isMention: Boolean,
    ) {
        val history = NotificationHistory()

        /** Count of *incoming* messages since last clear (own inline replies don't count) — summary line. */
        var count = 0
        var kind: ConversationKind = ConversationKind.DM
        var title: String? = null
        var avatarBytes: ByteArray? = null
    }

    /** Immutable snapshot of a [ConvState] captured under the lock, so building/posting stays lock-free. */
    private class Render(
        val tag: String,
        val conversationId: String,
        val kind: ConversationKind,
        val isMention: Boolean,
        val title: String?,
        val avatarBytes: ByteArray?,
        val messages: List<NotifMessage>,
    )

    /** Guards [states]; every mutation + snapshot happens under it. */
    private val states = LinkedHashMap<String, ConvState>()

    override fun createChannel() = NotificationChannels.ensure(context)

    override fun notify(
        incoming: NotifMessage,
        conversation: NotifConversation,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    ) = post(conversation, isMention = false, incoming, selfId, selfName, selfAvatarBytes)

    override fun notifyMention(
        incoming: NotifMessage,
        conversation: NotifConversation,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    ) = post(conversation, isMention = true, incoming, selfId, selfName, selfAvatarBytes)

    /** Records [incoming] in its conversation's state and (re)posts that notification + the group summary. */
    private fun post(
        conversation: NotifConversation,
        isMention: Boolean,
        incoming: NotifMessage,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    ) {
        // The user is already looking at this conversation — nothing to surface.
        if (incoming.conversationId == visibleConversationId) return
        val self = Self(selfId, selfName, selfAvatarBytes).also { lastSelf = it }
        val tag = tagFor(conversation.conversationId, isMention)
        val render =
            synchronized(states) {
                val state = states.getOrPut(tag) { ConvState(tag, conversation.conversationId, isMention) }
                state.kind = conversation.kind
                state.title = conversation.title
                state.avatarBytes = conversation.avatarBytes
                state.count += 1
                renderOf(state, state.history.add(incoming))
            }
        buildAndPost(render, self)
        postSummary()
    }

    override fun onReplied(
        notificationTag: String,
        text: String,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    ) {
        val self = Self(selfId, selfName, selfAvatarBytes).also { lastSelf = it }
        val render =
            synchronized(states) {
                val state = states[notificationTag] ?: return@synchronized null
                // Echo the sent reply as an outgoing line (senderId == self ⇒ renders as "You"). Not counted
                // toward the summary — it's our own message, and we don't re-alert (setOnlyAlertOnce). A blank
                // reply adds nothing; we still re-post the existing state to clear the "sending…" spinner.
                val messages =
                    if (text.isBlank()) {
                        state.history.snapshot()
                    } else {
                        val echo =
                            NotifMessage(
                                senderId = selfId,
                                senderName = selfName,
                                body = text,
                                sentAt = System.currentTimeMillis(),
                                conversationId = state.conversationId,
                                avatarBytes = selfAvatarBytes,
                            )
                        state.history.add(echo)
                    }
                renderOf(state, messages)
            }
        if (render == null) {
            // State gone (e.g. process restarted since the notification was shown) — clear the reply spinner.
            runCatching { manager.cancel(notificationTag, ID_MESSAGE) }
            return
        }
        buildAndPost(render, self)
        postSummary()
    }

    override fun setVisibleConversation(conversationId: String?) {
        visibleConversationId = conversationId
        // Leaving a chat (null) just resumes notifying; opening one clears what it already showed
        // (both its normal and mention entries).
        if (conversationId != null) clearConversation(conversationId)
    }

    override fun clearConversation(conversationId: String) {
        listOf(tagFor(conversationId, false), tagFor(conversationId, true)).forEach { tag ->
            synchronized(states) { states.remove(tag) }
            runCatching { manager.cancel(tag, ID_MESSAGE) }
        }
        postSummary()
    }

    override fun onDismissed(tag: String) {
        if (tag == DISMISS_ALL) {
            synchronized(states) { states.clear() }
            runCatching { manager.cancel(ID_SUMMARY) }
            return
        }
        synchronized(states) { states.remove(tag) }
        postSummary()
    }

    /** Snapshots the mutable [state] into an immutable [Render] (call under the [states] lock). */
    private fun renderOf(
        state: ConvState,
        messages: List<NotifMessage>,
    ) = Render(
        tag = state.tag,
        conversationId = state.conversationId,
        kind = state.kind,
        isMention = state.isMention,
        title = state.title,
        avatarBytes = state.avatarBytes,
        messages = messages,
    )

    /** Builds a MessagingStyle notification for one conversation and posts it under its tag. */
    private fun buildAndPost(
        r: Render,
        self: Self,
    ) {
        if (!canPost()) return
        val me = personOf(self.id, self.name.ifBlank { context.getString(R.string.notif_self_name) }, self.avatarBytes)
        val isGroupConversation = r.kind != ConversationKind.DM
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(isGroupConversation)
        // A 1:1 DM shows the peer's name as the title (from the message Person); a group/room/mention
        // shows the resolved conversation title instead.
        if (isGroupConversation) style.setConversationTitle(displayTitle(r.kind, r.title))
        r.messages.forEach { m ->
            style.addMessage(m.body, m.sentAt, personOf(m.senderId, m.senderName, m.avatarBytes))
        }

        // The conversation avatar (group photo / peer avatar, the Nearby room's Knit mark, else a generated
        // letter avatar) shown as the notification's prominent icon. A plain setLargeIcon is ignored by
        // MessagingStyle, so we publish a long-lived conversation shortcut carrying this icon and point the
        // notification at it — that gives the Signal-style avatar in the collapsed, group-child, and heads-up
        // views (Conversations section).
        val title = displayTitle(r.kind, r.title)
        val avatar = bitmapFor(r.avatarBytes) ?: fallbackAvatar(r.kind, title)
        pushConversationShortcut(r.conversationId, title, avatar)

        val channelId = if (r.isMention) NotificationChannels.MENTIONS else NotificationChannels.channelFor(r.kind)
        val builder =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_KEY_MESSAGES)
                .setShortcutId(r.conversationId)
                .setLocusId(LocusIdCompat(r.conversationId))
                .setLargeIcon(avatar)
                .setContentIntent(openChatIntent(r.tag, r.conversationId))
                .setDeleteIntent(dismissIntent(r.tag))
                .addAction(replyAction(r.tag, r.conversationId))
                .addAction(markReadAction(r.tag, r.conversationId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)

        postNotification(r.tag, ID_MESSAGE, builder.build())
    }

    /**
     * Publishes (or refreshes) a long-lived dynamic shortcut for [conversationId] carrying the conversation
     * [avatar] + [title] and a deep-link to the thread. A MessagingStyle notification that references this
     * via `setShortcutId` gets the Android "conversation" treatment: the shortcut icon renders as the
     * prominent avatar in every view (collapsed / group-child / heads-up), which a bare `setLargeIcon`
     * doesn't achieve. Also feeds the launcher long-press / share-sheet with recent conversations.
     * ShortcutManagerCompat LRU-evicts once over the per-app cap, so this stays bounded.
     */
    private fun pushConversationShortcut(
        conversationId: String,
        title: String,
        avatar: Bitmap,
    ) {
        val icon = IconCompat.createWithAdaptiveBitmap(avatar)
        val person =
            Person
                .Builder()
                .setKey(conversationId)
                .setName(title)
                .setIcon(icon)
                .build()
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/$conversationId")
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, conversationId)
                .setShortLabel(title.ifBlank { context.getString(R.string.app_name) })
                .setLongLived(true)
                .setIcon(icon)
                .setPerson(person)
                .setLocusId(LocusIdCompat(conversationId))
                .setIntent(intent)
                .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    /**
     * (Re)posts the group summary ("N messages in M chats") over the current per-conversation state, or
     * cancels it only once **no** conversations remain. The summary must survive down to a single child:
     * cancelling a group summary while a child still exists also cancels that child (Android groups them),
     * so we always keep it posted for ≥1 conversation. Stock Android hides the summary for a single-child
     * group and shows the child on its own — matching Signal, where the "N messages in M chats" line only
     * appears once messages span multiple chats (so we set that text only for ≥2, using the lone line
     * otherwise for the rare OEM that renders it).
     */
    private fun postSummary() {
        if (!canPost()) return
        val counts: List<Int>
        val lines: List<CharSequence>
        val channel: String
        synchronized(states) {
            if (states.isEmpty()) {
                runCatching { manager.cancel(ID_SUMMARY) }
                return
            }
            counts = states.values.map { it.count }
            lines = states.values.map { lineFor(it) }
            channel = summaryChannel(states.values)
        }
        val (total, chats) = summaryCounts(counts)
        val summaryText =
            if (chats >= 2) context.getString(R.string.notif_summary, total, chats) else lines.firstOrNull() ?: ""
        val inbox =
            NotificationCompat
                .InboxStyle()
                .setSummaryText(context.getString(R.string.app_name))
        lines.forEach { inbox.addLine(it) }
        val summary =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setStyle(inbox)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(summaryText)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_KEY_MESSAGES)
                .setGroupSummary(true)
                // Children alert on their own channels; the summary must not double-buzz.
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(dismissIntent(DISMISS_ALL))
                .setAutoCancel(true)
                .build()
        postNotification(null, ID_SUMMARY, summary)
    }

    /**
     * Posts [notification] under [id] (with an optional [tag]). The POST_NOTIFICATIONS permission check is
     * inlined here — right at the `manager.notify` call — because lint's flow analysis only recognizes the
     * guard when it sits on the direct path to notify, not when extracted into a helper like [canPost].
     */
    private fun postNotification(
        tag: String?,
        id: Int,
        notification: Notification,
    ) {
        if (!manager.areNotificationsEnabled()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { if (tag != null) manager.notify(tag, id, notification) else manager.notify(id, notification) }
    }

    /** One summary line: the bold conversation title followed by the latest message preview. */
    private fun lineFor(state: ConvState): CharSequence {
        val last = state.history.snapshot().lastOrNull()
        val title = displayTitle(state.kind, state.title)
        val preview =
            when {
                last == null -> ""
                state.kind == ConversationKind.DM -> last.body
                else -> "${last.senderName}: ${last.body}"
            }
        val text = if (preview.isBlank()) title else "$title  $preview"
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    /** Picks the summary's channel = the highest-importance kind currently present (so the group ranks right). */
    private fun summaryChannel(present: Collection<ConvState>): String =
        when {
            present.any { it.isMention } -> NotificationChannels.MENTIONS
            present.any { it.kind == ConversationKind.DM } -> NotificationChannels.DMS
            present.any { it.kind == ConversationKind.GROUP } -> NotificationChannels.GROUPS
            else -> NotificationChannels.NEARBY
        }

    private fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        // Explicit POST_NOTIFICATIONS check (runtime permission on API 33+). areNotificationsEnabled()
        // already implies it, but lint's flow analysis needs the explicit check on every path to notify().
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun personOf(
        id: String,
        name: String,
        avatarBytes: ByteArray?,
    ): Person {
        val display = name.ifBlank { id }
        return Person
            .Builder()
            .setKey(id)
            .setName(display)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmapFor(avatarBytes) ?: letterAvatar(display)))
            .build()
    }

    /** The real conversation title for all kinds (used for the shortcut label + avatar initial). */
    private fun displayTitle(
        kind: ConversationKind,
        title: String?,
    ): String =
        title?.takeIf { it.isNotBlank() } ?: when (kind) {
            ConversationKind.NEARBY -> context.getString(R.string.notif_title_nearby)
            ConversationKind.GROUP -> context.getString(R.string.group_unnamed)
            ConversationKind.DM -> "?"
        }

    private fun bitmapFor(bytes: ByteArray?): Bitmap? =
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }

    /**
     * The photoless avatar for a conversation: the Nearby/broadcast room gets the Knit mesh mark (matching
     * the chat list's room glyph), everything else gets a [letterAvatar] on its title initial.
     */
    private fun fallbackAvatar(
        kind: ConversationKind,
        title: String,
    ): Bitmap = if (kind == ConversationKind.NEARBY) roomAvatar() else letterAvatar(title)

    /**
     * The Nearby/broadcast room's icon: the Knit mesh mark ([R.drawable.ic_knit_room], the circular variant)
     * drawn edge-to-edge, the notification twin of the chat list's room glyph (`CircleGlyph` — a
     * `secondaryContainer` background with the `onSecondaryContainer`-tinted logo filling the circle). The
     * logo's own disc is the [logoTint] color and its mesh cut-outs reveal the [background] beneath, so the
     * two-tone circle carries its own contrast — fixed to the light-scheme pair to stay legible on either a
     * light or dark notification shade. The adaptive mask rounds the square bitmap to the same circle.
     */
    private fun roomAvatar(): Bitmap {
        // CoralSecondaryContainerLight / CoralOnSecondaryContainerLight — the chat-list room glyph colors.
        val background = 0xFFE0E0EC.toInt()
        val logoTint = 0xFF181824.toInt()
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        ContextCompat.getDrawable(context, R.drawable.ic_knit_room)?.mutate()?.apply {
            setTint(logoTint)
            setBounds(0, 0, AVATAR_PX, AVATAR_PX)
            draw(canvas)
        }
        return bitmap
    }

    /**
     * A generated avatar for [name] when it has no photo: a deterministically-colored circle (the adaptive
     * mask rounds the filled square) with the leading grapheme initial, mirroring the in-app
     * [app.getknit.knit.ui.components.Avatar] fallback so notifications match the rest of the app.
     */
    private fun letterAvatar(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colorFor(name))
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = AVATAR_PX * 0.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
        val baseline = AVATAR_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(avatarInitial(name), AVATAR_PX / 2f, baseline, paint)
        return bitmap
    }

    /** A stable, pleasant background hue for [name]'s letter avatar (same name -> same color). */
    private fun colorFor(name: String): Int {
        val hue = ((name.hashCode() % HUE_STEPS) + HUE_STEPS) % HUE_STEPS
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), AVATAR_SAT, AVATAR_VAL))
    }

    /** The leading grapheme of [name], uppercased (emoji-safe), or "?" when blank — mirrors the in-app avatar. */
    private fun avatarInitial(name: String): String {
        val trimmed = name.trimStart()
        if (trimmed.isEmpty()) return "?"
        val boundary = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
        val end = boundary.next()
        val grapheme = if (end == BreakIterator.DONE) trimmed else trimmed.substring(0, end)
        return grapheme.uppercase()
    }

    /** Deep-link tap: opens (or brings forward) [MainActivity] straight to `chat/<conversationId>`. */
    private fun openChatIntent(
        tag: String,
        conversationId: String,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/$conversationId")
        return PendingIntent.getActivity(context, requestCode(tag, CODE_OPEN), intent, immutable())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, CODE_SUMMARY_OPEN, intent, immutable())
    }

    private fun replyAction(
        tag: String,
        conversationId: String,
    ): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.notif_reply_hint))
                .build()
        val intent = actionIntent(ACTION_REPLY, tag).putExtra(EXTRA_CONV, conversationId)
        // RemoteInput requires a MUTABLE PendingIntent so the system can fill in the typed reply. FLAG_MUTABLE
        // is API 31; pre-S PendingIntents are mutable by default, so the reply still works there without it.
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        val pending = PendingIntent.getBroadcast(context, requestCode(tag, CODE_REPLY), intent, flags)
        return NotificationCompat.Action
            .Builder(R.drawable.ic_stat_mesh, context.getString(R.string.notif_action_reply), pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(
        tag: String,
        conversationId: String,
    ): NotificationCompat.Action {
        val intent = actionIntent(ACTION_MARK_READ, tag).putExtra(EXTRA_CONV, conversationId)
        val pending = PendingIntent.getBroadcast(context, requestCode(tag, CODE_MARK_READ), intent, immutable())
        return NotificationCompat.Action
            .Builder(R.drawable.ic_stat_mesh, context.getString(R.string.notif_action_mark_read), pending)
            .setShowsUserInterface(false)
            .build()
    }

    private fun dismissIntent(tag: String): PendingIntent {
        val intent = actionIntent(ACTION_DISMISS, tag)
        return PendingIntent.getBroadcast(context, requestCode(tag, CODE_DISMISS), intent, immutable())
    }

    private fun actionIntent(
        action: String,
        tag: String,
    ): Intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_TAG, tag)

    private fun immutable() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** A per-(tag, action) request code so distinct notifications' PendingIntents never clobber each other. */
    private fun requestCode(
        tag: String,
        action: Int,
    ) = tag.hashCode() * CODE_SLOTS + action

    private fun tagFor(
        conversationId: String,
        isMention: Boolean,
    ): String = if (isMention) MENTION_PREFIX + conversationId else conversationId

    companion object {
        const val EXTRA_TAG = "app.getknit.knit.NOTIF_TAG"
        const val EXTRA_CONV = "app.getknit.knit.NOTIF_CONV"
        const val KEY_TEXT_REPLY = "app.getknit.knit.KEY_TEXT_REPLY"

        const val ACTION_OPEN_CHAT = "app.getknit.knit.NOTIF_OPEN_CHAT"
        const val ACTION_REPLY = "app.getknit.knit.NOTIF_REPLY"
        const val ACTION_MARK_READ = "app.getknit.knit.NOTIF_MARK_READ"
        const val ACTION_DISMISS = "app.getknit.knit.NOTIF_DISMISS"

        /** Sentinel tag on the summary's delete intent: swiping the summary clears every message notification. */
        const val DISMISS_ALL = "app.getknit.knit.NOTIF_DISMISS_ALL"

        private const val MENTION_PREFIX = "mention:"

        // Notification ids — id 1 is MeshService's foreground notification; 2-5 were the retired per-channel
        // buckets. Per-conversation notifications now share one id disambiguated by tag; the summary gets its own.
        private const val ID_MESSAGE = 6
        private const val ID_SUMMARY = 7

        /** Groups every message notification under one stack with the summary. */
        private const val GROUP_KEY_MESSAGES = "app.getknit.knit.MESSAGES"

        // Generated letter-avatar geometry/palette (source avatars are 256²; this matches closely enough).
        private const val AVATAR_PX = 256
        private const val HUE_STEPS = 360
        private const val AVATAR_SAT = 0.5f
        private const val AVATAR_VAL = 0.65f

        // Request-code action slots (per tag), so open/reply/mark-read/dismiss don't collide.
        private const val CODE_OPEN = 0
        private const val CODE_REPLY = 1
        private const val CODE_MARK_READ = 2
        private const val CODE_DISMISS = 3
        private const val CODE_SLOTS = 4
        private const val CODE_SUMMARY_OPEN = 1
    }
}
