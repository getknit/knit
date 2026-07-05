package app.getknit.knit.ui.group

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.ui.util.computeAvatarCrop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One member row on the group-details screen, resolved to a display name + avatar + live presence. */
data class GroupMemberRow(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val online: Boolean,
    val isSelf: Boolean,
)

/** The group as shown on the details/settings screen: name, photo, and the resolved member roster. */
data class GroupDetailsUiState(
    val groupId: String,
    val title: String,
    val photoHash: String?,
    val members: List<GroupMemberRow>,
    // False once the group is left/deleted (its row is gone), so the screen can fall back to closing.
    val exists: Boolean = true,
)

/**
 * Backs the group details / settings screen (keyed by [groupId]). It surfaces the group's title
 * (shared name, or a locally-derived one via [groupTitle]), its photo, and the member roster resolved
 * against the cached peer profiles + live presence (the smoothed reachable set exposed as
 * [MeshManager.neighbors]). It also owns the group-management actions — rename, set photo, leave —
 * relocated here from the chat header so this screen is the single group-settings surface. The mesh
 * side of each action mirrors [app.getknit.knit.ui.chat.ChatViewModel]'s send path
 * ([GroupEntity.toInfo] floods the self-describing [GroupInfo] so members converge last-writer-wins).
 */
class GroupDetailsViewModel(
    private val groupId: String,
    private val groups: GroupRepository,
    peers: PeerRepository,
    private val meshManager: MeshController,
    private val avatars: AvatarStore,
    private val blobs: BlobRepository,
    identity: Identity,
    private val context: Context,
) : ViewModel() {
    private val me = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { me.value = identity.nodeId() }
    }

    val state: StateFlow<GroupDetailsUiState> =
        combine(
            groups.observeGroup(groupId),
            peers.observePeers(),
            meshManager.neighbors,
            me,
        ) { group, peerList, neighbors, myId ->
            val members = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
            val peersByNode = peerList.associateBy { it.nodeId }
            val onlineIds = neighbors.map { it.nodeId }.toSet()
            val rows =
                members.map { id ->
                    GroupMemberRow(
                        nodeId = id,
                        displayName = displayNameFor(peersByNode[id]?.name, id),
                        avatarHash = peersByNode[id]?.avatarHash,
                        online = id in onlineIds,
                        isSelf = id == myId,
                    )
                }
            // Self first (rendered as "You"), then the others connected-first, then alphabetical — mirroring
            // the contact picker's ordering.
            val self = rows.firstOrNull { it.isSelf }
            val others =
                rows
                    .filter { !it.isSelf }
                    .sortedWith(compareByDescending<GroupMemberRow> { it.online }.thenBy { it.displayName.lowercase() })
            GroupDetailsUiState(
                groupId = groupId,
                title =
                    groupTitle(
                        storedName = group?.name.orEmpty(),
                        memberIds = members,
                        selfId = myId,
                        fallback = context.getString(R.string.group_unnamed),
                    ) { id -> displayNameFor(peersByNode[id]?.name, id) },
                photoHash = group?.photoHash,
                members = listOfNotNull(self) + others,
                exists = group != null,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GroupDetailsUiState(groupId = groupId, title = "", photoHash = null, members = emptyList()),
        )

    /**
     * The self-describing [GroupInfo] flooded on every group frame, built from the local row so each
     * update re-asserts the current name **and** photo (both converge last-writer-wins, by their own
     * clocks). Kept in sync with [app.getknit.knit.ui.chat.ChatViewModel]'s copy on the send path.
     */
    private fun GroupEntity.toInfo() =
        GroupInfo(
            id = groupId,
            name = name.takeIf { it.isNotBlank() },
            members = GroupMembersStore.decode(members),
            createdBy = createdBy,
            photoHash = photoHash,
            photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
        )

    /**
     * Renames this group: updates the local store immediately and floods a group-update frame so members
     * converge right away (no waiting for the next message). The name is last-writer-wins by timestamp.
     */
    fun renameGroup(newName: String) {
        val trimmed = normalizeSingleLine(newName).take(TextLimits.GROUP_NAME)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val group = groups.find(groupId) ?: return@launch
            val updated = group.copy(name = trimmed, nameUpdatedAt = System.currentTimeMillis())
            groups.upsert(updated)
            meshManager.sendGroupUpdate(updated.toInfo())
        }
    }

    // The picked group photo awaiting crop (held here, not in SavedStateHandle — a Bitmap is large and not
    // parcel-friendly — so the crop survives configuration changes). Mirrors ProfileViewModel's avatar crop.
    private val _groupPhotoCropTarget = MutableStateFlow<Bitmap?>(null)
    val groupPhotoCropTarget: StateFlow<Bitmap?> = _groupPhotoCropTarget.asStateFlow()

    /** Picks an image for this group's photo and shows the crop UI; the save happens in [confirmGroupPhoto]. */
    fun pickGroupPhoto(uri: Uri) {
        viewModelScope.launch { _groupPhotoCropTarget.value = avatars.loadForCrop(uri) }
    }

    /**
     * Persists the cropped group photo and floods a group-update frame carrying its hash + clock, so every
     * member converges last-writer-wins and pulls the bytes. Any member can set it; the local row's photo
     * is updated immediately (the bytes are local), so it shows right away. [scale]/[offset]/[diameter]
     * come from the crop dialog's transform — the same flow as the profile avatar.
     */
    fun confirmGroupPhoto(
        scale: Float,
        offset: Offset,
        diameter: Float,
    ) {
        val source = _groupPhotoCropTarget.value ?: return
        _groupPhotoCropTarget.value = null
        viewModelScope.launch {
            val group = groups.find(groupId) ?: return@launch
            val crop = computeAvatarCrop(source.width, source.height, diameter, scale, offset.x, offset.y)
            val newHash = avatars.saveOwnAvatar(source, crop) ?: return@launch
            val oldHash = group.photoHash
            val updated = group.copy(photoHash = newHash, photoUpdatedAt = System.currentTimeMillis())
            groups.upsert(updated)
            meshManager.sendGroupUpdate(updated.toInfo())
            if (oldHash != null && oldHash != newHash) blobs.deleteIfUnreferenced(oldHash)
        }
    }

    fun cancelGroupPhotoCrop() {
        _groupPhotoCropTarget.value = null
    }

    /** Emitted after the leave persists, so the screen can navigate away (the thread no longer exists). */
    private val _left = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val left: SharedFlow<Unit> = _left.asSharedFlow()

    /**
     * Leaves this group: tells the other members (we're still a member, our key is known), then tombstones
     * it (so its frames stop being delivered and it can't be resurrected) and deletes its messages.
     * Emitted on [left] only after the leave persists, so navigating away can't cancel the write.
     */
    fun leaveGroup() {
        viewModelScope.launch {
            meshManager.sendGroupLeave(groupId)
            groups.leave(groupId)
            _left.tryEmit(Unit)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _groupPhotoCropTarget.value = null // drop the (large) pending crop bitmap
    }
}
