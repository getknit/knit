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
 * ([CodedPhyPolicy.effectiveRssi]), so every consumer's −90 floor keeps meaning what it meant. A PHY's reading
 * counts only while that PHY was heard in the same burst as the peer's latest sighting.
 *
 * Pure of Android and driven by an injected clock (all methods take `now`), so it is JVM-unit-testable with a
 * virtual clock ([app.getknit.knit.BlePresenceTrackerTest]) exactly like the other pure mesh components.
 */
class BlePresenceTracker(
    private val config: PresenceConfig = PresenceConfig(),
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
         * The smoothed RSSI on the 1M scale, counting a PHY only if it was heard in the latest burst. The latest
         * sighting's own PHY always is, so this is never empty once [note] has run.
         */
        fun smoothed(gapMs: Long): Double {
            fun fresh(at: Long?) = at != null && lastSeenAt - at <= gapMs
            return CodedPhyPolicy.effectiveRssi(rssi1m.takeIf { fresh(last1mAt) }, rssiCoded.takeIf { fresh(lastCodedAt) })
                ?: Double.NEGATIVE_INFINITY
        }

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
        // A new peer, or one back after a gap longer than presenceGapResetMs, restarts the dwell clock and
        // reseeds the RSSI (it walked away and back — don't average across the gap).
        if (existing == null || now - existing.lastSeenAt > config.presenceGapResetMs) {
            entries[s.nodeId] =
                Entry(
                    protoVersion = s.protoVersion,
                    capabilities = s.capabilities,
                    psm = s.psm,
                    digestCue = s.digestCue,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ).also { it.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, config.presenceGapResetMs) }
            return
        }
        existing.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, config.presenceGapResetMs)
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
                smoothedRssi = e.smoothed(config.presenceGapResetMs),
                dwellMs = now - e.firstSeenAt,
                lastSeenAgoMs = now - e.lastSeenAt,
                oneMSeenAgoMs = e.last1mAt?.let { now - it },
                codedSeenAgoMs = e.lastCodedAt?.let { now - it },
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
    fun smoothedRssiFor(nodeId: String): Double? = entries[nodeId]?.smoothed(config.presenceGapResetMs)

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
    /** How long a peer lingers in `reachable`/snapshots after its last sighting before being pruned. */
    val reachableLingerMs: Long = 90_000,
)
