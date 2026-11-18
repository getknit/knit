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
 * An outing with group chats and a backlog: two threes in a field small enough that everyone stays in range, each
 * party with its chat opened and its history sent at the boot. Every group post reaches every member, the stores
 * start the walk holding the history, and the oracle converges over DMs and chats alike.
 */
@RunWith(RobolectricTestRunner::class)
class MeshSimWorkloadTest {
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
    fun groupChatsAndABacklogConvergeAndTheBacklogIsInCustodyAsTheWalkStarts() =
        runBlocking {
            val scenario =
                SimScenario(
                    seed = 1,
                    walkers = WALKERS,
                    durationMs = BLINK_MS,
                    fieldM = FIELD_M,
                    dmMeanMs = MEAN_MS,
                    groupMeanMs = MEAN_MS,
                    backlog = BACKLOG,
                )
            val report = MeshSim(lab, scenario).play()
            val summary =
                report.text
                    .lineSequence()
                    .map(String::trim)
                    .filter { "=" in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            // The premise: the walk posted in the chats, and each store holds at least its party's history.
            assertEquals("2", summary["group_chats"])
            assertTrue("no group post was sent:\n${report.text}", summary.getValue("group_sent").toInt() > 0)
            val atWalk = summary.getValue("custody_rows_at_walk_min").toInt()
            assertTrue("a store started the walk with $atWalk rows:\n${report.text}", atWalk >= PARTY * BACKLOG)
            assertEquals("group posts missing:\n${report.text}", "0", summary["gather_group_posts_missing"])
            assertEquals("100", summary["group_by_end_pct"])
            assertTrue("never converged:\n${report.text}", report.converged)
            assertTrue("a message never arrived:\n${report.text}", report.neverArrived.isEmpty())
        }

    private companion object {
        const val WALKERS = 6
        const val PARTY = 3

        // A send more than the walk late is dropped, so the walk is long enough for a slow runner to still send.
        const val BLINK_MS = 5_000L
        const val MEAN_MS = 1_000L

        // Each walker's history: with its party's, more rows than a store holds from the boot alone.
        const val BACKLOG = 40

        // Every walker within the 25 m connect range of every other, the field's diagonal included.
        const val FIELD_M = 15.0
    }
}
