package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A mesh mixing the three link doors (#313, ADR 2026-11.8mhs's mixed-version rule): each pair takes the form its builds
 * allow — sealed between two sealing builds, plain and proven toward a v2.11.x door, plain and unproven toward a v2.10.x
 * one, either way round — with no refusal and no failed seal anywhere. The Nearby room converges over all six links, and
 * a DM relayed in over a plain link and out over a sealed one lands. Only the doors are older ([LinkDoor]): the mesh
 * above them is this build's.
 */
@RunWith(RobolectricTestRunner::class)
class LinkDoorLabTest {
    @get:Rule
    val chaos = LabChaos.rule()

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
    fun aMeshOfThreeDoorsTakesEachPairsFormAndConverges() =
        runBlocking {
            val old = lab.node("old", LabLimits(linkDoor = LinkDoor.PLAIN)).apply { setDisplayName("Old") }
            val mid = lab.node("mid", LabLimits(linkDoor = LinkDoor.PROOF)).apply { setDisplayName("Mid") }
            val hub = lab.node("hub").apply { setDisplayName("Hub") }
            val new = lab.node("new").apply { setDisplayName("New") }
            val nodes = listOf(old, mid, hub, new)

            // Every pair, each older door dialing and answering, so both halves of each form run.
            val pairs =
                listOf(
                    Triple(old, hub, "plain"),
                    Triple(hub, mid, "proven"),
                    Triple(mid, old, "plain"),
                    Triple(new, old, "plain"),
                    Triple(mid, new, "proven"),
                    Triple(hub, new, "sealed"),
                )
            for ((a, b, want) in pairs) {
                val result = lab.doorLink(a, b)
                val got =
                    when {
                        result.sealed -> "sealed"
                        result.proven -> "proven"
                        result.linked -> "plain"
                        else -> "refused"
                    }
                assertEquals("${a.name} → ${b.name}: $result", want, got)
            }
            for (n in nodes) {
                val f = n.metrics.frames()
                assertEquals("${n.name} refused or failed no seal", 0L, f.linkSealRefused + f.linkSealFailed)
            }
            val sealedLinks = nodes.map { it.metrics.frames().linksSealed }
            assertEquals("only the sealing pair counts a sealed link", listOf(0L, 0L, 1L, 1L), sealedLinks)

            lab.awaitAcquainted(*nodes.toTypedArray())
            nodes.forEach { assertTrue(it.sendRoom("hello from ${it.name}")) }
            lab.assertConverged(nodes, atLeast = nodes.size) { Conversations.NEARBY }

            // Old reaches New only through Hub now: in over a plain unproven link, out over the sealed one. Old and Mid part
            // too, so Hub hears the DM once and never counts Mid's relay as the overhear that cancels its own.
            lab.unlink(old, new)
            lab.unlink(mid, new)
            lab.unlink(old, mid)
            assertTrue(old.sendDm(new, "across the doors"))
            lab.assertConverged(listOf(old, new), atLeast = 1, carriers = listOf(mid, hub)) { n ->
                if (n === old) n.dmWith(new) else n.dmWith(old)
            }
            assertEquals("no door broke under the traffic", 0L, nodes.sumOf { it.metrics.frames().linkSealFailed })
            assertEquals(listOf(1, 1, 3, 1), nodes.map { it.transport.neighbors.value.size })
        }
}
