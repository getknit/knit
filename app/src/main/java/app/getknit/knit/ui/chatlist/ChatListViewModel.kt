package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
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
 * One row in the conversation list: the [Conversations.NEARBY] broadcast room ([isRoom] true), a
 * group chat ([isGroup] true, keyed by the group id, [title] is the group name), or a 1:1 DM keyed by
 * the peer's node id with the peer's [title]/[avatarHash]. [lastPreview]/[lastMessageAt] are null when
 * the conversation has no messages yet.
 */
data class ConversationRow(
    val id: String,
    val title: String,
    val avatarHash: String?,
    val isRoom: Boolean,
    val isGroup: Boolean,
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
    groups: GroupRepository,
    private val context: Context,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // Messages, the blocklist, and groups are pre-combined so the outer combine stays at the 5-flow
    // typed overload. Blocked senders' messages are filtered out, and their DM thread is dropped below.
    private data class ListBundle(
        val messages: List<MessageEntity>,
        val blocked: Set<String>,
        val groups: List<GroupEntity>,
    )

    private val messagesAndBlocks = combine(
        messages.observeMessages(), // ORDER BY sentAt ASC -> newest is last()
        settings.blockedNodeIds,
        groups.observeGroups(),
    ) { msgs, blocked, groupList ->
        ListBundle(msgs.filter { it.senderId !in blocked }, blocked, groupList)
    }

    val state: StateFlow<ChatListUiState> = combine(
        messagesAndBlocks,
        peers.observePeers(),
        settings.lastReadAll,
        myNodeId,
        meshManager.neighborCount,
    ) { bundle, peerList, lastReadAll, me, neighborCount ->
        val msgs = bundle.messages
        val blocked = bundle.blocked
        val activeGroups = bundle.groups.filter { !it.left }
        val groupIds = bundle.groups.map { it.groupId }.toSet() // left groups too, to hide stray rows
        val peersByNode = peerList.associateBy { it.nodeId }
        val byConversation = msgs.groupBy { it.conversationId }

        fun rowFor(
            conversationId: String,
            threadMsgs: List<MessageEntity>,
            title: String,
            isRoom: Boolean,
            isGroup: Boolean,
            avatarHash: String?,
        ): ConversationRow {
            val last = threadMsgs.lastOrNull()
            val lastReadAt = lastReadAll[conversationId] ?: 0L
            // Until our own id resolves, count nothing as unread so our own messages aren't miscounted.
            val unread = if (me == null) 0 else threadMsgs.count { it.sentAt > lastReadAt && it.senderId != me }
            return ConversationRow(
                id = conversationId,
                title = title,
                avatarHash = avatarHash,
                isRoom = isRoom,
                isGroup = isGroup,
                lastPreview = last?.let { previewFor(it, peersByNode, me, isDm = !isRoom && !isGroup) },
                lastMessageAt = last?.sentAt,
                unreadCount = unread,
            )
        }

        // The Nearby room is always present (even with no messages yet). Groups appear from the groups
        // table (so a freshly created group shows even before its first message); DM threads appear once
        // they have a message — excluding any conversation that is actually a group. Most-recent first.
        val nearby = rowFor(
            Conversations.NEARBY, byConversation[Conversations.NEARBY].orEmpty(),
            title = context.getString(R.string.nearby_title), isRoom = true, isGroup = false, avatarHash = null,
        )
        val groupRows = activeGroups.map { g ->
            val title = groupTitle(
                storedName = g.name,
                memberIds = GroupMembersStore.decode(g.members),
                selfId = me,
                fallback = context.getString(R.string.group_unnamed),
            ) { id -> displayNameFor(peersByNode[id]?.name, id) }
            val row = rowFor(
                g.groupId, byConversation[g.groupId].orEmpty(),
                title = title, isRoom = false, isGroup = true, avatarHash = null,
            )
            // An empty group sorts/labels by its creation time so it isn't stranded at the bottom.
            if (row.lastMessageAt == null) row.copy(lastMessageAt = g.createdAt) else row
        }
        val dms = byConversation
            .filterKeys { it != Conversations.NEARBY && it !in blocked && it !in groupIds }
            .map { (conversationId, threadMsgs) ->
                rowFor(
                    conversationId, threadMsgs,
                    title = displayNameFor(peersByNode[conversationId]?.name, conversationId),
                    isRoom = false, isGroup = false,
                    avatarHash = peersByNode[conversationId]?.avatarHash,
                )
            }
        ChatListUiState(
            conversations = (listOf(nearby) + groupRows + dms).sortedByDescending { it.lastMessageAt ?: 0L },
            neighborCount = neighborCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    /**
     * "Sender: body" preview, mirroring how ChatViewModel resolves names and labels own messages.
     * In a 1:1 DM the peer's name is already the row title, so an incoming message shows just its body;
     * our own messages still get the "You: …" prefix (it's not the recipient's name and signals who spoke).
     */
    private fun previewFor(
        message: MessageEntity,
        peersByNode: Map<String, PeerEntity>,
        me: String?,
        isDm: Boolean,
    ): String {
        val body = when {
            message.body.isNotBlank() -> message.body
            message.attachmentHash != null -> context.getString(R.string.chat_list_preview_photo)
            else -> ""
        }
        val isOwn = message.senderId == me
        if (isDm && !isOwn) return body
        val sender = if (isOwn) {
            context.getString(R.string.chat_self_name)
        } else {
            displayNameFor(peersByNode[message.senderId]?.name, message.senderId)
        }
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }
}
