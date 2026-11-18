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
 * A friend circle's boot ([Friends.Circle]): the whole circle meets and pins each other's keys, then only the walking
 * groups' links stay up, read as the boot reports. Two groups of three on `ble1m`, kilometres apart for a one-second walk
 * with no gather, so the walk raises nothing. A walk-only outing takes down every link the radio holds before it reports,
 * so a link the boot forgot to cut is also still up once the outing is over.
 */
@RunWith(RobolectricTestRunner::class)
class MeshSimBootTest {
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
    fun aCircleMeetsAtBootAndOnlyItsGroupsStayLinked() =
        runBlocking {
            val scenario =
                SimScenario(
                    seed = 1,
                    walkers = WALKERS,
                    durationMs = BLINK_MS,
                    fieldM = APART_M,
                    radio = BleRadio.NAME,
                    friends = Friends.Circle.NAME,
                    gather = false,
                )
            // The links as the boot left them, read when the boot reports and before the walk's first step.
            lateinit var sim: MeshSim
            var atBoot: Set<Link>? = null
            sim =
                MeshSim(lab, scenario) { line ->
                    if (line.startsWith("boot:")) atBoot = linked(sim)
                }
            val report = sim.play()
            val nodes = sim.nodes

            assertEquals("the walk raised a link:\n${report.text}", "0", summaryValue(report, "contacts_made"))
            assertEquals("the links the boot left up", WALKING_PAIRS, atBoot)
            for (a in nodes.indices) {
                for (b in nodes.indices) {
                    if (a == b) continue
                    assertTrue("w$a never pinned w$b's key at boot", nodes[a].knows(nodes[b]))
                    assertTrue("w$a is still linked to w$b once the outing is over", !nodes[a].transport.linkedTo(nodes[b].transport))
                }
            }
        }

    // Every pair with a link now, from either end.
    private fun linked(sim: MeshSim): Set<Link> {
        val nodes = sim.nodes
        return nodes.indices
            .flatMap { a -> (a + 1 until nodes.size).map { b -> Link(a, b) } }
            .filter { nodes[it.a].transport.linkedTo(nodes[it.b].transport) || nodes[it.b].transport.linkedTo(nodes[it.a].transport) }
            .toSet()
    }

    private fun summaryValue(
        report: SimReport,
        key: String,
    ): String =
        checkNotNull(
            report.text
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("$key=") },
        ) { "no $key in the summary:\n${report.text}" }.substringAfter('=')

    private companion object {
        // Two groups of three: one circle of six.
        const val WALKERS = 6
        const val BLINK_MS = 1_000L
        const val APART_M = 10_000.0

        // The two walking groups, {0, 1, 2} and {3, 4, 5}: the only links the radio starts with.
        val WALKING_PAIRS = setOf(Link(0, 1), Link(0, 2), Link(1, 2), Link(3, 4), Link(3, 5), Link(4, 5))
    }
}
