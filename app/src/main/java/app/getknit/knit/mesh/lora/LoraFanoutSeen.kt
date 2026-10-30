package app.getknit.knit.mesh.lora

/**
 * The fan-out's dedup: one airing of an own room post for as long as that post may air at all (#239, ADR 2026-10.rp3s's
 * 2026-10-29 amendment). Each key — the frame's signature prefix, `LoraMeshTransport.dedupKey` — is held until the
 * frame's own freshness deadline (`RideGate.freshUntil`, `sentAt + LoraFramePolicy.FRESH_MS`) on the wall clock.
 *
 * It replaced a ten-minute `SeenSet` on the elapsed clock. The router's seen window lapses at ten minutes too, while a
 * room post stays fresh enough to ride for fifteen, so an own post re-served to us in that gap — a neighbour's custody,
 * a spool pull, re-fanned by `InboundPipeline.onDeliver` — found both memories empty and aired a second time. Keyed on
 * the deadline [LoraFramePolicy.isFresh] and the send-time re-ask (`StaleAtSend.STALE`) read, on the clock they read, the
 * dedup ends exactly when the frame could no longer fly, so no gap opens between them: not at a TTL, and not when the wall
 * clock is set back or forward (both read the same step).
 *
 * Bounded twice: every [add] drops the entries already past their deadline, and at most [maxSize] are held, the oldest
 * recorded dropped first — a backstop, since an own post that is fresh is one the user typed in the last fifteen minutes.
 * [reopen] hands a slot back whole, every time: a frame that never flew records no airing, so it must not hold off the
 * next fan-out of the same bytes. Pure and `@Synchronized`: the fan-out and the pacer's `abandon` call it from different
 * coroutines.
 */
internal class LoraFanoutSeen(
    private val maxSize: Int = MAX_ENTRIES,
) {
    private val until = LinkedHashMap<String, Long>()

    /**
     * Records [key] as aired until [freshUntil] (wall clock) and says whether it is new: false while an entry for it
     * stands at [now], which is the fan-out's "already on its way".
     */
    @Synchronized
    fun add(
        key: String,
        freshUntil: Long,
        now: Long,
    ): Boolean {
        prune(now)
        if (key in until) return false
        until[key] = freshUntil
        while (until.size > maxSize) until.remove(until.keys.first())
        return true
    }

    /** Whether [key] is held at [now]. */
    @Synchronized
    fun contains(
        key: String,
        now: Long,
    ): Boolean = until[key]?.let { now <= it } == true

    /** Forgets [key]: the frame it recorded never flew. */
    @Synchronized
    fun reopen(key: String) {
        until.remove(key)
    }

    /** How many entries are held, expired ones included until the next [add] prunes them. For the JVM tests. */
    @Synchronized
    fun size(): Int = until.size

    private fun prune(now: Long) {
        until.entries.removeAll { now > it.value }
    }

    companion object {
        /** The backstop on entries held at once. */
        const val MAX_ENTRIES = 256
    }
}
