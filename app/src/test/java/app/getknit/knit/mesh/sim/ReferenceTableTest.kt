package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.ReferenceTable.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

class ReferenceTableTest {
    @Test
    fun everyTableIsWholeOnOneGridAndAgreesWithItsHeader() {
        val grid = ReferenceTable.ALL.first().grid()
        for (table in ReferenceTable.ALL) {
            assertArrayEquals("${table.name} is on the first table's grid", grid, table.grid(), 0.0)
            assertTrue("${table.name} was seen by a scanner the field has", ScanProfile.ALL.any { it.name == table.profile })
            for ((kind, key) in listOf(Kind.GAP to "gap_p50_s", Kind.CONTACT to "contact_p50_s")) {
                // The header's median, by nearest rank, lies in the grid cell where the counts pass half.
                val counts = table.counts(kind)
                val rank = (P50 * counts.last() + PERCENT - 1) / PERCENT
                val cell = counts.indexOfFirst { it >= rank }
                val median = table.header.getValue(key).toDouble()
                assertTrue("${table.name} $key $median in cell $cell", median <= grid[cell] && (cell == 0 || median > grid[cell - 1]))
            }
            // Every human trace's gaps fit a power law from their median up better than an exponential.
            val llr = table.header.getValue("gap_tail_llr").toDouble()
            assertTrue("${table.name}'s gap tail scored $llr", llr > 0)
        }
    }

    @Test
    fun aSampleDrawnFromATraceLiesCloseToItAndAnExponentialFar() {
        val table = ReferenceTable.read("infocom05")
        val random = Random(SEED)
        val drawn = List(SAMPLES) { draw(table, random) }.sorted()
        val near = checkNotNull(table.distance(Kind.GAP, drawn, ENDLESS_MS, TEN_MINUTES_S, TWO_HOURS_S))
        assertTrue("the trace's own shape scored $near", near.distance < 0.03)
        val exponential = List(SAMPLES) { (-ln(1 - random.nextDouble()) * TEN_MINUTES_S * MS_PER_S).toLong() }.sorted()
        val far = checkNotNull(table.distance(Kind.GAP, exponential, ENDLESS_MS, TEN_MINUTES_S, TWO_HOURS_S))
        assertTrue("an exponential of mean 10 minutes scored $far", far.distance > 0.15)
        // The grid's points at 1 s and 1.12 s leave none in between: a window there holds nothing to score.
        assertNull("no grid point between 1.01 and 1.1 seconds", table.distance(Kind.GAP, drawn, ENDLESS_MS, 1.01, 1.1))
        val few = drawn.take(ReferenceTable.MIN_SAMPLES - 1)
        assertNull("too few values to score", table.distance(Kind.GAP, few, ENDLESS_MS, null, TWO_HOURS_S))
    }

    @Test
    fun aRunsOwnLengthIsWeightedOutOfTheGapsItSees() {
        // Gaps uniform up to 2 hours, watched through 4-hour runs: a run holds a short gap whole more often than a long
        // one. Weighted for the run's length, the gaps it saw take the truth's shape again; unweighted, they do not.
        val grid = ReferenceTable.ALL.first().grid()
        val truth = grid.map { (min(it, TWO_HOURS_S) / TWO_HOURS_S * UNIFORM_N).roundToLong() }
        val table = ReferenceTable.parse("uniform", tableText(grid, truth))
        val spanMs = 2 * TWO_HOURS_MS
        val random = Random(SEED)
        val seen = mutableListOf<Long>()
        repeat(RUNS) {
            // Start well before the run, so the gaps are long under way when it opens.
            var atMs = -(random.nextDouble() * 4 * TWO_HOURS_MS).toLong() - 2 * TWO_HOURS_MS
            while (atMs < spanMs) {
                val gapMs = (random.nextDouble() * TWO_HOURS_MS).toLong()
                if (atMs >= 0 && atMs + gapMs <= spanMs) seen += gapMs
                atMs += gapMs
            }
        }
        seen.sort()
        val weighted = checkNotNull(table.distance(Kind.GAP, seen, spanMs, TEN_MINUTES_S, TWO_HOURS_S))
        val flat = checkNotNull(table.distance(Kind.GAP, seen, ENDLESS_MS, TEN_MINUTES_S, TWO_HOURS_S))
        assertTrue("weighted for the run, the gaps scored $weighted", weighted.distance < 0.025)
        assertTrue("unweighted, the gaps scored $flat", flat.distance > 0.05)
    }

    @Test
    fun aTableThatDoesNotAddUpIsRefused() {
        val grid = doubleArrayOf(0.0, 1.0, 10.0)
        val good = tableText(grid, listOf(0L, 1L, 2L))
        ReferenceTable.parse("good", good)
        assertThrows(IllegalArgumentException::class.java) { ReferenceTable.parse("falling", tableText(grid, listOf(0L, 2L, 1L))) }
        assertThrows(IllegalArgumentException::class.java) { ReferenceTable.parse("short", good.replace("# gaps=2", "# gaps=3")) }
        assertThrows(IllegalArgumentException::class.java) { ReferenceTable.parse("columns", good.replace("gaps_le", "gaps")) }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceTable.parse("grid", tableText(doubleArrayOf(0.0, 10.0, 1.0), listOf(0L, 1L, 2L)))
        }
    }

    // One gap of the trace's, by its own counts: a cell drawn as often as it holds gaps, a value even within it.
    private fun draw(
        table: ReferenceTable,
        random: Random,
    ): Long {
        val counts = table.counts(Kind.GAP)
        val grid = table.grid()
        val rank = random.nextLong(counts.last()) + 1
        val cell = counts.indexOfFirst { it >= rank }
        if (cell == 0) return 0
        return ((grid[cell - 1] + (grid[cell] - grid[cell - 1]) * (1 - random.nextDouble())) * MS_PER_S).toLong()
    }

    private fun tableText(
        grid: DoubleArray,
        counts: List<Long>,
    ): String =
        listOf("# name=test", "# profile=imote", "# gaps=${counts.last()}", "# contacts=${counts.last()}", "x_s\tgaps_le\tcontacts_le")
            .plus(grid.indices.map { "${grid[it]}\t${counts[it]}\t${counts[it]}" })
            .joinToString("\n")

    private companion object {
        const val SEED = 5
        const val SAMPLES = 20_000
        const val RUNS = 8_000
        const val UNIFORM_N = 1_000_000L
        const val TEN_MINUTES_S = 600.0
        const val TWO_HOURS_S = 7_200.0
        const val TWO_HOURS_MS = 7_200_000L
        const val MS_PER_S = 1_000.0
        const val PERCENT = 100

        // A run so long that every value weighs the same.
        const val ENDLESS_MS = Long.MAX_VALUE / 4
    }
}
