package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ChatRow(
    val id: String,
    val body: String,
    val mine: Boolean,
    val senderName: String,
    val senderNodeId: String,
    val avatarPath: String?,
    val sentAt: Long,
    val received: Boolean,
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentPath: String? = null,
    val mentions: List<Mention> = emptyList(),
    val reactions: List<ReactionSummary> = emptyList(),
)

/**
 * One emoji's tally on a message: the [emoji], how many people reacted with it ([count]), and whether
 * the local user is one of them ([mine], to highlight the chip). Distinct emoji become distinct chips;
 * the UI shows the count only when it exceeds 1.
 */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/** A person who can be "@"-mentioned: someone we've received a message from, resolved to a name. */
data class MentionCandidate(
    val nodeId: String,
    val displayName: String,
    val avatarPath: String?,
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    val myNodeId: String = "",
    val mentionCandidates: List<MentionCandidate> = emptyList(),
    // Conversation header: the room ([isRoom] true) or a 1:1 DM with [title]/[avatarPath] of the peer.
    val isRoom: Boolean = true,
    val title: String = "",
    val avatarPath: String? = null,
    // True when this DM's peer is blocked, so the header offers "Unblock" instead of "Block".
    val isBlocked: Boolean = false,
)

class ChatViewModel(
    private val conversationId: String,
    private val messages: MessageRepository,
    peers: PeerRepository,
    private val reactions: ReactionRepository,
    private val meshManager: MeshManager,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
    private val gallerySaver: GallerySaver,
    private val context: Context,
) : ViewModel() {

    /** This thread is the broadcast room (vs a 1:1 DM keyed by the peer's node id). */
    private val isRoom = conversationId == Conversations.NEARBY

    private val myNodeId = MutableStateFlow<String?>(null)

    /** True while the chat is on screen; drives the read watermark below. */
    private val chatForeground = MutableStateFlow(false)

    /** Image staged in the input bar, ready to send with the next message (null when none). */
    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    /** One-shot UI messages (a string res id), surfaced as toasts — e.g. the result of saving an image. */
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    /** Emitted once the DM's peer is blocked, so the screen can close (the thread is now hidden). */
    private val _closeChat = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeChat: SharedFlow<Unit> = _closeChat.asSharedFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        // Advance this conversation's read watermark while the chat is on screen: on every stream
        // emission (so messages arriving while you read don't reappear as unread), stamp newest sentAt.
        viewModelScope.launch {
            combine(chatForeground, messages.observeMessages(conversationId)) { foreground, msgs ->
                if (foreground) msgs.maxOfOrNull { it.sentAt } else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setLastReadAt(conversationId, watermark)
            }
        }
    }

    // Reactions and the blocklist are pre-combined into the messages stream so the outer combine below
    // stays at the 5-flow typed overload (a 6th flow falls back to unchecked Array<*> casts). Blocked
    // senders' messages are filtered out here, so they also drop out of rows and mention candidates.
    private val messagesWithReactions = combine(
        messages.observeMessages(conversationId),
        reactions.observeReactions(),
        settings.blockedNodeIds,
    ) { msgs, reacts, blocked -> Triple(msgs.filter { it.senderId !in blocked }, reacts, blocked) }

    val state: StateFlow<ChatUiState> = combine(
        messagesWithReactions,
        peers.observePeers(),
        meshManager.neighborCount,
        myNodeId,
        settings.displayName,
    ) { (msgs, reacts, blocked), peerList, count, me, myName ->
        val peersByNode = peerList.associateBy { it.nodeId }
        // Group once, then tally per emoji within each message's bucket. Orphan reactions (no matching
        // message yet) simply never produce a row until their message arrives.
        val reactionsByMessage = reacts.groupBy { it.messageId }
        val rows = msgs.map { m ->
            val mine = m.senderId == me
            val name = when {
                mine -> myName.ifBlank { context.getString(R.string.chat_self_name) }
                else -> displayNameFor(peersByNode[m.senderId]?.name, m.senderId)
            }
            val tallies = reactionsByMessage[m.id].orEmpty()
                .groupBy { it.emoji }
                .mapNotNull { (emoji, group) ->
                    // emoji is non-null in the stream (tombstones are filtered in the DAO); guard anyway.
                    if (emoji == null) null
                    else ReactionSummary(emoji, group.size, group.any { it.reactorNodeId == me })
                }
            ChatRow(
                id = m.id,
                body = m.body,
                mine = mine,
                senderName = name,
                senderNodeId = m.senderId,
                avatarPath = peersByNode[m.senderId]?.avatarPath,
                sentAt = m.sentAt,
                received = m.received,
                attachmentHash = m.attachmentHash,
                attachmentMime = m.attachmentMime,
                attachmentPath = m.attachmentPath,
                mentions = MentionStore.decode(m.mentions),
                reactions = tallies,
            )
        }
        // Autocomplete candidates: everyone we've received a message from, resolved to a display name.
        val candidates = msgs.asSequence()
            .map { it.senderId }
            .filter { it != me }
            .distinct()
            .map { id ->
                MentionCandidate(
                    nodeId = id,
                    displayName = displayNameFor(peersByNode[id]?.name, id),
                    avatarPath = peersByNode[id]?.avatarPath,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
        ChatUiState(
            rows = rows,
            neighborCount = count,
            myNodeId = me.orEmpty(),
            mentionCandidates = candidates,
            isRoom = isRoom,
            title = if (isRoom) {
                context.getString(R.string.nearby_title)
            } else {
                displayNameFor(peersByNode[conversationId]?.name, conversationId)
            },
            avatarPath = if (isRoom) null else peersByNode[conversationId]?.avatarPath,
            isBlocked = !isRoom && conversationId in blocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom))

    fun send(text: String, mentions: List<Mention> = emptyList()) {
        val trimmed = text.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        _pendingAttachment.value = null
        // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
        val recipientId = if (isRoom) null else conversationId
        viewModelScope.launch { meshManager.sendChat(trimmed, attachment, mentions, recipientId) }
    }

    /** Toggles the local user's [emoji] reaction on [messageId] (add / replace / remove) and floods it. */
    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { meshManager.sendReaction(messageId, emoji) }
    }

    /**
     * Removes [messageId] from this device only — its row, its reactions, and (if no other message
     * still references it) its content-addressed attachment blob. Sends nothing over the mesh.
     */
    fun deleteMessage(messageId: String) {
        val hash = state.value.rows.firstOrNull { it.id == messageId }?.attachmentHash
        viewModelScope.launch {
            messages.delete(messageId)
            reactions.deleteForMessage(messageId)
            if (hash != null && messages.countByAttachmentHash(hash) == 0) attachments.delete(hash)
            _events.tryEmit(R.string.chat_message_deleted)
        }
    }

    /** Blocks [nodeId] locally: their messages/reactions stop being stored, shown, and notified. */
    fun block(nodeId: String) {
        viewModelScope.launch {
            settings.block(nodeId)
            _events.tryEmit(R.string.chat_user_blocked)
            // Blocking the peer of a DM empties this thread (and hides it from the list), so close the
            // now-confusing screen. Emitted only after the block persists, so navigating away can't
            // cancel the write. Blocking from the Nearby room leaves the room open.
            if (!isRoom) _closeChat.tryEmit(Unit)
        }
    }

    /** Unblocks [nodeId], restoring their (never-deleted) message history. */
    fun unblock(nodeId: String) {
        viewModelScope.launch {
            settings.unblock(nodeId)
            _events.tryEmit(R.string.chat_user_unblocked)
        }
    }

    /** Ingests a picked or keyboard-inserted image and stages it in the input bar. */
    fun attach(uri: Uri) {
        viewModelScope.launch { _pendingAttachment.value = attachments.ingest(uri) }
    }

    fun clearAttachment() {
        _pendingAttachment.value = null
    }

    /** Exports the attachment at [path] to the public `Pictures/Knit` folder and toasts the result. */
    fun saveAttachment(path: String) {
        viewModelScope.launch {
            val ok = gallerySaver.saveToPictures(File(path))
            _events.tryEmit(if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed)
        }
    }

    /** A message's text was copied to the clipboard; surface the confirmation toast. */
    fun onMessageCopied() {
        _events.tryEmit(R.string.chat_message_copied)
    }

    /** Chat is on screen: suppress notifications and clear any active one (the user is reading). */
    fun onChatForeground() {
        chatForeground.value = true
        notifier.setChatVisible(true)
    }

    /** Chat left the screen: resume notifying for incoming messages. */
    fun onChatBackground() {
        chatForeground.value = false
        notifier.setChatVisible(false)
    }
}
