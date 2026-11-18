package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The simulator's first outing (a spike): friends arrive at a park in threes, wander it at random and keep
 * messaging each other, with real stacks whose links follow their feet ([MeshSim]).
 *
 * **A measurement, not a check.** It takes the minutes it simulates and prints what the mesh did with them:
 * `-Pknit.meshSim=<seed>` runs it (`knit.meshSimWalkers`, `knit.meshSimMinutes` and `knit.meshSimHeap` size it, and
 * [SimProperties] names the rest of the outing's shape) and every other run leaves it out. The report goes to
 * `app/build/mesh-sim/`, or to `knit.meshSimOut`, named for the outing and `knit.meshSimTag`, and never over an earlier
 * report: a second run of the same outing writes `-2`. It ends in a `summary:` block of `key=value` lines that
 * `scripts/sim-compare.py` lines up across runs. It asserts that the run was a fair one (the machine sent the whole
 * workload on time) and the lab's own invariant: once everyone has gathered, no DM is still missing and the oracle
 * converges. A failure is a finding about the mesh or the machine, with the report in its message.
 */
@RunWith(RobolectricTestRunner::class)
class ParkWalkSimTest {
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
    fun friendsWanderAParkAndKeepTalking() =
        runBlocking {
            val seed = SimProperties.seed()
            val walkers = SimProperties.walkers()
            val minutes = SimProperties.minutes()
            val scenario = SimProperties.scenario(seed, walkers, minutes)
            val bound = scenario.inboundSlots?.let { "-q$it" }.orEmpty() + if (scenario.gather) "" else "-walk"
            val dir = SimProperties.out()
            // The report's file holds the outing's progress while it runs, and the report once it is over. Made once the
            // outing is built, so a scenario it refuses (a radio with no such name) leaves no empty report behind.
            var out: File? = null
            val sim = MeshSim(lab, scenario) { line -> out?.appendText("$line\n") }
            out = freshReport(dir, "park-walk-s$seed-w$walkers-m$minutes$bound${SimProperties.tag()}")
            val report = sim.play()
            println(report.text)
            out.writeText(report.text + "\n")

            assertTrue("the workload sent no DM:\n${report.text}", report.dmsSent > 0)
            assertEquals("sends that fell past the end of the run, on a machine that could not keep up:\n${report.text}", 0, report.unsent)
            if (!report.checked) return@runBlocking
            assertEquals("DMs that never arrived, though everyone gathered:\n${report.text}", emptyList<String>(), report.neverArrived)
            assertTrue("the lab's oracle did not converge after the gather:\n${report.text}", report.converged)
        }
}
