package app.getknit.knit.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    val myNodeId: String = "",
)

class ChatViewModel(
    messages: MessageRepository,
    peers: PeerRepository,
    private val meshManager: MeshManager,
    private val identity: Identity,
    settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    /** Image staged in the input bar, ready to send with the next message (null when none). */
    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    val state: StateFlow<ChatUiState> = combine(
        messages.observeMessages(),
        peers.observePeers(),
        meshManager.neighborCount,
        myNodeId,
        settings.displayName,
    ) { msgs, peerList, count, me, myName ->
        val peersByNode = peerList.associateBy { it.nodeId }
        val rows = msgs.map { m ->
            val mine = m.senderId == me
            val name = when {
                mine -> myName.ifBlank { "You" }
                else -> displayNameFor(peersByNode[m.senderId]?.name, m.senderId)
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
            )
        }
        ChatUiState(rows = rows, neighborCount = count, myNodeId = me.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) {
        val trimmed = text.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        _pendingAttachment.value = null
        viewModelScope.launch { meshManager.sendChat(trimmed, attachment) }
    }

    /** Ingests a picked or keyboard-inserted image and stages it in the input bar. */
    fun attach(uri: Uri) {
        viewModelScope.launch { _pendingAttachment.value = attachments.ingest(uri) }
    }

    fun clearAttachment() {
        _pendingAttachment.value = null
    }

    /** Chat is on screen: suppress notifications and clear any active one (the user is reading). */
    fun onChatForeground() = notifier.setChatVisible(true)

    /** Chat left the screen: resume notifying for incoming messages. */
    fun onChatBackground() = notifier.setChatVisible(false)
}
