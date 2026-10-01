package app.getknit.knit.ui.requests

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupFaces
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.chat.messagePreview
import app.getknit.knit.ui.observeConversationTable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // The ` (Alias)` suffix already inside [title] when another known peer shares this DM peer's name (ADR 058).
    val discriminator: String? = null,
    // The other members a photo-less group request draws as its glyph (`groupFaces`, ADR 2026-09.zapp); empty for a DM.
    val faces: List<GroupFace> = emptyList(),
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
    private val drafts: DraftRepository,
    identity: Identity,
    private val context: Context,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // Per-thread summaries, never the table: the newest ordinary message of each thread is all a request
    // row shows, and a notice never speaks for a thread here either (see ChatListViewModel). Senders in the
    // blocked set are left out of every summary, as the chat list's badge already leaves them out, so a
    // stranger's group whose only speaker is blocked reads the same on both screens.
    private val table =
        messages.observeConversationTable(setOf(MessageEntity.KIND_NORMAL), settings.blockedNodeIds, myNodeId)

    val requests: StateFlow<List<RequestRow>> =
        combine(
            table,
            settings.acceptedConversations.distinctUntilChanged(),
            peers.observeDirectory(),
            groups.observeGroups(),
        ) { t, accepted, directory, groupList ->
            // Until our own id resolves we can't compute "self-authored", so surface nothing rather than
            // mis-classifying our own threads as requests during the ~1s cold-start gap.
            val me = t.me ?: return@combine emptyList<RequestRow>()
            val groupsById = groupList.associateBy { it.groupId }
            val verified =
                directory.peers
                    .filter { it.verified }
                    .map { it.nodeId }
                    .toSet()

            // A conversation is a pending request when it isn't Nearby, isn't blocked, and the shared
            // predicate says it's not yet accepted — matching the notify gate exactly. The group-senders
            // half is who has posted an *ordinary* message there, as MessageDao.sendersIn counts it: a
            // notice's senderId is the event's subject, not an author, so counting one would let a peer
            // who merely renamed themselves or left promote a stranger's group out of this list.
            fun isRequest(id: String): Boolean =
                id != Conversations.NEARBY &&
                    id !in t.blocked &&
                    !Conversations.isAccepted(id, accepted, verified, t.authored, t.groupSenders[id].orEmpty())

            t.conversations
                .filter(::isRequest)
                .mapNotNull { id -> requestRowFor(id, t.heads[id], groupsById[id], directory, me) }
                .sortedByDescending { it.lastMessageAt ?: 0L }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The inbox row for one pending thread, or null for a group we have left or hold no roster row for. */
    private fun requestRowFor(
        conversationId: String,
        last: MessageEntity?,
        group: GroupEntity?,
        directory: PeerDirectory,
        me: String,
    ): RequestRow? {
        val isGroup = Conversations.kindFor(conversationId) == ConversationKind.GROUP
        // A group we've already left (or that has no roster row yet) isn't an active request.
        if (isGroup && (group == null || group.left)) return null
        val memberIds = if (isGroup) GroupMembersStore.decode(group?.members ?: "") else emptyList()
        val title =
            if (isGroup) {
                groupTitle(
                    storedName = group?.name ?: "",
                    memberIds = memberIds,
                    selfId = me,
                    fallback = context.getString(R.string.group_unnamed),
                ) { id -> directory.label(id).text }
            } else {
                directory.label(conversationId).text
            }
        return RequestRow(
            conversationId = conversationId,
            title = title,
            avatarHash = if (isGroup) group?.photoShownHash else directory.byNode[conversationId]?.avatarHash,
            isGroup = isGroup,
            lastPreview = last?.let { previewFor(it, directory, isGroup) },
            lastMessageAt = last?.sentAt,
            discriminator = if (isGroup) null else directory.label(conversationId).discriminator,
            faces = if (isGroup) groupFaces(memberIds, me, directory) else emptyList(),
        )
    }

    /**
     * Emitted with the conversation id once an accept has persisted, so the screen can open that thread.
     * Emitting only after the write means navigating away can't cancel it — this ViewModel (and its
     * scope) dies with the inbox back-stack entry the accept navigation pops.
     */
    private val _accepted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val accepted: SharedFlow<String> = _accepted.asSharedFlow()

    /**
     * Accept a request: it moves into the main chat list and the sender's messages notify normally, then
     * [accepted] fires so the thread opens instead of leaving the user staring at the inbox.
     */
    fun accept(conversationId: String) {
        viewModelScope.launch {
            settings.accept(conversationId)
            _accepted.tryEmit(conversationId)
        }
    }

    /** Block the DM peer (a DM's conversationId is the peer node id). Not offered for group requests. */
    fun block(nodeId: String) {
        viewModelScope.launch { settings.block(nodeId, peers.find(nodeId)?.deviceTag) }
    }

    /** Decline: clear a DM's messages, or hard-delete a group so it leaves the list. Local only. */
    fun delete(conversationId: String) {
        viewModelScope.launch {
            // Whatever was typed at the stranger goes with the thread it was typed in.
            drafts.clear(conversationId)
            when (Conversations.kindFor(conversationId)) {
                ConversationKind.GROUP -> groups.delete(conversationId)

                ConversationKind.DM -> messages.deleteByConversation(conversationId)

                // No room is ever a request (Conversations.isAccepted), so none can be declined here; the
                // arms exist so the compiler keeps saying so.
                ConversationKind.NEARBY, ConversationKind.MESHTASTIC, ConversationKind.COMMONS -> Unit
            }
        }
    }

    // "Sender: body" preview, mirroring ChatListViewModel. A DM request is always from the stranger, so
    // it shows just the body; a group request prefixes the sender's name.
    private fun previewFor(
        message: MessageEntity,
        directory: PeerDirectory,
        isGroup: Boolean,
    ): String {
        val body = messagePreview(context, message)
        if (!isGroup) return body
        val sender = directory.label(message.senderId).text
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }
}
