package app.getknit.knit.ui.requests

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
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

/**
 * One pending message request: a stranger's DM (keyed by their node id, [isGroup] false) or a group a
 * stranger added you to (keyed by the group id). [avatarHash] is the peer avatar or the group photo
 * (null → the leading glyph); [lastPreview]/[lastMessageAt] describe the newest message in the thread.
 */
data class RequestRow(
    val conversationId: String,
    val title: String,
    val avatarHash: String?,
    val isGroup: Boolean,
    val lastPreview: String?,
    val lastMessageAt: Long?,
)

/**
 * The Message Requests inbox: the DM/group conversations that are **not** accepted — a stranger's first
 * contact, which [app.getknit.knit.mesh.InboundPipeline] delivers + acks silently and which is filtered
 * out of the main chat list. "Accepted vs request" uses the shared [Conversations.isAccepted] predicate,
 * so this list and the notify gate agree exactly (Nearby / accepted-set / verified peer / self-authored).
 * Per row: **Accept** (persist to the accepted set), **Block** (DM only — a DM's conversationId is the
 * peer node id), or **Delete** (clear a DM's thread / hard-delete a group). All local — nothing here
 * touches the mesh relay/custody seam.
 */
class MessageRequestsViewModel(
    private val messages: MessageRepository,
    private val settings: SettingsStore,
    private val peers: PeerRepository,
    private val groups: GroupRepository,
    identity: Identity,
    private val context: Context,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // Messages + the accepted/blocked sets are pre-combined so the outer combine stays within the
    // 5-flow typed overload (it then adds peers + groups + myNodeId, for four total).
    private data class Bundle(
        val messages: List<MessageEntity>,
        val accepted: Set<String>,
        val blocked: Set<String>,
    )

    private val messagesAndSets =
        combine(
            messages.observeMessages(),
            settings.acceptedConversations,
            settings.blockedNodeIds,
        ) { msgs, accepted, blocked -> Bundle(msgs, accepted, blocked) }

    val requests: StateFlow<List<RequestRow>> =
        combine(
            messagesAndSets,
            peers.observePeers(),
            groups.observeGroups(),
            myNodeId,
        ) { bundle, peerList, groupList, me ->
            // Until our own id resolves we can't compute "self-authored", so surface nothing rather than
            // mis-classifying our own threads as requests during the ~1s cold-start gap.
            if (me == null) return@combine emptyList<RequestRow>()
            val msgs = bundle.messages
            val peersByNode = peerList.associateBy { it.nodeId }
            val groupsById = groupList.associateBy { it.groupId }
            val verified = peerList.filter { it.verified }.map { it.nodeId }.toSet()
            val authored = msgs.filter { it.senderId == me }.map { it.conversationId }.toSet()
            // Senders per thread, so a group a known peer has posted in falls through to the chat list
            // instead of showing here (matches the notify gate and chat list).
            val sendersByConversation =
                msgs.groupBy { it.conversationId }.mapValues { (_, tms) -> tms.map { it.senderId }.toSet() }

            // A conversation is a pending request when it isn't Nearby, isn't blocked, and the shared
            // predicate says it's not yet accepted — matching the notify gate exactly.
            fun isRequest(id: String): Boolean =
                id != Conversations.NEARBY &&
                    id !in bundle.blocked &&
                    !Conversations.isAccepted(id, bundle.accepted, verified, authored, sendersByConversation[id].orEmpty())

            val byConversation = msgs.groupBy { it.conversationId }
            val rows =
                byConversation.mapNotNull { (conversationId, threadMsgs) ->
                    if (!isRequest(conversationId)) return@mapNotNull null
                    val isGroup = Conversations.kindFor(conversationId) == ConversationKind.GROUP
                    val group = groupsById[conversationId]
                    // A group we've already left (or that has no roster row yet) isn't an active request.
                    if (isGroup && (group == null || group.left)) return@mapNotNull null
                    val title =
                        if (isGroup) {
                            groupTitle(
                                storedName = group?.name ?: "",
                                memberIds = GroupMembersStore.decode(group?.members ?: ""),
                                selfId = me,
                                fallback = context.getString(R.string.group_unnamed),
                            ) { id -> displayNameFor(peersByNode[id]?.name, id) }
                        } else {
                            displayNameFor(peersByNode[conversationId]?.name, conversationId)
                        }
                    val last = threadMsgs.lastOrNull()
                    RequestRow(
                        conversationId = conversationId,
                        title = title,
                        avatarHash =
                            if (isGroup) group?.photoHash else peersByNode[conversationId]?.avatarHash,
                        isGroup = isGroup,
                        lastPreview = last?.let { previewFor(it, peersByNode, isGroup) },
                        lastMessageAt = last?.sentAt,
                    )
                }
            rows.sortedByDescending { it.lastMessageAt ?: 0L }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accept a request: it moves into the main chat list and the sender's messages notify normally. */
    fun accept(conversationId: String) {
        viewModelScope.launch { settings.accept(conversationId) }
    }

    /** Block the DM peer (a DM's conversationId is the peer node id). Not offered for group requests. */
    fun block(nodeId: String) {
        viewModelScope.launch { settings.block(nodeId, peers.find(nodeId)?.deviceTag) }
    }

    /** Decline: clear a DM's messages, or hard-delete a group so it leaves the list. Local only. */
    fun delete(conversationId: String) {
        viewModelScope.launch {
            when (Conversations.kindFor(conversationId)) {
                ConversationKind.GROUP -> groups.delete(conversationId)
                ConversationKind.DM -> messages.deleteByConversation(conversationId)
                ConversationKind.NEARBY -> Unit
            }
        }
    }

    // "Sender: body" preview, mirroring ChatListViewModel. A DM request is always from the stranger, so
    // it shows just the body; a group request prefixes the sender's name.
    private fun previewFor(
        message: MessageEntity,
        peersByNode: Map<String, PeerEntity>,
        isGroup: Boolean,
    ): String {
        if (message.kind == MessageEntity.KIND_MEMBER_LEFT) {
            return context.getString(
                R.string.chat_group_member_left,
                displayNameFor(peersByNode[message.senderId]?.name, message.senderId),
            )
        }
        val body =
            when {
                message.body.isNotBlank() -> message.body
                message.attachmentHash != null -> context.getString(R.string.chat_list_preview_photo)
                else -> ""
            }
        if (!isGroup) return body
        val sender = displayNameFor(peersByNode[message.senderId]?.name, message.senderId)
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }
}
