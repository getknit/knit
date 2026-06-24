package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Stable id of the public broadcast room, surfaced in the chat list as "Nearby". */
const val NEARBY_CONVERSATION_ID = "nearby"

/**
 * One row in the conversation list. Today the list holds only the [NEARBY_CONVERSATION_ID] room
 * ([isRoom] true); the shape carries [avatarPath]/[title] so future 1:1 DM rows slot in unchanged.
 * [lastPreview]/[lastMessageAt] are null when the conversation has no messages yet.
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
)

/**
 * Read-only projection of the conversation list. The unread watermark
 * ([SettingsStore.nearbyLastReadAt]) is written by [app.getknit.knit.ui.chat.ChatViewModel] while the
 * chat is on screen; this VM only reads it.
 */
class ChatListViewModel(
    messages: MessageRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
    private val context: Context,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    val state: StateFlow<ChatListUiState> = combine(
        messages.observeMessages(), // ORDER BY sentAt ASC -> newest is last()
        peers.observePeers(),
        settings.nearbyLastReadAt,
        myNodeId,
    ) { msgs, peerList, lastReadAt, me ->
        val peersByNode = peerList.associateBy { it.nodeId }
        val last = msgs.lastOrNull()
        // Until our own id resolves, count nothing as unread so our own messages aren't miscounted.
        val unread = if (me == null) 0 else msgs.count { it.sentAt > lastReadAt && it.senderId != me }
        val nearby = ConversationRow(
            id = NEARBY_CONVERSATION_ID,
            title = context.getString(R.string.nearby_title),
            avatarPath = null,
            isRoom = true,
            lastPreview = last?.let { previewFor(it, peersByNode, me) },
            lastMessageAt = last?.sentAt,
            unreadCount = unread,
        )
        ChatListUiState(listOf(nearby).sortedByDescending { it.lastMessageAt ?: 0L })
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
