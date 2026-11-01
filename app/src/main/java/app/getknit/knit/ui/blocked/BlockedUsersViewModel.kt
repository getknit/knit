package app.getknit.knit.ui.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
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
 * cached peer profiles for names/avatars, plus an [unblock] action. Nothing here calls the mesh: unblocking
 * removes the id, the (never-deleted) message history reappears reactively, and `mesh/BlockListWatch`
 * sees the change and applies the person's held profile again (ADR 2026-11.nsbf). The name shown is the one
 * they had at the block, and the face the monogram, until then.
 */
class BlockedUsersViewModel(
    private val settings: SettingsStore,
    private val peers: PeerRepository,
) : ViewModel() {
    val blocked: StateFlow<List<BlockedUser>> =
        combine(
            settings.blockedNodeIds,
            peers.observeDirectory(),
        ) { blockedIds, directory ->
            val byNode = directory.byNode
            blockedIds
                .map { id ->
                    BlockedUser(
                        nodeId = id,
                        displayName = directory.label(id).text,
                        avatarHash = byNode[id]?.avatarHash,
                    )
                }.sortedBy { it.displayName.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unblock(nodeId: String) {
        viewModelScope.launch { settings.unblock(nodeId, peers.find(nodeId)?.deviceTag) }
    }
}
