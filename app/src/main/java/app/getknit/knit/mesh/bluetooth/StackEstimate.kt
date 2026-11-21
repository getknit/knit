package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.link.FramedLink
import java.util.concurrent.atomic.AtomicReference

/**
 * What one Bluetooth link's stack buffer still holds of what custody counts (the custody-carry-policy contract's §2,
 * revision 14): Android's L2CAP socket takes about 120 KB before a write blocks and never says what it holds (knit-next
 * #121, ADR 2026-10.nxfb), so [app.getknit.knit.mesh.link.FramedLink.handedDownBytes] going up says nothing of the air.
 * Read as drained, the stack would hold eight rounds and a live text would wait behind them (ADR 2026-10.8jwn measured 50
 * to 260 s). So this counts what the link handed down, less what its drain rate has sent since: a leaky bucket, filled by
 * the handed-down counter's growth and emptied at the rate the read passes, never below zero.
 *
 * Bytes handed down between two reads are put in the bucket at the later read, so the estimate errs high, which holds a
 * round back rather than piling one on the stack. A file's chunks share that buffer and are not counted (§2: a file's
 * own bytes aren't); while a file moves only tier 2 rides with it.
 *
 * One per link, made at registration, so a replaced link starts its own. Lock-free (a CAS on one anchor), as
 * `MeshTransport.carrier` requires; pure, on the clock its reader passes (the plane's elapsedRealtime: the controller
 * drains while the CPU sleeps).
 */
internal class StackEstimate(
    startedAt: Long,
) {
    // [milli] is the bucket in thousandths of a byte, so a drain read every few milliseconds isn't rounded down at each read.
    private class Anchor(
        val handed: Long,
        val at: Long,
        val milli: Long,
    ) {
        // Rounded up: an estimate errs high.
        val below: Long get() = (milli + MS_PER_SEC - 1) / MS_PER_SEC
    }

    private val anchor = AtomicReference(Anchor(0L, startedAt, 0L))

    /**
     * The counted bytes estimated still in the buffer below at [now], with the link's counter at [handedDown] and its
     * drain at [bytesPerSec] since the last read. A reader racing another with an older counter or clock adds nothing and
     * drains nothing: the anchor never moves back.
     */
    fun below(
        handedDown: Long,
        bytesPerSec: Long,
        now: Long,
    ): Long {
        while (true) {
            val a = anchor.get()
            val next = step(a, handedDown, bytesPerSec, now)
            if (next === a || anchor.compareAndSet(a, next)) return next.below
        }
    }

    private fun step(
        a: Anchor,
        handedDown: Long,
        bytesPerSec: Long,
        now: Long,
    ): Anchor {
        val added = (handedDown - a.handed).coerceAtLeast(0L)
        val ms = (now - a.at).coerceAtLeast(0L)
        if (added == 0L && ms == 0L) return a
        val milli = (a.milli - milliSent(ms, bytesPerSec)).coerceAtLeast(0L) + added * MS_PER_SEC
        return Anchor(maxOf(a.handed, handedDown), maxOf(a.at, now), milli)
    }

    companion object {
        /** What [bytesPerSec] sends in [ms]; saturates rather than overflow on a long quiet. */
        fun drained(
            ms: Long,
            bytesPerSec: Long,
        ): Long = milliSent(ms, bytesPerSec).let { if (it == Long.MAX_VALUE) it else it / MS_PER_SEC }

        // [drained] in thousandths of a byte (bytes a second times milliseconds), saturating.
        private fun milliSent(
            ms: Long,
            bytesPerSec: Long,
        ): Long =
            when {
                ms <= 0L || bytesPerSec <= 0L -> 0L
                ms > Long.MAX_VALUE / bytesPerSec -> Long.MAX_VALUE
                else -> ms * bytesPerSec
            }

        private const val MS_PER_SEC = 1000L
    }
}

/**
 * What a Bluetooth link says it holds for its peer ([LinkOutbound], the custody-carry-policy contract's §2): its own
 * [queued] bytes plus what its [estimate] says the stack below still holds of [handedDown] at [rate], with [fileMoving].
 * The caller reads [queued] before [handedDown], so a flush between the two counts twice rather than never.
 *
 * - a link whose writer ended ([FramedLink.NOT_DRAINING] or more queued): [LinkOutbound.NOT_DRAINING], whatever else;
 * - no [rate]: null, the link can't be estimated (custody gives it one round a digest), never read as drained;
 * - no [estimate] (the link was torn down between the two reads): [LinkOutbound.NOT_DRAINING];
 * - else the queue plus the stack's estimate, at [now], with the rate custody times its next look by.
 */
@Suppress("LongParameterList") // one link's readings, each taken by the caller in its order
internal fun stackOutbound(
    queued: Long,
    handedDown: Long,
    rate: Long?,
    estimate: StackEstimate?,
    now: Long,
    fileMoving: Boolean,
): LinkOutbound? {
    if (queued >= FramedLink.NOT_DRAINING) return LinkOutbound(LinkOutbound.NOT_DRAINING)
    if (rate == null) return null
    if (estimate == null) return LinkOutbound(LinkOutbound.NOT_DRAINING)
    return LinkOutbound(queued + estimate.below(handedDown, rate, now), drainBytesPerSec = rate, fileMoving = fileMoving)
}

/**
 * The drain rate a Bluetooth link's [StackEstimate] runs at: the rate its file feed keeps for it now (ADR 2026-10.8jwn,
 * 2026-10.2da4, 2026-10.5buf), which is all the link has measured or been given. Pure; the transport passes the readings.
 */
internal object StackDrainPolicy {
    /**
     * - a link on Coded, [codedPace]: its own pace, outside the split (1 KiB/s, the edge of the 2026-10-02 walk);
     * - a [windowed] link whose last stream measured a drain ([drained] > 0): that drain, shared with the
     *   [otherWindowedFeeds] going beside it now. A link that [mayHaveBoosted] (one that has had a boost, whatever it can
     *   earn now: audio, a dead tuner or the switch off take that away and leave the drain it measured) and hasn't
     *   [earned] it now is read no faster than the [pacedShare] it would be fed at now: too high fills the stack a live
     *   text waits behind, too low only leaves the link idle for a while (§9);
     * - else, [earned] now: the [boosted] share; not: the [pacedShare] times the share of its pace the link's last
     *   measured stream drained ([drainRatio]).
     *
     * While the link streams a file of its own ([ownFeed]) that file takes its share of the same stack: Coded's pace and a
     * windowed drain are split with it here, and the transport counts it among the paced feeds behind [boosted] and
     * [pacedShare]. Null when that comes to no rate at all (an unbounded debug budget): the link can't be estimated.
     */
    @Suppress("LongParameterList") // the estimate's inputs, each read off the link or the transport
    fun rate(
        codedPace: Int?,
        drained: Int,
        windowed: Boolean,
        otherWindowedFeeds: () -> Int,
        ownFeed: Boolean,
        drainRatio: Double,
        mayHaveBoosted: Boolean,
        earned: Boolean,
        boosted: () -> Int,
        pacedShare: () -> Int,
    ): Long? {
        val r =
            when {
                codedPace != null -> {
                    codedPace / (if (ownFeed) 2 else 1)
                }

                drained > 0 && windowed -> {
                    val share = drained / (otherWindowedFeeds() + 1 + (if (ownFeed) 1 else 0))
                    if (mayHaveBoosted && !earned) minOf(share, pacedShare()) else share
                }

                earned -> {
                    boosted()
                }

                else -> {
                    (pacedShare() * drainRatio).toInt()
                }
            }
        return r.toLong().takeIf { it > 0L }
    }
}
