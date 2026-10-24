package app.getknit.knit.mesh.wifiaware

/**
 * The peers a live call holds a Wi-Fi Aware link to (`MeshTransport.holdLink`, docs/VOICE_CALLS.md §3.11): while one is
 * held, its link is not torn down for being idle ([NanLinkLife]). A sliding TTL renewed by the call's next ask, so a
 * hold lapses on its own once the call ends or its process dies; capped, since a phone is in one call at a time. Pure,
 * so `NanLinkHoldTest` pins it; `@Synchronized` for the callers' threads against the link supervisors'.
 */
internal class NanLinkHolds(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val until = HashMap<String, Long>()

    /** Holds [nodeId]'s link for [ttlMs] from now. */
    @Synchronized
    fun note(nodeId: String) {
        val now = clock()
        until.values.removeAll { it <= now }
        if (nodeId !in until && until.size >= maxEntries) until.entries.minByOrNull { it.value }?.let { until.remove(it.key) }
        until[nodeId] = now + ttlMs
    }

    /** Whether [nodeId]'s link is held now. */
    @Synchronized
    fun isHeld(nodeId: String): Boolean = (until[nodeId] ?: return false) > clock()

    /** Whether any link is held now: a call wants its link, so the plane does not go dormant ([NanDormancyPolicy]). */
    @Synchronized
    fun anyHeld(): Boolean {
        val now = clock()
        return until.values.any { it > now }
    }

    @Synchronized
    fun clearAll() = until.clear()

    companion object {
        /** Past two of a walkie call's 10 s asks, so one late ask never lets its link go. */
        const val DEFAULT_TTL_MS = 25_000L
        const val DEFAULT_MAX_ENTRIES = 2
    }
}

/** When a Wi-Fi Aware link's sync is over and it comes down (`WifiAwareTransport.superviseLink`). Pure. */
internal object NanLinkLife {
    /**
     * Whether a link [idleMs] quiet and [ageMs] old is done, on a side whose idle teardown is [quiescenceMs] and whose
     * absolute window is [windowMs]. A [held] link is never done for being idle — the call that holds it wants it between
     * its bursts — but its window still ends it: a responder has no other way to tell a dead initiator from a quiet one,
     * and the hold raises the link again at once.
     */
    fun done(
        idleMs: Long,
        ageMs: Long,
        quiescenceMs: Long,
        windowMs: Long,
        held: Boolean,
    ): Boolean = (!held && idleMs >= quiescenceMs) || ageMs >= windowMs
}
