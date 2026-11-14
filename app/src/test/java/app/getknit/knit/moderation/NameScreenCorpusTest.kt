package app.getknit.knit.moderation

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The measurement behind ADR 2026-11.y4pc, run again on demand: the shipped [NameScreen] over the name data that
 * `scripts/gen-name-screen-lists.py` reads, printing how many real names it hides. Opt-in, because the data is not in
 * the repository (about 60 MB, pinned by SHA-256 in the generator):
 *
 *     KNIT_NAME_CORPUS=<dir> ./gradlew :app:testDebugUnitTest --rerun \
 *       --tests 'app.getknit.knit.moderation.NameScreenCorpusTest' -i
 *
 * It prints each rate with the hidden names themselves, and fails above a ceiling a little over what the ADR measured
 * (no given name in a hundred thousand people, a hundredth of a percent of surnames), so a regeneration that starts
 * hiding real names fails the run that checks it. The contract tests, which always run, are [NameListsTest] and
 * [NameScreenTest].
 */
class NameScreenCorpusTest {
    @Test
    fun printWhatTheShippedScreenHidesInRealNames() {
        val dir = System.getenv(ENV)?.let(::File)
        assumeTrue("set $ENV to the generator's --corpus directory to measure", dir?.isDirectory == true)
        val screen = ShippedWordList.nameScreen()
        val givenShare = report("given names (SSA, births since 1950)", screen, given(File(dir, "ssa/babynames.csv")))
        val surnameShare = report("surnames (2010 Census, people)", screen, surnames(File(dir, "census/Names_2010Census.csv")))
        assertTrue("given names hidden: $givenShare %", givenShare <= GIVEN_CEILING)
        assertTrue("surnames hidden: $surnameShare %", surnameShare <= SURNAME_CEILING)
    }

    private fun report(
        label: String,
        screen: NameScreen,
        weighted: Map<String, Long>,
    ): Double {
        val hidden = weighted.filterKeys { screen.check(it, NameScreen.Audience.PUBLIC).flagged }
        val people = weighted.values.sum().toDouble()
        val share = PERCENT * hidden.values.sum() / people
        println("$label: ${hidden.size} of ${weighted.size} hidden, %.4f %% of people: ${hidden.keys.sorted()}".format(share))
        return share
    }

    private fun given(csv: File): Map<String, Long> {
        val births = HashMap<String, Long>()
        csv.useLines { lines ->
            lines.drop(1).forEach { line ->
                val cells = line.split(',') // year, sex, name, n, prop
                if (cells[0].toInt() >= SINCE) births.merge(cells[2], cells[3].toLong(), Long::plus)
            }
        }
        return births
    }

    private fun surnames(csv: File): Map<String, Long> =
        csv.useLines { lines ->
            lines
                .drop(1)
                .map { it.split(',') }
                .filter { it[0] != "ALL OTHER NAMES" }
                .associate { it[0].lowercase().replaceFirstChar(Char::titlecase) to it[2].toLong() }
        }

    private companion object {
        const val ENV = "KNIT_NAME_CORPUS"
        const val SINCE = 1950
        const val PERCENT = 100.0

        // Percent of people. Measured 2026-11-14: 0.0000 % of given names (Hymie), 0.0002 % of surnames.
        const val GIVEN_CEILING = 0.001
        const val SURNAME_CEILING = 0.01
    }
}
