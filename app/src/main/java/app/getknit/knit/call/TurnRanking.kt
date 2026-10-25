package app.getknit.knit.call

/**
 * The phone's ranking of the TURN servers its spools name (docs/SPOOL_PROTOCOL.md §7.6, C-7.6-51 to C-7.6-54): which
 * entry a `turnreq` names, which one credential a call hands libwebrtc, and which server a refused one gives way to.
 *
 * It holds one score per entry for the network the phone is on — the least accepted round trip [TurnMeasure] found, or
 * none when it was measured and never answered — and drops them all on a network change. A refusal (486 or 508) puts
 * an entry last for [REFUSED_MS], across network changes. Nothing here is stored, and nothing is kept per network name.
 *
 * Every order starts from a base order (one spool's list, or the call's spools in asking order) and reads:
 * - entries within [TIE_MS] of the best score, in base order: a stable choice, and the operator's preference;
 * - the other answered entries, by ascending score;
 * - entries never answered or not yet measured, in base order — with no score at all, the base order stands;
 * - refused entries, in base order.
 *
 * [pinnedSlot] is the debug bridge's pin (`…debug.CALL --es op turnpin`, in memory): the server at that place in a
 * spool's list goes first whatever its score or refusal, so a lab trial can make the call try a refusing box first.
 */
class TurnRanking(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // Entry → least accepted round trip in ms on this network, or [SILENT] when it was measured and never answered.
    private val scores = HashMap<String, Long>()

    // Entry → when its refusal mark lapses, on [clock].
    private val refusedUntil = HashMap<String, Long>()

    /** A 1-based slot every ranking puts first; null leaves the ranking to the scores. Set by the debug bridge only. */
    @Volatile
    var pinnedSlot: Int? = null

    /** [entry]'s measurement on this network: its least round trip, or null when it never answered. */
    @Synchronized
    fun record(
        entry: String,
        roundTripMs: Long?,
    ) {
        scores[entry] = roundTripMs?.coerceAtLeast(0L) ?: SILENT
    }

    /** Whether [entry] was measured on this network, answered or not. */
    @Synchronized
    fun measured(entry: String): Boolean = entry in scores

    /** [entry]'s score on this network, or null when it has none (never answered, or not measured). */
    @Synchronized
    fun score(entry: String): Long? = scores[entry]?.takeIf { it != SILENT }

    /** The default network changed: every score was for the last one. A refusal mark keeps its time. */
    @Synchronized
    fun networkChanged() = scores.clear()

    /** An allocation on [entry] was refused with 486 or 508 (C-7.6-54): last for [REFUSED_MS]. */
    @Synchronized
    fun refuse(entry: String) {
        refusedUntil[entry] = clock() + REFUSED_MS
    }

    /** [entries], one spool's `turnHosts` in its order, best first (C-7.6-51). */
    @Synchronized
    fun rank(entries: List<String>): List<String> {
        val ordered = order(entries.distinct())
        val pinned = pinnedSlot?.let { entries.getOrNull(it - 1) } ?: return ordered
        return listOf(pinned) + (ordered - pinned)
    }

    /** The entry a `turnreq` to the spool listing [entries] names: the best of them, or null when there are none. */
    fun best(entries: List<String>): String? = rank(entries).firstOrNull()

    /**
     * C-7.6-53: of [servers]' credentials from spools that name their servers, the one whose server ranks best — the
     * lowest score, within [TIE_MS] of it the one from the spool asked first, a measured server before an unmeasured
     * one — or null when none of them is such a credential.
     */
    @Synchronized
    fun choose(servers: List<TurnServer>): TurnServer? {
        val ranked = servers.filter { it.ranked }.sortedBy { it.asked }
        if (ranked.isEmpty()) return null
        pinnedSlot?.let { slot -> ranked.firstOrNull { it.slot == slot }?.let { return it } }
        val best = order(ranked.map(::keyOf).distinct()).first()
        return ranked.first { keyOf(it) == best }
    }

    /**
     * C-7.6-54's next server after [refused]: the best entry other than it over every list [servers]' spools named,
     * in asking order, or null when they named no other.
     */
    @Synchronized
    fun next(
        servers: List<TurnServer>,
        refused: String,
    ): String? {
        val named =
            servers
                .filter { it.ranked }
                .sortedBy { it.asked }
                .flatMap { it.spoolHosts }
                .distinct()
        return order(named).firstOrNull { it != refused }
    }

    /** How many entries [servers]' spools named, without repeats: the field log's `ranked`. */
    fun named(servers: List<TurnServer>): Int =
        servers
            .filter { it.ranked }
            .flatMap { it.spoolHosts }
            .distinct()
            .size

    // A ranked credential with no UDP URL (which a conforming spool never sends) has no entry to score; it ranks as an
    // unmeasured one under a key of its own.
    private fun keyOf(server: TurnServer): String = server.entry ?: "#${server.asked}"

    private fun order(base: List<String>): List<String> {
        val now = clock()
        refusedUntil.values.removeAll { it <= now }
        val refused = base.filter { it in refusedUntil }
        val live = base.filterNot { it in refusedUntil }
        val answered = live.filter { score(it) != null }
        val silent = live.filterNot { score(it) != null }
        if (answered.isEmpty()) return silent + refused
        val best = answered.minOf { scores.getValue(it) }
        val near = answered.filter { scores.getValue(it) <= best + TIE_MS }
        val far = answered.filterNot { it in near }.sortedBy { scores.getValue(it) }
        return near + far + silent + refused
    }

    companion object {
        /** Scores this close to the best keep their base order (C-7.6-51, C-7.6-53). */
        const val TIE_MS = 10L

        /** How long a refused server stays last (C-7.6-51, C-7.6-54). */
        const val REFUSED_MS = 5 * 60_000L

        private const val SILENT = -1L
    }
}
