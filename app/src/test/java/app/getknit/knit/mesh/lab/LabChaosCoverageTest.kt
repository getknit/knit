package app.getknit.knit.mesh.lab

import app.getknit.knit.legal.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mesh-lab scenario class carries [LabChaos.rule]. Without it a class runs plain under
 * `-Pknit.labChaos` — no noise, a green that means nothing — and its failures name no seed. Plain JVM: it
 * reads the sources, so a new `*LabTest` that forgot the rule fails here before it ever reaches CI.
 */
class LabChaosCoverageTest {
    @Test
    fun everyLabScenarioClassCarriesTheChaosRule() {
        val dir = repoFile("app/src/test/java/app/getknit/knit/mesh/lab")
        val classes = dir.listFiles { f -> f.name.endsWith("LabTest.kt") }.orEmpty()
        assertTrue("no *LabTest.kt found under $dir", classes.isNotEmpty())
        val missing = classes.filterNot { RULE.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertTrue("these mesh/lab classes lack `@get:Rule val chaos = LabChaos.rule()`: $missing", missing.isEmpty())
    }

    private companion object {
        val RULE = Regex("""@get:Rule\s+val\s+\w+\s*=\s*LabChaos\.rule\(\)""")
    }
}
