package app.getknit.knit.mesh.sim

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

/**
 * Walkers replayed from waypoints, recorded or generated elsewhere, in BonnMotion's `.movements` format: one line a
 * walker, a run of `time x y` triples in seconds and metres, times rising (BonnMotion's `Scenario.java`). Between two
 * waypoints a walker moves in a straight line at an even pace, found by binary search, so an hour of a crowd stored as
 * waypoints is tens of megabytes where every 500 ms sample of it would be hundreds.
 *
 * A walker is in the field from its first waypoint to its last and nowhere else ([present]): a recorded pedestrian walks
 * into the camera's view and out of it. Before and after, [position] holds it at its first and last place, which nobody
 * reads, since an absent walker links to nobody. The field is the box from the origin to the farthest waypoint, so a
 * trace is shifted to non-negative coordinates before it is written.
 */
class TraceMobility private constructor(
    /** What the trace is, for a report: its file's name. */
    val name: String,
    private val timesMs: List<LongArray>,
    private val xs: List<DoubleArray>,
    private val ys: List<DoubleArray>,
) : Mobility {
    val walkers: Int get() = timesMs.size

    /** The last waypoint's time: when the last walker leaves. */
    val durationMs: Long = timesMs.maxOf { it.last() }
    val widthM: Double = xs.maxOf { it.max() }
    val heightM: Double = ys.maxOf { it.max() }

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        val times = timesMs[walker]
        val x = xs[walker]
        val y = ys[walker]
        if (atMs <= times.first()) return Point(x.first(), y.first())
        if (atMs >= times.last()) return Point(x.last(), y.last())
        // The last waypoint at or before [atMs]: the leg it starts holds the time.
        var lo = 0
        var hi = times.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= atMs) lo = mid else hi = mid - 1
        }
        val done = (atMs - times[lo]).toDouble() / (times[lo + 1] - times[lo])
        return Point(x[lo] + (x[lo + 1] - x[lo]) * done, y[lo] + (y[lo + 1] - y[lo]) * done)
    }

    override fun present(
        walker: Int,
        atMs: Long,
    ): Boolean = atMs in timesMs[walker].first()..timesMs[walker].last()

    companion object {
        /** The trace in [file], gzipped when its name ends in `.gz`. */
        fun read(file: File): TraceMobility = file.inputStream().use { parse(file.name, it, gzipped = file.name.endsWith(".gz")) }

        /** The trace in [input], named [name]. */
        fun parse(
            name: String,
            input: InputStream,
            gzipped: Boolean,
        ): TraceMobility {
            val text = (if (gzipped) GZIPInputStream(input) else input).bufferedReader().readText()
            return parse(name, text)
        }

        /** The trace [text] holds, named [name]: one walker a non-blank line. */
        fun parse(
            name: String,
            text: String,
        ): TraceMobility {
            val times = mutableListOf<LongArray>()
            val xs = mutableListOf<DoubleArray>()
            val ys = mutableListOf<DoubleArray>()
            for ((n, line) in text.lines().withIndex()) {
                val numbers =
                    line
                        .trim()
                        .split(WHITESPACE)
                        .filter(String::isNotEmpty)
                        .map(String::toDouble)
                if (numbers.isEmpty()) continue
                val where = "$name line ${n + 1}"
                require(numbers.size % TRIPLE == 0) { "$where: waypoints are time x y, ${numbers.size} numbers is not" }
                val points = numbers.chunked(TRIPLE)
                val at = LongArray(points.size) { (points[it][0] * MS_PER_S).roundToLong() }
                require(at.asList().zipWithNext().all { (a, b) -> b > a }) { "$where: a walker's times rise" }
                require(points.all { it[1] >= 0 && it[2] >= 0 }) { "$where: a trace lies at non-negative x and y" }
                times += at
                xs += DoubleArray(points.size) { points[it][1] }
                ys += DoubleArray(points.size) { points[it][2] }
            }
            require(times.isNotEmpty()) { "$name holds no walker" }
            return TraceMobility(name, times, xs, ys)
        }

        private val WHITESPACE = Regex("\\s+")
        private const val TRIPLE = 3
        private const val MS_PER_S = 1_000.0
    }
}
