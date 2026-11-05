package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState

/**
 * The presence scan's glue between what it heard and its policies ([CodedPhyPolicy], [ScanDemandPolicy]): which peers
 * count as far, when Coded-only windows run and why, and what the transport publishes as reachable and as near. Pure, so
 * [BluetoothMeshTransport]'s choices are asserted on the JVM.
 */
internal object ScanPlan {
    /**
     * How long the scan waits before its next window, null to scan now: while the adapter is off ([adapterOn] false),
     * [adapterOffWaitMs], for the adapter's own event; while a dial is in flight ([dialing]) or another connect holds
     * the arbiter, [connectPauseMs], for the connect's end (a scan starves a connect); else until [nextWindowAt], the
     * slot of a window a dial cut short, when that is still ahead of [now].
     */
    @Suppress("LongParameterList") // the window's gates and its two waits, each from a different source
    fun beforeWindow(
        adapterOn: Boolean,
        dialing: Boolean,
        arbiterBusy: Boolean,
        nextWindowAt: Long,
        now: Long,
        connectPauseMs: Long,
        adapterOffWaitMs: Long,
    ): Long? =
        when {
            !adapterOn -> adapterOffWaitMs
            dialing || arbiterBusy -> connectPauseMs
            nextWindowAt > now -> nextWindowAt - now
            else -> null
        }

    /**
     * The scan's posture for the gap after a window, by the name its log lines use: [HELD] (a debug build's hold,
     * [ScanHold]), [HUNT], [FLOOR] (settled or audio-contended), [BOOST] (links held, promotion work), [LONELY] (no link,
     * still aggressive) and [RELAXED] (no link, past the lonely window on battery). [wire] is the debug bridge's word.
     */
    enum class Posture(
        val wire: String,
    ) {
        HELD("held"),
        HUNT("hunt"),
        FLOOR("floor"),
        BOOST("boost"),
        LONELY("lonely"),
        RELAXED("relaxed"),
    }

    /**
     * The gap after a presence window: [ms] long, whether it keeps wall time through sleep ([keepsWallTime]), the
     * [posture] that chose it, and whether the `bt scan lonely: relaxed` edge reads relaxed ([lonelyRelaxed]).
     */
    data class Gap(
        val ms: Long,
        val keepsWallTime: Boolean,
        val posture: Posture,
        val lonelyRelaxed: Boolean = false,
    )

    /**
     * The gap after a presence window: the hunting gap while a debug [held] hold stands or it [hunt]s, else the settled
     * [floor]'s or the duty cycle's. Only a hunting gap keeps wall time (ADR 2026-10.pj9w): the settled, linked and
     * relaxed gaps are power budgets and stretch while the CPU sleeps.
     *
     * [held] is `…debug.SCAN --ez hold true` (#285, a device trial's lever): the hunt's gap, whatever the links, the floor,
     * Bluetooth audio or the lonely clock say, so a trial phone keeps scanning densely while it counts adverts. [floor] is
     * not asked while held, so no `bt scan → floor` edge is logged, and the lonely edge reads not relaxed. It moves only
     * the gap: the window, the scan mode and the PHYs stay the power state's and the hunt's own, and nothing that reads
     * whether the phone hunts (Coded windows, a dial ending a window, the rotation's hold) sees it.
     */
    @Suppress("LongParameterList") // the gap's inputs, each from a different source in the transport
    fun gapAfterWindow(
        power: PowerState,
        hunt: Boolean,
        floor: () -> Boolean,
        links: Int,
        aloneForMs: Long,
        held: Boolean = false,
    ): Gap {
        if (held) return Gap(PowerPolicy.HUNT_IDLE_MS, keepsWallTime = true, posture = Posture.HELD)
        if (hunt) return Gap(PowerPolicy.HUNT_IDLE_MS, keepsWallTime = true, posture = Posture.HUNT)
        val relaxed = links == 0 && PowerPolicy.lonelyRelaxed(power, aloneForMs)
        val wallTime = PowerPolicy.hunting(power, links, aloneForMs)
        if (floor()) return Gap(PowerPolicy.settledIdleAfterScan(power, links, aloneForMs), wallTime, Posture.FLOOR, relaxed)
        val posture =
            when {
                links > 0 -> Posture.BOOST
                relaxed -> Posture.RELAXED
                else -> Posture.LONELY
            }
        return Gap(PowerPolicy.idleAfterScan(power, links, aloneForMs), wallTime, posture, relaxed)
    }

    /** The sighted peers not [linked] that are heard on Coded alone ([CodedPhyPolicy.codedOnly]): the far ones. */
    fun far(
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
    ): List<String> = snaps.filter { it.nodeId !in linked && CodedPhyPolicy.codedOnly(it.codedLagMs) }.map { it.nodeId }

    /**
     * Whether the scan runs Coded-only windows at all ([CodedPhyPolicy.scanPhys] alternates them): only with Coded [on],
     * and then while this phone is [alone], hears a [far] peer, [hunt]s, or [chased] a peer another plane sees.
     */
    fun codedWindows(
        on: Boolean,
        alone: Boolean,
        far: List<String>,
        hunt: Boolean,
        chased: Boolean,
    ): Boolean = on && (alone || far.isNotEmpty() || hunt || chased)

    /** Why Coded-only windows came on, for their edge's log line: the first of [codedWindows]' reasons, else the chase. */
    fun codedWindowsWhy(
        alone: Boolean,
        hunt: Boolean,
        far: List<String>,
        chasedCoded: () -> List<String>,
    ): String =
        when {
            alone -> "alone"
            hunt -> "hunt"
            far.isNotEmpty() -> "codedOnly=$far"
            else -> "chase=${chasedCoded()}"
        }

    /** The three sets the transport publishes from one presence read ([published]). */
    data class Published(
        val reachable: Set<Peer>,
        val near: Set<Peer>,
        val aware: AwareCandidateTracker.Sets,
    )

    /**
     * What the transport publishes from one [snaps] read (ADR 2026-11.xp6d's reader, obligation 10): [reachable] and
     * [near] carry resolved and linked peers only, so no unresolved form's handle ([FormKeys]) becomes a met peer, a
     * nearby entry or a foreign-near corroboration; the Aware candidate sets ([aware], [AwareCandidateTracker.sets]) take
     * every snapshot, so an unresolved tag carrying the Aware flag still wakes a dormant Aware plane at its first sighting
     * (wifi-aware's design finding 2). Filtering before all three would lose that wake; filtering none would leak tags.
     */
    fun published(
        snaps: List<BlePresenceTracker.Snapshot>,
        linkPeers: Collection<Peer>,
        aware: (List<BlePresenceTracker.Snapshot>) -> AwareCandidateTracker.Sets,
    ): Published {
        val resolved = snaps.filter { !FormKeys.isForm(it.nodeId) }
        return Published(reachable(resolved, linkPeers), near(resolved, linkPeers), aware(snaps))
    }

    /**
     * What the transport publishes as reachable: every sighted peer in [snaps], and every peer it holds a link to
     * ([linkPeers]), so a linked peer stays nearby while the throttled scan has not re-sighted it. A link's [Peer] wins
     * over a sighting's on the same node id.
     */
    fun reachable(
        snaps: List<BlePresenceTracker.Snapshot>,
        linkPeers: Collection<Peer>,
    ): Set<Peer> {
        val byId = HashMap<String, Peer>()
        snaps.forEach { byId[it.nodeId] = Peer(it.nodeId, it.protoVersion, it.capabilities) }
        linkPeers.forEach { byId[it.nodeId] = it }
        return byId.values.toSet()
    }

    /**
     * What the transport publishes as near ([app.getknit.knit.mesh.MeshTransport.near]): every peer it holds a link to
     * ([linkPeers], any PHY), and every sighted peer not heard on Coded alone ([CodedPhyPolicy.codedOnly]) whose
     * strongest **fresh raw** reading — 1M if [BlePresenceTracker.Snapshot.oneMFresh], Coded if
     * [BlePresenceTracker.Snapshot.codedFresh] — reaches [floorDbm]. Never the Coded credit: it stands for the PHY's
     * sensitivity, and raw dBm is a power reading. Never a stale reading: a walk-away peer keeps its last strong 1M one.
     * Coded reaches far past Wi-Fi Aware's NDP range, so `reachable` held a walk-away peer from one Coded advert at raw
     * −101 dBm and corroborated the Aware wedge watchdog's process kill on screen (P9, 2026-10-20; ADR 2026-09.9dnk).
     * Either PHY counts, so a close peer stays near across an off-screen Coded-only window. A subset of [reachable] on
     * the same inputs. A link's [Peer] wins over a sighting's on the same node id.
     */
    fun near(
        snaps: List<BlePresenceTracker.Snapshot>,
        linkPeers: Collection<Peer>,
        floorDbm: Int = PromotionConfig.DEFAULT_RSSI_FLOOR_DBM,
    ): Set<Peer> {
        val byId = HashMap<String, Peer>()
        snaps
            .filter { !CodedPhyPolicy.codedOnly(it.codedLagMs) && (freshRaw(it) ?: Double.NEGATIVE_INFINITY) >= floorDbm }
            .forEach { byId[it.nodeId] = Peer(it.nodeId, it.protoVersion, it.capabilities) }
        linkPeers.forEach { byId[it.nodeId] = it }
        return byId.values.toSet()
    }

    // The stronger of the snapshot's fresh raw readings, null when neither PHY was heard in the latest burst.
    private fun freshRaw(s: BlePresenceTracker.Snapshot): Double? =
        listOfNotNull(s.rssi1m.takeIf { s.oneMFresh }, s.rssiCoded.takeIf { s.codedFresh }).maxOrNull()
}
