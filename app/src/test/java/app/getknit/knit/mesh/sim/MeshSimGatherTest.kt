package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A walking gather ([GatherBy.WALK]) in an outing with stacks: two groups stand apart at their gates through a short walk, each of
 * their walkers DMing only the other group ([Friends.Circle], every DM away), then walk to the field's centre while the
 * radio raises its own links. Every DM can arrive only over a link the walk-in raised, and the outing delivers and
 * converges over them alone.
 */
@RunWith(RobolectricTestRunner::class)
class MeshSimGatherTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun anOutingThatWalksToItsGatherConvergesOverTheRadiosLinks() =
        runBlocking {
            val scenario =
                SimScenario(
                    seed = 1,
                    walkers = WALKERS,
                    durationMs = BLINK_MS,
                    fieldM = FIELD_M,
                    motion = Motion.GROUPS,
                    friends = Friends.Circle.NAME,
                    awayShare = 1.0,
                    dmMeanMs = DM_MEAN_MS,
                    gatherBy = GatherBy.WALK,
                )
            val report = MeshSim(lab, scenario).play()
            val summary =
                report.text
                    .lineSequence()
                    .map(String::trim)
                    .filter { "=" in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            // The premise: DMs were sent, and the walk itself linked nobody across the groups.
            assertTrue("no DM was sent:\n${report.text}", report.dmsSent > 0)
            assertEquals("the walk linked the groups:\n${report.text}", "0", summary["contacts_made"])
            assertEquals("the walk-in left ${summary["gather_meshes"]} meshes:\n${report.text}", "1", summary["gather_meshes"])
            assertTrue("never converged:\n${report.text}", report.converged)
            assertTrue("a DM never arrived:\n${report.text}", report.neverArrived.isEmpty())
        }

    private companion object {
        const val WALKERS = 6

        // Long enough that a stalled runner still sends: a send more than the walk late is dropped. The groups stand at
        // their gates for the scenario's 10 s departure, so the walk links nobody across them.
        const val BLINK_MS = 5_000L

        // A few DMs from each walker inside the blink.
        const val DM_MEAN_MS = 1_000L

        // Small enough that the walk-in takes seconds, not minutes.
        const val FIELD_M = 40.0
    }
}
