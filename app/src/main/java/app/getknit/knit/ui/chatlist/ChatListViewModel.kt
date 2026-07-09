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
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.TransportHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the conversation list: the [Conversations.NEARBY] broadcast room ([isRoom] true), a
 * group chat ([isGroup] true, keyed by the group id, [title] is the group name, [avatarHash] its photo
 * or null for the glyph), or a 1:1 DM keyed by the peer's node id with the peer's [title]/[avatarHash].
 * [lastPreview]/[lastMessageAt] are null when the conversation has no messages yet.
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
    // Number of pending message-request threads (stranger DM/group not yet accepted), for the top-bar badge.
    val requestCount: Int = 0,
    val neighborCount: Int = 0,
    // Radio health, so the connection header can distinguish "nobody nearby" from radios off/seized.
    val transportHealth: TransportHealth = TransportHealth.Healthy,
    // The radio-off warning banner to show (or null), already accounting for the user's dismissal.
    val radioWarning: RadioWarning? = null,
    // True only for the initial seed value (see [state]'s stateIn below), before the underlying Room +
    // DataStore + mesh flows have all first-emitted. The list shows a skeleton instead of a blank screen
    // for that ~1s cold-start gap. Defaults false so every real combine emission — and the previews —
    // render content; only the seed passes true.
    val isLoading: Boolean = false,
)

/**
 * Read-only projection of the conversation list. The per-conversation read watermarks
 * ([SettingsStore.lastReadAll]) are written by [app.getknit.knit.ui.chat.ChatViewModel] while a chat
 * is on screen; this VM only reads them to compute unread badges.
 */
class ChatListViewModel(
    private val messages: MessageRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
    meshManager: MeshController,
    private val groups: GroupRepository,
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
        val accepted: Set<String>,
    )

    // Neighbor count + radio health + the (already-dismissal-aware) banner, folded into one source.
    private data class MeshStatus(
        val neighborCount: Int,
        val health: TransportHealth,
        val warning: RadioWarning?,
    )

    private val messagesAndBlocks =
        combine(
            messages.observeMessages(), // ORDER BY sentAt ASC -> newest is last()
            settings.blockedNodeIds,
            groups.observeGroups(),
            settings.acceptedConversations,
        ) { msgs, blocked, groupList, accepted ->
            ListBundle(msgs.filter { it.senderId !in blocked }, blocked, groupList, accepted)
        }

    // Radio-off banner: which warning the per-radio statuses imply, and whether the user has dismissed it.
    // The critical AllRadiosOff warning is never stored in [dismissed], so it always shows (not dismissible).
    private val dismissed = MutableStateFlow<RadioWarning?>(null)

    private val rawWarning =
        meshManager.transportStatuses
            .map { radioWarningFor(it) }
            .distinctUntilChanged()

    private val visibleWarning =
        combine(rawWarning, dismissed) { warning, hidden ->
            if (warning != null && warning != hidden) warning else null
        }

    init {
        // Re-arm: when radios recover (warning clears), forget any prior dismissal so a later off-episode
        // shows the banner again.
        viewModelScope.launch { rawWarning.collect { if (it == null) dismissed.value = null } }
    }

    // Neighbor count + radio health + the banner folded into one source so the main state combine stays
    // within its five-flow arity.
    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            visibleWarning,
        ) { count, health, warning -> MeshStatus(count, health, warning) }

    val state: StateFlow<ChatListUiState> =
        combine(
            messagesAndBlocks,
            peers.observePeers(),
            settings.lastReadAll,
            myNodeId,
            meshStatus,
        ) { bundle, peerList, lastReadAll, me, (neighborCount, health, warning) ->
            val msgs = bundle.messages
            val blocked = bundle.blocked
            val activeGroups = bundle.groups.filter { !it.left }
            val groupIds = bundle.groups.map { it.groupId }.toSet() // left groups too, to hide stray rows
            val peersByNode = peerList.associateBy { it.nodeId }
            val byConversation = msgs.groupBy { it.conversationId }
            // Partition out stranger "message requests" using the SAME shared predicate as the notify gate
            // (Nearby / accepted-set / verified peer / self-authored) so this list and the gate agree. A
            // pending DM/group is dropped from here and surfaced in the Message Requests inbox instead.
            val accepted = bundle.accepted
            val verified = peerList.filter { it.verified }.map { it.nodeId }.toSet()
            val authored = msgs.filter { it.senderId == me }.map { it.conversationId }.toSet()
            // Senders per thread, so a group a known peer has posted in reads as a chat rather than a request.
            val sendersByConversation = byConversation.mapValues { (_, tms) -> tms.map { it.senderId }.toSet() }

            fun isPending(conversationId: String): Boolean =
                conversationId !in blocked &&
                    !Conversations.isAccepted(
                        conversationId,
                        accepted,
                        verified,
                        authored,
                        sendersByConversation[conversationId].orEmpty(),
                    )

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
                // Status notices (e.g. "X left the chat") are quiet — they never raise an unread badge.
                val unread =
                    if (me == null) {
                        0
                    } else {
                        threadMsgs.count {
                            it.sentAt > lastReadAt && it.senderId != me && it.kind == MessageEntity.KIND_NORMAL
                        }
                    }
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
            val nearby =
                rowFor(
                    Conversations.NEARBY,
                    byConversation[Conversations.NEARBY].orEmpty(),
                    title = context.getString(R.string.nearby_title),
                    isRoom = true,
                    isGroup = false,
                    avatarHash = null,
                )
            val groupRows =
                activeGroups.filter { !isPending(it.groupId) }.map { g ->
                    val title =
                        groupTitle(
                            storedName = g.name,
                            memberIds = GroupMembersStore.decode(g.members),
                            selfId = me,
                            fallback = context.getString(R.string.group_unnamed),
                        ) { id -> displayNameFor(peersByNode[id]?.name, id) }
                    val row =
                        rowFor(
                            g.groupId,
                            byConversation[g.groupId].orEmpty(),
                            title = title,
                            isRoom = false,
                            isGroup = true,
                            avatarHash = g.photoHash,
                        )
                    // An empty group sorts/labels by its creation time so it isn't stranded at the bottom.
                    if (row.lastMessageAt == null) row.copy(lastMessageAt = g.createdAt) else row
                }
            val dms =
                byConversation
                    .filterKeys { it != Conversations.NEARBY && it !in blocked && it !in groupIds && !isPending(it) }
                    .map { (conversationId, threadMsgs) ->
                        rowFor(
                            conversationId,
                            threadMsgs,
                            title = displayNameFor(peersByNode[conversationId]?.name, conversationId),
                            isRoom = false,
                            isGroup = false,
                            avatarHash = peersByNode[conversationId]?.avatarHash,
                        )
                    }
            // Count of threads moved to the requests inbox (mirrors exactly what the two filters above drop).
            val requestCount =
                byConversation.keys.count { it != Conversations.NEARBY && it !in groupIds && isPending(it) } +
                    activeGroups.count { isPending(it.groupId) }
            ChatListUiState(
                conversations = (listOf(nearby) + groupRows + dms).sortedByDescending { it.lastMessageAt ?: 0L },
                requestCount = requestCount,
                neighborCount = neighborCount,
                transportHealth = health,
                radioWarning = warning,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState(isLoading = true))

    /**
     * Hides the currently-shown radio-off banner. Only the dismissible warnings (Bluetooth/Wi-Fi off) are
     * recorded — the critical [RadioWarning.AllRadiosOff] is intentionally not dismissible, so a request to
     * dismiss it is ignored. A recorded dismissal is forgotten once the radios recover (see the re-arm
     * collector), so a later off-episode shows the banner again.
     */
    fun dismissRadioWarning() {
        state.value.radioWarning
            ?.takeIf { it != RadioWarning.AllRadiosOff }
            ?.let { dismissed.value = it }
    }

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
        // Status notices have an empty body and their senderId is the event's subject (the member who
        // left); render the localized line directly rather than "Sender: " with a blank body.
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
        val isOwn = message.senderId == me
        if (isDm && !isOwn) return body
        val sender =
            if (isOwn) {
                context.getString(R.string.chat_self_name)
            } else {
                displayNameFor(peersByNode[message.senderId]?.name, message.senderId)
            }
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }

    /**
     * Deletes a conversation locally: clears its messages (DM/group) and, for a group, hard-deletes the
     * group row so it leaves the list but can be re-added by a future group frame. Nearby is not
     * deletable. Sends nothing over the mesh; the list updates from the underlying flows.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            when (Conversations.kindFor(conversationId)) {
                ConversationKind.NEARBY -> Unit

                // the broadcast room can't be deleted
                ConversationKind.GROUP -> groups.delete(conversationId)

                ConversationKind.DM -> messages.deleteByConversation(conversationId)
            }
        }
    }
}
