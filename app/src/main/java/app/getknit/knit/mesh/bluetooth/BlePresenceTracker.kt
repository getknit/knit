package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.Peer

/**
 * The BLE presence model: per-peer smoothed RSSI, continuous-presence dwell, and last-seen linger — fed by
 * scan sightings and read by [PromotionPolicy] (which decides who to link) and the transport (which exposes
 * `reachable` to the UI). Identity is the advertised **nodeId**, never the MAC, so a rotated BLE
 * resolvable-random-address simply lands as a fresh sighting under the same nodeId.
 *
 * A peer heard on both PHYs (the Coded PHY experiment, ADR 2026-10.yvn6) keeps one smoothed RSSI per PHY — the two
 * read on different scales — and its [Snapshot.smoothedRssi] is the stronger of them on the 1M scale
 * ([CodedPhyPolicy.effectiveRssi], at the credit [codedCreditDb] names), so every consumer's −90 floor keeps meaning
 * what it meant. A PHY's reading counts only while that PHY was heard in the same burst as the peer's latest sighting.
 * Coded hits are far sparser than 1M ones (a 1 s advert, a scan that shares its window), so a Coded sighting that
 * follows a Coded sighting is continuous presence across [PresenceConfig.codedGapResetMs], not the 1M gap.
 *
 * Pure of Android and driven by an injected clock (all methods take `now`), so it is JVM-unit-testable with a
 * virtual clock ([app.getknit.knit.BlePresenceTrackerTest]) exactly like the other pure mesh components.
 */
class BlePresenceTracker(
    private val config: PresenceConfig = PresenceConfig(),
    // Read at every score, so `…debug.PHY --ei credit` moves it live (ADR 2026-10.yvn6).
    private val codedCreditDb: () -> Double = { CodedPhyPolicy.CODED_RSSI_CREDIT_DB },
) {
    /** One scan hit for a peer: RSSI plus the fields decoded from its [BleAdvertPayload]. */
    data class Sighting(
        val nodeId: String,
        val rssiDbm: Int,
        val protoVersion: Int,
        val capabilities: Long,
        val psm: Int,
        val digestCue: Int,
        /** Heard on the Coded PHY (`ScanResult.primaryPhy`), not 1M. */
        val coded: Boolean = false,
    )

    /** A peer's current presence: smoothed RSSI, continuous dwell, and staleness — the promotion inputs. */
    data class Snapshot(
        val nodeId: String,
        val protoVersion: Int,
        val capabilities: Long,
        val psm: Int,
        val digestCue: Int,
        val smoothedRssi: Double,
        val dwellMs: Long,
        val lastSeenAgoMs: Long,
        /** Since the last 1M / Coded sighting, null when that PHY has not been heard since the peer (re)appeared. */
        val oneMSeenAgoMs: Long? = lastSeenAgoMs,
        val codedSeenAgoMs: Long? = null,
        /** Each PHY's own smoothed advert RSSI, on its own scale (no credit) — for the trial's readouts. */
        val rssi1m: Double? = null,
        val rssiCoded: Double? = null,
    )

    private class Entry(
        var protoVersion: Int,
        var capabilities: Long,
        var psm: Int,
        var digestCue: Int,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var rssi1m: Double? = null,
        var last1mAt: Long? = null,
        var rssiCoded: Double? = null,
        var lastCodedAt: Long? = null,
    ) {
        /**
         * The smoothed RSSI on the 1M scale, counting a PHY only if it was heard in the latest burst (within that
         * PHY's own gap of the latest sighting). The latest sighting's own PHY always is, so this is never empty once
         * [note] has run.
         */
        fun smoothed(
            config: PresenceConfig,
            creditDb: Double,
        ): Double {
            fun fresh(
                at: Long?,
                gapMs: Long,
            ) = at != null && lastSeenAt - at <= gapMs
            return CodedPhyPolicy.effectiveRssi(
                rssi1m.takeIf { fresh(last1mAt, config.presenceGapResetMs) },
                rssiCoded.takeIf { fresh(lastCodedAt, config.codedGapResetMs) },
                creditDb,
            ) ?: Double.NEGATIVE_INFINITY
        }

        /** The gap past which a sighting on [coded]'s PHY starts presence over: wider only for Coded after Coded. */
        fun gapFor(
            coded: Boolean,
            config: PresenceConfig,
        ): Long = if (coded && lastCodedAt == lastSeenAt) config.codedGapResetMs else config.presenceGapResetMs

        fun note(
            rssi: Int,
            coded: Boolean,
            now: Long,
            alpha: Double,
            gapMs: Long,
        ) {
            val last = if (coded) lastCodedAt else last1mAt
            val held = if (coded) rssiCoded else rssi1m
            val next = if (held == null || last == null || now - last > gapMs) rssi.toDouble() else alpha * rssi + (1 - alpha) * held
            if (coded) {
                rssiCoded = next
                lastCodedAt = now
            } else {
                rssi1m = next
                last1mAt = now
            }
            lastSeenAt = now
        }
    }

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun onSighting(
        s: Sighting,
        now: Long,
    ) {
        val existing = entries[s.nodeId]
        val phyGapMs = if (s.coded) config.codedGapResetMs else config.presenceGapResetMs
        // A new peer, or one back after a gap longer than presenceGapResetMs (codedGapResetMs for Coded after Coded),
        // restarts the dwell clock and reseeds the RSSI (it walked away and back — don't average across the gap).
        if (existing == null || now - existing.lastSeenAt > existing.gapFor(s.coded, config)) {
            entries[s.nodeId] =
                Entry(
                    protoVersion = s.protoVersion,
                    capabilities = s.capabilities,
                    psm = s.psm,
                    digestCue = s.digestCue,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ).also { it.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, phyGapMs) }
            return
        }
        existing.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, phyGapMs)
        existing.protoVersion = s.protoVersion
        existing.capabilities = s.capabilities
        existing.psm = s.psm
        existing.digestCue = s.digestCue
    }

    /** Presence snapshots for every peer still within the reachable linger, oldest sightings pruned first. */
    @Synchronized
    fun snapshots(now: Long): List<Snapshot> {
        entries.entries.removeAll { now - it.value.lastSeenAt > config.reachableLingerMs }
        return entries.map { (nodeId, e) ->
            Snapshot(
                nodeId = nodeId,
                protoVersion = e.protoVersion,
                capabilities = e.capabilities,
                psm = e.psm,
                digestCue = e.digestCue,
                smoothedRssi = e.smoothed(config, codedCreditDb()),
                dwellMs = now - e.firstSeenAt,
                lastSeenAgoMs = now - e.lastSeenAt,
                oneMSeenAgoMs = e.last1mAt?.let { now - it },
                codedSeenAgoMs = e.lastCodedAt?.let { now - it },
                rssi1m = e.rssi1m,
                rssiCoded = e.rssiCoded,
            )
        }
    }

    /** The smoothed "who's nearby" set for the UI (peers seen within the linger window). */
    @Synchronized
    fun reachable(now: Long): Set<Peer> = snapshots(now).map { Peer(it.nodeId, it.protoVersion, it.capabilities) }.toSet()

    /** The L2CAP PSM last advertised by [nodeId], for an initiator opening a channel — or null if unknown. */
    @Synchronized
    fun psmFor(nodeId: String): Int? = entries[nodeId]?.psm

    /** [nodeId]'s current smoothed RSSI, or null if unseen — a cheap lookup for the scan-demand boost gate. */
    @Synchronized
    fun smoothedRssiFor(nodeId: String): Double? = entries[nodeId]?.smoothed(config, codedCreditDb())

    @Synchronized
    fun forget(nodeId: String) {
        entries.remove(nodeId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/**
 * Tunables for [BlePresenceTracker]. Defaults chosen for an always-on background mesh; all field-tunable.
 */
data class PresenceConfig(
    /** EWMA weight on each new RSSI sample: higher = more responsive, lower = smoother. */
    val rssiEwmaAlpha: Double = 0.35,
    /** A gap since last sighting longer than this resets the dwell clock and reseeds RSSI (peer left and returned). */
    val presenceGapResetMs: Long = 8_000,
    /**
     * The same, for a Coded sighting after a Coded sighting (the Coded PHY experiment, ADR 2026-10.yvn6): a far peer's
     * Coded hits arrive seconds apart, and an 8 s silence would restart its dwell before it ever reached the threshold.
     */
    val codedGapResetMs: Long = 30_000,
    /** How long a peer lingers in `reachable`/snapshots after its last sighting before being pruned. */
    val reachableLingerMs: Long = 90_000,
)
