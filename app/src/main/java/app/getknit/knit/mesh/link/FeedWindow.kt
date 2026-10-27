package app.getknit.knit.mesh.link

/**
 * A Bluetooth file feed clocked by its receiver's reports instead of a fixed pace (ADR 2026-10.nsr7's phase 2). The
 * sender cannot see the stack's queue, but the receiver's `blobprog` reports say how much it holds. So, as TCP does with
 * its acks, the feed bounds the bytes written and not yet reported held — the stack's queue, the air, and what landed
 * since the last report — to a window: the drain over the round trip, plus [queueMs] of it queued. The air stays busy,
 * a frame queued behind the file waits about [queueMs] past the round trip on any link speed, and the feed runs at
 * whatever the link drains — dark, lit, crowded or 2M — where a fixed pace overfeeds a slow pair and starves a fast one
 * (the A/B of 2026-10-07: 28 KiB/s held texts minutes behind a photo, 4 KiB/s took 111 s where the same dark pair drained
 * a photo in 12 s).
 *
 * The estimates are BBR's:
 * - **Drain:** Δheld ÷ Δms between reports on the receiver's clock, so a report's trip back costs it nothing, over at
 *   least [MIN_SAMPLE_MS] (a reader catching up a burst reports bytes ms apart). The rate is the largest of the last
 *   [SAMPLES], a few seconds of them (noise and a connection event's batching only ever pull a sample down), held to
 *   [ceiling]. Four, about a second, let a feed that was briefly the limit (a dark sender waking late for a report)
 *   drag its own window down in a spiral (device sweep 6: 20 → 4.5 KB/s on a link that drained 15). A link that really
 *   slowed is read from the round trip instead: a chunk back after twice the round trip and its queue waited behind a
 *   growing queue, and the filter keeps only what it measures next.
 * - **Round trip:** from a chunk's write to the report that first holds all of it, on the sender's clock; the smallest
 *   in the last [RTT_WINDOW_MS]. A sample taken behind the standing queue counts the queue too, so the smallest is the
 *   one that does not; and when none smaller has come for that long, the window drops to one smallest chunk for a round
 *   ([probingRtt]) so the queue drains and a chunk's sample reads the air alone. That is also how the estimate rises
 *   when the link slows mid-file (a 15 → 255 ms interval step). Without the round trip the window covered only the gap
 *   between reports, and a lit sender to a dark receiver, whose reports came back slowly, got half the link (device
 *   sweep 4, 2026-10-07: 2.9 and 4.0 KB/s on a link that drained 5.3 and 8.8).
 * - **Growth:** a window larger than the drain over the round trip delivers more than the rate in a round, so while the
 *   feed is the limit each round raises the rate, until the air is the limit and the extra, about [queueMs] of it,
 *   stands as the queue. No timer drains the estimate: a sender whose CPU sleeps (a dark phone) is woken by the reports
 *   it waits on.
 * - **Silence:** before the first report the window is [INITIAL_WINDOW] (four smallest chunks). With no report for [NO_REPORT_MS] — a peer
 *   that never reports, or whose reports stopped — the owner paces the feed the old way ([pacing]) until one comes. A
 *   report that lands no new whole chunk is not hearing from the receiver ([report]): one repeating its `held`, or
 *   adding a byte at a time, held the window shut for the link's life (#149).
 * - **Two-second reporter:** a receiver whose reports come [SLOW_REPORT_MS] apart, each covering several chunks, is
 *   paced too ([slowReporter]), in chunks small enough ([pacedChunkBytes]) that a receiver reporting every chunk answers
 *   each well inside that gap. [RECOVER_REPORTS] such answers in a row, each one chunk at the paced spacing, give the
 *   window back: a dozing receiver's late reports read like a two-second reporter's, and one woken mid-file (its user
 *   picking it up to look) drains at its full rate again (the D2 run of 2026-10-08: a dozing P9 left a photo paced at
 *   4 KiB/s to its end). A backlog read in a burst does not count: its reports come milliseconds apart.
 *
 * - **Floors:** the smallest chunk ([minChunk]) sizes the rest — the window's floor two of them, the starting window
 *   four, a starting chunk two. 2 KiB on 1M and 2M; a Coded link's is smaller, so its slower air (about 1 KB/s at the
 *   edge of the 2026-10-02 walk) holds no more queue than its fixed pace did (the Coded amendment of 2026-10-08).
 *
 * One stream at a time ([start]). Not thread-safe: its owner ([FramedLink]) calls it under one lock. Pure, clocked by
 * the `now` each call passes.
 */
internal class FeedWindow(
    private val ceiling: Int = CEILING_BYTES_PER_SEC,
) {
    /** The queue, in ms of the rate, the feed keeps ahead of the receiver. Read before every chunk from the link's pace. */
    var queueMs: Long = QUEUE_MS

    /**
     * The smallest chunk the feed writes, which sizes the window's floor, its start and a probe's room: [MIN_CHUNK_BYTES]
     * on 1M and 2M, less on Coded. Read before every chunk from the link's pace, so a PHY step takes it at the next.
     * Never under [SMALLEST_CHUNK_BYTES]: a dark receiver reports on the chunk that brings its report's bytes
     * (`RxReports.WINDOWED_EVERY_BYTES`), and the window's floor of two chunks smaller than half that could all land
     * unreported, leaving the feed to wait out [NO_REPORT_MS] (the simulation, at 512 B and 2.7 KB/s).
     */
    var minChunk: Int = MIN_CHUNK_BYTES
        set(value) {
            field = value.coerceIn(SMALLEST_CHUNK_BYTES, LinkFraming.FILE_CHUNK_BYTES / INITIAL_CHUNKS)
        }

    private val minWindow get() = MIN_WINDOW_CHUNKS * minChunk
    private val initialWindow get() = INITIAL_WINDOW_CHUNKS * minChunk
    private val initialChunk get() = INITIAL_CHUNKS * minChunk

    /** The drain measured from the receiver's reports, in bytes a second: 0 until a sample is taken. */
    var rate = 0
        private set

    /** The round trip the window covers, in ms: [INITIAL_RTT_MS] until measured. */
    var rttMs = INITIAL_RTT_MS
        private set

    /** Whether the window is down to one smallest chunk for a round, so the next round trip measured reads the air alone. */
    var probingRtt = false
        private set

    private var offset = 0L
    private var written = 0L
    private var lastHeld = -1L

    // When the stream began or a report last landed a chunk, on the sender's clock: [pacing]'s silence counts from it.
    private var heardAt = 0L

    // Where the next drain sample is measured from: the last report a sample was taken at (or the stream's start).
    private var sampleHeld = -1L
    private var sampleMs = 0L
    private val samples = IntArray(SAMPLES)
    private var sampleCount = 0
    private var nextSample = 0

    // The chunks written and not yet reported held: where each ends (stream-relative), when it went out, and how much was
    // unreported ahead of it then.
    private val outEnds = ArrayDeque<Long>()
    private val outAt = ArrayDeque<Long>()
    private val outAhead = ArrayDeque<Long>()

    // When [rttMs] was last measured as the smallest, and, while probing, what was written when the probe began: the
    // probe ends at the round trip of a chunk written after it. Kept across the link's streams, as BBR keeps its round
    // trip per connection: a file fed in full still has its tail in the stack's queue (ADR 2026-10.nxfb), so the next
    // stream's first sample counts it, and taken fresh each stream the estimate grew with every file sent back to back
    // until it locked at [MAX_RTT_MS] (the bluetooth-reviewer's finding). [forgetLink] drops it on a PHY step.
    private var rttAt = -1L
    private var rttRaw = Long.MAX_VALUE
    private var probeFrom = 0L

    // What was written (stream-relative) when a step to or from Coded forgot the drain, while the receiver has not yet
    // reported all of it; -1 else. Records sized for 1M take seconds each on Coded, and the drain they would be timed
    // by is gone: [silenceMs] waits them out rather than pacing on every one.
    private var stepBacklog = -1L

    // The receiver's last report time on its own clock, and how many reports in a row came [SLOW_REPORT_MS] or more
    // apart: a receiver that reports every two seconds (its window switch off, or a build that predates the window)
    // never reports the starting window, and a window waiting on it ran in three-second fits below the paced base
    // (the bluetooth-reviewer's finding). Two such gaps and the stream is paced ([pacing]) until [RECOVER_REPORTS]
    // reports in a row come one chunk at a time, inside the gap: a two-second reporter never sends one.
    private var lastReportMs = -1L
    private var slowGaps = 0
    private var onTimeReports = 0

    // The shortest gap, on the receiver's clock, an on-time report may follow the last by: half the paced chunks'
    // spacing ([chunkFor]). A dozing receiver working through its backlog reports each chunk milliseconds apart, and
    // three of those would give the window back to a receiver still asleep (the bluetooth-reviewer's finding).
    private var onTimeFloorMs = 0L

    // Where the stream stood (stream-relative) when it was last taken for a two-second reporter: only a chunk written
    // after it can be answered on time. The window's chunks still out at that moment are a backlog the floor cannot
    // judge, since no paced chunk had set it yet (the bluetooth-reviewer's second pass).
    private var pacedFrom = 0L

    /** Whether the stream paces a receiver taken for a two-second reporter, until it answers chunk by chunk again. */
    var slowReporter = false
        private set

    /** A stream starts at [offset] of its blob, at [now]. */
    fun start(
        offset: Long,
        now: Long,
    ) {
        this.offset = offset
        written = 0L
        lastHeld = -1L
        heardAt = now
        sampleHeld = -1L
        sampleMs = 0L
        rate = 0
        sampleCount = 0
        nextSample = 0
        outEnds.clear()
        outAt.clear()
        outAhead.clear()
        probingRtt = false
        lastReportMs = -1L
        slowGaps = 0
        onTimeReports = 0
        onTimeFloorMs = 0L
        pacedFrom = 0L
        slowReporter = false
        stepBacklog = -1L
    }

    /**
     * The link stepped PHY: its round trip is another's. With [drain] — a step to or from Coded, whose air runs at a
     * fraction of 1M's — so is its drain, mid-stream too: the filter keeps the largest of its samples, so a 1M rate kept
     * past a step to Coded sized the window for 1M over Coded's round trip, a queue of tens of seconds of the slower air
     * before a chunk came back slow enough to clear it. The window then probes: one chunk at a time until one written
     * after the step comes back, so its round trip reads the new air and not the backlog the old one left, and the drain
     * is measured afresh from there (the bluetooth-reviewer's finding). A step between 1M and 2M keeps the drain: the
     * filter follows a halving by itself, and a doubling grows the window.
     */
    fun forgetLink(drain: Boolean) {
        rttMs = INITIAL_RTT_MS
        rttAt = -1L
        rttRaw = Long.MAX_VALUE
        probingRtt = false
        if (!drain) return
        rate = 0
        sampleCount = 0
        nextSample = 0
        probingRtt = true
        probeFrom = written
        stepBacklog = written
    }

    /** [bytes] more of the stream went to the socket at [now]. */
    fun wrote(
        bytes: Int,
        now: Long,
    ) {
        outAhead.addLast(queued())
        written += bytes
        outEnds.addLast(written)
        outAt.addLast(now)
    }

    /**
     * The receiver holds [held] bytes of the blob (its whole length so far, a resume's prefix included), [ms] after the
     * stream's header reached it, and the report came at [now]. A report older than the last, or naming more than was
     * written, moves nothing past what it can prove. One that lands no new whole chunk is not hearing from the receiver,
     * and takes no sample that resets the drain: a receiver reports only once a whole record landed and its bytes grew,
     * and a record is one chunk, so an honest report always lands one. A peer repeating one `held`, or adding a byte to
     * it, every few seconds kept [pacing] off and the window shut for the link's life (#149, ADR 2026-10.5buf's
     * amendment of 2026-10-27); now it is paced after [silenceMs] and the file finishes.
     */
    fun report(
        held: Long,
        ms: Long,
        now: Long,
    ) {
        val proven = held.coerceAtMost(offset + written)
        if (proven < maxOf(lastHeld, offset)) return
        val standing = !probingRtt && offset + written - proven > STANDING_WINDOWS * target()
        // The round trip of the last chunk this report holds whole.
        var sentAt = -1L
        var end = 0L
        var ahead = 0L
        var landed = 0
        while (outEnds.isNotEmpty() && outEnds.first() <= proven - offset) {
            end = outEnds.removeFirst()
            sentAt = outAt.removeFirst()
            ahead = outAhead.removeFirst()
            landed++
        }
        noteReportGap(ms, landed, end)
        val slowed = sentAt >= 0 && noteTrip(now - sentAt, end, ahead, now)
        if (stepBacklog >= 0 && proven - offset >= stepBacklog) stepBacklog = -1L
        if (!probingRtt && rttAt >= 0 && now - rttAt > RTT_WINDOW_MS) {
            probingRtt = true
            probeFrom = written
        }
        sampleDrain(proven, ms, reset = landed > 0 && (standing || slowed))
        lastHeld = proven
        if (landed > 0) heardAt = now
    }

    /**
     * A chunk ending at [end] came back [ms] after its write, with [ahead] unreported ahead of it then: its round trip is
     * noted ([noteRtt]), and true if it says the link slowed. A chunk back after half again what it should have taken —
     * the round trip, the queue, and what was ahead of it at the drain — waited behind a queue that grew, and the drain's
     * filter keeps only what it measures next. Judged by what was ahead, not by the window: a round trip carried over
     * from a faster file would otherwise read every chunk at a slow pair's floor as slowed (the bluetooth-reviewer's
     * finding).
     */
    private fun noteTrip(
        ms: Long,
        end: Long,
        ahead: Long,
        now: Long,
    ): Boolean {
        val expectedMs = rttMs + queueMs + if (rate > 0) ahead * MS_PER_SECOND / rate else 0L
        val slowed = !probingRtt && rttAt >= 0 && ms * SLOWED_DEN > SLOWED_NUM * expectedMs
        noteRtt(ms, end, ahead, now)
        return slowed
    }

    /**
     * A drain sample from the last sampled report to [proven] held at [ms] on the receiver's clock, if that spans
     * [MIN_SAMPLE_MS]; none while probing, whose window is smaller than the pipe. [reset] keeps only this one: a standing
     * queue or a slow round trip says the air set it, and the larger ones before it are a link that slowed.
     */
    private fun sampleDrain(
        proven: Long,
        ms: Long,
        reset: Boolean,
    ) {
        val (fromHeld, fromMs) = if (sampleHeld < 0) offset to 0L else sampleHeld to sampleMs
        if (ms - fromMs < MIN_SAMPLE_MS) return
        if (!probingRtt) {
            val sample = ((proven - fromHeld) * MS_PER_SECOND / (ms - fromMs)).coerceIn(0L, ceiling.toLong()).toInt()
            if (reset) {
                sampleCount = 0
                nextSample = 0
            }
            samples[nextSample] = sample
            nextSample = (nextSample + 1) % SAMPLES
            sampleCount = minOf(sampleCount + 1, SAMPLES)
            rate = samples.take(sampleCount).max()
        }
        sampleHeld = proven
        sampleMs = ms
    }

    /**
     * A report [ms] after the header, on the receiver's clock, covering [landed] more chunks: two gaps of [SLOW_REPORT_MS]
     * that each covered more than one chunk mark a two-second reporter. A windowed receiver reports every chunk, so its
     * gaps are the chunks' own, and long on a slow link or across a probe: read by time alone they paced a slow link's
     * window back to the fixed pace (the bluetooth-reviewer's finding). Once marked, [RECOVER_REPORTS] reports in a
     * row unmark it, each one chunk written since ([pacedFrom]), under [SLOW_REPORT_MS] after the last and no sooner than
     * [onTimeFloorMs]: [pacedChunkBytes] keeps the paced chunks that close, and the floor keeps a backlog's burst out.
     */
    private fun noteReportGap(
        ms: Long,
        landed: Int,
        end: Long,
    ) {
        if (lastReportMs >= 0) {
            val gap = ms - lastReportMs
            val slowGap = slowGapMs()
            slowGaps = if (gap >= slowGap && landed > 1) slowGaps + 1 else 0
            if (slowGaps >= SLOW_GAPS && !slowReporter) {
                slowReporter = true
                pacedFrom = written
            }
            if (slowReporter) {
                val onTime = gap in onTimeFloorMs until slowGap && landed == 1 && end > pacedFrom
                onTimeReports = if (onTime) onTimeReports + 1 else 0
                if (onTimeReports >= RECOVER_REPORTS) {
                    slowReporter = false
                    slowGaps = 0
                    onTimeReports = 0
                }
            }
        }
        lastReportMs = maxOf(lastReportMs, ms)
    }

    /**
     * A chunk ending at [end] (stream-relative) came back [ms] after its write, with [ahead] unreported ahead of it then.
     * The smallest is the trip; a probe's first chunk back, written with at most one smallest chunk ahead of it, is the
     * trip whatever it reads, and ends the probe. A chunk the paced fallback wrote meanwhile, behind a backlog (a step to
     * Coded's, whose 1M records outlast the silence), does not: its trip is the backlog's (the bluetooth-reviewer's
     * finding). It ends at that first chunk because a probe of a whole round more cost a fifth of a slow-report link
     * (the simulation), and one waiting for an empty pipe left the air idle a round each time. Kept across the link's
     * files, the estimate can lag a rise between them (a peer's screen going off lets the link boost go, with no PHY
     * step) for up to [RTT_WINDOW_MS], until the probe.
     */
    private fun noteRtt(
        ms: Long,
        end: Long,
        ahead: Long,
        now: Long,
    ) {
        if (probingRtt && end > probeFrom && ahead <= minChunk) {
            rttRaw = ms
            rttMs = ms.coerceIn(MIN_RTT_MS, MAX_RTT_MS)
            rttAt = now
            probingRtt = false
            return
        }
        // No larger, compared before the clamp: a steady link's samples keep the estimate fresh, and one clamped to the
        // ceiling must not.
        if (rttAt < 0 || ms <= rttRaw) {
            rttRaw = ms
            rttMs = ms.coerceIn(MIN_RTT_MS, MAX_RTT_MS)
            rttAt = now
        }
    }

    /**
     * The writer slept [ms] past the wait it asked for (its `delay` stops while the CPU sleeps). With nothing unreported
     * the receiver had nothing to report, and that time does not count toward [pacing]'s silence; with a window out it
     * owes a report, and the silence counts (the bluetooth-reviewer's finding). A backstop: every wait starts with
     * something out, and the report that empties it wakes the writer, so only a receiver silent on a window it owes
     * reaches it.
     */
    fun slept(
        ms: Long,
        now: Long,
    ) {
        if (ms > 0 && queued() == 0L) heardAt = minOf(now, heardAt + ms)
    }

    /**
     * Whether the owner paces the feed the old way: the receiver silent for [NO_REPORT_MS] (until it reports), or one that
     * reports only every two seconds (for the rest of the stream).
     */
    fun pacing(now: Long): Boolean = slowReporter || now - heardAt >= silenceMs()

    /**
     * How long [records] of the smallest chunk take to cross at the measured rate; 0 before one. A receiver reports a
     * chunk only once all of it has crossed, so on Coded's slow air at the far edge (under about 0.7 KB/s) one chunk
     * outlasts [NO_REPORT_MS], and two [SLOW_REPORT_MS]: read by those alone, every chunk tripped the silence, and a
     * feed taken for a two-second reporter never got its window back (the bluetooth-reviewer's finding). On 1M and 2M,
     * at any drain over about 2 KB/s, the constants stand.
     */
    private fun recordsMs(records: Int): Long =
        if (rate > 0) minOf(records * minChunk * MS_PER_SECOND / rate, records * MAX_CHUNK_CROSSING_MS) else 0L

    /** The report gap that marks a two-second reporter, and bounds an on-time report: two chunks' time at least. */
    private fun slowGapMs(): Long = maxOf(SLOW_REPORT_MS, recordsMs(SLOW_RECORDS))

    /**
     * The silence after which the feed is paced: three chunks' time at least, and three chunks' longest crossing while a
     * step to Coded's backlog is still out ([stepBacklog]).
     */
    private fun silenceMs(): Long {
        val silence = maxOf(NO_REPORT_MS, recordsMs(SILENT_RECORDS))
        return if (stepBacklog >= 0) maxOf(silence, SILENT_RECORDS * MAX_CHUNK_CROSSING_MS) else silence
    }

    /**
     * The bytes the window lets be written and not yet reported held: the rate over the round trip plus [queueMs],
     * one smallest chunk's room while probing the round trip (so at most one chunk is ahead of the next: every record is
     * at least [minChunk]), or the starting window before a rate. Never under two smallest chunks, so a slow link never
     * stops and waits for each report.
     */
    fun target(): Long =
        when {
            probingRtt -> minChunk + 1L
            rate <= 0 -> initialWindow.toLong()
            else -> (rate.toLong() * (rttMs + queueMs) / MS_PER_SECOND).coerceIn(minWindow.toLong(), MAX_WINDOW)
        }

    /** The bytes written that no report has said the receiver holds. */
    fun queued(): Long = written - if (lastHeld < 0) 0L else lastHeld - offset

    /** How long to wait before the next chunk: 0 while the window has room, else until a report frees it. */
    fun waitMs(): Long = if (queued() < target()) 0L else REPORT_WAIT_MS

    /** The next chunk's size: about [CHUNK_MS] of the rate, so a frame queued mid-file waits at most a small chunk. */
    fun chunkBytes(): Int {
        if (rate <= 0) return initialChunk
        val chunk = rate.toLong() * CHUNK_MS / MS_PER_SECOND
        return chunk.coerceIn(minChunk.toLong(), LinkFraming.FILE_CHUNK_BYTES.toLong()).toInt()
    }

    /**
     * A paced chunk's size, from the pace's own [paced]: while [slowReporter], [minChunk], so at the paced base a
     * receiver reporting every chunk answers each about half a second apart (a second on Coded), inside
     * [SLOW_REPORT_MS], and can earn the window back. A two-second reporter's reports still come two seconds apart,
     * several chunks each.
     */
    fun pacedChunkBytes(paced: Int): Int = if (slowReporter) minOf(paced, minChunk) else paced

    /**
     * The next chunk's size for a feed with [pace]: the window's own while [windowed]; while a windowed feed is paced,
     * [pacedChunkBytes] (small enough that a receiver reporting every chunk shows itself); a paced link's, its pace's.
     */
    fun chunkFor(
        windowed: Boolean,
        pace: PaceConfig,
    ): Int {
        if (windowed) return chunkBytes()
        if (!pace.windowed) return pace.chunkBytes
        val chunk = pacedChunkBytes(pace.chunkBytes)
        onTimeFloorMs = chunk * MS_PER_SECOND / maxOf(pace.bytesPerSec, 1).toLong() / 2
        return chunk
    }

    /** Why a windowed feed is paced, for its `bt file window` line: a two-second reporter, or a silent receiver. */
    fun pacedWhy(): String = if (slowReporter) "paced: two-second reporter" else "paced: receiver silent"

    companion object {
        // The queue a feed keeps ahead of the receiver past the round trip: about the wait a frame behind the file sees.
        const val QUEUE_MS = 250L

        // The fastest drain a report is believed at, which bounds a garbled or lying one: about twice the best 1M
        // drain measured (29 KB/s at a 15 ms interval, ADR 2026-10.2da4).
        const val CEILING_BYTES_PER_SEC = 64 * 1024

        // The floors, in smallest chunks: the window's, the starting window, a chunk before the rate is known.
        private const val MIN_WINDOW_CHUNKS = 2
        private const val INITIAL_WINDOW_CHUNKS = 4
        private const val INITIAL_CHUNKS = 2

        // A chunk carries about this much of the rate, between these sizes (on 1M and 2M: a Coded link's floor is its
        // pace's own, [minChunk]).
        const val CHUNK_MS = 250L
        const val MIN_CHUNK_BYTES = 2048
        const val INITIAL_CHUNK_BYTES = INITIAL_CHUNKS * MIN_CHUNK_BYTES

        // The smallest [minChunk] a link may set: two of them make a receiver's report on chunk bytes alone.
        const val SMALLEST_CHUNK_BYTES = MIN_CHUNK_BYTES / MIN_WINDOW_CHUNKS

        // The window's bounds, at the 1M/2M chunk floor (a Coded link's floor scales the lower ones): never so small that
        // a slow link stops and waits for each report, never past a second of the ceiling.
        const val MIN_WINDOW = MIN_WINDOW_CHUNKS * MIN_CHUNK_BYTES.toLong()
        const val MAX_WINDOW = 64L * 1024

        // What may be in flight before the first report measures anything: two seconds of the old 4 KiB/s base.
        const val INITIAL_WINDOW = INITIAL_WINDOW_CHUNKS * MIN_CHUNK_BYTES.toLong()

        // A receiver silent this long gets the old pace until it reports.
        const val NO_REPORT_MS = 3_000L

        // The longest one wait for room lasts; a report wakes the writer sooner.
        const val REPORT_WAIT_MS = 500L

        // The shortest span, on the receiver's clock, a drain sample is measured over.
        const val MIN_SAMPLE_MS = 250L

        // The round trip: assumed until measured, bounded, the smallest over this long before the window probes it.
        const val INITIAL_RTT_MS = 500L
        const val MIN_RTT_MS = 50L
        const val MAX_RTT_MS = 3_000L
        const val RTT_WINDOW_MS = 10_000L

        // A receiver reporting this far apart (its own clock), twice running, reports every two seconds: pace it.
        const val SLOW_REPORT_MS = 1_900L
        private const val SLOW_GAPS = 2

        // The same two, in the smallest chunk's time at the measured rate, where that is longer ([recordsMs]); a chunk's
        // crossing counted at most this long, a 1 KiB one at 0.3 KB/s, so one low sample (a link that nearly stalled
        // for a moment) cannot hold a receiver whose reports stopped unpaced for minutes (the bluetooth-reviewer's
        // finding): the silence is at most about ten seconds.
        private const val SLOW_RECORDS = 2
        private const val SILENT_RECORDS = 3
        const val MAX_CHUNK_CROSSING_MS = 3_400L

        // How many reports in a row, one chunk at a time at the paced spacing, give a two-second reporter its window
        // back: a second and a half at the paced base.
        const val RECOVER_REPORTS = 3

        // How many windows of queue at a report make it a standing one, whose sample is the drain: what a report finds
        // unheld is the queue plus the round trip's bytes plus the writer's last chunk, so one window fired on nearly
        // every report of a lit sender (the bluetooth-reviewer's port). The writer stops at one window, so this fires
        // when the window shrinks under a queue sized for the old one (the round trip's estimate dropped). A drain
        // that falls by half or more shows as slowed round trips; a smaller fall is followed as the twelve samples age
        // out, a few seconds at four reports a second, longer on a slow link.
        private const val STANDING_WINDOWS = 2

        private const val SAMPLES = 12

        // A chunk's round trip past this many of what it should have taken says the link slowed.
        private const val SLOWED_NUM = 3
        private const val SLOWED_DEN = 2
        private const val MS_PER_SECOND = 1_000L
    }
}
