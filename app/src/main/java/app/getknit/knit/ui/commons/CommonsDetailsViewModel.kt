package app.getknit.knit.ui.commons

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.ui.group.GroupMemberRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A commons as its details screen shows it: name, relay, the members this phone has seen in it, and whether it went stale. */
data class CommonsDetailsUiState(
    val conversationId: String,
    val title: String,
    // The relay's host, never its token (`SpoolUrl.host`); null until the room's row has loaded.
    val relayHost: String?,
    val members: List<GroupMemberRow>,
    // The relay no longer runs this room ([RelayFacts.staleRooms]): its operator rotated the invite.
    val stale: Boolean = false,
    // False once the room is left (here or from the relay row), so the screen can fall back to closing.
    val exists: Boolean = true,
)

/**
 * Backs a commons' room details (docs/SPOOL_PROTOCOL.md §7.4), keyed by its [conversationId]: the relay's name for
 * the room, the relay it lives on, and its members — the nodes whose frames this phone has pulled from it
 * (`commons_members`), resolved against the peer directory and presence exactly as a group's roster is — plus Leave,
 * the same act as the relay row's. A commons has no roster to edit and no photo to set, so that is the whole surface.
 * [commons] is null where the commons is hidden (`BuildConfig.COMMONS`): the screen then shows an empty room that
 * closes itself, as one that was left does.
 */
class CommonsDetailsViewModel(
    private val conversationId: String,
    private val commons: CommonsRepository?,
    peers: PeerRepository,
    private val meshManager: MeshController,
    relayFacts: Flow<RelayFacts>,
    identity: Identity,
    // StaticFieldLeak: Koin hands every ViewModel the application context (`androidContext()`), never an Activity.
    @field:SuppressLint("StaticFieldLeak") private val context: Context,
) : ViewModel() {
    private val me = MutableStateFlow<String?>(null)

    private val _left = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once Leave has removed the room, for the screen to pop back past the thread it took with it. */
    val left: SharedFlow<Unit> = _left.asSharedFlow()

    init {
        viewModelScope.launch { me.value = identity.nodeId() }
    }

    val state: StateFlow<CommonsDetailsUiState> =
        combine(
            commons?.observe(conversationId) ?: flowOf(null),
            commons?.observeMemberIds(conversationId) ?: flowOf(emptyList()),
            peers.observeDirectory(),
            combine(meshManager.neighbors, relayFacts) { neighbors, facts -> neighbors to facts },
            me,
        ) { room, memberIds, directory, (neighbors, facts), myId ->
            val onlineIds = neighbors.mapTo(HashSet()) { it.nodeId }
            val rows =
                (listOfNotNull(myId) + memberIds.filter { it != myId }).map { id ->
                    val label = directory.label(id)
                    GroupMemberRow(
                        nodeId = id,
                        displayName = label.text,
                        avatarHash = directory.byNode[id]?.avatarHash,
                        online = id in onlineIds,
                        isSelf = id == myId,
                        discriminator = label.discriminator,
                    )
                }
            // Self first (rendered as "You"), then the others connected-first, then alphabetical — the group's order.
            val others =
                rows
                    .filter { !it.isSelf }
                    .sortedWith(compareByDescending<GroupMemberRow> { it.online }.thenBy { it.displayName.lowercase() })
            CommonsDetailsUiState(
                conversationId = conversationId,
                title = room?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.commons_title),
                relayHost = room?.spoolUrl?.let(SpoolUrl::host),
                members = rows.filter { it.isSelf } + others,
                stale = conversationId in facts.staleRooms,
                exists = room != null,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CommonsDetailsUiState(conversationId = conversationId, title = "", relayHost = null, members = emptyList()),
        )

    /** Leaves the room — its history goes, its people stay contacts (`CommonsRepository.leave`) — and re-derives the scope table. */
    fun leave() {
        val store = commons ?: return
        viewModelScope.launch {
            store.leave(conversationId)
            meshManager.refreshRelays()
            _left.tryEmit(Unit)
        }
    }
}
