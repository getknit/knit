package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The instruments ([PairLog], [ContactStats], [VirtualScanner], [FieldMeter]), the steady start, trace replay, and the
 * baseline they exist to show: random waypoint's exponential gaps and alike pairs.
 */
class FieldMeterTest {
    @Test
    fun aPairLogKeepsEveryContactAndCensorsTheOnesItDidNotSeeBeginOrEnd() {
        val log = PairLog()
        val ab = Link(0, 1)
        val bc = Link(1, 2)
        log.begin(setOf(ab), atMs = 0)
        log.record(1_000, LinkChanges(raised = listOf(bc), dropped = listOf(ab)))
        log.record(3_000, LinkChanges(raised = listOf(ab), dropped = listOf(bc)))
        log.record(6_000, LinkChanges(raised = emptyList(), dropped = listOf(ab)))
        log.record(7_000, LinkChanges(raised = listOf(ab), dropped = emptyList()))

        val contacts = log.contacts(endMs = 10_000)
        assertEquals(
            listOf(Contact(0, 1_000, censored = true), Contact(3_000, 6_000), Contact(7_000, 10_000, censored = true)),
            contacts[ab],
        )
        assertEquals(listOf(Contact(1_000, 3_000)), contacts[bc])

        val stats = ContactStats.of(contacts, walkers = 3, fromMs = 0, toMs = 10_000)
        assertEquals("every contact counts", 4, stats.contacts)
        assertEquals("only the ones seen to begin and end have a length", listOf(2_000L, 3_000L), stats.durationsMs)
        assertEquals("one gap a gap between two of a pair's contacts", listOf(1_000L, 2_000L), stats.ictsMs)
        assertEquals("two of the three pairs met", 66, stats.pairsMetPct)
    }

    @Test
    fun alikePairsScoreNoCorrelationBetweenHalvesAndFriendsWhoStayTogetherNearOne() {
        val walkers = 60
        val hourMs = 3_600_000L
        val random = SimSeeds.stream(1, Purpose.SCAN, 99)
        // Every pair meets at the moments of a Poisson process, about four times an hour: whatever one half held says
        // nothing of the other.
        val alike =
            pairsOf(walkers)
                .associateWith { _ ->
                    generateSequence(Contact(0, 0)) { last ->
                        val start = last.endMs + (-ln(1 - random.nextDouble()) * 900_000).toLong()
                        Contact(start, start + 30_000)
                    }.drop(1).takeWhile { it.endMs <= hourMs }.toList()
                }.filterValues { it.isNotEmpty() }
        val alikeR = checkNotNull(ContactStats.of(alike, walkers, 0, hourMs).halvesR)
        assertTrue("alike pairs scored r = $alikeR", abs(alikeR) < 0.1)

        // Ten pairs stay together the whole hour beside the same random meetings.
        val friends = alike + pairsOf(walkers).take(10).associateWith { listOf(Contact(0, hourMs, censored = true)) }
        val friendsR = checkNotNull(ContactStats.of(friends, walkers, 0, hourMs).halvesR)
        assertTrue("friends scored r = $friendsR", friendsR > 0.9)
    }

    @Test
    fun theTailFitTellsAnExponentialFromAPowerLaw() {
        val random = SimSeeds.stream(2, Purpose.SCAN, 98)
        val exponential = List(4_000) { (-ln(1 - random.nextDouble()) * 600_000).toLong() + 1 }.sorted()
        val pareto = List(4_000) { (60_000 / (1 - random.nextDouble()).pow(1 / 0.6)).toLong() }.sorted()
        val expLlr = checkNotNull(ContactStats.tailLlr(exponential))
        val paretoLlr = checkNotNull(ContactStats.tailLlr(pareto))
        assertTrue("an exponential's tail scored $expLlr", expLlr < 0)
        assertTrue("a power law's tail scored $paretoLlr", paretoLlr > 0)
        assertNull("a handful of gaps fits nothing", ContactStats.tailLlr(exponential.take(ContactStats.MIN_TAIL)))
    }

    @Test
    fun aScannerSightsAPassByAboutAsOftenAsItsWindowsOverlapIt() {
        val oneSided = ScanProfile.IMOTE.copy(name = "one-sided", code = 9, mutual = false)
        val caught = mapOf(oneSided to 0, ScanProfile.IMOTE to 0).toMutableMap()
        val seeds = 400
        for (seed in 1..seeds) {
            for (profile in caught.keys.toList()) {
                val scanner = VirtualScanner(profile, seed.toLong(), walkers = 2)
                // Within range for 20 s, 100 s in, of a 5-minute run.
                for (atMs in 0L..300_000L step 500L) {
                    scanner.tick(atMs)
                    scanner.near(0, 1, if (atMs in 100_000L until 120_000L) 10.0 else 100.0)
                }
                if (scanner.contacts().isNotEmpty()) caught[profile] = caught.getValue(profile) + 1
            }
        }
        // A 5 s inquiry every 120 s overlaps a 20 s pass with probability 25/120 for each end, plus 0.025 for a second scan
        // whose negative jitter brings it into the pass: 0.233 an end, so either end 41% and both 5%.
        val either = caught.getValue(oneSided).toDouble() / seeds
        val both = caught.getValue(ScanProfile.IMOTE).toDouble() / seeds
        assertTrue("one-sided scanners caught $either of the passes", either in 0.33..0.49)
        assertTrue("mutual scanners caught $both of the passes", both < 0.10)
    }

    @Test
    fun aScannerNeverSeesAGapShorterThanAPeriodAndItsJitter() {
        val world = SimWorld(scenario(motion = Motion.WAYPOINT_STEADY, minutes = 120))
        val scanner = VirtualScanner(ScanProfile.IMOTE, seed = 1, walkers = WALKERS)
        for (atMs in 0L..120 * MS_PER_MINUTE step 500L) {
            scanner.tick(atMs)
            val places = (0 until WALKERS).map { world.mobility.position(it, atMs) }
            for (a in 0 until WALKERS) {
                for (b in a + 1 until WALKERS) scanner.near(a, b, places[a].distanceTo(places[b]))
            }
        }
        val gaps = ContactStats.of(scanner.contacts(), WALKERS, 0, 120 * MS_PER_MINUTE).ictsMs
        assertTrue("two hours of the field gave ${gaps.size} gaps", gaps.size > 100)
        val floor = ScanProfile.IMOTE.periodMs + ScanProfile.IMOTE.jitterMs
        assertTrue("the shortest gap was ${gaps.first()} ms", gaps.first() > floor)
    }

    @Test
    fun scannersInStepSeeTheSameContactAtBothEnds() {
        val scanner = VirtualScanner(ScanProfile.TWENTY_SECOND, seed = 1, walkers = 3)
        for (atMs in 0L..120_000L step 500L) {
            scanner.tick(atMs)
            scanner.near(0, 1, 1.0)
            scanner.near(0, 2, 2.0)
        }
        assertEquals(mapOf(Link(0, 1) to listOf(Contact(0, 120_000, censored = true))), scanner.contacts())
    }

    @Test
    fun aSteadyStartStandsAndSpreadsAsTheLongRunWalkDoes() {
        val walkers = 10_000
        val fieldM = 300.0
        val starts = List(walkers) { Point(it % 100 * 3.0, it / 100 * 3.0) }
        val steady = RandomWaypoint(seed = 3, widthM = fieldM, heightM = fieldM, starts = starts, departMs = 0, steady = true)
        val gates = RandomWaypoint(seed = 3, widthM = fieldM, heightM = fieldM, starts = starts, departMs = 0)
        val lateMs = 3 * 3_600_000L

        // A walker stands the share of time the walk spends standing.
        val speeds = RandomWaypoint.DEFAULT_SPEED_MPS
        val inverseSpeed = ln(speeds.endInclusive / speeds.start) / (speeds.endInclusive - speeds.start)
        val walkS = RandomWaypoint.meanDistanceM(fieldM, fieldM) * inverseSpeed
        val standS = RandomWaypoint.DEFAULT_MAX_PAUSE_MS / 2_000.0
        val standing = (0 until walkers).count { steady.position(it, 0) == steady.position(it, 1) }.toDouble() / walkers
        assertTrue("$standing stood at the start, against ${standS / (walkS + standS)}", abs(standing - standS / (walkS + standS)) < 0.03)

        // The long-run walk crowds the middle: the central quarter of the field holds far more than a quarter.
        fun middle(
            walk: RandomWaypoint,
            atMs: Long,
        ): Double =
            (0 until walkers).count { walk.position(it, atMs).let { p -> p.x in 75.0..225.0 && p.y in 75.0..225.0 } }.toDouble() / walkers
        val atStart = middle(steady, 0)
        val longRun = middle(gates, lateMs)
        assertTrue("the middle held $atStart at a steady start and $longRun hours in", abs(atStart - longRun) < 0.03)
        assertTrue("the middle held $atStart, no more than a uniform spread's", atStart > 0.35)
        assertTrue("the gates spread uniformly and put ${middle(gates, 0)} in the middle", middle(gates, 0) < 0.3)
    }

    @Test
    fun theMeanDistanceInARectangleIsGhoshs() {
        assertEquals(0.5214, RandomWaypoint.meanDistanceM(1.0, 1.0), 1e-4)
        val random = SimSeeds.stream(4, Purpose.SCAN, 97)
        val samples = 400_000
        val sampled =
            (0 until samples).sumOf {
                val dx = (random.nextDouble() - random.nextDouble()) * 2
                val dy = random.nextDouble() - random.nextDouble()
                sqrt(dx * dx + dy * dy)
            } / samples
        assertEquals(sampled, RandomWaypoint.meanDistanceM(2.0, 1.0), 0.003)
    }

    @Test
    fun aTraceWalksBetweenItsWaypointsAndIsThereOnlyBetweenItsFirstAndLast() {
        val trace = TraceMobility.parse("two", "0 0 0  10 10 0\n\n5 4 4 6 4 5 8 6 5\n")
        assertEquals(2, trace.walkers)
        assertEquals(Point(5.0, 0.0), trace.position(0, 5_000))
        assertEquals(Point(4.0, 4.5), trace.position(1, 5_500))
        assertEquals(Point(5.0, 5.0), trace.position(1, 7_000))
        assertTrue(trace.present(0, 0) && trace.present(0, 10_000) && !trace.present(0, 10_001))
        assertTrue(!trace.present(1, 4_999) && trace.present(1, 5_000))
        assertEquals(10_000L, trace.durationMs)
        assertEquals(10.0, trace.widthM, 0.0)
        assertEquals(5.0, trace.heightM, 0.0)
        assertThrows(IllegalArgumentException::class.java) { TraceMobility.parse("short", "0 1 1 2 2") }
        assertThrows(IllegalArgumentException::class.java) { TraceMobility.parse("back", "5 1 1 4 2 2") }
        assertThrows(IllegalArgumentException::class.java) { TraceMobility.parse("west", "0 -1 1") }
    }

    @Test
    fun aRecordedEntranceQueueReplaysThroughTheRadio() {
        val trace =
            checkNotNull(javaClass.getResourceAsStream(QUEUE)) { "no $QUEUE among the test resources" }
                .use { TraceMobility.parse(QUEUE, it, gzipped = true) }
        assertEquals("participants in run 030", 75, trace.walkers)
        val scenario =
            SimScenario(
                seed = 1,
                walkers = trace.walkers,
                durationMs = trace.durationMs,
                fieldM = maxOf(trace.widthM, trace.heightM),
                radio = BleRadio.NAME,
                motion = Motion.TRACE,
            )
        val text = FieldRun(SimWorld(scenario, trace)).play()
        val summary = summaryOf(text)
        assertEquals(text, "100", summary["near30_pairs_met_pct"])
        assertTrue("a crowd in 9 m all wants more than six links:\n$text", summary.getValue("cap_time_pct").toInt() > 50)
        assertTrue("nobody left through the gate:\n$text", (0 until trace.walkers).count { !trace.present(it, trace.durationMs) } > 0)
    }

    @Test
    fun randomWaypointsGapsAreExponentialAndItsPairsAlike() {
        // The failures the baseline exists to show, at the 500 ms truth and through an iMote's eyes.
        val waypoint = summaryOf(FieldRun(SimWorld(scenario(motion = Motion.WAYPOINT_STEADY, minutes = 120))).play())
        for (log in listOf("link", "near30", "imote")) {
            val llr = waypoint.getValue("${log}_ict_tail_llr").toDouble()
            assertTrue("$log's gaps scored $llr as a power law over an exponential", llr < 0)
            val r = waypoint.getValue("${log}_halves_r").toDouble()
            assertTrue("$log's pairs scored halves r = $r", abs(r) < 0.15)
        }
        // Scanning every two minutes misses most passes and merges the rest: far fewer contacts than the 500 ms truth.
        val scanned = waypoint.getValue("imote_per_pair_hour").toDouble()
        val truth = waypoint.getValue("near30_per_pair_hour").toDouble()
        assertTrue("the iMote saw $scanned contacts a pair-hour against $truth", scanned < truth / 2)
        // Scored against the conference's iMotes: a number to line runs up by, not a pass.
        assertNotEquals("the iMote's gaps were scored against Infocom 2005", SimSummary.NONE, waypoint.getValue("imote_ks_infocom05_gap"))
        // A steady start links nobody at boot: every DM pair the radio linked, it linked on the walk.
        assertEquals("DM pairs the walk linked in two hours", "100", waypoint["dm_pairs_ever_linked_pct"])
    }

    @Test
    fun walkingGroupsAreUnevenPairsThatNeverPart() {
        val groups = summaryOf(FieldRun(SimWorld(scenario(motion = Motion.GROUPS, minutes = 60))).play())
        val r = groups.getValue("near30_halves_r").toDouble()
        assertTrue("walking groups scored halves r = $r", r > 0.8)
        assertEquals("100", groups["group_together_pct"])
        assertEquals("0.00", groups["group_partings_per_pair_hour"])
        assertEquals("the pairs the groups start linked count as linked", "100", groups["dm_pairs_ever_linked_pct"])
    }

    @Test
    fun randomWaypointGroupsPartAtTheirGates() {
        val gates = summaryOf(FieldRun(SimWorld(scenario(motion = Motion.WAYPOINT, minutes = 60))).play())
        val partings = gates.getValue("group_partings_per_pair_hour").toDouble()
        assertTrue("groups parted $partings times a pair-hour", partings >= 0.9)
        val together = gates.getValue("group_together_pct").toInt()
        assertTrue("groups stayed together $together% of the hour", together < 25)
    }

    private fun scenario(
        motion: String,
        minutes: Int,
    ) = SimScenario(
        seed = 1,
        walkers = WALKERS,
        durationMs = minutes * MS_PER_MINUTE,
        fieldM = 300.0,
        radio = BleRadio.NAME,
        motion = motion,
    )

    private fun pairsOf(walkers: Int): List<Link> = (0 until walkers).flatMap { a -> (a + 1 until walkers).map { Link(a, it) } }

    private fun summaryOf(text: String): Map<String, String> =
        text
            .lineSequence()
            .dropWhile { it != SimSummary.HEADER }
            .drop(1)
            .map(String::trim)
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private companion object {
        const val WALKERS = 48
        const val MS_PER_MINUTE = 60_000L
        const val QUEUE = "juelich-2018crowdqueue-030.movements.gz"
    }
}
