package app.getknit.knit.mesh.inet

/**
 * When to offer a contact a direct link (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` L-7-7): the spool first, a link as
 * the last resort. A spool this phone holds open carries our messages within seconds, so a link is offered only when
 * one has not arrived: a contact is due when something of ours to them — a DM, or a post in a group they are in — has
 * waited [TICK_WAIT_MS] for their delivery tick, within the last [WINDOW_MS], and
 *  - no radio holds a link to them: a radio neighbour's tick is slow for the radio's own reasons, and the radio carries
 *    the frame for free;
 *  - no link to them is up, or has been since that message was sent: a link that was up had its chance at it — sent
 *    while it was up, or named in its digest at link-up — and offering again for the same message would open, idle and
 *    close a link a minute for a tick that will not come (a peer that cannot open it, a peer gone);
 *  - no offer to them is in flight, and they are not resting after a refusal that sent nothing ([due]'s `quietUntil`).
 *
 * At most [MAX_LINKS] links are up or being offered at once, the longest-waiting contacts first. A message older than
 * [WINDOW_MS] is the spool's to deliver whenever the contact is back: a link is for a conversation going on now. Pure:
 * the database's rows, the radios and the links are its inputs.
 */
object LinkDemandPolicy {
    /** One message of ours that [peerId] has not ticked: a DM to them, or a post in a group they are in. */
    data class Pending(
        val peerId: String,
        val sentAt: Long,
    )

    /** How long a message of ours waits for its tick before the contact is offered a link. */
    const val TICK_WAIT_MS = 20_000L

    /** How old a message may be and still call for a link. */
    const val WINDOW_MS = 15 * 60_000L

    /** How many links this phone keeps up, or offers, at once on demand. */
    const val MAX_LINKS = 2

    /**
     * The contacts to offer a link now, longest-waiting first, from [pending] at [now]. [up] is the contacts a link is up
     * to, [offering] those an offer to is in flight, [lastUpAt] when a link to one was last up (`DirectLinks.lastUpAt`).
     */
    @Suppress("LongParameterList") // the demand's inputs, each one of the rule's clauses
    fun due(
        pending: List<Pending>,
        now: Long,
        radioLinked: (String) -> Boolean,
        up: Set<String>,
        offering: Set<String> = emptySet(),
        lastUpAt: (String) -> Long? = { null },
        quietUntil: Map<String, Long> = emptyMap(),
        maxLinks: Int = MAX_LINKS,
    ): List<String> {
        val room = maxLinks - up.size - offering.size
        if (room <= 0) return emptyList()
        return pending
            .asSequence()
            .filter { now - it.sentAt in TICK_WAIT_MS..WINDOW_MS }
            .filter { it.peerId !in up && it.peerId !in offering && now >= (quietUntil[it.peerId] ?: 0L) }
            .filter { p -> lastUpAt(p.peerId)?.let { p.sentAt > it } ?: true }
            .groupBy({ it.peerId }, { it.sentAt })
            .mapValues { (_, times) -> times.min() }
            .filterKeys { !radioLinked(it) }
            .entries
            .sortedBy { it.value }
            .take(room)
            .map { it.key }
    }
}
