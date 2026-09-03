package app.getknit.knit.presence

/**
 * Pure "whom should the open-to-chat cue name, and when" decision — no Android, no coroutines, every entry
 * point takes `now`, JVM-unit-tested ([app.getknit.knit.presence.OpenToChatPolicyTest]). The driver is
 * [OpenToChatWatch]; this object owns the rules.
 *
 * The cue is a nudge to post in the Nearby room when somebody who declared themselves open to chat is in
 * **direct radio range** while the local user has declared the same. The inputs are joined by the watch:
 * the qualifying set is [qualifying] (own flag on, a short-range neighbor, a peer row carrying the flag, not
 * blocked, and **not someone we have already met**), and everything below is about *newcomers* to that set.
 *
 * The cue introduces strangers, so anyone the user has already exchanged messages with is out of scope
 * entirely — two people who live together would otherwise be re-introduced to each other for as long as
 * they both leave the flag on. "Already met" is a two-way exchange, not mere contact: a DM thread with a
 * message each way, or a group both have posted in
 * ([app.getknit.knit.data.message.MessageDao.observeAcquaintedPeers]). A stranger who has only posted once
 * in a shared room, or one we have DM'd with no reply yet, is still someone worth being introduced to.
 *
 * Five rules, one constant each, chosen against the transports' own numbers (a BLE sighting lingers 90 s, a
 * Wi-Fi Aware one 150 s):
 *  - a newcomer waits [HOLD_MS] so that walking into a room full of people becomes **one** cue, anchored on
 *    the oldest arrival so a trickle cannot push it out forever;
 *  - a peer already named is named again only after a real departure — out of the set for [ABSENCE_MS], well
 *    past any sighting linger or sync-driven flap — **and** [PEER_COOLDOWN_MS] since the last cue about them,
 *    **and** fewer than [PEER_DAILY_CAP] cues about them in the last [DAY_MS];
 *  - cues are posted at most once per [ALERT_GAP_MS] overall: newcomers inside the gap are *held* and ride the
 *    next post (if still present), so every post is a real cue rather than a quiet refresh.
 *
 * Continuous presence never re-cues (no departure, no arrival). The own flag going off empties the set, so
 * everyone departs and nothing is pending; going back on makes everyone arrive through the same gate — which is
 * what makes "switch it on in a full room" one batched cue with no special case. After a process restart the
 * departure clock is unknowable ([State.leftAt] is empty), so for a peer the persisted [State.named] stamps
 * remember, the cooldown and the daily cap alone decide.
 */
object OpenToChatPolicy {
    /** Batch window: how long the first newcomer waits for company before the cue is posted. */
    const val HOLD_MS = 20_000L

    /** Out of the qualifying set this long counts as a genuine departure (≫ the 90 s / 150 s sighting lingers). */
    const val ABSENCE_MS = 15 * 60_000L

    /** Minimum gap between two cues naming the same peer. */
    const val PEER_COOLDOWN_MS = 2 * 60 * 60_000L

    /** Most cues naming the same peer within any rolling [DAY_MS]. */
    const val PEER_DAILY_CAP = 2

    /** The rolling window [PEER_DAILY_CAP] counts over, and how long a [State.named] stamp is kept. */
    const val DAY_MS = 24 * 60 * 60_000L

    /** Minimum gap between two cue posts, whoever they name. */
    const val ALERT_GAP_MS = 60 * 60_000L

    /**
     * The policy's whole memory. [present] is the qualifying set as of the last [fold]; [leftAt] when an absent
     * peer left it (this process only); [named] the cue stamps per peer, newest last, kept for [DAY_MS]
     * (persisted); [pending] the newcomers waiting for the hold, in arrival order with their arrival time;
     * [lastPostAt] when the last cue was posted (persisted; 0 = never).
     */
    data class State(
        val present: Set<String> = emptySet(),
        val leftAt: Map<String, Long> = emptyMap(),
        val named: Map<String, List<Long>> = emptyMap(),
        val pending: Map<String, Long> = emptyMap(),
        val lastPostAt: Long = 0L,
    )

    /** A [flush]: the state after it and the peers the cue names, in arrival order. */
    class Flush(
        val state: State,
        val show: List<String>,
    )

    /**
     * The peers the cue may name right now: direct neighbors carrying the flag, minus the blocked and minus
     * the [acquainted] (anyone we have already exchanged messages with, who needs no introduction), and only
     * while our own flag is on.
     */
    fun qualifying(
        ownFlag: Boolean,
        neighborIds: Set<String>,
        openIds: Set<String>,
        blocked: Set<String>,
        acquainted: Set<String>,
    ): Set<String> = if (!ownFlag) emptySet() else (neighborIds intersect openIds) - blocked - acquainted

    /**
     * Applies the current [qualifying] set: a peer that left it is stamped in [State.leftAt] and dropped from
     * the pending batch (never name someone who already walked away), and a newcomer joins the batch when the
     * per-peer rules allow. Idempotent for an unchanged set.
     */
    fun fold(
        state: State,
        qualifying: Set<String>,
        now: Long,
    ): State {
        val leftAt = state.leftAt.toMutableMap()
        val pending = LinkedHashMap(state.pending)
        for (peer in state.present) {
            if (peer !in qualifying) {
                leftAt[peer] = now
                pending.remove(peer)
            }
        }
        for (peer in qualifying) {
            if (peer in state.present) continue
            val away = leftAt.remove(peer)
            if (eligible(state.named[peer].orEmpty(), away, now)) pending.putIfAbsent(peer, now)
        }
        return state.copy(present = qualifying, leftAt = leftAt, pending = pending)
    }

    private fun eligible(
        stamps: List<Long>,
        leftAt: Long?,
        now: Long,
    ): Boolean {
        val last = stamps.maxOrNull() ?: return true
        if (now - last < PEER_COOLDOWN_MS) return false
        if (stamps.count { now - it < DAY_MS } >= PEER_DAILY_CAP) return false
        return leftAt == null || now - leftAt >= ABSENCE_MS
    }

    /** When the pending batch is due — the hold on its oldest arrival, or the hourly gap, whichever is later. */
    fun dueAt(state: State): Long? {
        val oldest = state.pending.values.minOrNull() ?: return null
        val gapEnds = if (state.lastPostAt == 0L) 0L else state.lastPostAt + ALERT_GAP_MS
        return maxOf(oldest + HOLD_MS, gapEnds)
    }

    /** Posts the batch: stamps every named peer, prunes the stamps, and resets the hold. */
    fun flush(
        state: State,
        now: Long,
    ): Flush {
        val show = state.pending.keys.toList()
        val named = state.named.toMutableMap()
        for (peer in show) named[peer] = named[peer].orEmpty() + now
        return Flush(
            state = state.copy(named = prune(named, now), pending = emptyMap(), lastPostAt = now),
            show = show,
        )
    }

    /** Drops stamps older than [DAY_MS] — everything the cooldown and the daily cap can still read. */
    fun prune(
        named: Map<String, List<Long>>,
        now: Long,
    ): Map<String, List<Long>> =
        named
            .mapValues { (_, stamps) -> stamps.filter { now - it < DAY_MS } }
            .filterValues { it.isNotEmpty() }

    /** [State.named] as `"<peerId>|<millis>"` entries, one per stamp, for the settings store. */
    fun encodeNamed(named: Map<String, List<Long>>): Set<String> =
        named.flatMapTo(mutableSetOf()) { (peer, stamps) -> stamps.map { "$peer|$it" } }

    /** The inverse of [encodeNamed]; a malformed entry is skipped. */
    fun decodeNamed(entries: Set<String>): Map<String, List<Long>> =
        entries
            .mapNotNull { entry ->
                val at = entry.lastIndexOf('|')
                if (at <= 0) return@mapNotNull null
                val stamp = entry.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
                entry.substring(0, at) to stamp
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, stamps) -> stamps.sorted() }
}
