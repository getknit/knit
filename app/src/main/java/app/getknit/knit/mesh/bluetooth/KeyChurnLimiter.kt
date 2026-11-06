package app.getknit.knit.mesh.bluetooth

/**
 * Bounds what a contact's key churn costs the resolver (ADR 2026-11.xp6d's reader, obligation 6; the security pass's
 * follow-up): a new key is up to a window's 140 HMACs, so a contact streaming generations would otherwise spend the
 * confined worker without end. Between the key store's listing ([offer], node id → keys newest first, the whole set) and
 * [AdvertAliases.setKeys]:
 *
 * 1. a contact's listing **gains a key** at most once per [minIntervalMs], and at most [dailyCap] times per rolling
 *    [dayMs]; a change inside either is held, and when the window frees the **newest** listing held applies, never the
 *    first ([held], and [coalesced] for a held listing a newer one replaced);
 * 2. **unlisting is never delayed**: a contact leaving the listing and a key dropped apply at once, and a change that
 *    both drops and gains applies its drop at once and holds its gain.
 *
 * A contact that leaves the listing and comes back within [dayMs] is counted against what it gained before. So a
 * contact streaming generations costs the table at most three new keys' tags, 420 HMACs, per [minIntervalMs], whether
 * or not it drops out between them (the window's own slide, a slot's tags per listed key, is beside that).
 *
 * **A re-entry with keys it held is a relisting, not a gain** (step 5b's W7): a contact that left (a `DirectContacts`
 * flap) and comes back with only keys it had applied when it left relists at most once per [minIntervalMs] and never
 * counts toward [dailyCap]; it gained nothing new to the resolver. Otherwise a contact flapping 25 times a day would be
 * held for the rest of the day at its 25th return, unrecognised by a contact who did nothing wrong. Pure, on the
 * elapsed clock the caller passes; single-threaded with the resolver's worker.
 */
internal class KeyChurnLimiter(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val dailyCap: Int = DAILY_CAP,
    private val dayMs: Long = DAY_MS,
) {
    private class State(
        var applied: List<ByteArray>,
    ) {
        var pending: List<ByteArray>? = null

        // The held listing is a relisting ([KeyChurnLimiter]'s KDoc): only the interval applies to it.
        var pendingRelist = false
        val gains = ArrayDeque<Long>()

        // When a listing last went to the resolver (a gain or a relisting): the interval runs from it.
        var listedAt: Long? = null
    }

    // What a contact that left held: its gains, the keys applied when it left, and when it was last listed.
    private class Departed(
        val gains: ArrayDeque<Long>,
        val keys: List<ByteArray>,
        val listedAt: Long?,
    )

    private val contacts = HashMap<String, State>()

    // The gains of contacts that left the listing (a DirectContacts flap), kept a day: a re-entry counts against them, so
    // leaving and coming back never escapes the bound. At most one per contact that left.
    private val departed = HashMap<String, Departed>()

    /** Changes held for a window, and held changes a newer one replaced. */
    var held = 0L
        private set

    var coalesced = 0L
        private set

    /**
     * The store's whole listing at [now]. Returns the listing to hand the resolver now, or null when nothing it holds
     * changed (every gain held).
     */
    fun offer(
        listing: Map<String, List<ByteArray>>,
        now: Long,
    ): Map<String, List<ByteArray>>? {
        departed.values.removeAll { d -> (d.listedAt ?: d.gains.lastOrNull())?.let { now - it >= dayMs } != false }
        val leaving = contacts.keys - listing.keys
        leaving.forEach { id -> contacts.remove(id)?.let { departed[id] = Departed(it.gains, it.applied, it.listedAt) } }
        var changed = leaving.isNotEmpty()
        for ((id, keys) in listing) {
            val state = contacts[id]
            // A contact new to the listing gains as any other does, against what it gained before it left.
            changed = (if (state == null) firstListing(id, keys, now) else apply(state, keys, now)) || changed
        }
        return if (changed) applied() else null
    }

    /** Applies the held listings whose window has freed at [now]: the listing to hand the resolver, or null for none. */
    fun release(now: Long): Map<String, List<ByteArray>>? {
        var changed = false
        for (state in contacts.values) {
            val pending = state.pending
            if (pending != null && now >= dueAt(state)) {
                state.pending = null
                state.applied = pending
                if (!state.pendingRelist) state.gains.addLast(now)
                state.pendingRelist = false
                state.listedAt = now
                changed = true
            }
        }
        return if (changed) applied() else null
    }

    private fun firstListing(
        id: String,
        keys: List<ByteArray>,
        now: Long,
    ): Boolean {
        val state = State(emptyList())
        val before = departed.remove(id)
        before?.let {
            state.gains.addAll(it.gains)
            state.listedAt = it.listedAt
        }
        contacts[id] = state
        // Only keys it held when it left: a relisting, under the interval alone.
        state.pendingRelist = before != null && keys.all { k -> before.keys.any { it.contentEquals(k) } }
        if (now >= dueAt(state)) {
            state.applied = keys
            if (!state.pendingRelist) state.gains.addLast(now)
            state.pendingRelist = false
            state.listedAt = now
            return true
        }
        // Back inside its window: held like any other change, with nothing listed meanwhile.
        state.pending = keys
        held++
        return false
    }

    /** When the first held listing may apply, on the elapsed clock; null with none held. */
    fun nextDueAt(): Long? = contacts.values.filter { it.pending != null }.minOfOrNull { dueAt(it) }

    private fun apply(
        state: State,
        keys: List<ByteArray>,
        now: Long,
    ): Boolean {
        if (same(state.applied, keys)) {
            if (state.pending != null) coalesced++
            state.pending = null
            state.pendingRelist = false
            return false
        }
        // The listing held already, offered again: it stays held as it was (a relisting stays one).
        if (state.pending?.let { same(it, keys) } == true) return false
        val gains = keys.any { k -> state.applied.none { it.contentEquals(k) } }
        if (!gains) {
            // A pure drop: never delayed.
            state.applied = keys
            state.pending = null
            state.pendingRelist = false
            return true
        }
        state.pendingRelist = false
        if (now >= dueAt(state)) {
            state.applied = keys
            state.pending = null
            state.gains.addLast(now)
            state.listedAt = now
            return true
        }
        if (state.pending != null) coalesced++ else held++
        state.pending = keys
        // Its drop goes at once: what stays listed is what both name.
        val kept = state.applied.filter { k -> keys.any { it.contentEquals(k) } }
        if (kept.size == state.applied.size) return false
        state.applied = kept
        return true
    }

    /** When [state]'s next listing may go: the interval from its last one, and for a gain the daily cap too. */
    private fun dueAt(state: State): Long {
        while (state.gains.size > dailyCap) state.gains.removeFirst()
        val last = maxOf(state.listedAt ?: Long.MIN_VALUE, state.gains.lastOrNull() ?: Long.MIN_VALUE)
        val interval = if (last == Long.MIN_VALUE) Long.MIN_VALUE else last + minIntervalMs
        if (state.pendingRelist) return interval
        val daily = if (state.gains.size >= dailyCap) state.gains.first() + dayMs else Long.MIN_VALUE
        return maxOf(interval, daily)
    }

    private fun applied(): Map<String, List<ByteArray>> = contacts.filterValues { it.applied.isNotEmpty() }.mapValues { it.value.applied }

    private fun same(
        a: List<ByteArray>,
        b: List<ByteArray>,
    ): Boolean = a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val DAILY_CAP = 24
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
