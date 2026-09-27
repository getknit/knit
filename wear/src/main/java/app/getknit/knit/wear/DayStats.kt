package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState

/**
 * One reading as the watch keeps it for the day's history: when the watch got it ([atMs], its own clock), the
 * state and counts. [carrying] is null from a phone build that does not send it.
 */
data class Sample(
    val atMs: Long,
    val state: MeshState,
    val nearby: Int,
    val far: Int,
    val relayed: Long,
    val carrying: Int?,
)

/** Time today spent in each kind of state, as far as the watch saw it (gaps it did not see count nowhere). */
data class DayShare(
    val linkedMs: Long = 0,
    val aloneMs: Long = 0,
    val weakMs: Long = 0,
    val restingMs: Long = 0,
) {
    val totalMs: Long get() = linkedMs + aloneMs + weakMs + restingMs
}

/**
 * The watch's own view of today, from its history of readings — nothing here comes from the phone as such:
 * [relayed] is the rise in the phone's all-time counter across today's readings (null until there are two to
 * difference), [share] how the day went, [busiest] the most peers in range and when, [linkedSinceMs] the start
 * of the current unbroken Linked run, and [lastPeerAtMs] the last reading with anyone in range, when there is
 * nobody now.
 */
data class Today(
    val relayed: Long?,
    val share: DayShare,
    val busiest: Pair<Int, Long>?,
    val linkedSinceMs: Long?,
    val lastPeerAtMs: Long?,
)

/**
 * Pure arithmetic over the reading history. A reading stands for the state until the next one, but for no
 * more than [SPAN_MS] — two complication periods — so an hour the watch spent off the wrist is not painted
 * as whatever it saw last.
 */
object DayStats {
    const val SPAN_MS = 10 * 60_000L

    fun today(
        samples: List<Sample>,
        dayStartMs: Long,
        nowMs: Long,
    ): Today {
        val sorted = samples.filter { it.atMs <= nowMs }.sortedBy { it.atMs }
        val todays = sorted.filter { it.atMs >= dayStartMs }
        return Today(
            relayed = relayedToday(sorted, dayStartMs),
            share = share(sorted, dayStartMs, nowMs),
            busiest = todays.filter { it.nearby > 0 }.maxByOrNull { it.nearby }?.let { it.nearby to it.atMs },
            linkedSinceMs = linkedSince(sorted, nowMs),
            lastPeerAtMs = if ((sorted.lastOrNull()?.nearby ?: 0) > 0) null else sorted.lastOrNull { it.nearby > 0 }?.atMs,
        )
    }

    /**
     * The sum of the counter's rises between consecutive readings that end today. A pair that straddles
     * midnight counts only when it is one span apart (else the rise may be yesterday's); a fall — the phone
     * restored from a backup, or reinstalled — is not a negative day, just a new baseline.
     */
    private fun relayedToday(
        sorted: List<Sample>,
        dayStartMs: Long,
    ): Long? {
        val pairs =
            sorted.zipWithNext().filter { (a, b) ->
                b.atMs >= dayStartMs && (a.atMs >= dayStartMs || b.atMs - a.atMs <= SPAN_MS)
            }
        return if (pairs.isEmpty()) null else pairs.sumOf { (a, b) -> (b.relayed - a.relayed).coerceAtLeast(0) }
    }

    private fun share(
        sorted: List<Sample>,
        dayStartMs: Long,
        nowMs: Long,
    ): DayShare {
        var share = DayShare()
        for ((i, s) in sorted.withIndex()) {
            val next = sorted.getOrNull(i + 1)?.atMs ?: nowMs
            val start = maxOf(s.atMs, dayStartMs)
            val end = minOf(next, nowMs, s.atMs + SPAN_MS)
            if (end <= start) continue
            val ms = end - start
            share =
                when (s.state) {
                    MeshState.Linked -> share.copy(linkedMs = share.linkedMs + ms)
                    MeshState.Alone -> share.copy(aloneMs = share.aloneMs + ms)
                    MeshState.Degraded, MeshState.NoRadio -> share.copy(weakMs = share.weakMs + ms)
                    MeshState.Off, MeshState.Paused -> share.copy(restingMs = share.restingMs + ms)
                }
        }
        return share
    }

    /** The start of the Linked run the latest reading belongs to, if it is Linked and still fresh. */
    private fun linkedSince(
        sorted: List<Sample>,
        nowMs: Long,
    ): Long? {
        val last = sorted.lastOrNull() ?: return null
        if (last.state != MeshState.Linked || nowMs - last.atMs > StatusText.STALE_MS) return null
        var start = last.atMs
        for (i in sorted.indices.reversed().drop(1)) {
            val s = sorted[i]
            if (s.state != MeshState.Linked || start - s.atMs > SPAN_MS) break
            start = s.atMs
        }
        return start
    }

    /**
     * Peers in range over [fromMs]..[toMs] in [buckets] equal steps: the most seen in each step, 0 while the
     * mesh was at rest, null where the watch has no reading (the chart leaves a gap there).
     */
    fun nearbySeries(
        samples: List<Sample>,
        fromMs: Long,
        toMs: Long,
        buckets: Int,
    ): List<Int?> {
        val step = (toMs - fromMs).coerceAtLeast(1) / buckets.coerceAtLeast(1)
        val out = MutableList<Int?>(buckets) { null }
        for (s in samples) {
            if (s.atMs < fromMs || s.atMs >= toMs) continue
            val i = ((s.atMs - fromMs) / step.coerceAtLeast(1)).toInt().coerceIn(0, buckets - 1)
            out[i] = maxOf(out[i] ?: 0, s.nearby)
        }
        return out
    }
}
