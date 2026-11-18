package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.AirStats
import app.getknit.knit.mesh.lab.LabNode
import java.util.Locale

/**
 * What the field's air links did from the moment this was made ([LinkAir][app.getknit.knit.mesh.lab.LinkAir]), summed over
 * every node's [AirStats]: bytes and records aired, records a cut lost or a closing link refused, how long a record
 * waited from its send to its delivery, and the deepest queue and backlog one link reached. Each is this tally's own:
 * the walk's from the walk's first step, the outing's from the boot's end.
 */
internal class AirTally(
    private val nodes: List<LabNode>,
) {
    private val bytes = sum { it.bytes.get() }
    private val records = sum { it.records.get() }
    private val cut = sum { it.cut.get() }
    private val refused = sum { it.refused.get() }
    private val waits = AirStats.merge(nodes.map { it.transport.aired.waitBuckets() })
    private val peaks = nodes.map { it.transport.aired.peaksFromNow() }

    private fun sum(read: (AirStats) -> Long): Long = nodes.sumOf { read(it.transport.aired) }

    private fun moved(): Moved {
        val now = AirStats.merge(nodes.map { it.transport.aired.waitBuckets() })
        return Moved(
            bytes = sum { it.bytes.get() } - bytes,
            records = sum { it.records.get() } - records,
            cut = sum { it.cut.get() } - cut,
            refused = sum { it.refused.get() } - refused,
            waits = LongArray(now.size) { now[it] - waits[it] },
            queuePeak = peaks.maxOf { it.queue.get() },
            backlogPeakMs = peaks.maxOf { it.backlogMs.get() },
        )
    }

    private class Moved(
        val bytes: Long,
        val records: Long,
        val cut: Long,
        val refused: Long,
        val waits: LongArray,
        val queuePeak: Long,
        val backlogPeakMs: Long,
    ) {
        fun wait(p: Int): Long? = AirStats.waitPercentileMs(waits, p)
    }

    /** The line [phase] reads, and its [SimSummary] keys, each ending in `_`[key]. */
    fun report(
        phase: String,
        key: String,
    ): Pair<String, List<Pair<String, Any?>>> {
        val m = moved()
        val line =
            "air $phase: ${m.records} records, ${"%.1f".format(Locale.ROOT, m.bytes / BYTES_PER_MB)} MB aired; " +
                "${m.cut} lost to a cut, ${m.refused} refused by a closing link; a record waited p50 ≤${m.wait(P50)} ms, " +
                "p90 ≤${m.wait(P90)} ms, max ≤${m.wait(MAX)} ms; deepest queue ${m.queuePeak} records, " +
                "${m.backlogPeakMs / MS_PER_S} s of backlog on one link"
        return line to
            listOf(
                "air_records_$key" to m.records,
                "air_bytes_$key" to m.bytes,
                "air_cut_$key" to m.cut,
                "air_refused_$key" to m.refused,
                "air_wait_p50_ms_$key" to m.wait(P50),
                "air_wait_p90_ms_$key" to m.wait(P90),
                "air_wait_max_ms_$key" to m.wait(MAX),
                "air_queue_peak_$key" to m.queuePeak,
                "air_backlog_peak_s_$key" to m.backlogPeakMs / MS_PER_S,
            )
    }

    private companion object {
        const val MAX = 100
        const val BYTES_PER_MB = 1_048_576.0
        const val MS_PER_S = 1_000L
    }
}
