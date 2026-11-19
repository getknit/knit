package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.lab.LinkAir
import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [applyLinks] carries every kind of radio change onto the lab: a raised link at its rate, a link moved onto another
 * plane at the new rate both ways, each end's route to the other, and a drop. Scripted changes from a radio of the test's
 * own, so every branch runs whatever the real radios happen to draw.
 */
@RunWith(RobolectricTestRunner::class)
class RadioLinksTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    // A radio whose rate the test moves: [fast] reads the fast plane's.
    private class Scripted : Radio {
        var fast = false
        override val name = "scripted"
        override val links = emptySet<Link>()
        override val air = SLOW

        override fun airOf(link: Link): LinkAir = if (fast) FAST else SLOW

        override fun step(
            atMs: Long,
            positions: List<Point>,
            present: BooleanArray?,
        ): LinkChanges = error("scripted: the test applies its own changes")

        override fun summaryValues(): List<Pair<String, Any?>> = emptyList()
    }

    @Test
    fun everyKindOfChangeReachesTheLab() =
        runBlocking {
            val nodes = listOf(lab.node("a"), lab.node("b"))
            val (a, b) = nodes
            val radio = Scripted()
            val link = Link(0, 1)

            lab.applyLinks(LinkChanges(raised = listOf(link), dropped = emptyList()), nodes::get, radio)
            assertEquals(SLOW.bytesPerSecond, a.transport.airRateTo(b.transport))
            assertEquals(SLOW.bytesPerSecond, b.transport.airRateTo(a.transport))

            radio.fast = true
            val moved = LinkChanges(emptyList(), emptyList(), rerated = listOf(link), routes = mapOf(link to BulkRoute.Linked))
            lab.applyLinks(moved, nodes::get, radio)
            assertEquals("a's side moved", FAST.bytesPerSecond, a.transport.airRateTo(b.transport))
            assertEquals("b's side moved", FAST.bytesPerSecond, b.transport.airRateTo(a.transport))
            assertEquals(BulkRoute.Linked, a.transport.bulkRoutes[b.nodeId])
            assertEquals(BulkRoute.Linked, b.transport.bulkRoutes[a.nodeId])

            lab.applyLinks(LinkChanges(emptyList(), dropped = listOf(link), routes = mapOf(link to BulkRoute.None)), nodes::get, radio)
            assertFalse(a.transport.linkedTo(b.transport))
            assertFalse(b.transport.linkedTo(a.transport))
            assertEquals(BulkRoute.None, a.transport.bulkRoutes[b.nodeId])
        }

    private companion object {
        val SLOW = LinkAir(bytesPerSecond = 9_000)
        val FAST = LinkAir(bytesPerSecond = 1_000_000, sharesNodeAir = false)
    }
}
