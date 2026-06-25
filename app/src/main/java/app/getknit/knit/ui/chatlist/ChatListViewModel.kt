package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the conversation list: the [Conversations.NEARBY] broadcast room ([isRoom] true) or a
 * 1:1 DM keyed by the peer's node id, with the peer's [title]/[avatarPath]. [lastPreview]/
 * [lastMessageAt] are null when the conversation has no messages yet.
 */
data class ConversationRow(
    val id: String,
    val title: String,
    val avatarPath: String?,
    val isRoom: Boolean,
    val lastPreview: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
)

data class ChatListUiState(
    val conversations: List<ConversationRow> = emptyList(),
    val neighborCount: Int = 0,
)

/**
 * Read-only projection of the conversation list. The per-conversation read watermarks
 * ([SettingsStore.lastReadAll]) are written by [app.getknit.knit.ui.chat.ChatViewModel] while a chat
 * is on screen; this VM only reads them to compute unread badges.
 */
class ChatListViewModel(
    messages: MessageRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
    meshManager: MeshManager,
    private val context: Context,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    val state: StateFlow<ChatListUiState> = combine(
        messages.observeMessages(), // ORDER BY sentAt ASC -> newest is last()
        peers.observePeers(),
        settings.lastReadAll,
        myNodeId,
        meshManager.neighborCount,
    ) { msgs, peerList, lastReadAll, me, neighborCount ->
        val peersByNode = peerList.associateBy { it.nodeId }
        val byConversation = msgs.groupBy { it.conversationId }

        fun rowFor(conversationId: String, threadMsgs: List<MessageEntity>): ConversationRow {
            val last = threadMsgs.lastOrNull()
            val lastReadAt = lastReadAll[conversationId] ?: 0L
            // Until our own id resolves, count nothing as unread so our own messages aren't miscounted.
            val unread = if (me == null) 0 else threadMsgs.count { it.sentAt > lastReadAt && it.senderId != me }
            val isRoom = conversationId == Conversations.NEARBY
            return ConversationRow(
                id = conversationId,
                title = if (isRoom) {
                    context.getString(R.string.nearby_title)
                } else {
                    displayNameFor(peersByNode[conversationId]?.name, conversationId)
                },
                avatarPath = if (isRoom) null else peersByNode[conversationId]?.avatarPath,
                isRoom = isRoom,
                lastPreview = last?.let { previewFor(it, peersByNode, me) },
                lastMessageAt = last?.sentAt,
                unreadCount = unread,
            )
        }

        // The Nearby room is always present (even with no messages yet); DM threads appear once they
        // have a message. Most-recent conversation first.
        val nearby = rowFor(Conversations.NEARBY, byConversation[Conversations.NEARBY].orEmpty())
        val dms = byConversation
            .filterKeys { it != Conversations.NEARBY }
            .map { (conversationId, threadMsgs) -> rowFor(conversationId, threadMsgs) }
        ChatListUiState(
            conversations = (listOf(nearby) + dms).sortedByDescending { it.lastMessageAt ?: 0L },
            neighborCount = neighborCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    /** "Sender: body" preview, mirroring how ChatViewModel resolves names and labels own messages. */
    private fun previewFor(
        message: MessageEntity,
        peersByNode: Map<String, PeerEntity>,
        me: String?,
    ): String {
        val sender = if (message.senderId == me) {
            context.getString(R.string.chat_self_name)
        } else {
            displayNameFor(peersByNode[message.senderId]?.name, message.senderId)
        }
        val body = when {
            message.body.isNotBlank() -> message.body
            message.attachmentHash != null -> context.getString(R.string.chat_list_preview_photo)
            else -> ""
        }
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }
}
