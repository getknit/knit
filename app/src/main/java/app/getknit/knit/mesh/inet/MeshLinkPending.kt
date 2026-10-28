package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore

/**
 * What a direct link's demand reads from the database (`docs/DIRECT_LINKS.md` L-7-7): each DM of ours still unticked,
 * and for each post of ours in a group, every member who has not acked it. One class for the app's wiring and the
 * mesh lab's, so the lab reads what a phone does.
 */
class MeshLinkPending(
    private val messages: MessageRepository,
    private val receipts: MessageReceiptRepository,
    // A group's row by id (`GroupRepository.find`): its members, and whether we left it.
    private val group: suspend (groupId: String) -> GroupEntity?,
) {
    /** Our messages sent in [since]..[until] that a contact has not ticked, one row per (contact, message). */
    suspend fun between(
        me: String,
        since: Long,
        until: Long,
    ): List<LinkDemandPolicy.Pending> {
        val dms = messages.unackedDmsBetween(me, since, until).map { LinkDemandPolicy.Pending(it.peerId, it.sentAt) }
        val posts = messages.ownGroupPostsBetween(me, since, until)
        if (posts.isEmpty()) return dms
        val acked = receipts.ackersOf(posts.map { it.id })
        val rosters =
            posts
                .map { it.conversationId }
                .distinct()
                .associateWith { id -> group(id)?.takeUnless { it.left }?.let { GroupMembersStore.decode(it.members).toSet() }.orEmpty() }
        val fromGroups =
            posts.flatMap { post ->
                (rosters[post.conversationId].orEmpty() - me - acked[post.id].orEmpty())
                    .map { LinkDemandPolicy.Pending(it, post.sentAt) }
            }
        return dms + fromGroups
    }
}
