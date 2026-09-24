package app.getknit.knit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A person the local user can start a 1:1 chat with, resolved to a display name + avatar. */
data class Contact(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val online: Boolean,
    // The ` (Alias)` suffix already inside [displayName] when another known peer shares the name (ADR 058).
    val discriminator: String? = null,
)

/** What the "new message" picker draws: the resolved contacts, or the fact that they aren't resolved yet. */
data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    // True until the underlying Room + DataStore + mesh flows have all first-emitted AND our own node id
    // has resolved. Without it the picker draws its "no contacts yet" empty state for those first frames —
    // right as the screen is fading in from the chat list, so the flash lands inside the nav transition and
    // reads as a stutter. Defaults false so every real combine emission — and the previews — render content.
    val isLoading: Boolean = false,
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
    private val meshManager: MeshController,
    private val identity: Identity,
    settings: SettingsStore,
    private val groups: GroupRepository,
    private val messages: MessageRepository,
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
            val createdAt = System.currentTimeMillis()
            groups.upsert(
                GroupEntity(
                    groupId = groupId,
                    name = "", // unnamed: titled locally per device until renamed
                    members = GroupMembersStore.encode(members),
                    createdBy = me,
                    createdAt = createdAt,
                    nameUpdatedAt = 0L,
                    left = false,
                ),
            )
            // The same "created this group" line every other member gets on first sight of the group
            // (InboundPipeline.reconcileGroup), written here because the creator never receives a frame
            // about their own group and would otherwise be the one person who couldn't see it. Both
            // writers mint the same deterministic row id, so a member who creates a group that later
            // reaches them by frame still ends up with one line.
            messages.save(StatusNotices.groupCreated(groupId, me, createdAt))
            // Ask the Internet plane to mint this group's spool root now (spec §3.2: we are the creator,
            // so we are the preferred minter and mint immediately). Without it the mint waits for the next
            // heal — a heartbeat, a motion trigger or a foreground resume — and the thread reads
            // "Not covered by relays yet" for minutes. After the row exists, since the pass reads it.
            meshManager.mintGroupRoots()
            _created.tryEmit(groupId)
        }
    }

    companion object {
        /** Max other people selectable for a group (8 total incl. the creator). */
        const val MAX_OTHER_MEMBERS = 7
    }

    // The three facts the picker needs from the messages table — which DM threads exist, which threads we
    // have spoken in, and who has posted in each group — read as distinct-id queries rather than as the
    // table. Pre-combined with groups + the accepted set so the outer combine stays within the 5-flow typed
    // overload (it then adds peers + neighbors + blocked + myNodeId).
    private data class Bundle(
        val conversations: Set<String>,
        val authored: Set<String>,
        val groups: List<GroupEntity>,
        val groupSenders: Map<String, Set<String>>,
        val accepted: Set<String>,
    )

    // Keyed by our own id, which resolves after construction: empty until then, and the state below stays
    // loading until then too, so the gap never renders as "no contacts".
    @OptIn(ExperimentalCoroutinesApi::class)
    private val authored: Flow<Set<String>> =
        myNodeId.flatMapLatest { me ->
            if (me == null) flowOf(emptySet()) else messages.observeConversationsIAuthoredIn(me).map { it.toSet() }
        }

    // Who has posted in each group, less blocked senders — the chat list's input to the group half of
    // `Conversations.isAccepted`, so a stranger's group invitation is a request here exactly when it is there.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val groupSenders: Flow<Map<String, Set<String>>> =
        settings.blockedNodeIds.distinctUntilChanged().flatMapLatest { messages.observeGroupSenders(it) }

    private val bundle =
        combine(
            messages.observeConversations(),
            authored,
            groups.observeGroups(),
            groupSenders,
            settings.acceptedConversations,
        ) { conversations, mine, groupList, senders, accepted -> Bundle(conversations.toSet(), mine, groupList, senders, accepted) }

    /** Accepted DM peers ∪ accepted-group co-members ∪ verified peers, minus self and blocked; connected first, then name. */
    val state: StateFlow<ContactsUiState> =
        combine(
            bundle,
            peers.observeDirectory(),
            meshManager.neighbors,
            settings.blockedNodeIds,
            myNodeId,
        ) { b, directory, neighbors, blocked, me ->
            // Until our own id resolves we can't compute "self-authored" (nor filter ourselves out), so
            // surface nothing rather than mis-including a thread during the ~1s cold-start gap. Still
            // *loading*, not empty — an empty list here would flash the "no contacts yet" state.
            if (me == null) return@combine ContactsUiState(isLoading = true)
            val online = neighbors.map { it.nodeId }.toSet()
            val byNode = directory.byNode
            val contactIds = contactIds(b.conversations, b.authored, b.groups, b.groupSenders, b.accepted, directory.verified, blocked, me)
            ContactsUiState(
                contacts =
                    contactIds
                        .map { id ->
                            val label = directory.label(id)
                            Contact(
                                nodeId = id,
                                displayName = label.text,
                                avatarHash = byNode[id]?.avatarHash,
                                online = id in online,
                                discriminator = label.discriminator,
                            )
                        }.sortedWith(compareByDescending<Contact> { it.online }.thenBy { it.displayName.lowercase() }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState(isLoading = true))
}
