package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.bluetooth.SideCapableTracker.Audience
import app.getknit.knit.mesh.power.PowerState

/**
 * When, and how hard, the BLE side channel listens ([BleSideChannel]'s extended scan). Pure, like
 * [ScanDemandPolicy], so the table is a JVM test.
 *
 * The honest cost of a connectionless channel is that hearing it means scanning: a hardware-filtered scan
 * run continuously is ~10 % receiver duty at LOW_POWER and ~25 % at BALANCED, against the presence scan's
 * ~0.6 % once it is floored — some fifteen times the cost, all night, in a settled clique. So the scan runs
 * only while a page could tell this phone something its links will not (ADR 2026-09.u8qj): a flagged peer is
 * sighted but **unlinked** (no link copy reaches us from it), or a file is streaming on one of our links
 * (either direction — that stream head-of-line-blocks the small frames behind it). With every flagged peer
 * linked and nothing streaming, `fastFanout`'s link copy already reaches us, and the scan is [Tier.Off].
 * It is also Off whenever nobody could be sending (no flagged peer at all, the channel dark) and whenever
 * scanning would cost something it must not (an L2CAP connect in flight or the board dial holding the
 * arbiter — scanning starves connects — or a low battery off the charger), and drops to LOW_POWER under
 * A2DP contention. Never LOW_LATENCY.
 *
 * Restarts are rationed: Android allows an app five scan starts per 30 s and the presence scan shares that
 * budget, so a tier change waits out [MIN_RESTART_GAP_MS] (stopping is always immediate), and a scan older
 * than [PERIODIC_RESTART_MS] is restarted before the stack demotes it to opportunistic at 30 min.
 */
internal object SideScanPolicy {
    enum class Tier { Off, LowPower, Balanced }

    data class Inputs(
        /** [BleSideChannel.live] — this controller can advertise and scan extended pages. */
        val live: Boolean,
        /** [SideCapableTracker.audience] — who nearby could be sending pages, and whether a link already reaches them. */
        val audience: Audience,
        /**
         * A file is streaming on one of our links, either direction (`FramedLink.txInProgress || rxInProgress`),
         * so that link's small frames wait behind it and a page is the faster way to hear them.
         */
        val streamInFlight: Boolean,
        /** An initiator L2CAP connect is in flight, or the Meshtastic board dial holds [BleConnectArbiter]. */
        val connectBusy: Boolean,
        /** [BluetoothAudioMonitor.contended] — A2DP audio is streaming on this controller. */
        val audioContended: Boolean,
        val power: PowerState,
    )

    /** The third row is the new one: every flagged peer linked and no stream blocking a link, the link copy already reaches us. */
    fun decide(i: Inputs): Tier =
        when {
            !i.live || i.connectBusy -> Tier.Off
            i.audience == Audience.Nobody -> Tier.Off
            i.audience == Audience.AllLinked && !i.streamInFlight -> Tier.Off
            i.audioContended -> Tier.LowPower
            i.power.charging -> Tier.Balanced
            i.power.batteryLow -> Tier.Off
            i.power.interactive -> Tier.Balanced
            else -> Tier.LowPower
        }

    /** Minimum gap between two scan starts (the per-app budget is five per 30 s, shared with the presence scan). */
    const val MIN_RESTART_GAP_MS = 30_000L

    /** Restart a running scan this often, ahead of the stack's 30-minute opportunistic demotion. */
    const val PERIODIC_RESTART_MS = 25 * 60_000L

    fun mayRestart(
        lastStartAt: Long,
        now: Long,
    ): Boolean = now - lastStartAt >= MIN_RESTART_GAP_MS

    /** A scan started at [startedAt] is due its periodic restart. */
    fun dueRestart(
        startedAt: Long,
        now: Long,
    ): Boolean = now - startedAt >= PERIODIC_RESTART_MS
}
