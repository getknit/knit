package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore

/**
 * What a direct link's demand reads from the database (`docs/DIRECT_LINKS.md` L-7-7, L-7-9): each DM of ours still
 * unticked, for each post of ours in a group every member who has not acked it (both over the last
 * [LinkDemandPolicy.WINDOW_MS] alone), each DM attachment of ours with no spool to go up to ([stuck]:
 * `MeshManager.attachmentStuck`), and each DM attachment of a contact's we want and do not hold ([wantedSince]:
 * `MeshManager.attachmentWantedSince`). One class for the app's wiring and the mesh lab's, so the lab reads what a phone
 * does.
 *
 * A wanted attachment's row is timed from when its message arrived or, if later, from when the want became one a link
 * may carry ([wantedSince]: the tap on a fast-only want held on a metered network, or the network turning unmetered,
 * ADR 2026-10.js2p, a5yj). [LinkDemandPolicy] offers for a row only while no link to the peer has been up since its
 * time, so a row left at the arrival would never call for a link once a link had come and gone while the want was held
 * (a chat's link, say, closed by a move to Wi-Fi, `ROUTE`). The row still waits [LinkDemandPolicy.WANT_WAIT_MS] from
 * that time, a tap's too: the sender's own offer or a radio may bring it first. A want is still read only for a message
 * that arrived within [LinkDemandPolicy.FILE_WINDOW_MS], so a tap on a DM older than that raises no link.
 *
 * A wanted attachment held for a fast route ([fastHeld]: over the person's download limit, with no fast holder) is marked
 * [LinkDemandPolicy.Pending.beside]: the one row that may call for a link to a contact a radio links too (L-7-11), since
 * the radio may not carry it and a free link may.
 */
class MeshLinkPending(
    private val messages: MessageRepository,
    private val receipts: MessageReceiptRepository,
    // A group's row by id (`GroupRepository.find`): its members, and whether we left it.
    private val group: suspend (groupId: String) -> GroupEntity?,
    // Whether our DM attachment (peer, hash) has no spool to go up to and is not proved held: the bulk trigger.
    private val stuck: suspend (peerId: String, hash: String) -> Boolean = { _, _ -> false },
    // Since when we have wanted a contact's DM attachment in a way a link may carry, holding none of it, or null: the
    // receiving end's trigger.
    private val wantedSince: suspend (peerId: String, hash: String) -> Long? = { _, _ -> null },
    // Whether that want is held for a fast route (`MeshManager.attachmentFastHeld`): its row may go beside a radio.
    private val fastHeld: suspend (hash: String) -> Boolean = { false },
) {
    /**
     * The rows in [since]..[until] that call for a link, one per (contact, message): our unticked messages over the last
     * [LinkDemandPolicy.WINDOW_MS] of it only, our stuck attachments by when they were sent and their wanted ones by when
     * they arrived or became linkable, the later.
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
                    .mapNotNull { row ->
                        wantedSince(row.peerId, row.hash)?.let {
                            LinkDemandPolicy.Pending(row.peerId, maxOf(row.sentAt, it), LinkWhy.WANT, beside = fastHeld(row.hash))
                        }
                    }
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
