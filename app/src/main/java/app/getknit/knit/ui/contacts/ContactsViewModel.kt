package app.getknit.knit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
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
 * Backs the "new message" contact picker. The list is your **established contacts** — deliberately not
 * every stranger you've seen in the Nearby room: a node id qualifies only if you've engaged with it
 * (an *accepted* DM, or a shared group) or explicitly verified its key. Concretely, after removing
 * yourself and blocked ids, a contact is any node in the union of:
 *  - **accepted DM peers** — a DM thread whose peer passes the shared [Conversations.isAccepted] rule
 *    (accepted out of the request queue / verified / already replied to), so an unanswered stranger DM
 *    stays a message request and out of this picker;
 *  - **group co-members** — everyone in any active (non-left) group you're in, even one with no messages
 *    yet; and
 *  - **verified peers** — anyone whose key you've verified out of band (covers a QR-verified contact you
 *    have never chatted with, who may not have a cached profile yet).
 *
 * Selecting one person starts a DM; selecting several creates a group via [createGroup].
 */
class ContactsViewModel(
    peers: PeerRepository,
    meshManager: MeshController,
    private val identity: Identity,
    settings: SettingsStore,
    private val groups: GroupRepository,
    messages: MessageRepository,
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

    // Messages + groups + the accepted set are pre-combined so the outer combine stays within the
    // 5-flow typed overload (it then adds peers + neighbors + blocked + myNodeId).
    private data class Bundle(
        val messages: List<MessageEntity>,
        val groups: List<GroupEntity>,
        val accepted: Set<String>,
    )

    private val bundle =
        combine(
            messages.observeMessages(),
            groups.observeGroups(),
            settings.acceptedConversations,
        ) { msgs, groupList, accepted -> Bundle(msgs, groupList, accepted) }

    /** Accepted DM peers ∪ active-group co-members ∪ verified peers, minus self and blocked; connected first, then name. */
    val contacts: StateFlow<List<Contact>> =
        combine(
            bundle,
            peers.observePeers(),
            meshManager.neighbors,
            settings.blockedNodeIds,
            myNodeId,
        ) { b, peerList, neighbors, blocked, me ->
            // Until our own id resolves we can't compute "self-authored" (nor filter ourselves out), so
            // surface nothing rather than mis-including a thread during the ~1s cold-start gap.
            if (me == null) return@combine emptyList<Contact>()
            val online = neighbors.map { it.nodeId }.toSet()
            val byNode = peerList.associateBy { it.nodeId }
            val verifiedIds = peerList.filter { it.verified }.map { it.nodeId }.toSet()
            val authored =
                b.messages
                    .filter { it.senderId == me }
                    .map { it.conversationId }
                    .toSet()
            // A DM thread's conversationId IS the peer's node id; keep only those the shared accept
            // predicate treats as a real conversation (matching the chat list / requests split).
            val acceptedDmPeers =
                b.messages
                    .asSequence()
                    .map { it.conversationId }
                    .filter { Conversations.kindFor(it) == ConversationKind.DM }
                    .filter { Conversations.isAccepted(it, b.accepted, verifiedIds, authored) }
                    .toSet()
            val groupMembers =
                b.groups
                    .filterNot { it.left }
                    .flatMap { GroupMembersStore.decode(it.members) }
                    .toSet()
            val contactIds = (acceptedDmPeers + groupMembers + verifiedIds) - blocked - me
            contactIds
                .map { id ->
                    Contact(
                        nodeId = id,
                        displayName = displayNameFor(byNode[id]?.name, id),
                        avatarHash = byNode[id]?.avatarHash,
                        online = id in online,
                    )
                }.sortedWith(compareByDescending<Contact> { it.online }.thenBy { it.displayName.lowercase() })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
