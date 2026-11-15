package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulator's pure parts: the walk, the radio, the workload and the arithmetic of the report. */
class SimWorldTest {
    private fun walk(seed: Long) =
        RandomWaypoint(
            seed = seed,
            widthM = FIELD_M,
            heightM = FIELD_M,
            starts = listOf(Point(10.0, 10.0), Point(90.0, 90.0)),
            departMs = 5_000,
        )

    @Test
    fun aWalkerStandsAtItsGateUntilItDeparts() {
        val walk = walk(seed = 7)
        assertEquals(Point(10.0, 10.0), walk.position(0, atMs = 0))
        assertEquals(Point(10.0, 10.0), walk.position(0, atMs = 4_999))
        assertEquals(Point(90.0, 90.0), walk.position(1, atMs = 4_999))
    }

    @Test
    fun aSeedWalksTheSamePathAndAnotherSeedADifferentOne() {
        val times = (0L..600_000L step 7_000L).toList()
        val path = { seed: Long -> walk(seed).let { w -> times.map { w.position(0, it) } } }
        assertEquals(path(7), path(7))
        assertNotEquals(path(7), path(8))
    }

    @Test
    fun aWalkerStaysInTheFieldAndNeverOutrunsItsTopSpeed() {
        val walk = walk(seed = 3)
        val stepMs = 1_000L
        val places = (0L..900_000L step stepMs).map { walk.position(1, it) }
        assertTrue(places.all { it.x in 0.0..FIELD_M && it.y in 0.0..FIELD_M })
        val farthest = places.zipWithNext { a, b -> a.distanceTo(b) }.max()
        assertTrue("a second's walk covered $farthest m", farthest <= RandomWaypoint.DEFAULT_SPEED_MPS.endInclusive + EPSILON)
        assertTrue("the walker never left its gate", places.last() != places.first())
    }

    @Test
    fun aPathCanBeReadOutOfOrder() {
        val forward = walk(seed = 5)
        val backward = walk(seed = 5)
        val late = backward.position(0, atMs = 400_000)
        assertEquals(forward.position(0, atMs = 60_000), backward.position(0, atMs = 60_000))
        assertEquals(forward.position(0, atMs = 400_000), late)
    }

    @Test
    fun aLinkNeedsTheDialTimeInRange() {
        val radio = DiscRadio(connectM = 25.0, dropM = 35.0, dialMs = 2_000)
        val near = listOf(Point(0.0, 0.0), Point(20.0, 0.0))
        assertEquals(emptyList<Link>(), radio.step(0, near).raised)
        assertEquals(emptyList<Link>(), radio.step(1_500, near).raised)
        assertEquals(listOf(Link(0, 1)), radio.step(2_000, near).raised)
        assertEquals(setOf(Link(0, 1)), radio.links)
        assertEquals(LinkChanges(emptyList(), emptyList()), radio.step(2_500, near))
    }

    @Test
    fun twoWalkersPassingEachOtherNeverLink() {
        val radio = DiscRadio(connectM = 25.0, dropM = 35.0, dialMs = 2_000)
        val near = listOf(Point(0.0, 0.0), Point(20.0, 0.0))
        val apart = listOf(Point(0.0, 0.0), Point(30.0, 0.0))
        radio.step(0, near)
        radio.step(1_500, apart)
        // Back in range, but the dial starts over: 1.5 s here, not 3.5 s.
        assertEquals(emptyList<Link>(), radio.step(2_000, near).raised)
        assertEquals(emptyList<Link>(), radio.step(3_500, near).raised)
        assertEquals(listOf(Link(0, 1)), radio.step(4_000, near).raised)
    }

    @Test
    fun aLinkHoldsPastItsDialRangeAndDropsPastItsOwn() {
        val radio = DiscRadio(connectM = 25.0, dropM = 35.0, dialMs = 2_000, initial = setOf(Link(0, 1)))
        val stretched = listOf(Point(0.0, 0.0), Point(34.0, 0.0))
        val gone = listOf(Point(0.0, 0.0), Point(36.0, 0.0))
        assertEquals(LinkChanges(emptyList(), emptyList()), radio.step(0, stretched))
        assertEquals(listOf(Link(0, 1)), radio.step(500, gone).dropped)
        assertEquals(emptySet<Link>(), radio.links)
        // Dropped means dialled afresh: back at 34 m is outside the dial range, so it stays down.
        assertEquals(LinkChanges(emptyList(), emptyList()), radio.step(5_000, stretched))
    }

    @Test
    fun hopsCountsLinksAlongTheShortestPath() {
        val line = setOf(Link(0, 1), Link(1, 2), Link(2, 3), Link(0, 3))
        assertEquals(0, hops(line, 2, 2))
        assertEquals(1, hops(line, 3, 0))
        assertEquals(2, hops(line, 0, 2))
        assertNull(hops(line, 0, 4))
        assertEquals(Reach.DIRECT, Reach.of(1))
        assertEquals(Reach.PATH, Reach.of(2))
        assertEquals(Reach.PARTITIONED, Reach.of(null))
    }

    @Test
    fun theLargestComponentCountsLoneWalkersAsOne() {
        assertEquals(1, largestComponent(emptySet(), walkers = 4))
        assertEquals(3, largestComponent(setOf(Link(0, 1), Link(1, 2), Link(3, 4)), walkers = 6))
    }

    @Test
    fun percentileIsNearestRank() {
        val sorted = (1L..10L).toList()
        assertEquals(5L, percentile(sorted, 50))
        assertEquals(9L, percentile(sorted, 90))
        assertEquals(10L, percentile(sorted, 100))
        assertEquals(1L, percentile(sorted, 1))
        assertNull(percentile(emptyList(), 50))
    }

    @Test
    fun theWorkloadKeepsDmsInsideAGroupAndInsideTheRun() {
        val groups = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6))
        val events = Workload.schedule(seed = 11, groups = groups, durationMs = 600_000, dmMeanMs = 20_000, roomMeanMs = 60_000)
        val dms = events.filterIsInstance<SimEvent.Dm>()
        assertTrue("a ten-minute run drew ${dms.size} DMs", dms.size > 50)
        assertTrue(dms.all { dm -> dm.to != dm.from && groups.first { dm.from in it }.contains(dm.to) })
        assertTrue("the walker who came alone DMs no one", dms.none { it.from == 6 })
        assertTrue(events.any { it is SimEvent.Room && it.from == 6 })
        assertTrue(events.all { it.atMs in 1 until 600_000 })
        assertEquals(events.sortedBy { it.atMs }, events)
        assertEquals("every body is its own", events.size, events.map { it.body }.toSet().size)
        assertEquals(events, Workload.schedule(seed = 11, groups = groups, durationMs = 600_000, dmMeanMs = 20_000, roomMeanMs = 60_000))
    }

    @Test
    fun theReportCountsOnlyWhatArrivedInTheRunTowardsLatency() {
        val dm = SimEvent.Dm(atMs = 1, from = 0, to = 1, body = "x")
        val lines =
            deliveryLines(
                listOf(
                    DmOutcome(dm, Reach.DIRECT, latencyMs = 200, arrivedInRun = true, acked = true),
                    DmOutcome(dm, Reach.PARTITIONED, latencyMs = 90_000, arrivedInRun = false, acked = false),
                    DmOutcome(dm, Reach.PARTITIONED, latencyMs = null, arrivedInRun = false, acked = false),
                ),
            )
        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].contains("direct") && lines[0].contains("arrived in run 100%") && lines[0].contains("p50 0.2s"))
        assertTrue(lines[1], lines[1].contains("path") && lines[1].contains("sent    0") && lines[1].contains("n/a"))
        assertTrue(lines[2], lines[2].contains("arrived in run   0%") && lines[2].contains("arrived by the end 50%"))
        // The DM that arrived 90 s on, after the run, counts as arrived by the end and towards no latency.
        assertTrue(lines[2], lines[2].contains("p50 n/a") && lines[2].contains("max n/a"))
        assertTrue(lines[3], lines[3].contains("all") && lines[3].contains("arrived in run  33%") && lines[3].contains("ticked 33%"))
        assertTrue(lines[3], lines[3].contains("p90 0.2s") && lines[3].contains("max 0.2s"))
    }

    private companion object {
        const val FIELD_M = 100.0
        const val EPSILON = 1e-6
    }
}
