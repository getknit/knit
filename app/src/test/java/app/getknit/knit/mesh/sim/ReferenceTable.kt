package app.getknit.knit.mesh.sim

import kotlin.math.abs
import kotlin.math.max

/**
 * A published Bluetooth trace's contacts, reduced to counts on a log grid by `scripts/sim-reference.py` and cited in
 * `reference/README.md` beside them. Each grid point (0 s, then 20 a decade from 1 s) counts the trace's gaps and its
 * contact lengths at or below it; 1 − count / total is the CCDF its paper plots.
 *
 * A trace is read as its files record it, one side at a time: "A saw B" from A's first sighting of B to its last, the
 * gap from that run to A's next. The two ends are never matched into one contact, because the iMotes' clocks were set by
 * hand and are not trusted below about five minutes. A run is therefore compared through a [VirtualScanner] of the same
 * [profile], by its [VirtualScanner.sightings].
 *
 * [distance] scores a run against a column by its Kolmogorov–Smirnov distance: the largest difference between the two
 * cumulative shares at the grid points of a window both can see. It is an effect size, not a test: a seeded run's
 * samples are large enough to make any p-value meaningless. A run of a few hours sees its long gaps less often than its
 * short ones: a gap of length g fits inside a run of length T in T − g of the run's starting places. So each of the
 * run's samples is weighted by 1 / (T − g). The traces' own spans of days and months make the same weight on theirs
 * negligible.
 */
class ReferenceTable private constructor(
    val name: String,
    /** The table's `# key=value` lines: its source, its trace's size and the numbers [ContactStats] would give. */
    val header: Map<String, String>,
    private val gridS: DoubleArray,
    private val gapsLe: LongArray,
    private val contactsLe: LongArray,
) {
    /** The [ScanProfile.name] that sees a field the way the trace's devices saw theirs. */
    val profile: String get() = header.getValue("profile")

    /** Which of the table's two columns: the gaps between a pair's runs, or the runs' lengths. */
    enum class Kind { GAP, CONTACT }

    /**
     * A run's [distance] over the grid window [fromS] to [toS] (0 when the window takes every value up to [toS]),
     * from [samples] of the run's and [referenceSamples] of the trace's.
     */
    data class Fit(
        val distance: Double,
        val fromS: Double,
        val toS: Double,
        val samples: Int,
        val referenceSamples: Long,
    )

    internal fun grid(): DoubleArray = gridS.copyOf()

    internal fun counts(kind: Kind): LongArray = (if (kind == Kind.GAP) gapsLe else contactsLe).copyOf()

    /**
     * The distance of a run's [sortedMs] values from this table's [kind] over the window from the first grid point at or
     * above [fromS] (exclusive) to the last at or below [toS], or from 0 inclusive when [fromS] is null. The run spanned
     * [spanMs]. Null when the window holds no grid point, or fewer than [MIN_SAMPLES] values of either side.
     */
    fun distance(
        kind: Kind,
        sortedMs: List<Long>,
        spanMs: Long,
        fromS: Double?,
        toS: Double,
    ): Fit? {
        val lo = if (fromS == null) BEFORE_GRID else gridS.indexOfFirst { it >= fromS }
        val hi = gridS.indexOfLast { it <= toS }
        if ((fromS != null && lo < 0) || hi <= lo) return null
        val reference = if (kind == Kind.GAP) gapsLe else contactsLe
        val (count, weight) = cumulative(sortedMs, spanMs)
        val samples = count[hi] - (if (lo == BEFORE_GRID) 0 else count[lo])
        val referenceBase = if (lo == BEFORE_GRID) 0L else reference[lo]
        val referenceSamples = reference[hi] - referenceBase
        val base = if (lo == BEFORE_GRID) 0.0 else weight[lo]
        val total = weight[hi] - base
        if (samples < MIN_SAMPLES || referenceSamples < MIN_SAMPLES || total <= 0) return null
        var distance = 0.0
        for (k in lo + 1..hi) {
            val run = (weight[k] - base) / total
            val trace = (reference[k] - referenceBase).toDouble() / referenceSamples
            distance = max(distance, abs(run - trace))
        }
        val from = if (lo == BEFORE_GRID) 0.0 else gridS[lo]
        return Fit(distance, from, gridS[hi], samples, referenceSamples)
    }

    // How many of [sortedMs] lie at or below each grid point, and their summed weights 1 / (span − value).
    private fun cumulative(
        sortedMs: List<Long>,
        spanMs: Long,
    ): Pair<IntArray, DoubleArray> {
        val count = IntArray(gridS.size)
        val weight = DoubleArray(gridS.size)
        var i = 0
        var sum = 0.0
        for (k in gridS.indices) {
            val limitMs = gridS[k] * MS_PER_S
            while (i < sortedMs.size && sortedMs[i] <= limitMs) {
                if (sortedMs[i] < spanMs) sum += 1.0 / (spanMs - sortedMs[i])
                i++
            }
            count[k] = i
            weight[k] = sum
        }
        return count to weight
    }

    companion object {
        /** Fewer values than this in a window score nothing. */
        const val MIN_SAMPLES = 30

        /** The traces whose tables are among the test resources, by name. */
        val NAMES = listOf("infocom05", "infocom06", "reality")

        /** Every table, read once. */
        val ALL: List<ReferenceTable> by lazy { NAMES.map(::read) }

        private const val BEFORE_GRID = -1
        private const val MS_PER_S = 1_000.0
        private val COLUMNS = listOf("x_s", "gaps_le", "contacts_le")

        /** The table `reference/<name>.tsv` among the test resources. */
        fun read(name: String): ReferenceTable {
            val path = "reference/$name.tsv"
            val text =
                checkNotNull(ReferenceTable::class.java.getResourceAsStream(path)) { "no $path among the test resources" }
                    .bufferedReader()
                    .use { it.readText() }
            return parse(name, text)
        }

        /** A table's [text], named [name]: `# key=value` lines, then the columns and one row a grid point. */
        fun parse(
            name: String,
            text: String,
        ): ReferenceTable {
            val header = LinkedHashMap<String, String>()
            val rows = mutableListOf<List<String>>()
            var columns: List<String>? = null
            for (line in text.lines().filter(String::isNotBlank)) {
                when {
                    line.startsWith("# ") -> {
                        val (key, value) = line.removePrefix("# ").split("=", limit = 2)
                        header[key] = value
                    }

                    columns == null -> {
                        columns = line.split('\t')
                    }

                    else -> {
                        rows += line.split('\t')
                    }
                }
            }
            require(columns == COLUMNS) { "$name: the columns are $COLUMNS, not $columns" }
            require(rows.isNotEmpty() && rows.all { it.size == COLUMNS.size }) { "$name: a row a grid point, three values each" }
            val grid = DoubleArray(rows.size) { rows[it][0].toDouble() }
            val gaps = LongArray(rows.size) { rows[it][1].toLong() }
            val contacts = LongArray(rows.size) { rows[it][2].toLong() }
            require(grid.asList().zipWithNext().all { (a, b) -> b > a }) { "$name: the grid rises" }
            for ((column, counts) in listOf("gaps" to gaps, "contacts" to contacts)) {
                require(counts.asList().zipWithNext().all { (a, b) -> b >= a }) { "$name: $column only accumulate" }
                require(counts.last() == header[column]?.toLong()) {
                    "$name: the last point holds every one of its ${header[column]} $column"
                }
            }
            require("profile" in header) { "$name: which scanner saw it" }
            return ReferenceTable(name, header, grid, gaps, contacts)
        }
    }
}
