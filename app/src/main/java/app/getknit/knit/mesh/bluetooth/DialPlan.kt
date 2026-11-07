package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.ConnectFailReason

/**
 * The connection engine's glue between the sighted peers and its policies ([PromotionPolicy], [LonelyDialPolicy],
 * [ScanDemandPolicy]): which peers each one is handed, how their answers combine, and where a dial goes. Pure, free of
 * Android and of a clock, so [BluetoothMeshTransport]'s choices are asserted on the JVM.
 */
internal object DialPlan {
    /**
     * The kind of a dial in flight, recorded when it starts (ADR 2026-11.xp6d): once adverts can be blinded, a dial's
     * kind no longer follows from the id order (a blinding phone ordinary-dials every clear advert, larger ids among
     * them, and a lonely dial may go to a tag), so it is never inferred from it.
     */
    enum class DialKind {
        ORDINARY,

        /** ADR 2026-09.hj4a's lonely dial: at most one in flight. */
        LONELY,
    }

    /**
     * A dial in flight (ADR 2026-11.xp6d's reader, obligation 9): its [kind], recorded when it starts; the per-peer key
     * it was attributed to then ([peerKey]: the resolved id, or an unresolved form's handle), which `failConnect` reads
     * as the dial's resolved id; the [address] it went to, so no ACL release lands on it; and whether [peerKey] already
     * held a link when it began ([linkedAtStart]): such a dial crossed nothing, so its refusal backs off
     * ([DialKeys.linkedNow]). Required, never defaulted: the two readings of a record are opposite failures.
     */
    data class InFlight(
        val kind: DialKind,
        val peerKey: String,
        val address: String?,
        val linkedAtStart: Boolean,
    )

    /**
     * Whether a link held now suppresses a dial to [nodeId] whose answer names [suppressor]: any link ([linked]) for a
     * clear advert, a proven one ([proven]) for a key's, the claimant's own link (the one [held] names under the id now)
     * for a claim's, none for a hint's or an unresolved form's. The one thing beside it is [DialKeys.dialHeld]: our own
     * proven dial to that same tag and address, which needs the dial key and so is read by the candidate filters.
     */
    fun suppressed(
        suppressor: BlePresenceTracker.Suppressor,
        nodeId: String,
        linked: Set<String>,
        proven: Set<String>,
        held: (String) -> Any? = { null },
    ): Boolean =
        when (suppressor) {
            BlePresenceTracker.Suppressor.Linked -> nodeId in linked
            BlePresenceTracker.Suppressor.Proven -> nodeId in proven
            is BlePresenceTracker.Suppressor.Claim -> held(nodeId) === suppressor.link
            BlePresenceTracker.Suppressor.None -> false
        }

    /**
     * The sighted peers the ordinary order has me dial ([AdvertOrder.iDial] on [mine] against each snapshot's advertised
     * form: for two clear phones, a smaller id), not [linked], no dial to it [inFlight], not a tag and address a held dial
     * of ours reached ([dialHeld], [DialKeys.dialHeld]: required, so a call site cannot drop it). A clear phone never dials
     * a blinded advert here; the blinding side dials it.
     */
    fun ordinaryCandidates(
        mine: AdvertOrder.Mine,
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        inFlight: Set<String>,
        dialHeld: (BlePresenceTracker.Snapshot) -> Boolean,
        proven: Set<String> = linked,
        held: (String) -> Any? = { null },
    ): List<BlePresenceTracker.Snapshot> =
        snaps.filter {
            AdvertOrder.iDial(mine.current, it.form) && !suppressed(it.suppressor, it.nodeId, linked, proven, held) &&
                it.nodeId !in inFlight && !dialHeld(it)
        }

    /**
     * Whether a sighted peer advertising [theirs] at [smoothedRssi] gives the scan promotion work: one I, airing [mine],
     * would dial ([AdvertOrder.iDial]; a smaller id between two clear phones), not held off by a link its answer's
     * [suppressor] counts ([suppressed] over [linked] and [proven], read now), at or above [rssiFloorDbm] so it can
     * promote, and not [backingOff] (read last: it takes the transport's lock). [linked] and [backingOff] read [nodeId],
     * the key presence attributes the sighting to. The scan's floor reads the same gate over every sighting
     * ([scanCandidates]) as a fresh sighting's boost does over one.
     */
    @Suppress("LongParameterList") // the gate's inputs, each a different source in the transport
    fun promotable(
        mine: AdvertOrder.Mine,
        theirs: AdvertOrder.Form,
        nodeId: String,
        smoothedRssi: Double?,
        linked: Set<String>,
        rssiFloorDbm: Double,
        suppressor: BlePresenceTracker.Suppressor = BlePresenceTracker.Suppressor.Linked,
        proven: Set<String> = linked,
        held: (String) -> Any? = { null },
        backingOff: (String) -> Boolean,
    ): Boolean =
        AdvertOrder.iDial(mine.current, theirs) && !suppressed(suppressor, nodeId, linked, proven, held) && smoothedRssi != null &&
            smoothedRssi >= rssiFloorDbm && !backingOff(nodeId)

    /**
     * Whether the connect loop's own promotion ([PromotionPolicy.decide] over the [links] it scores, with [backoff] and
     * [config], the debug cap included) would dial [snap]'s peer once its dwell had ripened: a free slot under the
     * budget, or it beats the weakest evictable link by the hysteresis. A cold hunt ([CodedPhyPolicy.coldHunt]) is worth
     * its windows only then — at the budget behind stronger links its dwell ripens to nothing. The dwell is the one gate
     * left out, being what the hunt's windows ripen; the floor and the backoff stay in.
     */
    fun promotes(
        snap: BlePresenceTracker.Snapshot,
        links: List<PromotionPolicy.LinkSnapshot>,
        backoff: Set<String>,
        config: PromotionConfig,
    ): Boolean {
        val ripe = snap.copy(dwellMs = maxOf(snap.dwellMs, config.dwellThresholdMs))
        return snap.nodeId in PromotionPolicy.decide(listOf(ripe), links, backoff, config).promote
    }

    /** The sighted peers [promotable] keeps, less a tag and address a held dial of ours reached: the scan's promotion work. */
    fun scanCandidates(
        mine: AdvertOrder.Mine,
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        backoff: Set<String>,
        rssiFloorDbm: Double,
        dialHeld: (BlePresenceTracker.Snapshot) -> Boolean,
        proven: Set<String> = linked,
        held: (String) -> Any? = { null },
    ): Set<String> =
        snaps
            .filter {
                !dialHeld(it) &&
                    promotable(
                        mine,
                        it.form,
                        it.nodeId,
                        it.smoothedRssi,
                        linked,
                        rssiFloorDbm,
                        it.suppressor,
                        proven,
                        held,
                    ) { id -> id in backoff }
            }.mapTo(HashSet()) { it.nodeId }

    /**
     * The sighted peers [LonelyDialPolicy] may pick from: not [linked] (or linked on an answer that may not suppress a
     * dial), advertising a PSM, [dialable] (an address known for the form it airs now), each marked whether it is in
     * [backoff] (by per-peer key, [backedOff]) and carrying its advertised form. The policy applies the order
     * ([AdvertOrder.lonelyMayDial]) and its other gates.
     */
    fun lonelyCandidates(
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        backoff: Set<String>,
        proven: Set<String> = linked,
        held: (String) -> Any? = { null },
        dialable: (BlePresenceTracker.Snapshot) -> Boolean,
    ): List<LonelyDialPolicy.Candidate> =
        snaps
            .filter { !suppressed(it.suppressor, it.nodeId, linked, proven, held) && it.psm != 0 && dialable(it) }
            .map { LonelyDialPolicy.Candidate(it.nodeId, it.smoothedRssi, it.dwellMs, it.nodeId in backoff, it.form) }

    /** Whether a lonely dial is [inFlight]: by the kind each dial recorded when it started ([DialKind]), never the order. */
    fun lonelyDialInFlight(inFlight: Map<String, DialKind>): Boolean = inFlight.values.any { it == DialKind.LONELY }

    /**
     * The promotion [ordinary] decided, with [lonely]'s dial added (ADR 2026-09.hj4a), then held to a debug [cap] if one
     * is set: a dial still in flight holds a slot too, so back-to-back ticks cannot overshoot it, and each eviction
     * frees one. [inFlightCount] is read only under a cap: it takes the transport's lock.
     */
    fun decision(
        ordinary: PromotionPolicy.Decision,
        lonely: LonelyDialPolicy.Candidate?,
        cap: Int?,
        linkCount: Int,
        inFlightCount: () -> Int,
    ): PromotionPolicy.Decision {
        val decided = if (lonely == null) ordinary else ordinary.copy(promote = ordinary.promote + lonely.nodeId)
        if (cap == null) return decided
        val room = cap - linkCount - inFlightCount() + decided.evict.size
        return decided.copy(promote = decided.promote.take(room.coerceAtLeast(0)))
    }

    /**
     * Where a dial goes, and whether it is the Coded address: a peer heard on Coded alone ([viaCoded]) is dialed at its
     * [coded] address, any other at its [oneM] one, else its Coded one. Null when the one it needs is not known.
     *
     * The flag names the address returned, not the one asked for: a fallback to the Coded address is a Coded dial,
     * with Coded's watchdog (`CodedPhyPolicy.connectTimeoutMs`), its count and its `via=coded` (#148). A peer first
     * heard in a Coded-only window has no 1M address until its lag ripens, and a plain connect to its Coded set opens
     * on Coded; under the request's flag it got the 12 s 1M watchdog and logged `via=1m`.
     */
    fun <T : Any> target(
        viaCoded: Boolean,
        oneM: T?,
        coded: T?,
    ): Pair<T, Boolean>? = if (viaCoded || oneM == null) coded?.let { it to true } else oneM to false

    /**
     * The peers another plane reports that I would dial: the only ones worth a chase. [AdvertOrder.initiable] on
     * [localId] and whether [mine] blinds: the classic order (the larger id dials) for a phone that airs its id, which
     * keeps chasing smaller ids even when they blind (that waste is accepted, as its KDoc says).
     */
    fun initiable(
        localId: String,
        mine: AdvertOrder.Mine,
        peers: Set<String>,
    ): Set<String> = AdvertOrder.initiable(localId, blinding = mine.blinded, peers = peers)

    /**
     * Whether a GATT payload read of [address] may start: never an address a link holds ([linkAddresses]), and never
     * while a dial is in flight ([dialing], read after the address: it takes the transport's lock) or another connect
     * holds the arbiter: the read is a connection the controller must make, as a dial is. [GattPayloads] decides whether
     * one is due.
     */
    fun mayReadGatt(
        address: String,
        linkAddresses: Collection<String>,
        arbiterBusy: Boolean,
        dialing: () -> Boolean,
    ): Boolean = address !in linkAddresses && !dialing() && !arbiterBusy

    /**
     * Whether [promote] holds a dial the transport can make ([canDial]: an address to go to, as `initiateTo` needs), so
     * a GATT read that comes due yields to it (`GattPayloads.yieldTo`, #154). A promoted peer with no address is left
     * out: `initiateTo` returns without dialing it, and it would stay promoted, holding every read off, for as long as
     * presence keeps it (a peer heard on Coded alone once the Coded mode goes off). One that is dialed and fails is
     * backed off, and so leaves the promotion by itself.
     */
    fun dialWaiting(
        promote: List<String>,
        canDial: (String) -> Boolean,
    ): Boolean = promote.any(canDial)

    /**
     * Whether a failed dial forgets the peer's GATT payload, so its next advert reads it afresh: a dial that failed
     * before its channel opened may have used a PSM the peer no longer listens on (an iPhone that restarted); a HELLO
     * refusal says nothing of the PSM (companion change A3, ADR 2026-09.kwq2).
     */
    fun forgetsGattPayload(reason: ConnectFailReason): Boolean = reason != ConnectFailReason.HANDSHAKE

    /** The `via=` a dial's `bt initiating to` line carries while the Coded experiment runs ([codedOn]); empty when off. */
    fun via(
        codedOn: Boolean,
        viaCoded: Boolean,
    ): String =
        when {
            !codedOn -> ""
            viaCoded -> " via=coded"
            else -> " via=1m"
        }
}
