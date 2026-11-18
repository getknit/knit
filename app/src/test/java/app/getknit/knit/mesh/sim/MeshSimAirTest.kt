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
 * A modelled radio's airtime reaches every link [MeshSim] makes: the boot's, each walk step's and the gather's. Two short
 * gathered outings of six walkers on `ble1m`, one whose walk raises links that stay up and one whose walk raises none,
 * then every link left between the nodes once the outing is over is read off their transports.
 */
@RunWith(RobolectricTestRunner::class)
class MeshSimAirTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * Everyone stands within a few metres, so the walk's steps raise links across the two groups and none goes down: each
     * is still up once the gather is over. (A walk-only outing takes every link down before it reports.)
     */
    @Test
    fun theLinksAWalkRaisesAirAtTheRadiosRate() =
        runBlocking {
            val sim = MeshSim(lab, outing(durationMs = WALK_MS, fieldM = HUDDLE_M))
            val report = sim.play()

            assertTrue("the walk raised no link:\n${report.text}", contactsMade(report) > 0)
            assertAllAired(sim.nodes, report)
        }

    /** The two groups stand kilometres apart, so the walk raises nothing, and the gather links them. */
    @Test
    fun theLinksAGatherMakesAirAtTheRadiosRate() =
        runBlocking {
            val sim = MeshSim(lab, outing(durationMs = BLINK_MS, fieldM = APART_M))
            val report = sim.play()

            assertEquals("the walk raised a link:\n${report.text}", 0, contactsMade(report))
            assertAllAired(sim.nodes, report)
        }

    private fun outing(
        durationMs: Long,
        fieldM: Double,
    ) = SimScenario(seed = 1, walkers = WALKERS, durationMs = durationMs, fieldM = fieldM, radio = BleRadio.NAME)

    // Every pair linked now has airtime both ways, and some pair across the two groups is linked: the walk's or the
    // gather's own, since the boot links each group alone.
    private fun assertAllAired(
        nodes: List<LabNode>,
        report: SimReport,
    ) {
        val linked =
            nodes.indices.flatMap { a -> (a + 1 until nodes.size).map { b -> a to b } }.filter { (a, b) ->
                nodes[a].transport.linkedTo(nodes[b].transport) || nodes[b].transport.linkedTo(nodes[a].transport)
            }
        assertTrue("no link across the groups:\n${report.text}", linked.any { (a, b) -> a / GROUP != b / GROUP })
        for ((a, b) in linked) {
            val there = nodes[a].transport.airLinkTo(nodes[b].transport)
            val back = nodes[b].transport.airLinkTo(nodes[a].transport)
            assertTrue("w$a–w$b is linked without air (out $there, back $back)", there && back)
        }
    }

    private fun contactsMade(report: SimReport): Int =
        checkNotNull(
            report.text
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("contacts_made=") },
        ) { "no contacts_made in the summary:\n${report.text}" }.substringAfter('=').toInt()

    private companion object {
        const val WALKERS = 6
        const val GROUP = 3

        // Long enough for a dial: most draws fall under 8 s.
        const val WALK_MS = 8_000L
        const val BLINK_MS = 1_000L
        const val HUDDLE_M = 5.0
        const val APART_M = 10_000.0
    }
}
