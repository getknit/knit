package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.forward.ForwardRepository

/**
 * The sender's proof of a DM photo that a contact's direct link carried because no spool would take it (ADR 2026-10.a5yj
 * L-7-7, ADR 2026-10.vy27). The bulk trigger offers the link the moment every spool carrying the pair refuses the
 * upload; the spool plane has settled that upload by then (locked, or too large) and does not look at it again until a
 * files ticket, a replaced connection or its hourly recheck, so it never asks the holder query that the deferral's
 * rounds ask for a photo still waiting. Without the answer the sender holds no `attachment_holders` row for the link, and
 * a later files ticket uploads a photo the recipient already has.
 *
 * So each [round] — every demand round ([LinkDemand.ROUND_MS]) — asks every peer [linkPeers] names that no radio links
 * about each of our DM attachments to them still [stuck]: no spool takes it and its hold is not proved. What is missing
 * is read from the database each time, never a memo of what was asked or served (ADR 2026-09.ptv8): asking ends at the
 * proof (the yes lands through `BlobExchange.onPeerHolds` as a `DirectLink` holder row, and the row makes it unstuck),
 * at a gone link (its peer leaves [linkPeers]), or once the message is older than [windowMs], custody's TTL — a link that
 * came up late on a backoff, or a recipient that fetched late (it asks a link's peer last), still gets its proof. No
 * count bounds it: a FETCHING answer proves nothing and the next round asks again. [ask] is the batched
 * `BlobExchange.confirmHeld`, which keeps its own gates (`CAP_BLOB_HAVE`, what the link carries, one query per
 * (hash, peer) per `PROOF_FLOOR_MS`); a proof query is not a link's use, so it never holds a link open.
 *
 * DM only ([MessageRepository.ownDmAttachmentsBetween]): no member's proof speaks for another's hold of a group's
 * picture. Stuck rows only: a photo no spool takes is one only the link could carry. It is not the only writer of a
 * sender's `DirectLink` row — the spool's push-half deferral asks a link peer too, about a photo held back while the link
 * is up (L-7-6), where the link usually carried it as well — so that row means a link confirmed the hold, the link's
 * delivery save for a peer that got the photo another way (ADR 2026-10.a5yj's 2026-10-28 amendment). Pure: the
 * database, the radios and the ask are its inputs.
 */
class LinkHolderProof(
    private val messages: MessageRepository,
    private val selfId: suspend () -> String,
    private val radioLinked: (peerId: String) -> Boolean,
    private val stuck: suspend (peerId: String, hash: String) -> Boolean,
    private val ask: (hash: String, peerId: String) -> Unit,
    private val clock: () -> Long,
    private val windowMs: Long = ForwardRepository.DEFAULT_TTL_MS,
) {
    /**
     * Asks each of [linkPeers] no radio links about our DM attachments to them that are still stuck; returns the
     * (peer, hash) pairs asked, in the order asked.
     */
    suspend fun round(linkPeers: Collection<String>): List<Pair<String, String>> {
        val peers = linkPeers.filterNotTo(HashSet(), radioLinked)
        if (peers.isEmpty()) return emptyList()
        val now = clock()
        val asked =
            messages
                .ownDmAttachmentsBetween(selfId(), now - windowMs, now)
                .asSequence()
                .filter { it.peerId in peers }
                .map { it.peerId to it.hash }
                .distinct()
                .toList()
                .filter { (peer, hash) -> stuck(peer, hash) }
        asked.forEach { (peer, hash) -> ask(hash, peer) }
        return asked
    }
}
