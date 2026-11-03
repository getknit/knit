package app.getknit.knit.mesh.bluetooth

/**
 * The bounds on what cold far sightings cost (ADR 2026-10.yvn6, the 2026-10-20 amendment): [CodedPhyPolicy.coldHunt]
 * names an action, and [tryStart] — the last gate, so nothing is spent on an action not taken — grants it a hold or
 * refuses it. Three bounds, all of them needed, since a parked neighbour heard through a wall is cold for as long as it
 * stays there and every phone in a crowd may be:
 *
 * - **Per peer:** at most one cold action (a hunt or a fast window) per peer per cooldown, the peers kept in a
 *   [maxPeers]-entry LRU like `BlePresenceTracker`'s 1M marks.
 * - **One at a time:** at most one cold hunt and one cold fast window running, whoever they are for.
 * - **Phone-wide budget:** at most the hold (`PhyTuning.fastHoldMs`) of each kind per cooldown across all peers. A
 *   spell that ended early ([end]: the peer linked, backed off or left presence) spends only the time it ran, and a
 *   grant is the budget left, never less than [MIN_GRANT_MS].
 *
 * Pure and thread-safe: the scan loop starts and cancels spells, the transport's link edges end them.
 */
internal class ColdSightings(
    maxPeers: Int = MAX_PEERS,
) {
    private class Spell(
        val peer: String,
        val kind: ColdAction,
        val start: Long,
        var end: Long,
    )

    // When each peer's last cold action began, least recently used out first.
    private val lastStart =
        object : LinkedHashMap<String, Long>(CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxPeers
        }

    private val spells = ArrayList<Spell>()

    /**
     * A [kind] cold action for [peer] at [now]: the hold granted, up to [holdMs], or null when a bound refuses it. The
     * budget is [holdMs] per [cooldownMs], the same window as the per-peer cooldown.
     */
    @Synchronized
    fun tryStart(
        peer: String,
        kind: ColdAction,
        now: Long,
        holdMs: Long,
        cooldownMs: Long,
    ): Long? {
        if (kind == ColdAction.NONE) return null
        val since = now - cooldownMs
        spells.removeAll { it.end <= since }
        val last = lastStart[peer]
        if (last != null && now - last < cooldownMs) return null
        val ofKind = spells.filter { it.kind == kind }
        if (ofKind.any { it.end > now }) return null
        val spent = ofKind.sumOf { minOf(it.end, now) - maxOf(it.start, since) }
        val grant = holdMs - spent
        if (grant < minOf(MIN_GRANT_MS, holdMs)) return null
        lastStart[peer] = now
        spells += Spell(peer, kind, now, now + grant)
        return grant
    }

    /** [peer]'s running cold spells end at [now]: it linked, backed off or left presence. Its cooldown stands. */
    @Synchronized
    fun end(
        peer: String,
        now: Long,
    ) {
        spells.filter { it.peer == peer && it.end > now }.forEach { it.end = now }
    }

    /** Forgets every spell and cooldown: the transport stopped. */
    @Synchronized
    fun clear() {
        spells.clear()
        lastStart.clear()
    }

    companion object {
        /**
         * The least hold worth granting: one hunting window and its gap (12 s each at BALANCED, `PowerPolicy.HUNT_IDLE_MS`).
         * A scrap of budget shorter than that buys no window a dial could ripen in, and would still spend a cooldown.
         */
        const val MIN_GRANT_MS = 24_000L

        /**
         * Why a running cold hunt ends at this scan window, or null while it runs on: its peer [linked] (a link-up
         * can land between the scan loop's read of the links and the hunt's start), is [backingOff], is no longer
         * [present], or the switch or the low-power posture is now [off]. The first reason that holds, for the log line.
         */
        fun endReason(
            linked: Boolean,
            backingOff: Boolean,
            present: Boolean,
            off: Boolean,
        ): String? =
            when {
                linked -> "linked"
                backingOff -> "backoff"
                !present -> "gone"
                off -> "off"
                else -> null
            }

        private const val MAX_PEERS = 64
        private const val CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
