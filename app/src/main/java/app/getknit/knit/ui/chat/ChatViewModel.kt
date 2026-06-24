package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

class ChatViewModel(
    messages: MessageRepository,
    peers: PeerRepository,
    reactions: ReactionRepository,
    private val meshManager: MeshManager,
    private val identity: Identity,
    settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
    private val context: Context,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    /** True while the chat is on screen; drives the read watermark below. */
    private val chatForeground = MutableStateFlow(false)

    /** Image staged in the input bar, ready to send with the next message (null when none). */
    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        // Advance the Nearby read watermark while the chat is on screen: on every stream emission
        // (so messages arriving while you read don't reappear as unread), stamp the newest sentAt.
        viewModelScope.launch {
            combine(chatForeground, messages.observeMessages()) { foreground, msgs ->
                if (foreground) msgs.maxOfOrNull { it.sentAt } else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setNearbyLastReadAt(watermark)
            }
        }
    }

    // Reactions are pre-combined into the messages stream so the outer combine below stays at the
    // 5-flow typed overload (a 6th flow falls back to unchecked Array<*> casts).
    private val messagesWithReactions = combine(
        messages.observeMessages(),
        reactions.observeReactions(),
    ) { msgs, reacts -> msgs to reacts }

    val state: StateFlow<ChatUiState> = combine(
        messagesWithReactions,
        peers.observePeers(),
        meshManager.neighborCount,
        myNodeId,
        settings.displayName,
    ) { (msgs, reacts), peerList, count, me, myName ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String, mentions: List<Mention> = emptyList()) {
        val trimmed = text.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        _pendingAttachment.value = null
        viewModelScope.launch { meshManager.sendChat(trimmed, attachment, mentions) }
    }

    /** Toggles the local user's [emoji] reaction on [messageId] (add / replace / remove) and floods it. */
    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { meshManager.sendReaction(messageId, emoji) }
    }

    /** Ingests a picked or keyboard-inserted image and stages it in the input bar. */
    fun attach(uri: Uri) {
        viewModelScope.launch { _pendingAttachment.value = attachments.ingest(uri) }
    }

    fun clearAttachment() {
        _pendingAttachment.value = null
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
