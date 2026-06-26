package app.getknit.knit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A person the local user can start a 1:1 chat with, resolved to a display name + avatar. */
data class Contact(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val online: Boolean,
)

/**
 * Backs the "new message" contact picker. The list is every known peer (we hold a cached
 * profile/avatar for them) plus any currently-connected neighbor we haven't profiled yet — so you can
 * message someone the instant they connect, before their profile frame arrives.
 */
class ContactsViewModel(
    peers: PeerRepository,
    meshManager: MeshManager,
    identity: Identity,
    settings: SettingsStore,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    /** Known peers ∪ live neighbors, minus ourselves and blocked ids; connected first, then alphabetical. */
    val contacts: StateFlow<List<Contact>> = combine(
        peers.observePeers(),
        meshManager.neighbors,
        myNodeId,
        settings.blockedNodeIds,
    ) { peerList, neighbors, me, blocked ->
        val online = neighbors.map { it.nodeId }.toSet()
        val byNode = peerList.associateBy { it.nodeId }
        val nodeIds = (peerList.map { it.nodeId } + online).toSet() - setOfNotNull(me) - blocked
        nodeIds
            .map { id ->
                Contact(
                    nodeId = id,
                    displayName = displayNameFor(byNode[id]?.name, id),
                    avatarHash = byNode[id]?.avatarHash,
                    online = id in online,
                )
            }
            .sortedWith(compareByDescending<Contact> { it.online }.thenBy { it.displayName.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
