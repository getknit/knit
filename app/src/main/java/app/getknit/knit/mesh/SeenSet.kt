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
    private val ttlMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxSize
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
}
