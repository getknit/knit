package app.getknit.knit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * message someone the instant they connect, before their profile frame arrives. Selecting one person
 * starts a DM; selecting several creates a group via [createGroup].
 */
class ContactsViewModel(
    peers: PeerRepository,
    meshManager: MeshManager,
    private val identity: Identity,
    settings: SettingsStore,
    private val groups: GroupRepository,
) : ViewModel() {

    private val myNodeId = MutableStateFlow<String?>(null)

    /** Emits the new group's conversation id once it's persisted, so the screen can open its chat. */
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    /**
     * Opens the group for [memberIds] (the selected others; this device is added automatically),
     * creating it if needed. The group id is derived from the member set ([Conversations.groupIdFor]),
     * so selecting the same people always resolves to the *same* group — no duplicate threads. If the
     * group already exists (we created it, or another member's messages auto-created it) it's simply
     * reopened; if it was previously left, re-creating rejoins it. The group starts unnamed (each device
     * renders its own default from the members; see [app.getknit.knit.data.message.groupTitle]) until
     * someone renames it. Emits on [created] only after the row exists (no startup race in
     * [app.getknit.knit.ui.chat.ChatViewModel]); members learn of a new group on its first message.
     */
    fun createGroup(memberIds: List<String>) {
        viewModelScope.launch {
            val me = identity.nodeId()
            val members = (memberIds + me).distinct()
            val groupId = Conversations.groupIdFor(members)
            val existing = groups.find(groupId)
            if (existing != null && !existing.left) {
                _created.tryEmit(groupId) // already have this exact group — just open it
                return@launch
            }
            groups.upsert(
                GroupEntity(
                    groupId = groupId,
                    name = "", // unnamed: titled locally per device until renamed
                    members = GroupMembersStore.encode(members),
                    createdBy = me,
                    createdAt = System.currentTimeMillis(),
                    nameUpdatedAt = 0L,
                    left = false,
                ),
            )
            _created.tryEmit(groupId)
        }
    }

    companion object {
        /** Max other people selectable for a group (8 total incl. the creator). */
        const val MAX_OTHER_MEMBERS = 7
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
