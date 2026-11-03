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
 * 2. **unlisting is never delayed**: a contact leaving the listing, a key dropped and a new collision (which the store
 *    leaves out) apply at once, and a change that both drops and gains applies its drop at once and holds its gain.
 *
 * A contact that leaves the listing and comes back within [dayMs] is counted against what it gained before. So a
 * contact streaming generations costs the table at most three new keys' tags, 420 HMACs, per [minIntervalMs], whether
 * or not it drops out between them (the window's own slide, a slot's tags per listed key, is beside that). Pure, on the
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
        val gains = ArrayDeque<Long>()
    }

    private val contacts = HashMap<String, State>()

    // The gains of contacts that left the listing (a DirectContacts flap, every key colliding), kept a day: a re-entry
    // counts against them, so leaving and coming back never escapes the bound. At most one per contact that left.
    private val departed = HashMap<String, ArrayDeque<Long>>()

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
        departed.values.removeAll { gains -> gains.lastOrNull()?.let { now - it >= dayMs } != false }
        val leaving = contacts.keys - listing.keys
        leaving.forEach { id -> contacts.remove(id)?.let { departed[id] = it.gains } }
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
            if (pending != null && gainAllowed(state, now)) {
                state.pending = null
                state.applied = pending
                state.gains.addLast(now)
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
        departed.remove(id)?.let(state.gains::addAll)
        contacts[id] = state
        if (gainAllowed(state, now)) {
            state.applied = keys
            state.gains.addLast(now)
            return true
        }
        // Back inside its window: held like any other gain, with nothing listed meanwhile.
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
            return false
        }
        val gains = keys.any { k -> state.applied.none { it.contentEquals(k) } }
        if (!gains) {
            // A pure drop: never delayed.
            state.applied = keys
            state.pending = null
            return true
        }
        if (gainAllowed(state, now)) {
            state.applied = keys
            state.pending = null
            state.gains.addLast(now)
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

    private fun gainAllowed(
        state: State,
        now: Long,
    ): Boolean = now >= dueAt(state)

    private fun dueAt(state: State): Long {
        while (state.gains.size > dailyCap) state.gains.removeFirst()
        val interval = state.gains.lastOrNull()?.let { it + minIntervalMs } ?: Long.MIN_VALUE
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
