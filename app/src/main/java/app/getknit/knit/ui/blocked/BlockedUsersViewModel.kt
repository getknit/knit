package app.getknit.knit.ui.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.displayNameFor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A blocked person resolved to a display name + avatar for the management list. */
data class BlockedUser(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
)

/**
 * Backs the "Blocked users" screen: the blocked node ids ([SettingsStore.blockedNodeIds]) joined with
 * cached peer profiles for names/avatars, plus an [unblock] action. Nothing here touches the mesh —
 * unblocking just removes the id, and the (never-deleted) message history reappears reactively.
 */
class BlockedUsersViewModel(
    private val settings: SettingsStore,
    private val peers: PeerRepository,
) : ViewModel() {
    val blocked: StateFlow<List<BlockedUser>> =
        combine(
            settings.blockedNodeIds,
            peers.observePeers(),
        ) { blockedIds, peerList ->
            val byNode = peerList.associateBy { it.nodeId }
            blockedIds
                .map { id ->
                    BlockedUser(
                        nodeId = id,
                        displayName = displayNameFor(byNode[id]?.name, id),
                        avatarHash = byNode[id]?.avatarHash,
                    )
                }.sortedBy { it.displayName.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unblock(nodeId: String) {
        viewModelScope.launch { settings.unblock(nodeId, peers.find(nodeId)?.deviceTag) }
    }
}
