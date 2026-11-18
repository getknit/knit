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
 * it) and every other run leaves it out. The report goes to `app/build/mesh-sim/`, or to `knit.meshSimOut`, named for
 * the outing and `knit.meshSimTag`, and never over an earlier report: a second run of the same outing writes `-2`. It
 * ends in a `summary:` block of `key=value` lines that `scripts/sim-compare.py` lines up across runs. It asserts that the run
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
            val scenario = scenario(seed, walkers, minutes)
            val bound = scenario.inboundSlots?.let { "-q$it" }.orEmpty() + if (scenario.gather) "" else "-walk"
            val tag = System.getProperty(TAG)?.let { "-$it" }.orEmpty()
            val dir = File(System.getProperty(OUT) ?: "build/mesh-sim").apply { mkdirs() }
            // The report's file holds the outing's progress while it runs, and the report once it is over. Made once the
            // outing is built, so a scenario it refuses (a radio with no such name) leaves no empty report behind.
            var out: File? = null
            val sim = MeshSim(lab, scenario) { line -> out?.appendText("$line\n") }
            out = freshReport(dir, "park-walk-s$seed-w$walkers-m$minutes$bound$tag")
            val report = sim.play()
            println(report.text)
            out.writeText(report.text + "\n")

            assertTrue("the workload sent no DM:\n${report.text}", report.dmsSent > 0)
            assertEquals("sends that fell past the end of the run, on a machine that could not keep up:\n${report.text}", 0, report.unsent)
            if (!report.checked) return@runBlocking
            assertEquals("DMs that never arrived, though everyone gathered:\n${report.text}", emptyList<String>(), report.neverArrived)
            assertTrue("the lab's oracle did not converge after the gather:\n${report.text}", report.converged)
        }

    // The outing the properties name. The field grows with the crowd, so a bigger run is the same park with more of it, not
    // a denser one.
    private fun scenario(
        seed: Long,
        walkers: Int,
        minutes: Int,
    ) = SimScenario(
        seed = seed,
        walkers = walkers,
        durationMs = minutes * MS_PER_MINUTE,
        fieldM = FIELD_M * sqrt(walkers.toDouble() / DEFAULT_WALKERS),
        inboundSlots = System.getProperty(INBOUND)?.let { if (it == "phone") MeshLab.PHONE_INBOUND_SLOTS else it.toInt() },
        settleMs = (System.getProperty(SETTLE)?.toLong() ?: 0L) * MS_PER_MINUTE,
        gather = System.getProperty(GATHER) != "false",
        radio = System.getProperty(RADIO) ?: DiscRadio.NAME,
        motion = System.getProperty(MOTION) ?: Motion.WAYPOINT,
        groups = System.getProperty(GROUPS) ?: GroupSizes.FIXED,
        friends = System.getProperty(FRIENDS) ?: Friends.Group.name,
    )

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

        /** Which [Radio] makes the links ([Radio.NAMES]); `disc` when unset. */
        const val RADIO = "knit.meshSimRadio"

        /** How the walkers move ([Motion.NAMES]); `rwp` when unset. */
        const val MOTION = "knit.meshSimMotion"

        /** The group sizes ([GroupSizes.of]): a number, `street`, or `fixed` (threes) when unset. */
        const val GROUPS = "knit.meshSimGroups"

        /** Whom the walkers DM ([Friends.NAMES]); `group` when unset. */
        const val FRIENDS = "knit.meshSimFriends"

        /** The directory the report goes to, in place of `build/mesh-sim` (relative to the app module). */
        const val OUT = "knit.meshSimOut"

        /** A word the report's file name ends in: the build or the radio a run measured, to tell two runs apart. */
        const val TAG = "knit.meshSimTag"
        const val DEFAULT_WALKERS = 12
        const val DEFAULT_MINUTES = 4
        const val MS_PER_MINUTE = 60_000L

        /** Twelve walkers in 150 m square: about one other walker in dial range of each, on average. */
        const val FIELD_M = 150.0
    }
}
