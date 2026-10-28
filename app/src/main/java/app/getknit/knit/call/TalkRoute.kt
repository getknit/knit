package app.getknit.knit.call

import java.util.TreeMap

/**
 * The speaker's half of a walkie session's reports (docs/VOICE_CALLS.md C-3.11-10, ADR 2026-10.79yb's tenth
 * amendment): whether its chunks should go through a relay beside the radios ([castToo]), judged by what the peer says
 * it heard of each burst. The radios are tried first and kept while they deliver, so a session leans on a relay only
 * after the radios have shown they lose its bursts:
 *  - a burst whose report shows the radios lost more than [lossMax] of it, or with no report at all [waitMs] after its
 *    last chunk went, turns the cast on;
 *  - [wholeToReturn] reports in a row with the radios whole turn it off again. The peer counts a chunk that came both
 *    ways on both planes, so a radio path that recovered while the cast ran shows here.
 *
 * Each burst it judges goes to [judged] too: its chunks, and how many the report says came over either plane, or null
 * when no report came in time — the diagnostics card's sent loss (`WalkieMeter.judged`).
 *
 * Only for a peer that reports (`CallPayload.hr`); [WalkieTalk] builds none for one that does not, and its chunks go
 * by the radio evidence alone, as before. Pure (`TalkRouteTest`).
 */
class TalkRoute(
    private val waitMs: Long = WalkiePolicy.REPORT_WAIT_MS,
    private val lossMax: Double = WalkiePolicy.RADIO_LOSS_MAX,
    private val wholeToReturn: Int = WalkiePolicy.RADIO_WHOLE_TO_RETURN,
    private val judged: (chunks: Int, heard: Int?) -> Unit = { _, _ -> },
) {
    private class Out(
        val chunks: Int,
        val at: Long,
    )

    // Bursts sent and not yet judged, by burst number.
    private val waiting = TreeMap<Int, Out>()
    private var whole = 0

    /** Whether the chunks going out now go through a relay as well as the radios. */
    @Volatile
    var castToo = false
        private set

    /** Burst [b] went out whole, [chunks] of it, its last at [at]. */
    @Synchronized
    fun sent(
        b: Int,
        chunks: Int,
        at: Long,
    ) {
        if (chunks <= 0) return
        waiting[b] = Out(chunks, at)
        while (waiting.size > MAX_WAITING) waiting.pollFirstEntry()
    }

    /** The peer's [report] of a burst; a report of one not waiting (a duplicate, or one judged lost already) is ignored. */
    @Synchronized
    fun report(report: TalkReport) {
        val out = waiting.remove(report.b) ?: return
        judged(out.chunks, report.radio + report.relay)
        val lost = (out.chunks - report.radio).toDouble() / out.chunks
        if (lost > lossMax) lossy() else radioWhole()
    }

    /** Judges every burst whose report is overdue at [now] as lost. */
    @Synchronized
    fun expire(now: Long) {
        while (true) {
            val first = waiting.firstEntry() ?: return
            if (now - first.value.at < waitMs) return
            waiting.pollFirstEntry()
            judged(first.value.chunks, null)
            lossy()
        }
    }

    private fun lossy() {
        castToo = true
        whole = 0
    }

    private fun radioWhole() {
        if (!castToo) return
        whole++
        if (whole >= wholeToReturn) {
            castToo = false
            whole = 0
        }
    }

    private companion object {
        // A speaker outrunning every report would otherwise grow this without bound.
        const val MAX_WAITING = 16
    }
}

/**
 * The listener's half (C-3.11-10): which chunks of each of the peer's bursts came over the radios and which through a
 * relay, and when a burst is ready to be reported — [settleMs] after its last chunk landed, so the slower plane's copy
 * counts too, or [endMs] after its newest chunk when its last never came ([WalkiePolicy.END_MS], the player's own
 * give-up). Each burst is reported once; a chunk of one already reported is not counted again. Pure (`TalkRouteTest`).
 */
class HeardLedger(
    private val settleMs: Long = WalkiePolicy.REPORT_SETTLE_MS,
    private val endMs: Long = WalkiePolicy.END_MS,
) {
    private class Heard {
        val radio = HashSet<Int>()
        val relay = HashSet<Int>()
        var lastAt = 0L
        var endAt: Long? = null
    }

    private val bursts = TreeMap<Int, Heard>()

    // The bursts reported lately, so a straggler of one is not taken for a new burst.
    private val reported = ArrayDeque<Int>()

    /** Chunk [chunk] of the peer's came at [at], cast through a relay when [viaRelay]. */
    @Synchronized
    fun note(
        chunk: TalkChunk,
        viaRelay: Boolean,
        at: Long,
    ) {
        if (chunk.b in reported) return
        val heard = bursts.getOrPut(chunk.b) { Heard() }
        (if (viaRelay) heard.relay else heard.radio) += chunk.q
        heard.lastAt = maxOf(heard.lastAt, at)
        if (chunk.e && heard.endAt == null) heard.endAt = at
        while (bursts.size > MAX_BURSTS) bursts.pollFirstEntry()
    }

    /** The reports due at [now], each burst once. */
    @Synchronized
    fun due(now: Long): List<TalkReport> {
        val ready = bursts.filterValues { h -> h.endAt?.let { now - it >= settleMs } ?: (now - h.lastAt >= endMs) }
        return ready.map { (b, h) ->
            bursts.remove(b)
            reported.addLast(b)
            if (reported.size > MAX_BURSTS) reported.removeFirst()
            TalkReport(b = b, radio = h.radio.size, relay = h.relay.size)
        }
    }

    /** When the next report falls due, or null with nothing held. */
    @Synchronized
    fun nextDueAt(): Long? = bursts.values.minOfOrNull { h -> h.endAt?.plus(settleMs) ?: (h.lastAt + endMs) }

    private companion object {
        const val MAX_BURSTS = 16
    }
}
