package app.getknit.knit.mesh.inet

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * The mesh's answers to a link's lookups ([LinkScope]): the groups from the group store (founding roster: members and
 * departed), the addressees of every conversation naming a blob from the mesh ([addressees],
 * `MeshManager.blobAddressees`), our avatar from settings, and custody's envelopes from the carry store. One class for
 * the app's wiring and the mesh lab's, so the lab runs the lookups a phone does.
 */
class MeshLinkScope(
    private val groups: GroupRepository,
    private val addressees: suspend (hash: String) -> List<Set<String>>,
    private val ownAvatar: suspend () -> String?,
    private val custody: ForwardStore,
    private val clock: () -> Long,
) : LinkScope {
    override suspend fun sharedGroups(peerId: String): Map<String, Set<String>> =
        groups.groupsWith(peerId).associate { group ->
            group.groupId to (GroupMembersStore.decode(group.members) + GroupMembersStore.decode(group.departed)).toSet()
        }

    override suspend fun addresseeSetsOf(hash: String): List<Set<String>> = addressees(hash)

    override suspend fun ownAvatarKey(): String? = ownAvatar()

    override suspend fun custodyEnvelopes(ids: List<String>): Map<String, RelayEnvelope> =
        custody.liveFramesByIds(ids, clock()).associate { it.envelope.id to it.envelope }
}
