package app.getknit.knit.mesh.bluetooth

/**
 * The pure "which nearby peers should we hold a persistent Bluetooth link to?" decision — the BLE analogue of
 * Wi-Fi Aware's `DigestTracker`/sync-wanted logic, but promotion is driven by **dwell + RSSI** (proximity)
 * rather than a data-sync cue, because BLE links are cheap and persistent (no one-NDP cost). Bounded by a
 * connection budget with weakest-first, hysteretic eviction so a dense crowd of transient passers-by can't
 * thrash the link set.
 *
 * Fully pure (the caller supplies all clock-derived durations, like `PowerPolicy`), so it is JVM-unit-testable
 * ([app.getknit.knit.PromotionPolicyTest]). The caller pre-filters [candidates] to peers it should initiate to
 * (the nodeId tie-break `local > peer`, and not already linked).
 */
object PromotionPolicy {
    /** A currently-held link's state, for the eviction decision. */
    data class LinkSnapshot(
        val nodeId: String,
        val smoothedRssi: Double,
        val ageMs: Long,
        val idleMs: Long,
    )

    /** Node ids to open a link to, and node ids to tear a link down for, this tick. */
    data class Decision(
        val promote: List<String>,
        val evict: List<String>,
    )

    fun decide(
        candidates: List<BlePresenceTracker.Snapshot>,
        links: List<LinkSnapshot>,
        backoff: Set<String>,
        config: PromotionConfig = PromotionConfig(),
    ): Decision {
        // Promotable = close enough (RSSI floor), stuck around (dwell), and not backing off — strongest first.
        val promotable =
            candidates
                .filter { it.smoothedRssi >= config.rssiFloorDbm && it.dwellMs >= config.dwellThresholdMs && it.nodeId !in backoff }
                .sortedByDescending { it.smoothedRssi }

        // Links we're allowed to drop (held at least linkMinHoldMs — time hysteresis against thrash), weakest first.
        val evictable = links.filter { it.ageMs >= config.linkMinHoldMs }.sortedBy { it.smoothedRssi }
        val evict = mutableListOf<String>()

        // Shed any strict overflow first (e.g. after accepting more inbound links than the budget), weakest go.
        val overflow = (links.size - config.maxLinks).coerceAtLeast(0)
        evict += evictable.take(overflow).map { it.nodeId }

        val promote = mutableListOf<String>()
        val free = config.maxLinks - (links.size - evict.size)
        if (free > 0) {
            promote += promotable.take(free).map { it.nodeId }
        } else {
            // At budget: replace the weakest still-evictable link only if the best candidate clearly beats it
            // (RSSI hysteresis), so normal RSSI jitter can't ping-pong a link.
            val weakest = evictable.drop(overflow).firstOrNull()
            val best = promotable.firstOrNull()
            if (weakest != null && best != null && best.smoothedRssi >= weakest.smoothedRssi + config.rssiHysteresisDb) {
                promote += best.nodeId
                evict += weakest.nodeId
            }
        }
        return Decision(promote, evict)
    }
}

/**
 * Tunables for [PromotionPolicy]. Defaults sized for an always-on venue mesh; all field-tunable. BLE links are
 * cheap, so [dwellThresholdMs] is seconds, not the ~90s a costly NAN NDP would justify.
 */
data class PromotionConfig(
    /** Continuous presence before promoting — filters out passers-by. */
    val dwellThresholdMs: Long = 12_000,
    /** Smoothed-RSSI floor (edge of usable BLE range) in dBm; below this a peer is never promoted. BLE
     *  reaches further than NAN's NDP, so this is set generously — it excludes only genuinely poor signals
     *  rather than gating to same-room proximity. */
    val rssiFloorDbm: Int = DEFAULT_RSSI_FLOOR_DBM,
    /** Connection budget: max simultaneous persistent Bluetooth links. */
    val maxLinks: Int = 6,
    /** A candidate must beat the weakest link by this many dB to evict it (RSSI jitter is ±5). */
    val rssiHysteresisDb: Int = 8,
    /** Never evict a link younger than this — time hysteresis so a fresh link isn't dropped immediately. */
    val linkMinHoldMs: Long = 20_000,
) {
    private companion object {
        // A negative default can't be inlined without tripping MagicNumber, so it lives here as a named const.
        // -90 keeps a small margin above typical BLE 1M-PHY sensitivity (~-90..-95 dBm): broaden reach to the
        // edge of usable range without churning connect-backoff on doomed sub-sensitivity attempts.
        const val DEFAULT_RSSI_FLOOR_DBM = -90
    }
}
