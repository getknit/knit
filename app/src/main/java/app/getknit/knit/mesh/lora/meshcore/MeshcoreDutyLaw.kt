package app.getknit.knit.mesh.lora.meshcore

/**
 * The regional duty law on a MeshCore board, read off the board's own transmit counter (ADR 2026-11.gsqu's Stage 5a-1
 * amendment). MeshCore's firmware enforces no region (`Dispatcher::updateTxBudget` is a 50 % token bucket, spike Q4), so
 * Knit's own ledger is the only governor there — and the ledger books only Knit's frames. This is the check the ledger
 * cannot be: RADIO stats' `tx_air` counts **everything** the radio transmitted, every client's sends, our board's acks and
 * PATH returns, and any repeats (Q5 calls it the ground truth). While the trailing hour's **observed** `tx_air` reaches the
 * band's duty ([verdict]), every Knit frame is to be held. A band with no duty limit (100 %, US and ANZ 915) never holds.
 *
 * **Only air Knit observed** (the maintainer's decision, 2026-11-24, amending gsqu's law check). Abiding by local law is
 * the user's burden; this check is a courtesy, and it never assumes the worst about air it did not see. What counts is
 * the counter's rise between two samples Knit took of one boot — a persisted ring of that boot across an app restart is
 * still observation, the counter being real evidence between two of our samples. Never the counter's value since boot,
 * never the seconds before a first sample, never the gap between our last sample of a boot and its reboot. So a first
 * connection to a board that has been up and transmitting for hours starts at zero, and a reboot hands out a fresh hour
 * save what the old boot was seen to spend. The field case that set it: a US 915 board replugged after its battery ran
 * flat read `unseen` as a whole hour, built a 3,601-s pre-boot term against a 3,600-s limit, and held a typed DM until it
 * went stale — on a band a radio cannot exceed.
 *
 * **The board's uptime is the clock.** A sample is `(uptime, tx_air)` off one poll's CORE and RADIO stats, so the window
 * is measured on the board, which is what transmitted, and it survives an **app** restart: the board's counter does not
 * reset when the phone's process dies, and a persisted ring ([snapshot] / [restore]) gives the next process its baseline
 * back. A **board** reboot zeroes both: a sample that reads earlier uptime, a lower counter, or a boot time (wall clock −
 * uptime) moved by more than [bootToleranceMs] from the ring's is a different boot, and the ring is dropped — but not
 * what it was seen to spend. The old boot's observed trailing hour at our last sample of it is carried as a **pre-boot
 * term** ([PreBoot]) on the wall clock, added to every bound until an hour after that last sample, when all of it has left
 * the window; each boot's term keeps its own deadline. Nothing compares the wall clock with the phone's elapsed clock.
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
     * The law's answer for a band of a given duty: what the trailing hour was seen to spend at most ([trailingTxSecs]),
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
     * What one earlier boot was seen to spend inside its last hour, carried past a reboot: [txSecs] — its observed trailing
     * hour at our last sample of it, never a second we did not see — counted until [untilWallMs] on the wall clock, an
     * hour after that last sample, when all of it has left the window. Each boot's term keeps its own deadline
     * (lora-reviewer, 2026-11-24): folded into a later one, an earlier boot's air would count past its own hour.
     */
    data class PreBoot(
        val txSecs: Long,
        val untilWallMs: Long,
    )

    /** What the plane state persists ([snapshot] / [restore]): the ring with the newest reading, and the pre-boot terms. */
    data class State(
        val samples: List<Sample>,
        val preBoots: List<PreBoot>,
    )

    /** The terms carried from earlier boots, each to its own deadline; at most [MAX_PRE_BOOTS], the earliest dropped first. */
    private val preBoots = mutableListOf<PreBoot>()

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
            // A new boot: what the old one was seen to spend inside its last hour still counts, until that hour has passed
            // our last sample of it. Nothing for the stretch after that sample: what the radio did unseen is not evidence
            // (the maintainer's decision, 2026-11-24). Earlier boots' terms keep their own deadlines.
            val observed = observedTxSecs(samplesTo(last), last.uptimeSecs - windowSecs)
            if (observed > 0L) preBoots += PreBoot(observed + TRUNCATION_SECS, wallOf(last) + windowSecs * MS_PER_SECOND)
            prune(wallNowMs)
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

    /** Drops the terms lapsed at [wallMs], then the earliest past [MAX_PRE_BOOTS] (an under-count, never an over-count). */
    private fun prune(wallMs: Long) {
        preBoots.removeAll { it.untilWallMs <= wallMs }
        preBoots.sortBy { it.untilWallMs }
        while (preBoots.size > MAX_PRE_BOOTS) preBoots.removeAt(0)
    }

    /**
     * What the radio was seen to transmit over the trailing [windowSecs], in whole seconds, or null before any sample: the
     * least the counter's rises between samples of this boot Knit took put inside the window, never its value since boot
     * nor any second before our first sample (the maintainer's decision, 2026-11-24). One second is added for the
     * counters' truncation to whole seconds once a span has been observed, and the pre-boot terms while they live. A lone
     * first sample reads 0.
     */
    @Synchronized
    fun trailingTxSecs(): Long? {
        val now = latest ?: return null
        return projected(samplesTo(now), now, 0L)
    }

    /** The ring and [now], oldest first. */
    private fun samplesTo(now: Sample): List<Sample> = if (ring.lastOrNull() == now) ring.toList() else ring.toList() + now

    /**
     * The trailing hour as it will read [aheadSecs] of the board's seconds after [now] if the radio sends nothing more: the
     * observed air still inside the window then, the truncation second once a span was observed, and the terms alive then.
     * Never rises with [aheadSecs], so [releaseIn] can search it.
     */
    private fun projected(
        samples: List<Sample>,
        now: Sample,
        aheadSecs: Long,
    ): Long {
        val spanned = samples.size >= 2 && samples.last().uptimeSecs > samples.first().uptimeSecs
        val observed = observedTxSecs(samples, now.uptimeSecs + aheadSecs - windowSecs)
        return observed + (if (spanned) TRUNCATION_SECS else 0L) + liveTxSecs(wallOf(now) + aheadSecs * MS_PER_SECOND)
    }

    /**
     * The least observed air the window starting at [start] holds: the rise from the baseline (the last sample at or before
     * [start], else the first) to the newest, less what of the rise to the sample after the baseline can have come before
     * [start]. The counter rises no faster than the clock, so that is at most `start − baseline`; the rest of that
     * straddling rise is counted, and every later rise in full (lora-reviewer, 2026-11-24: a long gap between two samples
     * is not placed inside the hour). Nothing before the baseline is counted.
     */
    private fun observedTxSecs(
        samples: List<Sample>,
        start: Long,
    ): Long {
        val at = samples.indexOfLast { it.uptimeSecs <= start }.coerceAtLeast(0)
        val base = samples.getOrNull(at) ?: return 0L
        val next = samples.getOrNull(at + 1) ?: return 0L
        val early = minOf(next.txAirSecs - base.txAirSecs, maxOf(0L, start - base.uptimeSecs))
        return (samples.last().txAirSecs - base.txAirSecs - early).coerceAtLeast(0L)
    }

    /** The terms still alive at [wallMs], summed. */
    private fun liveTxSecs(wallMs: Long): Long = preBoots.filter { wallMs < it.untilWallMs }.sumOf { it.txSecs }

    /** When [sample] was read, on the wall clock. */
    private fun wallOf(sample: Sample): Long = sample.bootWallMs + sample.uptimeSecs * MS_PER_SECOND

    /**
     * The law for a band of [dutyPercent] ([app.getknit.knit.mesh.lora.LoraBand]), or null when nothing is known. Before any
     * sample of this boot the pre-boot terms still alive at [wallNowMs] (ones a restore kept with no usable ring) answer
     * alone: what earlier boots were seen to spend inside the hour is still spent, so they hold until their deadlines when
     * they reach the duty by themselves (lora-reviewer's follow-up on Stage 5a-2). Without [wallNowMs], or with no live
     * term, null.
     *
     * A band of [dutyPercent] 100 or more sets no duty (US and ANZ 915, [app.getknit.knit.mesh.lora.LoraBand.US_915]): a
     * radio cannot exceed it, so it never holds, whatever the ring or the terms read.
     */
    @Synchronized
    fun verdict(
        dutyPercent: Double,
        wallNowMs: Long? = null,
    ): Verdict? {
        val limit = windowSecs * dutyPercent / PERCENT
        val now = latest
        val ahead: (Long) -> Long =
            if (now != null) {
                val samples = samplesTo(now)
                ({ projected(samples, now, it) })
            } else {
                val wall = wallNowMs ?: return null
                if (liveTxSecs(wall) == 0L) return null
                ({ liveTxSecs(wall + it * MS_PER_SECOND) })
            }
        val trailing = ahead(0L)
        val holding = limited(dutyPercent) && trailing >= limit
        // Past the window and every term's deadline nothing is left but the truncation second.
        val wallNow = now?.let(::wallOf) ?: wallNowMs ?: 0L
        val termsLeftSecs = ((preBoots.maxOfOrNull { it.untilWallMs } ?: wallNow) - wallNow).coerceAtLeast(0L) / MS_PER_SECOND
        val horizon = windowSecs + 1 + termsLeftSecs
        return Verdict(trailing, limit, holding, if (holding) releaseIn(ahead, limit, horizon) else null)
    }

    /**
     * In how many of the board's seconds the hold lifts if nothing more is sent: the first second at which [ahead] reads
     * under [limit], found by halving (it never rises), or null when not even [horizon] — past the window and every term's
     * deadline — gets there (a limit under the truncation second).
     */
    private fun releaseIn(
        ahead: (Long) -> Long,
        limit: Double,
        horizon: Long,
    ): Long? {
        if (ahead(horizon) >= limit) return null
        var held = 0L
        var lifted = horizon
        while (lifted - held > 1) {
            val mid = held + (lifted - held) / 2
            if (ahead(mid) >= limit) held = mid else lifted = mid
        }
        return lifted
    }

    /** Whether a band of [dutyPercent] limits the duty at all: under 100 %. */
    private fun limited(dutyPercent: Double): Boolean = dutyPercent < PERCENT

    /**
     * The ring and the newest reading, oldest first, and the pre-boot terms: what the plane state persists so a restart
     * keeps its baseline.
     */
    @Synchronized
    fun snapshot(): State {
        val now = latest ?: return State(emptyList(), preBoots.toList())
        return State(samplesTo(now), preBoots.toList())
    }

    /**
     * Puts back what [snapshot] saved. The first new sample keeps the ring only if it is the same boot; if not, the ring's
     * observed last hour becomes a pre-boot term as any reboot's does, and each old term lapses on its own wall-clock
     * deadline. A term built under the rule before 2026-11-24 (its unseen gap counted) is the caller's to drop, not to pass
     * here: this class cannot tell one from the other (`MeshcoreHooks.stateOf`).
     */
    @Synchronized
    fun restore(state: State) {
        ring.clear()
        latest = null
        preBoots.clear()
        preBoots += state.preBoots.filter { it.txSecs > 0L }
        prune(Long.MIN_VALUE)
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
        preBoots.clear()
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

        /** The most pre-boot terms kept: a boot loop past it drops the earliest, an under-count the policy allows. */
        const val MAX_PRE_BOOTS = 8

        private const val TRUNCATION_SECS = 1L
        private const val MS_PER_SECOND = 1_000L
        private const val PERCENT = 100.0
    }
}
