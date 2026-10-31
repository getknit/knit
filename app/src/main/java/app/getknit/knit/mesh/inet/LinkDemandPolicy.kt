package app.getknit.knit.mesh.inet

import app.getknit.knit.data.forward.ForwardRepository

/**
 * When to offer a contact a direct link (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` L-7-7 and L-7-9): the spool first, a
 * link as the last resort. A spool this phone holds open carries our messages within seconds, so a link is offered only
 * when one has not arrived, or cannot ([Pending.why]):
 *  - [LinkWhy.TICK]: something of ours to them — a DM, or a post in a group they are in — has waited [TICK_WAIT_MS] for
 *    their delivery tick, within the last [WINDOW_MS];
 *  - [LinkWhy.FILE]: at once, a DM attachment of ours to them has no spool to go up to (every spool carrying the pair
 *    refuses the upload), within the last [FILE_WINDOW_MS];
 *  - [LinkWhy.WANT]: a DM attachment of theirs we want has gone [WANT_WAIT_MS] since its message arrived with nobody
 *    bringing it, within the last [FILE_WINDOW_MS] — the sender may have given up offering, or not know it can;
 *
 * and, for each,
 *  - no radio holds a link to them: a radio neighbour's tick is slow for the radio's own reasons, and the radio carries
 *    the frame for free;
 *  - no link to them is up, or has been since that row's time: a link that was up had its chance at it — sent while it
 *    was up, or named in its digest at link-up — and offering again for the same row would open, idle and close a link a
 *    minute for a tick that will not come (a peer that cannot open it, a peer gone). A link that lost its path while a
 *    file crossed it had no chance at the file, and `DirectLinks.lastUpAt` does not count it;
 *  - they have not declined an offer since that row's time (`DirectLinks.declinedAt`, L-2-6): their person has
 *    links off, and only something newer asks again;
 *  - no offer to them is in flight, and they are not resting after a refusal that sent nothing ([due]'s `quietUntil`).
 *
 * At most [MAX_LINKS] links are up or being offered at once, the longest-waiting contacts first. When they are all
 * taken and a contact waits, the link idle longest gives up its slot ([evict]) — but only one idle [EVICT_IDLE_MS] or
 * more, so under contention a link lives about as long as it did before the idle wait grew to three minutes, and two
 * contacts never take turns closing each other's links. A message older than
 * [WINDOW_MS] is the spool's to deliver whenever the contact is back: a link is for a conversation going on now. A file
 * no spool carries has no such second path, so it is offered for as long as custody keeps its frame, at the links'
 * backoff (`PunchPolicy.backoffMs`, capped at half an hour). Pure: the database's rows, the radios and the links are its
 * inputs.
 */
object LinkDemandPolicy {
    /**
     * One row that calls for a link to [peerId] at [at] (when it was sent; for a [LinkWhy.WANT], when its message
     * arrived), by [why]: [LinkWhy.TICK], [LinkWhy.FILE] or [LinkWhy.WANT].
     */
    data class Pending(
        val peerId: String,
        val at: Long,
        val why: LinkWhy = LinkWhy.TICK,
    )

    /** How long a message of ours waits for its tick before the contact is offered a link. */
    const val TICK_WAIT_MS = 20_000L

    /** How old a message may be and still call for a link. */
    const val WINDOW_MS = 15 * 60_000L

    /** How old a file, ours stuck or theirs wanted, may be and still call for a link: custody's keep (24 h). */
    const val FILE_WINDOW_MS = ForwardRepository.DEFAULT_TTL_MS

    /** How long a file of theirs we want waits, from its message's arrival, before we offer a link for it. */
    const val WANT_WAIT_MS = 60_000L

    /** How many links this phone keeps up, or offers, at once on demand. */
    const val MAX_LINKS = 2

    /** How long a link must have been idle before it gives up its slot to a contact waiting for one. */
    const val EVICT_IDLE_MS = 60_000L

    /**
     * The contacts to offer a link now, longest-waiting first, each with the [LinkWhy] of its longest-waiting row, from
     * [pending] at [now]. [up] is the contacts a link is up to, [offering] those an offer to is in flight, [lastUpAt]
     * when a link to one was last up (`DirectLinks.lastUpAt`), [declinedAt] when one last declined (`DirectLinks.declinedAt`).
     */
    @Suppress("LongParameterList") // the demand's inputs, each one of the rule's clauses
    fun offers(
        pending: List<Pending>,
        now: Long,
        radioLinked: (String) -> Boolean,
        up: Set<String>,
        offering: Set<String> = emptySet(),
        lastUpAt: (String) -> Long? = { null },
        quietUntil: Map<String, Long> = emptyMap(),
        declinedAt: (String) -> Long? = { null },
        maxLinks: Int = MAX_LINKS,
    ): List<Pair<String, LinkWhy>> {
        val room = maxLinks - up.size - offering.size
        if (room <= 0) return emptyList()
        return pending
            .asSequence()
            .filter { now - it.at in windowOf(it.why) }
            .filter { it.peerId !in up && it.peerId !in offering && now >= (quietUntil[it.peerId] ?: 0L) }
            .filter { p -> lastUpAt(p.peerId)?.let { p.at > it } ?: true }
            .filter { p -> declinedAt(p.peerId)?.let { p.at > it } ?: true }
            .groupBy { it.peerId }
            .mapValues { (_, rows) -> rows.minBy { it.at } }
            .filterKeys { !radioLinked(it) }
            .values
            .sortedBy { it.at }
            .take(room)
            .map { it.peerId to it.why }
    }

    /** [offers]' contacts alone. */
    @Suppress("LongParameterList") // [offers]' inputs
    fun due(
        pending: List<Pending>,
        now: Long,
        radioLinked: (String) -> Boolean,
        up: Set<String>,
        offering: Set<String> = emptySet(),
        lastUpAt: (String) -> Long? = { null },
        quietUntil: Map<String, Long> = emptyMap(),
        declinedAt: (String) -> Long? = { null },
        maxLinks: Int = MAX_LINKS,
    ): List<String> = offers(pending, now, radioLinked, up, offering, lastUpAt, quietUntil, declinedAt, maxLinks).map { it.first }

    /**
     * The up link to close now so a waiting contact can have its slot, or null: every slot is taken ([up] and
     * [offering]), [offers] would offer some contact one were a slot free, and among [idleSince] (`DirectLinks.idleSince`:
     * the links neither held nor moving a file, by since when they have been idle) the one idle longest has been so for
     * [EVICT_IDLE_MS] or more. The other inputs are [offers]'.
     */
    @Suppress("LongParameterList") // [offers]' inputs and the links' idleness
    fun evict(
        pending: List<Pending>,
        now: Long,
        radioLinked: (String) -> Boolean,
        up: Set<String>,
        idleSince: Map<String, Long>,
        offering: Set<String> = emptySet(),
        lastUpAt: (String) -> Long? = { null },
        quietUntil: Map<String, Long> = emptyMap(),
        declinedAt: (String) -> Long? = { null },
        maxLinks: Int = MAX_LINKS,
    ): String? {
        val taken = up.size + offering.size
        if (taken < maxLinks) return null
        val waiting = offers(pending, now, radioLinked, up, offering, lastUpAt, quietUntil, declinedAt, maxLinks = taken + 1)
        if (waiting.isEmpty()) return null
        return idleSince
            .filter { (peer, since) -> peer in up && now - since >= EVICT_IDLE_MS }
            .minByOrNull { it.value }
            ?.key
    }

    // How old a row of [why] may be, from its time, and call for a link.
    private fun windowOf(why: LinkWhy): LongRange =
        when (why) {
            LinkWhy.FILE -> 0L..FILE_WINDOW_MS
            LinkWhy.WANT -> WANT_WAIT_MS..FILE_WINDOW_MS
            else -> TICK_WAIT_MS..WINDOW_MS
        }
}
