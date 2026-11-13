package app.getknit.knit.mesh.lora.meshcore

/**
 * The regional duty law on a MeshCore board, read off the board's own transmit counter (ADR 2026-11.gsqu's Stage 5a-1
 * amendment). MeshCore's firmware enforces no region (`Dispatcher::updateTxBudget` is a 50 % token bucket, spike Q4), so
 * Knit's own ledger is the only governor there — and the ledger books only Knit's frames. This is the check the ledger
 * cannot be: RADIO stats' `tx_air` counts **everything** the radio transmitted, every client's sends, our board's acks and
 * PATH returns, and any repeats (Q5 calls it the ground truth). While the trailing hour's `tx_air` reaches the band's duty
 * ([verdict]), every Knit frame is to be held. Nothing feeds it until Stage 5a-2.
 *
 * **The board's uptime is the clock.** A sample is `(uptime, tx_air)` off one poll's CORE and RADIO stats, so the window
 * is measured on the board, which is what transmitted, and it survives an **app** restart: the board's counter does not
 * reset when the phone's process dies, and a persisted ring ([snapshot] / [restore]) gives the next process its baseline
 * back. A **board** reboot zeroes both: a sample that reads earlier uptime, a lower counter, or a boot time (wall clock −
 * uptime) moved by more than [bootToleranceMs] from the ring's is a different boot, and the ring is dropped — but not
 * what it spent. The old boot's trailing-hour bound at its last sample, plus the seconds between that sample and the new
 * boot (the radio may have sent all of them unseen, at most an hour), is carried as a **pre-boot term** ([PreBoot]) on
 * the wall clock, added to every bound until an hour after the new boot (or the last sample, if later), so a reboot
 * never hands out a fresh hour either. Nothing compares the wall clock with the phone's elapsed clock.
 *
 * **Never a fresh allowance on a restart.** With no baseline an hour old, the trailing hour is bounded from what is known
 * ([trailingTxSecs]): the counter cannot have risen faster than the clock, and it started at zero at boot. A process that
 * starts with no persisted ring therefore reads `min(tx_air, the hour)` — on a board that has transmitted for long, a hold
 * of up to an hour after a launch on a 1 % band, never a window handed out new.
 *
 * Pure, clock-free (the caller passes the wall clock and the board's counters) and every entry point `@Synchronized`.
 */
internal class MeshcoreDutyLaw(
    private val windowSecs: Long = WINDOW_SECS,
    private val spacingSecs: Long = SPACING_SECS,
    private val bootToleranceMs: Long = BOOT_TOLERANCE_MS,
) {
    /** One poll: the board's [uptimeSecs] and [txAirSecs], and the boot it belongs to, on the wall clock ([bootWallMs]). */
    data class Sample(
        val uptimeSecs: Long,
        val txAirSecs: Long,
        val bootWallMs: Long,
    )

    /**
     * The law's answer for a band of a given duty: what the trailing hour spent at most ([trailingTxSecs], an upper bound),
     * what the duty allows of it ([limitSecs]), whether every Knit frame is held, and — while held — in how many of the
     * board's seconds the hold lifts if the radio transmits nothing more ([releaseInSecs]; null when not held).
     */
    data class Verdict(
        val trailingTxSecs: Long,
        val limitSecs: Double,
        val holding: Boolean,
        val releaseInSecs: Long?,
    )

    /**
     * What earlier boots spent inside the hour, carried past a reboot: [txSecs] at most — the old boot's last-hour bound and
     * the unseen seconds up to the new boot — counted until [untilWallMs] on the wall clock (an hour after the new boot).
     */
    data class PreBoot(
        val txSecs: Long,
        val untilWallMs: Long,
    )

    /** What the plane state persists ([snapshot] / [restore]): the ring with the newest reading, and the pre-boot term. */
    data class State(
        val samples: List<Sample>,
        val preBoot: PreBoot?,
    )

    /** The term carried from earlier boots, or null. */
    private var preBoot: PreBoot? = null

    /** Spaced samples of this boot, oldest first, the oldest at or before the window's start once there is one. */
    private val ring = ArrayDeque<Sample>()

    /** The newest reading, spaced or not. */
    private var latest: Sample? = null

    /** One poll's [uptimeSecs] (CORE stats) and [txAirSecs] (RADIO stats), read at [wallNowMs]. */
    @Synchronized
    fun onSample(
        wallNowMs: Long,
        uptimeSecs: Long,
        txAirSecs: Long,
    ) {
        if (uptimeSecs < 0 || txAirSecs < 0) return
        val sample = Sample(uptimeSecs, txAirSecs, wallNowMs - uptimeSecs * MS_PER_SECOND)
        val last = latest
        if (last != null && !sameBoot(last, sample)) {
            // A new boot: what the old one spent inside its last hour still counts. So does what it may have sent after
            // our last sample of it and before it rebooted — other clients, acks, repeats while the app was away — bounded
            // by the class's own rule that the counter rises no faster than the clock: the gap up to the new boot, at most
            // an hour. One term, the two amounts together, living to the later deadline: an hour after the new boot, or
            // after the last sample where the "new boot" reads earlier (a counter backwards, a clock moved, a stale ring).
            val spent = trailingTxSecs() ?: 0L
            val unseen = ((sample.bootWallMs - wallOf(last)) / MS_PER_SECOND).coerceIn(0L, windowSecs)
            val until = maxOf(wallOf(last), sample.bootWallMs) + windowSecs * MS_PER_SECOND
            preBoot = PreBoot(spent + unseen, until).takeIf { it.untilWallMs > wallNowMs }
            ring.clear()
            latest = null
        }
        latest = sample
        val spaced = ring.lastOrNull()
        if (spaced == null || uptimeSecs - spaced.uptimeSecs >= spacingSecs) ring.addLast(sample)
        // Keep exactly one sample at or before the window's start: the baseline the bound reads.
        val start = uptimeSecs - windowSecs
        while (ring.size >= 2 && ring[1].uptimeSecs <= start) ring.removeFirst()
    }

    /**
     * An upper bound on what the radio transmitted over the trailing [windowSecs], in whole seconds, or null before any
     * sample. Every bound below is sound, and the least is taken:
     * - since boot, the counter's own value (it was 0 then);
     * - from a sample at or before the window's start, the rise since it;
     * - from a sample inside the window, the rise since it plus at most the seconds between the window's start and it, and
     *   never more than its own count.
     *
     * One second is added for the counters' truncation to whole seconds, and the pre-boot term while it lives.
     */
    @Synchronized
    fun trailingTxSecs(): Long? {
        val now = latest ?: return null
        val start = now.uptimeSecs - windowSecs
        var bound = now.txAirSecs
        if (start > 0) {
            for (s in ring + now) {
                val rise = now.txAirSecs - s.txAirSecs
                val before = if (s.uptimeSecs <= start) 0L else minOf(s.txAirSecs, s.uptimeSecs - start)
                bound = minOf(bound, rise + before)
            }
        }
        return bound + TRUNCATION_SECS + (livePreBoot(now)?.txSecs ?: 0L)
    }

    /** The pre-boot term, if it still counts at [now]'s wall clock. */
    private fun livePreBoot(now: Sample): PreBoot? = preBoot?.takeIf { wallOf(now) < it.untilWallMs }

    /** When [sample] was read, on the wall clock. */
    private fun wallOf(sample: Sample): Long = sample.bootWallMs + sample.uptimeSecs * MS_PER_SECOND

    /**
     * The law for a band of [dutyPercent] ([app.getknit.knit.mesh.lora.LoraBand]), or null when nothing is known. Before any
     * sample of this boot a pre-boot term still alive at [wallNowMs] (one a restore kept with no usable ring) answers alone:
     * what earlier boots spent inside the hour is still spent, so it holds until the term's deadline when it reaches the
     * duty by itself (lora-reviewer's follow-up on Stage 5a-2). Without [wallNowMs], or with no live term, null.
     */
    @Synchronized
    fun verdict(
        dutyPercent: Double,
        wallNowMs: Long? = null,
    ): Verdict? {
        val now = latest ?: return preBootVerdict(dutyPercent, wallNowMs)
        val trailing = trailingTxSecs() ?: return null
        val limit = windowSecs * dutyPercent / PERCENT
        val holding = trailing >= limit
        // With no more air the bound falls as the window's start passes each sample: the hold lifts once it passes the
        // first sample after which less than the limit was spent — and, while a pre-boot term lives, not before it lapses
        // (an upper bound: the term may not have been what held it).
        val pre = livePreBoot(now)
        val releaseIn =
            if (!holding) {
                null
            } else {
                val ringLift =
                    if (trailing - (pre?.txSecs ?: 0L) < limit) {
                        0L // this boot alone is under the limit: only the pre-boot term holds it
                    } else {
                        (ring + now)
                            .firstOrNull { now.txAirSecs - it.txAirSecs + TRUNCATION_SECS < limit }
                            ?.let { maxOf(0L, it.uptimeSecs + windowSecs - now.uptimeSecs) }
                    }
                val preLift = pre?.let { maxOf(0L, (it.untilWallMs - wallOf(now) + MS_PER_SECOND - 1) / MS_PER_SECOND) } ?: 0L
                ringLift?.let { maxOf(it, preLift) }
            }
        return Verdict(trailing, limit, holding, releaseIn)
    }

    /** [verdict] from a live pre-boot term alone, before any sample of this boot. */
    private fun preBootVerdict(
        dutyPercent: Double,
        wallNowMs: Long?,
    ): Verdict? {
        val wall = wallNowMs ?: return null
        val pre = preBoot?.takeIf { wall < it.untilWallMs } ?: return null
        val limit = windowSecs * dutyPercent / PERCENT
        val holding = pre.txSecs >= limit
        val releaseIn = if (holding) (pre.untilWallMs - wall + MS_PER_SECOND - 1) / MS_PER_SECOND else null
        return Verdict(pre.txSecs, limit, holding, releaseIn)
    }

    /**
     * The ring and the newest reading, oldest first, and the pre-boot term: what the plane state persists so a restart
     * keeps its baseline.
     */
    @Synchronized
    fun snapshot(): State {
        val now = latest ?: return State(emptyList(), preBoot)
        val samples = if (ring.lastOrNull() == now) ring.toList() else ring.toList() + now
        return State(samples, preBoot)
    }

    /**
     * Puts back what [snapshot] saved. The first new sample keeps the ring only if it is the same boot; if not, the ring's
     * last hour becomes the pre-boot term as any reboot's does, and an old term lapses on its own wall-clock deadline.
     */
    @Synchronized
    fun restore(state: State) {
        ring.clear()
        latest = null
        preBoot = state.preBoot
        val sorted = state.samples.sortedBy { it.uptimeSecs }
        val first = sorted.firstOrNull() ?: return
        if (sorted.any { !sameBoot(first, it) || it.txAirSecs < 0 }) return
        sorted.forEach(ring::addLast)
        latest = sorted.last()
    }

    /** A new board: nothing known. */
    @Synchronized
    fun reset() {
        ring.clear()
        latest = null
        preBoot = null
    }

    /** Whether [next] continues [prev]'s boot: neither counter backwards, and the boot time where it was. */
    private fun sameBoot(
        prev: Sample,
        next: Sample,
    ): Boolean =
        next.uptimeSecs >= prev.uptimeSecs &&
            next.txAirSecs >= prev.txAirSecs &&
            kotlin.math.abs(next.bootWallMs - prev.bootWallMs) <= bootToleranceMs

    companion object {
        /** The duty cycle's observation period: an hour (ERC 70-03's, and every region's in [app.getknit.knit.mesh.lora.LoraBand]). */
        const val WINDOW_SECS = 3_600L

        /** The ring keeps a sample a minute at most: about 61 for the hour, whatever the poll rate. */
        const val SPACING_SECS = 60L

        /**
         * How far the boot time read off two samples may differ and still be one boot: the phone's clock adjusted under us,
         * the poll's own latency. A board reboot moves it by its whole uptime.
         */
        const val BOOT_TOLERANCE_MS = 120_000L

        private const val TRUNCATION_SECS = 1L
        private const val MS_PER_SECOND = 1_000L
        private const val PERCENT = 100.0
    }
}
