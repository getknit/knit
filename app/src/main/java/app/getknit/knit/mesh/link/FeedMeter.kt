package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.FileFeed

/**
 * How a link's file feed is really going, measured from the receiver's progress reports (ADR 2026-10.nsr7). The sender
 * cannot see its own drain: the Bluetooth stack's queue sits between its writes and the air (ADR 2026-10.8jwn), so what
 * it wrote says nothing of what arrived. The receiver's `blobprog` reports do: the bytes it holds, and when by its own
 * clock, so the rate between two reports is the drain whatever a report waited for on its way back.
 *
 * One stream at a time ([start] … [end]), plus what a stream leaves the link: [drainRatio], how far below its pace it
 * measured — the sender feeds below the drain on purpose, so a report shows min(pace, drain), and only a shortfall says
 * anything about the link. The shortfall is read only from reports whose window ran at one pace with the writer awake:
 * a pace that rose mid-window, or a `delay` the CPU slept through, would read as a slow air.
 *
 * A report is the receiver's word, so it is held to what this side can prove (#158): what it holds is never more than
 * was written ([wrote]) nor less than the stream's offset, and no sample is believed past [ceiling]. Unclamped, a
 * `held` past the stream read the feed as about to end, parked a third asker behind it, and set the link's [lastRate]
 * for the next feed; since the clock a report's `ms` is on is the peer's too, the clamp alone did not bound the rate.
 *
 * Not thread-safe: its owner ([FramedLink]) calls it under one lock. Pure, on an injected clock.
 */
internal class FeedMeter(
    private val now: () -> Long,
    // The shortest span, on the receiver's clock, a sample is measured over ([MIN_SAMPLE_MS]); a test of the arithmetic
    // alone sets it lower.
    private val minSampleMs: Long = MIN_SAMPLE_MS,
    // The fastest drain a sample is believed at, as [FeedWindow]'s: the meter is fed only by a Bluetooth link's reports,
    // where no honest feed comes near it. A test of the arithmetic alone sets it higher.
    private val ceiling: Int = FeedWindow.CEILING_BYTES_PER_SEC,
) {
    /**
     * The share of its pace the last measured stream on this link drained, when that fell short of the pace: 1.0 when
     * it kept up, or nothing has been measured. An expected rate is the pace a file would get, times this.
     */
    var drainRatio = 1.0
        private set

    /**
     * The rate the last measured stream on this link drained at, in bytes a second: 0 before one, or since a PHY step.
     * A windowed feed runs at the drain, so this is what the next one is expected to take (nsr7's phase 2).
     */
    var lastRate = 0
        private set

    /** Whether the stream going now has a rate measured from its reports. */
    val measured: Boolean get() = rate > 0

    private var key: String? = null
    private var offset = 0L
    private var length = -1L

    // The stream's bytes written so far (stream-relative): the most a report can prove the receiver holds.
    private var written = 0L
    private var startedAt = 0L
    private var lastHeld = -1L
    private var lastMs = 0L

    // Where the next sample is measured from. A report under [minSampleMs] after it takes no sample, so the meter samples
    // about every two seconds however often the receiver reports: its settle, smoothing and first-rate deadline count
    // samples and were tuned to nsr7's two-second reports, and a windowed sender's receiver reports on every chunk (ADR
    // 2026-10.5buf), whose single quarter-second sample could set a deadline that lapses mid-file and serves a second copy
    // beside it. A reader catching up a burst reports bytes ms apart, too, which would read MB/s.
    private var sampleHeld = -1L
    private var sampleMs = 0L
    private var advancedAt = 0L
    private var reportAt = 0L
    private var rate = 0
    private var pace = 0
    private var deadlineAt = 0L
    private var deadlinePace = 0

    // The writer's pace wait under way: when it began (-1: none) and what it asked for. Its `delay` stops while the CPU
    // sleeps, so whatever it runs past that is the writer's sleep, not the feed's ([feed], [resumed]).
    private var pauseAt = -1L
    private var pauseMs = 0L

    // What the writer overslept since the receiver's reports last moved: not the feed's time.
    private var sleptSinceAdvance = 0L

    // Reports since the pace last changed or the writer overslept, and the shortfall read from those that came after
    // two of them (a report's window lags the writer by the stack's queue): -1.0 until one has.
    private var steadyReports = 0

    // The pace moved since the last report: the next one's window spans both paces, so it only rebases the rate.
    private var paceMoved = false
    private var ratio = -1.0

    /** A stream of [key] begins; its offset and length come once its source is open ([sized]). */
    fun start(key: String) {
        this.key = key
        offset = 0L
        length = -1L
        written = 0L
        startedAt = now()
        lastHeld = -1L
        lastMs = 0L
        sampleHeld = -1L
        sampleMs = 0L
        paceMoved = false
        advancedAt = startedAt
        rate = 0
        pace = 0
        deadlineAt = 0L
        deadlinePace = 0
        pauseAt = -1L
        sleptSinceAdvance = 0L
        steadyReports = 0
        ratio = -1.0
    }

    /** The stream starts at [offset] of the blob and carries [length] bytes of it. */
    fun sized(
        offset: Long,
        length: Long,
    ) {
        this.offset = offset
        this.length = length
    }

    /** The writer handed the stream's next [bytes] to the stack: a report can hold that much more. */
    fun wrote(bytes: Int) {
        written += bytes
    }

    /**
     * The pace the writer feeds the stream's next chunk at: what a measured rate is held against ([drainRatio]), and
     * what the ETA reads until a report measures the new pace.
     */
    fun paced(pace: Int) {
        if (pace != this.pace) {
            steadyReports = 0
            // A rate smoothed over the old pace says little of the new one (a boost earned mid-stream): the pace stands
            // in until a report whose whole window ran at the new pace measures again (device run of 2026-10-07: a
            // sample spanning the earn read a 2 MiB feed at half its speed and sent the next asker beside it).
            if (this.pace > 0) {
                rate = 0
                paceMoved = true
            }
        }
        this.pace = pace
    }

    /** The writer goes into its pace wait between chunks, for [ms]. */
    fun pausing(ms: Long) {
        pauseAt = now()
        pauseMs = ms
    }

    /**
     * The writer came out of its pace wait. Whatever it ran past what it asked for, it slept: its `delay` stops while
     * the CPU sleeps, and no chunk went out, so the receiver had nothing new to report. Neither the stall nor the
     * deadline counts that time, and the reports around it say nothing of the air.
     */
    fun resumed() {
        val overslept = if (pauseAt < 0) 0L else now() - pauseAt - pauseMs
        pauseAt = -1L
        if (overslept <= 0) return
        if (deadlineAt != 0L) deadlineAt += overslept
        sleptSinceAdvance += overslept
        steadyReports = 0
    }

    /** The stream is over: the shortfall it measured, if any, stays as the link's [drainRatio]. */
    fun end() {
        if (ratio >= 0) drainRatio = if (ratio < KEPT_UP) ratio else 1.0
        if (rate > 0) lastRate = rate
        key = null
        pauseAt = -1L
    }

    /** The link's PHY stepped: what its last stream measured says nothing of the new one. */
    fun forgetDrain() {
        drainRatio = 1.0
        lastRate = 0
    }

    /**
     * The peer holds [reported] bytes of [key] (the whole length so far), [ms] after its header arrived. True when it
     * named the stream going now; a report of any other is stale and ignored.
     *
     * [reported] is read clamped into the stream's offset … what was written, and each sample capped at [ceiling] before it
     * is smoothed (#158): a liar's report reads as at most the sender's own writes, at no more than the fastest drain
     * believed. Clamped, never refused: a report of the stream going now still returns true, since the owner feeds the
     * window only then.
     */
    fun report(
        key: String,
        reported: Long,
        ms: Long,
    ): Boolean {
        if (key != this.key || length < 0) return false
        val held = reported.coerceIn(offset, offset + written)
        val (fromHeld, fromMs) = if (sampleHeld < 0) offset to 0L else sampleHeld to sampleMs
        if (paceMoved) {
            paceMoved = false
            steadyReports++
            sampleHeld = held
            sampleMs = ms
        } else if (ms - fromMs >= minSampleMs && held >= fromHeld) {
            val sample = ((held - fromHeld) * MS_PER_SECOND / (ms - fromMs)).coerceIn(0L, ceiling.toLong()).toInt()
            rate = if (rate == 0) sample else (rate * (SMOOTHING - 1) + sample) / SMOOTHING
            if (steadyReports >= STEADY_REPORTS && pace > 0) {
                val r = minOf(1.0, sample.toDouble() / pace)
                ratio = if (ratio < 0) r else (ratio * (SMOOTHING - 1) + r) / SMOOTHING
            }
            steadyReports++
            sampleHeld = held
            sampleMs = ms
        }
        if (held > maxOf(lastHeld, offset)) {
            advancedAt = now()
            sleptSinceAdvance = 0L
        }
        if (held >= lastHeld) {
            lastHeld = held
            lastMs = ms
            reportAt = now()
        }
        return true
    }

    /**
     * The stream as a [FileFeed]. [left] is what it still has to write, [wroteAt] its last write, and [pace] the rate its
     * pace would feed at (the owner's settling pace for its first chunks): the estimate until a report measures one.
     * [settled] is whether the pace is past its settle. Time the writer slept through its own pace wait ([pausing] …
     * [resumed], or under way now) counts toward neither the stall nor the deadline: no chunk went out.
     *
     * - **Stall:** nothing moved for [STALL_MS] — the receiver's reports once this stream has one, its writes before,
     *   since the stack takes them before the air does and a stream queued behind another's tail reports nothing yet.
     *   A writer pacing normally is no exemption: a receiver that stops reading with its link up stalls in 15 s.
     * - **Deadline:** set once, at the first measured rate, or from the pace when this stream has had no report for
     *   [STALL_MS] (a peer that never reports, or whose report never came): ETA × 5/4 + slack. Pushed back when the
     *   pace drops below what it was set at, which is this sender splitting its budget over another feed.
     */
    fun feed(
        left: Long,
        wroteAt: Long,
        pace: Int,
        settled: Boolean,
    ): FileFeed {
        val t = now()
        val measured = rate > 0
        val whole = maxOf(length, 0L)
        val delivered = if (lastHeld >= 0) lastHeld - offset else whole - left
        val remaining = (whole - delivered).coerceAtLeast(0L)
        val eta = etaNow(t, remaining, pace, settled)
        val asleep = if (pauseAt < 0) 0L else (t - pauseAt - pauseMs).coerceAtLeast(0L)
        val quiet = if (lastHeld >= 0) t - advancedAt - sleptSinceAdvance else t - wroteAt
        val stallIn = STALL_MS - (quiet - asleep)
        setDeadline(t, eta, remaining, pace, due = measured || (settled && lastHeld < 0 && t - startedAt >= STALL_MS))
        val deadlineIn = if (deadlineAt == 0L) null else deadlineAt + asleep - t
        val live = stallIn >= 0 && (deadlineIn == null || deadlineIn >= 0)
        val recheck = listOfNotNull(stallIn.takeIf { it > 0 }, deadlineIn?.takeIf { it > 0 }).minOrNull()
        return FileFeed(etaMs = eta, live = live, measured = measured, recheckMs = recheck)
    }

    /**
     * The ETA of [remaining] bytes at the measured rate, counted from the last report (the bytes it left have been
     * draining since), or at [pace] before one. Unsettled, the pace is the owner's settling guess (a boost not earned
     * yet), and a rate measured before the earn shows only the base: the faster of the two stands until the settle ends.
     */
    private fun etaNow(
        t: Long,
        remaining: Long,
        pace: Int,
        settled: Boolean,
    ): Long {
        val measured = rate > 0
        val r = if (measured && settled) rate else maxOf(rate, pace)
        val eta = etaOf(remaining, r)
        return if (lastHeld >= 0 && measured) (eta - (t - reportAt)).coerceAtLeast(0L) else eta
    }

    /** Sets the deadline once it is [due], and moves it back when the pace dropped below what it was set at. */
    private fun setDeadline(
        t: Long,
        eta: Long,
        remaining: Long,
        pace: Int,
        due: Boolean,
    ) {
        if (deadlineAt == 0L && due) {
            deadlineAt = t + deadlineFor(eta)
            deadlinePace = pace
        } else if (deadlineAt != 0L && pace in 1 until deadlinePace) {
            deadlineAt = maxOf(deadlineAt, t + deadlineFor(maxOf(eta, etaOf(remaining, pace))))
            deadlinePace = pace
        }
    }

    private fun etaOf(
        bytes: Long,
        rate: Int,
    ): Long = if (rate <= 0) 0L else bytes * MS_PER_SECOND / rate

    private fun deadlineFor(eta: Long): Long = eta * DEADLINE_NUM / DEADLINE_DEN + DEADLINE_SLACK_MS

    companion object {
        // A feed with no progress this long is over for the asks parked behind it: no report moved (once the stream has
        // one), or no write went through (before — a full stack buffer blocks the write).
        const val STALL_MS = 15_000L

        // A feed's deadline, set once from its first measured ETA: ETA × 5/4, plus slack for a slow start or a
        // report's lag. An 8 MiB file at 18 KiB/s measures about 7.6 min and gets about 10.
        const val DEADLINE_NUM = 5L
        const val DEADLINE_DEN = 4L
        const val DEADLINE_SLACK_MS = 30_000L

        // A stream that drained this much of its pace or more kept up: its link is limited by the pace, not the air.
        private const val KEPT_UP = 0.9

        // Reports skipped after a pace change or an overslept wait before one is read against the pace: the bytes a
        // report counts were written up to the stack's queue earlier.
        private const val STEADY_REPORTS = 2

        // The shortest span, on the receiver's clock, a sample is measured over: a paced feed's report interval
        // (RxReports.EVERY_MS), so a windowed feed's meter samples as nsr7 tuned it.
        const val MIN_SAMPLE_MS = 2_000L

        // A new sample counts a quarter: a connection event or a report's batching moves the rate little.
        private const val SMOOTHING = 4
        private const val MS_PER_SECOND = 1_000L
    }
}
