package app.getknit.knit.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.isKoinStarted
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Backs the message notification's quick actions — inline **Reply**, **Mark read**, and swipe-away
 * **dismiss**. Wired as the notifications' action/delete PendingIntents by [MessageNotifier].
 *
 * Reply sends straight through [MeshManager.sendChat] (the same path as `ChatViewModel`/the debug bridge,
 * which persists + floods our own copy), then echoes the sent text back into the notification. Mark-read
 * advances the conversation's read watermark ([SettingsStore.setLastReadAt], mirroring `ChatViewModel`'s
 * on-screen behavior) and clears the notification. The work is suspending (send + repo reads), so the
 * broadcast is kept alive with [goAsync] and run on the app-lifetime mesh [scope]. `exported="false"` —
 * only the system delivers these (the RemoteInput result rides a MUTABLE PendingIntent).
 */
class NotificationActionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val mesh: MeshController by inject()
    private val messages: MessageRepository by inject()
    private val groups: GroupRepository by inject()
    private val settings: SettingsStore by inject()
    private val identity: Identity by inject()
    private val blobs: BlobRepository by inject()
    private val notifier: Notifier by inject()
    private val gate: ConversationGate by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        // A tap that lands in a restricted-backup-mode process has no graph to act with — see [isKoinStarted].
        // Dropping it beats crashing: the notification stays, and the user's next tap lands in a normal process.
        if (!isKoinStarted()) {
            Log.w(TAG, "notification action $action in a process without the app graph — dropped")
            return
        }
        val pending = goAsync()
        scope.launch {
            runCatching {
                when (action) {
                    MessageNotifier.ACTION_REPLY -> {
                        handleReply(intent)
                    }

                    MessageNotifier.ACTION_MARK_READ -> {
                        handleMarkRead(intent)
                    }

                    MessageNotifier.ACTION_DISMISS -> {
                        intent.getStringExtra(MessageNotifier.EXTRA_TAG)?.let { notifier.onDismissed(it) }
                    }

                    else -> {
                        Unit
                    }
                }
            }.onFailure { Log.w(TAG, "notification action $action failed", it) }
            pending.finish()
        }
    }

    private suspend fun handleReply(intent: Intent) {
        val text =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(MessageNotifier.KEY_TEXT_REPLY)
                ?.toString()
                ?.trim()
                .orEmpty()
        val conv = intent.getStringExtra(MessageNotifier.EXTRA_CONV) ?: return
        val tag = intent.getStringExtra(MessageNotifier.EXTRA_TAG) ?: conv
        // A notification can outlive its thread: a peer blocked, a group or commons left, a chat deleted while it sat
        // in the shade. A reply there would message a blocked peer, rejoin a left group by our own signed frame, or
        // bring a deleted thread back, so it sends nothing and the notification goes with its thread (ADR 2026-10.jbsa).
        if (!gate.isOffered(conv)) {
            Log.i(TAG, "reply under a conversation no longer offered — dropped")
            notifier.clearConversation(conv)
            return
        }
        // An empty reply still re-posts the notification (with a blank echo) to clear its "sending" spinner.
        if (text.isNotBlank()) {
            // Route exactly as ChatViewModel.send / DebugBridgeReceiver.handleSend, by the conversation kind.
            val kind = Conversations.kindFor(conv)
            when {
                // Inline reply is not offered for a bridged board room (the Meshtastic room, the MeshCore room): a post
                // there needs the first-use disclosure and the byte budget, which live in the composer, and the
                // notification carries no reply action for it (MessageNotifier). This is the backstop, not the gate, and it
                // asks the one predicate both rooms share, ahead of every route, so a third bridged room is refused too.
                kind.isBridged -> Unit

                kind == ConversationKind.NEARBY -> mesh.sendChat(text, recipientId = null, group = null)

                kind == ConversationKind.DM -> mesh.sendChat(text, recipientId = conv)

                kind == ConversationKind.GROUP -> groups.find(conv)?.let { mesh.sendChat(text, group = it.toGroupInfo()) }

                kind == ConversationKind.COMMONS -> mesh.sendCommons(conv, text, emptyList(), null)
            }
        }
        val me = identity.nodeId()
        val selfName = settings.displayName.first()
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.onReplied(tag, text, me, selfName, selfAvatar)
    }

    private suspend fun handleMarkRead(intent: Intent) {
        val conv = intent.getStringExtra(MessageNotifier.EXTRA_CONV) ?: return
        // The old watermark, read before it moves: what was unread under it is what this tap read.
        val since = settings.lastReadAt(conv).first()
        // Match ChatViewModel's foreground watermark: stamp the newest message's sentAt as last-read.
        val newest = messages.newestSentAt(conv)
        settings.setLastReadAt(conv, newest ?: System.currentTimeMillis())
        notifier.clearConversation(conv)
        // Last, and on its own: a failure here must never cost the person the tap's own effect above.
        runCatching { reportRead(conv, since) }.onFailure { Log.w(TAG, "mark read: read report failed", it) }
    }

    /**
     * "Mark read" is the person saying they read it, so the messages it clears count as read (ADR 2026-11.z6jw): the
     * unread ones under the old watermark [since]. The unread badge's own rule (`MessageDao.countUnreadIn`) over the
     * newest page: an ordinary message newer than the watermark that someone else wrote, from nobody blocked. The mesh
     * decides whom it tells — a DM or a group only, with the setting on, toward an author whose build can read it.
     */
    private suspend fun reportRead(
        conv: String,
        since: Long,
    ) {
        val kind = Conversations.kindFor(conv)
        if (kind != ConversationKind.DM && kind != ConversationKind.GROUP) return
        val me = identity.nodeId()
        val blocked = settings.blockedNodeIds.first()
        val unread =
            messages
                .page(conv, beforeSentAt = null, beforeId = null, limit = MARK_READ_MAX)
                .filter { m ->
                    m.sentAt > since && m.kind == MessageEntity.KIND_NORMAL && !(m.senderId == me && m.originNode == null) &&
                        m.senderId !in blocked
                }.map { it.id }
        if (unread.isNotEmpty()) mesh.onMessagesRead(unread)
    }

    private companion object {
        const val TAG = "KnitNotifAction"

        // The most messages one Mark read reports as read: a notification shows a handful, and a thread unread past this
        // was not read from the shade.
        const val MARK_READ_MAX = 64
    }
}
