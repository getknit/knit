package app.getknit.knit.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A remote peer's profile as shown on the read-only details screen. */
data class ProfileDetailsUiState(
    val nodeId: String,
    val displayName: String,
    val status: String,
    val avatarHash: String?,
    val online: Boolean,
    val isBlocked: Boolean,
)

/**
 * Backs the read-only Profile Details screen for another peer (keyed by [nodeId]). It surfaces the
 * peer's cached profile (name/status/avatar from the `peers` table), live presence (from the mesh
 * neighbor set), and whether they're blocked — and exposes block/unblock toggles. A peer we've only
 * just met (no cached profile row yet) still resolves to a friendly alias and a live online state.
 */
class ProfileDetailsViewModel(
    private val nodeId: String,
    peers: PeerRepository,
    meshManager: MeshManager,
    private val settings: SettingsStore,
) : ViewModel() {

    val state: StateFlow<ProfileDetailsUiState> = combine(
        peers.observePeers(),
        meshManager.neighbors,
        settings.blockedNodeIds,
    ) { peerList, neighbors, blocked ->
        val peer = peerList.firstOrNull { it.nodeId == nodeId }
        ProfileDetailsUiState(
            nodeId = nodeId,
            displayName = displayNameFor(peer?.name, nodeId),
            status = peer?.status.orEmpty(),
            avatarHash = peer?.avatarHash,
            online = neighbors.any { it.nodeId == nodeId },
            isBlocked = nodeId in blocked,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileDetailsUiState(
            nodeId = nodeId,
            displayName = displayNameFor(null, nodeId),
            status = "",
            avatarHash = null,
            online = false,
            isBlocked = false,
        ),
    )

    /** Blocks this peer locally: their messages/reactions stop being stored, shown, and notified. */
    fun block() {
        viewModelScope.launch { settings.block(nodeId) }
    }

    /** Unblocks this peer, restoring their (never-deleted) message history. */
    fun unblock() {
        viewModelScope.launch { settings.unblock(nodeId) }
    }
}
