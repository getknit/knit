package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore

/**
 * What a direct link's demand reads from the database (`docs/DIRECT_LINKS.md` L-7-7, L-7-9): each DM of ours still
 * unticked, for each post of ours in a group every member who has not acked it (both over the last
 * [LinkDemandPolicy.WINDOW_MS] alone), each DM attachment of ours with no spool to go up to ([stuck]:
 * `MeshManager.attachmentStuck`), and each DM attachment of a contact's we want and do not hold ([wanted]:
 * `MeshManager.attachmentWanted`), by when its message arrived. One class for the app's wiring and the mesh lab's, so
 * the lab reads what a phone does.
 */
class MeshLinkPending(
    private val messages: MessageRepository,
    private val receipts: MessageReceiptRepository,
    // A group's row by id (`GroupRepository.find`): its members, and whether we left it.
    private val group: suspend (groupId: String) -> GroupEntity?,
    // Whether our DM attachment (peer, hash) has no spool to go up to and is not proved held: the bulk trigger.
    private val stuck: suspend (peerId: String, hash: String) -> Boolean = { _, _ -> false },
    // Whether we want a contact's DM attachment and hold none of it: the receiving end's trigger.
    private val wanted: suspend (peerId: String, hash: String) -> Boolean = { _, _ -> false },
) {
    /**
     * The rows in [since]..[until] that call for a link, one per (contact, message): our unticked messages over the last
     * [LinkDemandPolicy.WINDOW_MS] of it only, our stuck attachments by when they were sent and their wanted ones by when
     * they arrived.
     */
    suspend fun between(
        me: String,
        since: Long,
        until: Long,
    ): List<LinkDemandPolicy.Pending> {
        val recent = maxOf(since, until - LinkDemandPolicy.WINDOW_MS)
        val dms = messages.unackedDmsBetween(me, recent, until).map { LinkDemandPolicy.Pending(it.peerId, it.sentAt) }
        val files =
            messages
                .ownDmAttachmentsBetween(me, since, until)
                .filter { stuck(it.peerId, it.hash) }
                .map { LinkDemandPolicy.Pending(it.peerId, it.sentAt, LinkWhy.FILE) } +
                messages
                    .peerDmAttachmentsArrivedBetween(me, since, until)
                    .filter { wanted(it.peerId, it.hash) }
                    .map { LinkDemandPolicy.Pending(it.peerId, it.sentAt, LinkWhy.WANT) }
        val posts = messages.ownGroupPostsBetween(me, recent, until)
        if (posts.isEmpty()) return dms + files
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
        return dms + files + fromGroups
    }
}
