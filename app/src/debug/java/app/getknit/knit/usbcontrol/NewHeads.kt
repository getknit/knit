package app.getknit.knit.usbcontrol

/**
 * Turns successive snapshots of "the newest message in every thread" into the messages that are new since a session
 * subscribed, which is the spike's event feed: Room re-emits that snapshot on every write to the messages table.
 *
 * - The first snapshot is the baseline and yields nothing.
 * - A head yields once, the first time its id is seen, and only if its [stampOf] (arrival on this phone, else its
 *   send time) is at or after [since]: a thread whose newest row was deleted falls back to an older head, which is
 *   not news.
 * - The ids seen are bounded at [memory], oldest forgotten first.
 *
 * Known gap: a message whose send time is older than its thread's newest never becomes a head, so it is never
 * yielded. The real feature reads arrivals directly instead.
 */
internal class NewHeads<T>(
    private val since: Long,
    private val idOf: (T) -> String,
    private val stampOf: (T) -> Long,
    private val memory: Int = DEFAULT_MEMORY,
) {
    private val seen = LinkedHashSet<String>()
    private var baseline = true

    fun next(heads: Collection<T>): List<T> {
        val fresh = heads.filter { idOf(it) !in seen }
        fresh.forEach { remember(idOf(it)) }
        if (baseline) {
            baseline = false
            return emptyList()
        }
        return fresh.filter { stampOf(it) >= since }.sortedBy(stampOf)
    }

    private fun remember(id: String) {
        seen += id
        if (seen.size > memory) seen.remove(seen.first())
    }

    private companion object {
        const val DEFAULT_MEMORY = 4096
    }
}
