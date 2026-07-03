package app.getknit.knit.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.protocol.GroupInfo
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
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val mesh: MeshManager by inject()
    private val messages: MessageRepository by inject()
    private val groups: GroupRepository by inject()
    private val settings: SettingsStore by inject()
    private val identity: Identity by inject()
    private val blobs: BlobRepository by inject()
    private val notifier: Notifier by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        scope.launch {
            runCatching {
                when (action) {
                    MessageNotifier.ACTION_REPLY -> handleReply(intent)
                    MessageNotifier.ACTION_MARK_READ -> handleMarkRead(intent)
                    MessageNotifier.ACTION_DISMISS ->
                        intent.getStringExtra(MessageNotifier.EXTRA_TAG)?.let { notifier.onDismissed(it) }
                    else -> Unit
                }
            }.onFailure { Log.w(TAG, "notification action $action failed", it) }
            pending.finish()
        }
    }

    private suspend fun handleReply(intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        val conv = intent.getStringExtra(MessageNotifier.EXTRA_CONV) ?: return
        val tag = intent.getStringExtra(MessageNotifier.EXTRA_TAG) ?: conv
        // An empty reply still re-posts the notification (with a blank echo) to clear its "sending" spinner.
        if (text.isNotBlank()) {
            // Route exactly as ChatViewModel.send / DebugBridgeReceiver.handleSend, by the conversation kind.
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> mesh.sendChat(text, recipientId = null, group = null)
                ConversationKind.DM -> mesh.sendChat(text, recipientId = conv)
                ConversationKind.GROUP -> groups.find(conv)?.let { mesh.sendChat(text, group = it.toGroupInfo()) }
            }
        }
        val me = identity.nodeId()
        val selfName = settings.displayName.first()
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.onReplied(tag, text, me, selfName, selfAvatar)
    }

    private suspend fun handleMarkRead(intent: Intent) {
        val conv = intent.getStringExtra(MessageNotifier.EXTRA_CONV) ?: return
        // Match ChatViewModel's foreground watermark: stamp the newest message's sentAt as last-read.
        val newest = messages.observeMessages(conv).first().maxOfOrNull { it.sentAt }
        settings.setLastReadAt(conv, newest ?: System.currentTimeMillis())
        notifier.clearConversation(conv)
    }

    /** Rebuilds the self-describing [GroupInfo] from the local row (mirrors ChatViewModel's private helper). */
    private fun GroupEntity.toGroupInfo(): GroupInfo = GroupInfo(
        id = groupId,
        name = name.takeIf { it.isNotBlank() },
        members = GroupMembersStore.decode(members),
        createdBy = createdBy,
        photoHash = photoHash,
        photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
    )

    private companion object {
        const val TAG = "KnitNotifAction"
    }
}
