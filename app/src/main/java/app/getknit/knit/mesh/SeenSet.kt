package app.getknit.knit.mesh

/**
 * Bounded, time-windowed set of frame ids already processed, used to terminate the mesh flood.
 *
 * Improves on the legacy app's seen-set, which was unbounded and never expired: this caps the
 * number of retained ids (LRU eviction) and treats an id as new again once its [ttlMillis] window
 * has elapsed. [clock] is injectable for deterministic tests.
 */
class SeenSet(
    private val maxSize: Int = 4096,
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val seen =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxSize
        }

    /** Records [id] and returns true if it was not seen within the TTL window (i.e. it is new). */
    @Synchronized
    fun add(id: String): Boolean {
        val now = clock()
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
     * Lets **one** more copy of [id] through inside its window, for a frame this node took and could not
     * use: a sealed DM addressed to us whose session we no longer hold. The sender's answer to our reset
     * is a fresh seal under the *same* id — by design, so every other node dedups it — and it lands
     * seconds after the copy custody just served us, well inside the window. Once per id per window:
     * a second reopen is refused, so a peer re-serving an unopenable frame can buy at most one extra
     * relay of it from us. Returns whether the id was reopened.
     */
    @Synchronized
    fun reopen(id: String): Boolean {
        val now = clock()
        val last = seen[id] ?: return false
        if (now - last >= ttlMillis) return false
        val reopenedAt = reopened[id]
        if (reopenedAt != null && now - reopenedAt < ttlMillis) return false
        seen.remove(id)
        reopened[id] = now
        return true
    }

    private val reopened =
        object : LinkedHashMap<String, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > REOPENED_MAX
        }

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

        /** Ids reopened once are remembered so they cannot be reopened twice; a small LRU, like [seen]. */
        private const val REOPENED_MAX = 256
    }
}
