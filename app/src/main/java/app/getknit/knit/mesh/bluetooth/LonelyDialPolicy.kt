package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.power.PowerPolicy

/**
 * The one exception to "the larger node id dials" (ADR 2026-09.hj4a, #103): a node that has held **no** Bluetooth
 * link for [PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS] may dial a sighted peer whose id sorts *above* its own.
 *
 * The ordinary rule leaves a smaller-id newcomer waiting on its larger neighbours' scans, and a settled,
 * screen-off clique scans at its floor — minutes apart, longer with every link it holds. The responder admits
 * the lonely dial because it has not sighted the newcomer ([BleAdmissionPolicy], ADR 2026-09.shzv: an unsighted
 * dialer is admitted whatever the order). If it has, it refuses, and its own ordinary dial is on the way; the
 * refusal is an ordinary failed dial with the ordinary backoff.
 *
 * Pure, like [PromotionPolicy]: the caller supplies the durations and pre-filters [Candidate]s to peers it could
 * dial at all (device and PSM known, not linked). The candidate gates are [PromotionPolicy]'s own — RSSI floor,
 * dwell, backoff — and at most one lonely dial is in flight at a time.
 */
internal object LonelyDialPolicy {
    /** A sighted peer, as [BluetoothMeshTransport] sees it this tick: [form] is what it advertised (ADR 2026-11.xp6d). */
    data class Candidate(
        val nodeId: String,
        val smoothedRssi: Double,
        val dwellMs: Long,
        val backedOff: Boolean,
        val form: AdvertOrder.Form = AdvertOrder.Form.clear(nodeId),
    )

    /**
     * The peer to dial now, or null: one the ordinary order leaves to dial me ([AdvertOrder.lonelyMayDial] on [mine];
     * between two clear phones a larger id, ADR 2026-11.xp6d). [aloneForMs] is how long the node has held no link — since the
     * transport started or the last link went down, whichever is later — and is ignored unless [linkCount] is 0.
     * [lonelyDialInFlight] is whether a lonely dial is still open (its kind recorded when it started).
     */
    fun pick(
        mine: AdvertOrder.Mine,
        linkCount: Int,
        aloneForMs: Long,
        candidates: List<Candidate>,
        lonelyDialInFlight: Boolean,
        config: PromotionConfig = PromotionConfig(),
    ): Candidate? {
        if (!open(linkCount, aloneForMs, lonelyDialInFlight)) return null
        return eligible(mine, candidates, config)
            .filter { it.dwellMs >= config.dwellThresholdMs }
            .maxByOrNull { it.smoothedRssi }
    }

    /**
     * How long until [pick] could name a peer through the clock alone — the window closing, or an eligible
     * candidate's dwell reaching the threshold — or null when only an event (a sighting, a backoff lapsing, a
     * link going down) could change it. The connection loop sleeps no longer than this, so the dial is not left
     * to the next sighting: on a screen-off phone that sighting restarts the dwell clock, and it would never land.
     */
    fun msUntilDue(
        mine: AdvertOrder.Mine,
        linkCount: Int,
        aloneForMs: Long,
        candidates: List<Candidate>,
        lonelyDialInFlight: Boolean,
        config: PromotionConfig = PromotionConfig(),
    ): Long? {
        if (linkCount > 0 || lonelyDialInFlight) return null
        val dwellWait = eligible(mine, candidates, config).minOfOrNull { (config.dwellThresholdMs - it.dwellMs).coerceAtLeast(0) }
        return dwellWait?.let { maxOf(it, PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS - aloneForMs, 0L) }
    }

    private fun open(
        linkCount: Int,
        aloneForMs: Long,
        lonelyDialInFlight: Boolean,
    ): Boolean = linkCount == 0 && !lonelyDialInFlight && aloneForMs >= PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS

    private fun eligible(
        mine: AdvertOrder.Mine,
        candidates: List<Candidate>,
        config: PromotionConfig,
    ): List<Candidate> =
        candidates.filter { AdvertOrder.lonelyMayDial(mine.current, it.form) && !it.backedOff && it.smoothedRssi >= config.rssiFloorDbm }
}
