package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode
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

    /**
     * A gather that delivers nothing in its window: every link it raises is held both ways from before its ends are told
     * of it ([GatherSeam]) until the gather's report has read what is missing, so every DM is, and the settle then carries
     * them. What decides the verdict is what never arrived, so the outing goes to the oracle and converges rather than
     * reading the gather's misses as stranded.
     */
    @Test
    fun aDmTheGathersWindowMissedAndTheSettleCarriedIsNotStranded() =
        runBlocking {
            val scenario =
                SimScenario(
                    seed = 1,
                    walkers = WALKERS,
                    durationMs = BLINK_MS,
                    fieldM = APART_M,
                    motion = Motion.GROUPS,
                    friends = Friends.Circle.NAME,
                    awayShare = 1.0,
                    dmMeanMs = DM_MEAN_MS,
                    gatherMs = 0,
                    settleMs = SETTLE_MS,
                )
            val held = HoldTheGather()
            val report = MeshSim(lab, scenario, held).play()
            val summary =
                report.text
                    .lineSequence()
                    .map(String::trim)
                    .filter { "=" in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            // The premise: the walk linked nobody across the groups, and the gather was read with every DM missing.
            assertTrue("no DM was sent:\n${report.text}", report.dmsSent > 0)
            assertEquals("the walk linked the groups:\n${report.text}", "0", summary["contacts_made"])
            assertTrue("the gather raised no link to hold", held.links.isNotEmpty())
            assertEquals("the gather was read with DMs in:\n${report.text}", report.dmsSent.toString(), summary["gather_dms_missing"])
            assertEquals("the settle left a DM missing:\n${report.text}", "0", summary["settle_dms_still_missing"])
            assertTrue("a DM the settle carried read as stranded:\n${report.text}", report.neverArrived.isEmpty())
            assertTrue("never converged:\n${report.text}", report.converged)
        }

    // Holds both ways of every link the ring gather raises, from before a frame can cross, until its report has read.
    private class HoldTheGather : GatherSeam {
        var links: List<Pair<LabNode, LabNode>> = emptyList()

        override fun linked(links: List<Pair<LabNode, LabNode>>) {
            this.links = links
            links.forEach { (a, b) ->
                a.transport.hold(b.transport)
                b.transport.hold(a.transport)
            }
        }

        override suspend fun read() {
            links.forEach { (a, b) ->
                a.transport.release(b.transport)
                b.transport.release(a.transport)
            }
        }
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

        // Gates far past the radio's connect range, so only the gather links the groups.
        const val APART_M = 1_000.0

        // Long enough for the held DMs to cross on a slow runner, and short of the oracle's wait, so the oracle still
        // judges custody.
        const val SETTLE_MS = 60_000L
    }
}
