package app.getknit.knit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

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
                else -> peersByNode[m.senderId]?.name?.ifBlank { m.senderId } ?: m.senderId
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
            )
        }
        ChatUiState(rows = rows, neighborCount = count, myNodeId = me.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { meshManager.sendChat(trimmed) }
    }

    /** Chat is on screen: suppress notifications and clear any active one (the user is reading). */
    fun onChatForeground() = notifier.setChatVisible(true)

    /** Chat left the screen: resume notifying for incoming messages. */
    fun onChatBackground() = notifier.setChatVisible(false)
}
