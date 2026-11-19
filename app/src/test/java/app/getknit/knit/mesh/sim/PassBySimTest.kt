package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pairs passing on a sidewalk ([PassBySim]): how often a pass is long enough for two walkers to swap what each brought.
 *
 * **A measurement, not a check.** `-Pknit.meshSim=<seed>` runs it, the `knit.meshSimPairs`, `…Speed`, `…LateralM`,
 * `…PassDms`, `…PassPictures`, `…PictureKb`, `…RangeM`, `…DropM` and `…Radio` properties shape it ([SimProperties.passBy]),
 * and every other run leaves it out. The report goes beside an outing's ([SimProperties.out]), named for the sidewalk and
 * `knit.meshSimTag`, never over an earlier one. It asserts only that every pair it set up was watched.
 */
@RunWith(RobolectricTestRunner::class)
class PassBySimTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        val asked = System.getProperty(SimProperties.SEED) != null
        assumeTrue("the mesh simulator runs only when asked: -P${SimProperties.SEED}=<seed>", asked)
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        if (::lab.isInitialized) lab.close()
    }

    @Test
    fun pairsPassOnASidewalk() =
        runBlocking {
            val scenario = SimProperties.passBy(SimProperties.seed())
            val name =
                "passby-s${scenario.seed}-p${scenario.pairs}-${scenario.radio}-r${scenario.connectM.toInt()}" +
                    "-d${scenario.dms}-i${scenario.pictures}${SimProperties.tag()}"
            var out: File? = null
            val sim = PassBySim(lab, scenario) { line -> out?.appendText("$line\n") }
            out = freshReport(SimProperties.out(), name)
            val report = sim.play()
            println(report.text)
            out.writeText(report.text + "\n")
            assertEquals("every pair is in the report:\n${report.text}", scenario.pairs, report.passes.size)
        }
}
