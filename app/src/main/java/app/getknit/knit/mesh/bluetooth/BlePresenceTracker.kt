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
 * Whether a peer is heard on Coded alone ([Snapshot.codedLagMs]) is measured on a clock that runs only while a scan
 * window listens on 1M ([onOneMListening]): a Coded-only window cannot hear a 1M advert, so its hits are no evidence
 * that the peer has none. The clock's reading at each peer's last 1M hit outlives its presence entry (a screen-off
 * scan is 8 s windows two minutes apart, so presence restarts every window). Only a 1M hit whose smoothed 1M reading,
 * that hit included, is at or above [oneMWeakDbm] moves the mark: a peer whose faint 1M advert still got through now and
 * then reset it on every hit, never became Coded-only, and was dialed on 1M at the edge while Coded heard it fine (ADR
 * 2026-10.yvn6, the 2026-10-20 amendment). A weaker hit is no evidence against Coded alone; a peer heard only so on 1M
 * is marked at its first hit, as one first heard on Coded is.
 *
 * Pure of Android and driven by an injected clock (all methods take `now`), so it is JVM-unit-testable with a
 * virtual clock ([app.getknit.knit.BlePresenceTrackerTest]) exactly like the other pure mesh components.
 */
class BlePresenceTracker(
    private val config: PresenceConfig = PresenceConfig(),
    // Read at every score, so `…debug.PHY --ei credit` moves it live (ADR 2026-10.yvn6).
    private val codedCreditDb: () -> Double = { CodedPhyPolicy.CODED_RSSI_CREDIT_DB },
    // Read at every 1M hit, so `…debug.PHY --ei oneMWeak` moves it live (the 2026-10-20 amendment).
    private val oneMWeakDbm: () -> Double = { PromotionConfig.DEFAULT_RSSI_FLOOR_DBM.toDouble() },
) {
    /**
     * What may suppress a dial to a sighted peer that holds a link here (ADR 2026-11.xp6d, obligation 11), read against
     * the links when the dial is decided ([DialPlan]), so a link that comes up after the sighting counts at once.
     */
    sealed interface Suppressor {
        /** Any link to the peer's id: a clear advert, the shipped rule. */
        data object Linked : Suppressor

        /** Only a proven link to the peer's id: a key's answer (nothing counts without the id's proof). */
        data object Proven : Suppressor

        /**
         * Only the claimant's own [link], while it is the one held under the peer's id: a claim's answer (ADR
         * 2026-11.xp6d, "a claim suppresses our dial only while its claimant's link is held"). A new link from the same
         * id is not it, so a liar who relinks cannot keep blocking the dial whose proof would void its lie.
         */
        data class Claim(
            val link: Any,
        ) : Suppressor {
            override fun toString(): String = "Claim"
        }

        /** Nothing: a hint's or an unresolved form's answer; the dial `iDial` calls for goes ahead. */
        data object None : Suppressor
    }

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
        /** The form it advertised ([Snapshot.advertised]); [nodeId] is the key it is attributed to. */
        val advertised: String = nodeId,
        val blinded: Boolean = false,
        /** What may suppress a dial on this answer ([Snapshot.suppressor]). */
        val suppressor: Suppressor = Suppressor.Linked,
    ) {
        override fun toString(): String = "Sighting(${FormKeys.shown(nodeId)}, blinded=$blinded, rssi=$rssiDbm)"
    }

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
        /**
         * How long a scan listened on 1M between the peer's last 1M hit and its latest Coded hit, null when this
         * presence holds no Coded hit; [CodedPhyPolicy.codedOnly] reads it. A peer never heard on 1M is measured from
         * its first Coded hit, so it counts only once a 1M listener has missed it for a while. A 1M hit under the weak
         * floor ([PhyTuning.oneMWeakDbm], on the smoothed 1M reading) does not count as one: the lag runs on through it.
         */
        val codedLagMs: Long? = null,
        /**
         * Whether that PHY was heard in the latest burst — the rule [smoothedRssi] counts a PHY's reading by. [rssi1m]
         * and [rssiCoded] outlive their PHY going silent (a walk-away peer keeps its last strong 1M reading while Coded
         * alone still hears it), so a reader of the raw readings checks these first ([ScanPlan.near]). The defaults
         * call a held reading fresh, for a snapshot built by hand.
         */
        val oneMFresh: Boolean = rssi1m != null,
        val codedFresh: Boolean = rssiCoded != null,
        /**
         * The form the peer advertised (ADR 2026-11.xp6d): [advertised] is advert bytes 1–16 as a 26-char string, its
         * node id when the advert is clear and a tag when [blinded]. Every pre-link role decides on it
         * (`AdvertOrder.iDial`), never on [nodeId], which is the key the sighting is attributed to: the resolved id, or an
         * unresolved form's handle (`FormKeys`). The latest sighting's form.
         */
        val advertised: String = nodeId,
        val blinded: Boolean = false,
        /**
         * What may suppress a dial `AdvertOrder.iDial` calls for on the latest sighting's answer (ADR 2026-11.xp6d's
         * "What an answer may do"), evaluated when the dial is decided, never frozen at the sighting: a clear advert's
         * by any link to [nodeId], a key's only by a proven one, a claim's only by its claimant's own link while that is
         * the link held under the id, a hint's and an unresolved form's never.
         */
        val suppressor: Suppressor = Suppressor.Linked,
    ) {
        /** Redacted: a tag never reaches a log through a snapshot ([FormKeys.shown]). */
        override fun toString(): String =
            "Snapshot(${FormKeys.shown(nodeId)}, blinded=$blinded, rssi=${smoothedRssi.toInt()}, dwellMs=$dwellMs)"
    }

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
        // The 1M-listening clock at the latest Coded hit.
        var lastCodedClock: Long? = null,
        var advertised: String = "",
        var blinded: Boolean = false,
        var suppressor: Suppressor = Suppressor.Linked,
    ) {
        /** Whether 1M was heard in the latest burst: its last hit within [PresenceConfig.presenceGapResetMs] of the latest sighting. */
        fun oneMFresh(config: PresenceConfig): Boolean = fresh(last1mAt, config.presenceGapResetMs)

        /** Whether Coded was heard in the latest burst: its last hit within [PresenceConfig.codedGapResetMs] of the latest sighting. */
        fun codedFresh(config: PresenceConfig): Boolean = fresh(lastCodedAt, config.codedGapResetMs)

        private fun fresh(
            at: Long?,
            gapMs: Long,
        ) = at != null && lastSeenAt - at <= gapMs

        /**
         * The smoothed RSSI on the 1M scale, counting a PHY only if it was heard in the latest burst ([oneMFresh],
         * [codedFresh]). The latest sighting's own PHY always is, so this is never empty once [note] has run.
         */
        fun smoothed(
            config: PresenceConfig,
            creditDb: Double,
        ): Double =
            CodedPhyPolicy.effectiveRssi(
                rssi1m.takeIf { oneMFresh(config) },
                rssiCoded.takeIf { codedFresh(config) },
                creditDb,
            ) ?: Double.NEGATIVE_INFINITY

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
            // A sighting resolved off the scan thread can land after a later one: every time takes the later of the two.
            if (coded) {
                rssiCoded = next
                lastCodedAt = maxOf(lastCodedAt ?: now, now)
            } else {
                rssi1m = next
                last1mAt = maxOf(last1mAt ?: now, now)
            }
            lastSeenAt = maxOf(lastSeenAt, now)
        }
    }

    private val entries = HashMap<String, Entry>()

    // The 1M-listening clock: banked time, plus the open window's since it began.
    private var oneMListenedMs = 0L
    private var oneMListenSince: Long? = null

    // The clock at each peer's last 1M hit at or above the weak floor, or at its first hit if it has had none. Kept
    // past the presence entry, least recently used out first.
    private val oneMMarks =
        object : LinkedHashMap<String, Long>(MARKS_CAPACITY, MARKS_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_MARKS
        }

    private fun oneMClock(now: Long): Long = oneMListenedMs + (oneMListenSince?.let { now - it } ?: 0)

    /** A scan window that hears 1M began ([listening]) or ended; a Coded-only window is not one. */
    @Synchronized
    fun onOneMListening(
        listening: Boolean,
        now: Long,
    ) {
        val since = oneMListenSince
        if (listening && since == null) oneMListenSince = now
        if (!listening && since != null) {
            oneMListenedMs += now - since
            oneMListenSince = null
        }
    }

    private fun noteClock(
        s: Sighting,
        entry: Entry,
        now: Long,
    ) {
        val clock = oneMClock(now)
        if (s.coded) entry.lastCodedClock = clock
        // [note] has already folded this hit into rssi1m. A weak 1M hit, like a Coded one, only seeds a missing mark.
        val strongOneM = !s.coded && (entry.rssi1m ?: Double.NEGATIVE_INFINITY) >= oneMWeakDbm()
        if (strongOneM) oneMMarks[s.nodeId] = clock else oneMMarks.getOrPut(s.nodeId) { clock }
    }

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
                    advertised = s.advertised,
                    blinded = s.blinded,
                    suppressor = s.suppressor,
                ).also {
                    it.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, phyGapMs)
                    noteClock(s, it, now)
                }
            return
        }
        // A sighting captured before the entry's latest (resolved late) moves its RSSI and nothing else.
        val latest = now >= existing.lastSeenAt
        existing.note(s.rssiDbm, s.coded, now, config.rssiEwmaAlpha, phyGapMs)
        noteClock(s, existing, now)
        if (!latest) return
        existing.protoVersion = s.protoVersion
        existing.capabilities = s.capabilities
        existing.psm = s.psm
        existing.digestCue = s.digestCue
        existing.advertised = s.advertised
        existing.blinded = s.blinded
        existing.suppressor = s.suppressor
    }

    /**
     * Moves [from]'s entry (an unresolved form's handle) to [to] (the id it resolved to by a key or by our own dial's
     * proof, ADR 2026-11.xp6d): its dwell, its RSSI and its 1M mark go with it. When [to] has an entry too, the two
     * merge: the earlier first sighting, the later last one, and the later entry's readings. [suppressor], when given, is
     * the answer that resolved it, carried onto the moved entry.
     */
    @Synchronized
    fun rekey(
        from: String,
        to: String,
        suppressor: Suppressor? = null,
    ) {
        val moved = entries.remove(from) ?: return
        suppressor?.let { moved.suppressor = it }
        val mark = oneMMarks.remove(from)
        val held = entries[to]
        if (held == null) {
            entries[to] = moved
            mark?.let { oneMMarks[to] = it }
            return
        }
        val newer = if (moved.lastSeenAt > held.lastSeenAt) moved else held
        newer.firstSeenAt = minOf(moved.firstSeenAt, held.firstSeenAt)
        newer.lastSeenAt = maxOf(moved.lastSeenAt, held.lastSeenAt)
        entries[to] = newer
        if (mark != null && to !in oneMMarks) oneMMarks[to] = mark
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
                codedLagMs = e.lastCodedClock?.let { coded -> maxOf(0L, coded - (oneMMarks[nodeId] ?: coded)) },
                oneMFresh = e.oneMFresh(config),
                codedFresh = e.codedFresh(config),
                advertised = e.advertised.ifEmpty { nodeId },
                blinded = e.blinded,
                suppressor = e.suppressor,
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
        oneMMarks.remove(nodeId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        oneMMarks.clear()
        oneMListenedMs = 0
        oneMListenSince = null
    }

    private companion object {
        // Peers whose last 1M hit is remembered past their presence; GattPayloads keeps as many addresses.
        const val MAX_MARKS = 64
        const val MARKS_CAPACITY = 16
        const val MARKS_LOAD_FACTOR = 0.75f
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
