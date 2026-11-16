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
import kotlin.math.sqrt

/**
 * The simulator's first outing (a spike): friends arrive at a park in threes, wander it at random and keep
 * messaging each other, with real stacks whose links follow their feet ([MeshSim]).
 *
 * **A measurement, not a check.** It takes the minutes it simulates and prints what the mesh did with them:
 * `-Pknit.meshSim=<seed>` runs it (`knit.meshSimWalkers`, `knit.meshSimMinutes` and `knit.meshSimHeap` size
 * it) and every other run leaves it out. The report goes to `app/build/mesh-sim/`. It asserts that the run
 * was a fair one (the machine sent the whole workload on time) and the lab's own invariant: once everyone has
 * gathered, no DM is still missing and the oracle converges. A failure is a finding about the mesh or the
 * machine, with the report in its message.
 */
@RunWith(RobolectricTestRunner::class)
class ParkWalkSimTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        assumeTrue("the mesh simulator runs only when asked: -P$SEED=<seed>", System.getProperty(SEED) != null)
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        if (::lab.isInitialized) lab.close()
    }

    @Test
    fun friendsWanderAParkAndKeepTalking() =
        runBlocking {
            val seed = checkNotNull(System.getProperty(SEED)).toLong()
            val walkers = System.getProperty(WALKERS)?.toInt() ?: DEFAULT_WALKERS
            val minutes = System.getProperty(MINUTES)?.toInt() ?: DEFAULT_MINUTES
            // The field grows with the crowd, so a bigger run is the same park with more of it, not a denser one.
            val scenario =
                SimScenario(
                    seed = seed,
                    walkers = walkers,
                    durationMs = minutes * MS_PER_MINUTE,
                    fieldM = FIELD_M * sqrt(walkers.toDouble() / DEFAULT_WALKERS),
                    inboundSlots = System.getProperty(INBOUND)?.let { if (it == "phone") MeshLab.PHONE_INBOUND_SLOTS else it.toInt() },
                    settleMs = (System.getProperty(SETTLE)?.toLong() ?: 0L) * MS_PER_MINUTE,
                    gather = System.getProperty(GATHER) != "false",
                )
            val bound = scenario.inboundSlots?.let { "-q$it" }.orEmpty() + if (scenario.gather) "" else "-walk"
            val out = File("build/mesh-sim").apply { mkdirs() }.resolve("park-walk-s$seed-w$walkers-m$minutes$bound.txt")
            // The report's file holds the outing's progress while it runs, and the report once it is over.
            out.writeText("")
            val report = MeshSim(lab, scenario) { out.appendText("$it\n") }.play()
            println(report.text)
            out.writeText(report.text + "\n")

            assertTrue("the workload sent no DM:\n${report.text}", report.dmsSent > 0)
            assertEquals("sends that fell past the end of the run, on a machine that could not keep up:\n${report.text}", 0, report.unsent)
            if (!report.checked) return@runBlocking
            assertEquals("DMs that never arrived, though everyone gathered:\n${report.text}", emptyList<String>(), report.neverArrived)
            assertTrue("the lab's oracle did not converge after the gather:\n${report.text}", report.converged)
        }

    private companion object {
        const val SEED = "knit.meshSim"
        const val WALKERS = "knit.meshSimWalkers"
        const val MINUTES = "knit.meshSimMinutes"

        /** `phone` ([MeshLab.PHONE_INBOUND_SLOTS]) or a number: each router's inbound queue drops what finds it full. */
        const val INBOUND = "knit.meshSimInbound"

        /** Minutes to watch custody parity after the gather ([SimScenario.settleMs]). */
        const val SETTLE = "knit.meshSimSettleMinutes"

        /** `false` ends the outing at the walk: a measurement with no gather, settle or oracle. */
        const val GATHER = "knit.meshSimGather"
        const val DEFAULT_WALKERS = 12
        const val DEFAULT_MINUTES = 4
        const val MS_PER_MINUTE = 60_000L

        /** Twelve walkers in 150 m square: about one other walker in dial range of each, on average. */
        const val FIELD_M = 150.0
    }
}
