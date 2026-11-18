package app.getknit.knit.mesh

/**
 * Bounded, time-windowed set of frame ids already processed, used to terminate the mesh flood.
 *
 * Improves on the legacy app's seen-set, which was unbounded and never expired: this caps the
 * number of retained ids (LRU eviction) and treats an id as new again once its [ttlMillis] window
 * has elapsed. [clock] is injectable for deterministic tests.
 */
class SeenSet(
    maxSize: Int = 4096,
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // How many reopened ids the guard table holds ([REOPENED_MAX]); injectable so `mesh/lab` can fill it with a small flood.
    private val reopenedMax: Int = REOPENED_MAX,
) {
    private val seen =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxSize
        }

    /**
     * Records [id] and returns true if it was not seen within the TTL window (i.e. it is new). An id under a live reopen
     * guard ([guardFor]) is never marked here: only [settle] marks it, once a copy has opened (#347).
     */
    @Synchronized
    fun add(id: String): Boolean {
        val now = clock()
        if (reopened[id]?.guards(now) == true) return false
        val last = seen[id]
        if (last != null && now - last < ttlMillis) {
            return false
        }
        seen[id] = now
        return true
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val last = seen[id] ?: return false
        return clock() - last < ttlMillis
    }

    /**
     * Opens the gate to [id] again, for a sealed DM addressed to us from [sender] that this node took and could not
     * use: a frame whose session we no longer hold. The sender's answer to our reset is a fresh seal under the *same* id
     * (no wire change, and the exists-gate dedups it), and it can land seconds after the copy custody just served us,
     * well inside the window. Only we reopen: a relay that saw the id in its window drops that seal too, so the sender
     * re-seals once more after the window (`MeshManager.resealRecentDmsTo`, ADR 2026-10.dmgw). Once per id per window,
     * judged from the last reopen's time whether or not it has [settle]d since: a peer re-serving an unopenable frame
     * cannot buy a second one.
     *
     * The reopen leaves a guard (#347, ADR 2026-10.dmgw's 2026-11-18 amendment): the id stays unmarked until a copy of
     * it opens ([settle]) or [REOPEN_GUARD_MS] passes. Marked again by the next copy, as it used to be, a stale original
     * or an earlier reset's re-seal spent the one reopen, and the second-pass re-seal behind it dropped as seen. While
     * the guard stands the router delivers a copy only once [spendTry] lets its signature through, and never relays one.
     * A full table makes room as [makeRoom] says. Returns whether the id was reopened.
     */
    @Synchronized
    fun reopen(
        id: String,
        sender: String,
    ): Boolean {
        val now = clock()
        val last = seen[id] ?: return false
        if (now - last >= ttlMillis) return false
        val before = reopened[id]
        if (before != null && now - before.at < ttlMillis) return false
        seen.remove(id)
        // Re-armed after a lapse, the id moves to the newest end; a new id first makes room.
        if (reopened.remove(id) == null && reopened.size >= reopenedMax) makeRoom(now)
        reopened[id] = Guard(now, sender)
        return true
    }

    /**
     * Drops one entry from a full guard table (#347). One that no longer guards goes first, oldest first: a lapsed guard,
     * or a settled one, whose id the settle marked. Only then a live one, and it is the newest guard of the sender holding
     * the most, so one sender's run of reopens evicts its own and never another sender's guard. Ties go to the sender whose
     * guard is newest. An evicted live guard leaves its id unmarked, and the next copy that fails reopens it afresh.
     */
    private fun makeRoom(now: Long) {
        reopened.entries.firstOrNull { !it.value.guards(now) }?.let {
            reopened.remove(it.key)
            return
        }
        val held = reopened.values.groupingBy { it.sender }.eachCount()
        val most = held.values.maxOrNull() ?: return
        val victim =
            reopened.entries
                .toList()
                .asReversed()
                .first { held[it.value.sender] == most }
                .key
        reopened.remove(victim)
    }

    /**
     * The live reopen guard on [id] — its sender and when it was set — or null: none was set, the id has [settle]d, or
     * [REOPEN_GUARD_MS] has passed since the reopen.
     */
    @Synchronized
    fun guardFor(id: String): ReopenGuard? {
        val guard = reopened[id]?.takeIf { it.guards(clock()) } ?: return null
        return ReopenGuard(guard.sender, guard.at)
    }

    /**
     * Spends one of [REOPEN_TRIES] deliveries for the copy of [id] signed [key] (its signature), checked and counted in
     * one step. A signature's count refills [ttlMillis] after its first try, so a copy that can open later — a follow-up
     * that rode ahead of its reset, a peek that raced a commit — is never refused for good, and a replayer is bounded
     * to [REOPEN_TRIES] opens per signature per window. At most [REOPEN_KEYS] signatures are kept per id, the least
     * recently tried evicted: a new one always gets its try. False for an id with no live guard.
     */
    @Synchronized
    fun spendTry(
        id: String,
        key: String,
    ): Boolean {
        val now = clock()
        val guard = reopened[id]?.takeIf { it.guards(now) } ?: return false
        val tries = guard.tries[key]
        when {
            tries == null -> guard.tries[key] = Tries(now)
            now - tries.firstAt >= ttlMillis -> guard.tries[key] = Tries(now)
            tries.count >= REOPEN_TRIES -> return false
            else -> tries.count++
        }
        return true
    }

    /**
     * A copy of [id] from [sender] opened: marks it seen, so later copies dedup as any id's do, and settles its guard. The
     * guard's time is kept, so a stale copy failing now still has its reopen refused inside the window. A no-op for an id
     * with no live guard, and for a guard another sender's frame set: only the guarded sender's copy ends it.
     */
    @Synchronized
    fun settle(
        id: String,
        sender: String,
    ) {
        val now = clock()
        val guard = reopened[id]?.takeIf { it.guards(now) && it.sender == sender } ?: return
        guard.settled = true
        seen[id] = now
    }

    // Insertion order, bounded by [makeRoom] at [reopenedMax].
    private val reopened = LinkedHashMap<String, Guard>(16, 0.75f, false)

    /** The reopen of one id: when, by whose frame, whether a copy has opened since, and the tries per signature. */
    private class Guard(
        val at: Long,
        val sender: String,
    ) {
        var settled = false

        val tries =
            object : LinkedHashMap<String, Tries>(4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tries>): Boolean = size > REOPEN_KEYS
            }

        fun guards(now: Long): Boolean = !settled && now - at < REOPEN_GUARD_MS
    }

    private class Tries(
        val firstAt: Long,
    ) {
        var count = 1
    }

    /** A live reopen guard as [guardFor] reads it: whose frame was reopened, and when. */
    class ReopenGuard(
        val sender: String,
        val at: Long,
    )

    /**
     * The live entries, oldest first — for a set whose window outlives the process that keeps it. Expired
     * ones are left out: they are new again by definition, so persisting them would only cost bytes.
     *
     * (The LoRa plane's profile re-fan gate is the one that needs this: its window is 12 hours against a
     * process that may live minutes — see `mesh/lora/LoraPlaneState`.)
     */
    @Synchronized
    fun stamps(): List<Pair<String, Long>> {
        val now = clock()
        return seen.entries.filter { now - it.value < ttlMillis }.map { it.key to it.value }
    }

    /**
     * Re-adds [stamps] as though each had been seen at its recorded time, skipping any that have since
     * expired. Applied oldest-first so a list longer than [maxSize] leaves the newest behind — the same rule
     * the LRU applies while running.
     */
    @Synchronized
    fun restore(stamps: List<Pair<String, Long>>) {
        val now = clock()
        stamps.sortedBy { it.second }.forEach { (id, at) -> if (now - at < ttlMillis) seen[id] = at }
    }

    companion object {
        /** Default flood-suppression window: an id counts as new again after 10 minutes. */
        const val DEFAULT_TTL_MS = 10 * 60_000L

        /**
         * Reopened ids remembered, so none is reopened twice in a window and each keeps its guard. Sized for a restore's
         * backlog, which custody bounds at `ForwardRepository.DEFAULT_MAX_ROWS` (1,000), so an honest restore's failures
         * fit; past it, [makeRoom].
         */
        const val REOPENED_MAX = 1_024

        /**
         * How long a reopen's guard keeps its id unmarked when no copy opens (#347): two dedup windows and two minutes,
         * so it outlasts the reset's second pass ([ResetReseal.SECOND_PASS_MS] after the first, which leaves up to a
         * window after the failure) with a tick to spare. Past it the next copy is marked, fails and re-arms the guard,
         * the path every id took before.
         */
        const val REOPEN_GUARD_MS = 2 * DEFAULT_TTL_MS + 2 * 60_000L

        /** Deliveries of one signature of a guarded id per window ([spendTry]). */
        const val REOPEN_TRIES = 3

        /** Signatures a guard counts tries for, the least recently tried evicted ([spendTry]). */
        const val REOPEN_KEYS = 8
    }
}
