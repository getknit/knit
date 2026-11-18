package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.ln

/** The simulator's pure parts: the walk, the radio, the workload and the arithmetic of the report. */
class SimWorldTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
    fun aScenarioNamesItsRadioAndAnUnknownNameIsRefused() {
        val scenario =
            SimScenario(seed = 1, walkers = 3, durationMs = 60_000, fieldM = FIELD_M, connectM = 20.0, dropM = 30.0, dialMs = 1_500)
        val radio = Radio.of(scenario, initial = setOf(Link(0, 1)))
        assertEquals(DiscRadio.NAME, radio.name)
        assertEquals(setOf(Link(0, 1)), radio.links)
        assertEquals(listOf("connect_m" to 20.0, "drop_m" to 30.0, "dial_ms" to 1_500L), radio.summaryValues())
        assertThrows(IllegalArgumentException::class.java) { Radio.of(scenario.copy(radio = "lora"), initial = emptySet()) }
    }

    @Test
    fun aBluetoothDialTakesItsOwnDrawnTimeTheSameForASeed() {
        val near = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        val raisedAt = { seed: Long ->
            val radio = BleRadio(seed, connectM = 25.0, dropM = 35.0)
            (0L..BleRadio.DIAL_MAX_MS step STEP_MS).first { radio.step(it, near).raised.isNotEmpty() }
        }
        assertEquals("one seed, one dial", raisedAt(7), raisedAt(7))
        val times = (1L..SEEDS).map(raisedAt)
        assertTrue(times.toString(), times.all { it in BleRadio.DIAL_MIN_MS - STEP_MS..BleRadio.DIAL_MAX_MS })
        assertTrue("every seed drew the same dial: $times", times.distinct().size > 1)
    }

    @Test
    fun aPairsNextApproachDrawsADialOfItsOwn() {
        // One pair walks into range and out again, over and over, on each of ten seeds: each approach draws anew, so one
        // dial says nothing of the next. A redraw from a seed one apart (the first gaussian of `java.util.Random` barely
        // moves between adjacent seeds) correlated about 0.44 here.
        val pairs = (1L..SEEDS_FOR_CORRELATION).flatMap { seed -> approachDials(seed).map(::ln).zipWithNext() }
        val r = pearson(pairs.map { it.first }, pairs.map { it.second })
        assertTrue("one approach's dial predicts the next: r = $r", r < MAX_REDRAW_CORRELATION)
    }

    // The dial times of [APPROACHES] approaches of one pair on [seed]'s radio: in range until linked, then far apart.
    private fun approachDials(seed: Long): List<Double> {
        val near = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        val far = listOf(Point(0.0, 0.0), Point(100.0, 0.0))
        val radio = BleRadio(seed, connectM = 25.0, dropM = 35.0)
        var atMs = 0L
        return (1..APPROACHES).map {
            val from = atMs
            while (radio.step(atMs, near).raised.isEmpty()) atMs += STEP_MS
            val took = atMs - from
            atMs += STEP_MS
            radio.step(atMs, far)
            atMs += STEP_MS
            took.toDouble().coerceAtLeast(1.0)
        }
    }

    private fun pearson(
        xs: List<Double>,
        ys: List<Double>,
    ): Double {
        val mx = xs.average()
        val my = ys.average()
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return cov / kotlin.math.sqrt(xs.sumOf { (it - mx) * (it - mx) } * ys.sumOf { (it - my) * (it - my) })
    }

    @Test
    fun aGroupOverTheLinkBudgetIsRefusedAtBootAndOneAtItIsNot() {
        assertThrows(IllegalArgumentException::class.java) { BleRadio(seed = 1, connectM = 25.0, dropM = 35.0, initial = clique(8)) }
        // Seven friends give each walker six links: the whole budget, and no more.
        assertEquals(clique(7), BleRadio(seed = 1, connectM = 25.0, dropM = 35.0, initial = clique(7)).links)
    }

    private fun clique(size: Int): Set<Link> = (0 until size).flatMap { a -> (a + 1 until size).map { b -> Link(a, b) } }.toSet()

    @Test
    fun aFullBluetoothNodeTakesNoMoreLinksAndTheNearestComeFirst() {
        // Walker 0 at the centre, four more on the axes at 21, 18, 20 and 19 m: each in dial range of walker 0 alone.
        val positions = listOf(Point(0.0, 0.0), Point(21.0, 0.0), Point(0.0, 18.0), Point(-20.0, 0.0), Point(0.0, -19.0))
        val radio = BleRadio(seed = 1, connectM = 25.0, dropM = 35.0, dialMedianMs = 1, maxLinks = 2)
        radio.step(0, positions)
        val raised = radio.step(BleRadio.DIAL_MAX_MS, positions).raised
        assertEquals("the two nearest, 18 m and 19 m", setOf(Link(0, 2), Link(0, 4)), raised.toSet())
        // A full node's dial waits: walker 0 holds two, so neither farther walker is raised however long it is in range.
        assertEquals(emptyList<Link>(), radio.step(2 * BleRadio.DIAL_MAX_MS, positions).raised)
        assertEquals(setOf(Link(0, 2), Link(0, 4)), radio.links)
    }

    @Test
    fun aBluetoothRadioAirsItsLinksAndNamesItsNumbers() {
        val scenario = SimScenario(seed = 1, walkers = 3, durationMs = 60_000, fieldM = FIELD_M, radio = BleRadio.NAME)
        val radio = Radio.of(scenario, initial = emptySet())
        assertEquals(BleRadio.LINK_BYTES_PER_S, radio.air?.bytesPerSecond)
        assertEquals(BleRadio.NODE_BYTES_PER_S, radio.nodeAirBytesPerSecond)
        assertEquals(null, Radio.of(scenario.copy(radio = DiscRadio.NAME), initial = emptySet()).air)
        val keys = radio.summaryValues().map { it.first }
        assertEquals(listOf("connect_m", "drop_m", "dial_ms", "max_links", "link_bps", "node_bps"), keys)
    }

    @Test
    fun theSlowBluetoothRadioIsTheSameRadioAtTheSlowestMeasuredDrain() {
        val scenario = SimScenario(seed = 1, walkers = 3, durationMs = 60_000, fieldM = FIELD_M, radio = BleRadio.SLOW_NAME)
        val radio = Radio.of(scenario, initial = emptySet())
        assertEquals(BleRadio.SLOW_NAME, radio.name)
        assertEquals(BleRadio.SLOW_LINK_BYTES_PER_S, radio.air?.bytesPerSecond)
        assertEquals(BleRadio.SLOW_NODE_BYTES_PER_S, radio.nodeAirBytesPerSecond)
        val values = radio.summaryValues().toMap()
        assertEquals(BleRadio.SLOW_LINK_BYTES_PER_S, values["link_bps"])
        assertEquals(BleRadio.SLOW_NODE_BYTES_PER_S, values["node_bps"])
        assertEquals(BleRadio.DIAL_MEDIAN_MS, values["dial_ms"])
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

    @Test
    fun theSummaryIsOneKeyValueLinePerNumberInTheOrderFirstPut() {
        val summary = SimSummary()
        summary["seed"] = 1
        summary["degree_mean"] = 2.5
        summary["probes_max_s"] = null
        summary["oracle"] = "pass"
        summary["seed"] = 2
        assertEquals(listOf("summary:", "  seed=2", "  degree_mean=2.50", "  probes_max_s=none", "  oracle=pass"), summary.lines())
        assertThrows(IllegalArgumentException::class.java) { summary["Bad Key"] = 1 }
        assertThrows(IllegalArgumentException::class.java) { summary["oracle"] = "did not converge" }
    }

    @Test
    fun theSummaryCountsDeliveryAsTheReportLinesDo() {
        val dm = SimEvent.Dm(atMs = 1, from = 0, to = 1, body = "x")
        val outcomes =
            listOf(
                DmOutcome(dm, Reach.DIRECT, latencyMs = 200, arrivedInRun = true, acked = true),
                DmOutcome(dm, Reach.PARTITIONED, latencyMs = 90_000, arrivedInRun = false, acked = false),
                DmOutcome(dm, Reach.PARTITIONED, latencyMs = null, arrivedInRun = false, acked = false),
            )
        val values = deliverySummary(outcomes).toMap()
        assertEquals(Reach.entries.size.inc() * DELIVERY_KEYS, values.size)
        assertEquals(1, values["dm_direct_sent"])
        assertEquals(100, values["dm_direct_in_run_pct"])
        assertEquals("0.2", values["dm_direct_p50_s"])
        assertEquals(0, values["dm_path_sent"])
        assertNull("nothing sent, no share", values["dm_path_in_run_pct"])
        assertEquals(0, values["dm_partitioned_in_run_pct"])
        assertEquals(50, values["dm_partitioned_by_end_pct"])
        // The one that arrived after the run counts towards no latency, as in the report's lines.
        assertNull(values["dm_partitioned_p50_s"])
        assertEquals(33, values["dm_all_in_run_pct"])
        assertEquals("0.2", values["dm_all_p90_s"])
        // The prose says the same of the same DMs: the two are computed apart, so they are held to each other here.
        val lines = deliveryLines(outcomes)
        assertTrue(lines[2], lines[2].contains("arrived in run   0%") && lines[2].contains("arrived by the end 50%"))
        assertTrue(lines[3], lines[3].contains("arrived in run  33%") && lines[3].contains("p50 0.2s p90 0.2s"))
    }

    @Test
    fun theContactSummaryReadsNoneBeforeAnyStepAndCountsTheSteps() {
        val log = ContactLog(walkers = 3)
        val before = log.summaryValues().toMap()
        assertEquals(0, before["contacts_made"])
        assertNull(before["contact_p50_s"])
        assertNull(before["degree_mean"])
        assertNull(before["largest_component_mean"])
        assertNull(before["all_joined_pct"])

        val ab = Link(0, 1)
        val bc = Link(1, 2)
        log.record(atMs = 0, LinkChanges(raised = listOf(ab, bc), dropped = emptyList()), links = setOf(ab, bc))
        log.record(atMs = 4_000, LinkChanges(raised = emptyList(), dropped = listOf(bc)), links = setOf(ab))
        val after = log.summaryValues().toMap()
        assertEquals(2, after["contacts_made"])
        assertEquals("4.0", after["contact_p50_s"])
        // Two links then one, over three walkers and two steps: (2·2 + 2·1) / 2 / 3.
        assertEquals(1.0, after["degree_mean"])
        assertEquals(2, after["degree_max"])
        assertEquals(2.5, after["largest_component_mean"])
        assertEquals(50, after["all_joined_pct"])
    }

    @Test
    fun theOracleWordSaysWhyAGatheredOutingNeverAskedIt() {
        assertEquals("pass", oracleWord(failure = null, stranded = false, unsettled = false))
        assertEquals("fail", oracleWord(failure = "w1–w2 did not converge", stranded = false, unsettled = false))
        // A stranded DM decides before an unsettled field: it is the stronger reason.
        assertEquals("stranded", oracleWord(failure = "not asked", stranded = true, unsettled = true))
        assertEquals("unsettled", oracleWord(failure = "not asked", stranded = false, unsettled = true))
    }

    @Test
    fun aReportNeverOverwritesAnEarlierOne() {
        val dir = tmp.newFolder()
        val first = freshReport(dir, "park-walk-s1")
        first.writeText("seed 1's report")
        val second = freshReport(dir, "park-walk-s1")
        val third = freshReport(dir, "park-walk-s1")
        assertEquals("park-walk-s1.txt", first.name)
        assertEquals("park-walk-s1-2.txt", second.name)
        assertEquals("park-walk-s1-3.txt", third.name)
        assertEquals("seed 1's report", first.readText())
        assertEquals("", second.readText())
    }

    @Test
    fun aHeadlineCounterIsWhatMovedAndAMissingOneIsNoneUnlessItIsAMapEntry() {
        val since = mapOf("frames.framesOriginated" to 10L, "seals.ctlDmSealedBy[PROFILE_FANOUT]" to 4L)
        val now = mapOf("frames.framesOriginated" to 25L, "seals.ctlDmSealedBy[PROFILE_FANOUT]" to 4L)
        val values = headlineCounters(now, since).toMap()
        assertEquals(15L, values["frames_originated_outing"])
        assertEquals(0L, values["seals_profile_fanout_outing"])
        assertEquals("a map entry that never counted", 0L, values["profile_republished_no_row_outing"])
        assertNull("a plain counter that is gone", values["frames_relayed_outing"])
    }

    private companion object {
        const val STEP_MS = 100L
        const val SEEDS = 20L
        const val APPROACHES = 20
        const val SEEDS_FOR_CORRELATION = 10L

        /** Independent draws measure about 0.07 here, a seed-adjacent redraw about 0.44. */
        const val MAX_REDRAW_CORRELATION = 0.25

        /** Sent, in-run share, by-end share, p50 and p90, per reach and for all. */
        const val DELIVERY_KEYS = 5
        const val FIELD_M = 100.0
        const val EPSILON = 1e-6
    }
}
