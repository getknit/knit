package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParkMobilityTest {
    @Test
    fun aSeedLaysOutOneConnectedParkWithLoopsAtItsTrailDensity() {
        val park = ParkLayout.generate(SEED, SIDE_M)
        val again = ParkLayout.generate(SEED, SIDE_M)
        assertEquals(park.junctions, again.junctions)
        assertEquals(park.edges, again.edges)
        assertEquals(park.pois, again.pois)
        assertNotEquals("another seed, another park", park.junctions, ParkLayout.generate(SEED + 1, SIDE_M).junctions)
        assertTrue("every junction reaches every other", park.connected())
        assertTrue("${park.edges.size} paths over ${park.junctions.size} junctions make a loop", park.edges.size >= park.junctions.size)
        val trail = park.trailMPerHa
        assertTrue("$trail m of path a hectare", abs(trail - ParkLayout.DEFAULT_TRAIL_M_PER_HA) <= 0.15 * ParkLayout.DEFAULT_TRAIL_M_PER_HA)
        for (gate in park.gates) {
            val at = park.junctions[gate]
            assertTrue("gate $gate at $at is on the fence", at.x == 0.0 || at.y == 0.0 || at.x == SIDE_M || at.y == SIDE_M)
        }
        // A bigger park, for enough places: the heaviest tenth draws most visits.
        val big = ParkLayout.generate(SEED, 2 * SIDE_M)
        val weights = big.pois.map { it.weight }.sortedDescending()
        val top = weights.take(weights.size / 10).sum() / weights.sum()
        assertTrue("the top tenth of ${weights.size} places holds $top of the popularity", top >= 0.4)
    }

    @Test
    fun aVisitorsPlaceIsTheSameWhateverOrderItIsAskedInAndStaysInThePark() {
        val groups = GroupSizes.Street.partition(SEED, WALKERS)
        val times = (0..FOUR_HOURS_MS step STEP_MS).toList()
        val forward = park(groups)
        val backward = park(groups)
        val there = times.map { t -> (0 until WALKERS).map { forward.position(it, t) } }
        val back = times.reversed().map { t -> (0 until WALKERS).map { backward.position(it, t) } }.reversed()
        assertEquals(there, back)
        assertTrue("a walker left the field", there.flatten().all { it.x in 0.0..SIDE_M && it.y in 0.0..SIDE_M })
        // One more group walks its own plan: nobody else's moves.
        val more = park(groups + listOf(listOf(WALKERS, WALKERS + 1)))
        assertEquals(there, times.map { t -> (0 until WALKERS).map { more.position(it, t) } })
    }

    @Test
    fun pausesFollowTheirTruncatedPowerLaw() {
        // Visitors who stay, so every one of them pauses all day.
        val park = park(singletons(PAUSERS), STAYING)
        val pauses = (0 until PAUSERS).flatMap { park.stops(it, EIGHT_HOURS_MS).second }.sorted()
        assertTrue("${pauses.size} pauses", pauses.size > 2_000)
        val k = ParkMobility.PAUSE_BETA - 1
        val lo = ParkMobility.PAUSE_MIN_MS.toDouble()
        val hi = ParkMobility.PAUSE_MAX_MS.toDouble()

        fun law(x: Double) = (1 - StrictMath.pow(lo / x, k)) / (1 - StrictMath.pow(lo / hi, k))
        val distance =
            pauses.withIndex().maxOf { (i, x) ->
                maxOf(
                    abs(law(x.toDouble()) - i.toDouble() / pauses.size),
                    abs(law(x.toDouble()) - (i + 1.0) / pauses.size),
                )
            }
        assertTrue("pauses lie $distance from the law", distance < 0.03)
    }

    @Test
    fun aGreaterLatpExponentPicksNearerPlaces() {
        fun meanHopM(latp: Double): Double {
            val park = park(singletons(PAUSERS), STAYING.copy(latp = latp))
            val hops =
                (0 until PAUSERS).flatMap { unit ->
                    park.stops(unit, EIGHT_HOURS_MS).first.zipWithNext { a, b ->
                        park.layout.pois[a]
                            .at
                            .distanceTo(park.layout.pois[b].at)
                    }
                }
            return hops.average()
        }
        val near = meanHopM(3.0)
        val far = meanHopM(1.5)
        assertTrue("a = 3 hopped $near m, a = 1.5 $far m", near < far)
    }

    @Test
    fun visitorsArriveInTheirWindowAndLeaveWhenTheirBudgetRunsOut() {
        val park = park(singletons(PAUSERS), ParkConfig(arrivalWindowMs = TWO_HOURS_MS))
        val visits = (0 until PAUSERS).map { park.visit(it, DAY_MS) }
        assertTrue("an arrival outside the window", visits.all { (arrived, _) -> arrived in 0..TWO_HOURS_MS })
        for ((unit, visit) in visits.withIndex()) {
            val (arrived, left) = visit
            if (arrived > 0) assertTrue("unit $unit is there before it arrives", !park.present(unit, arrived - 1))
            assertTrue("unit $unit is not there once it arrives", park.present(unit, arrived))
            val gone = checkNotNull(left) { "unit $unit never left in a day" }
            assertTrue("unit $unit is still there after it left", !park.present(unit, gone + 1))
        }
        // A visit is its budget, then the walk to the gate.
        val minutes = visits.map { (arrived, left) -> (checkNotNull(left) - arrived) / MS_PER_MINUTE }.sorted()
        val median = minutes[minutes.size / 2]
        assertTrue("the median visit lasted $median minutes", median in 45L..60L)
    }

    @Test
    fun aParkThatGathersKeepsEveryoneAndBringsThemToTheirSpots() {
        val groups = GroupSizes.Street.partition(SEED, WALKERS)
        // The outing's own config: a gathering outing turns the visit budgets off ([ParkConfig.of]).
        val outing =
            SimScenario(
                seed = SEED,
                walkers = WALKERS,
                durationMs = TWO_HOURS_MS,
                fieldM = SIDE_M,
                motion = Motion.PARK,
                gatherBy = GatherBy.WALK,
                arrivalWindowMs = TEN_MINUTES_MS,
            )
        val park = park(groups, ParkConfig.of(outing))
        val endMs = park.gatheredByMs
        assertTrue("the walk-in ended at $endMs", endMs > TWO_HOURS_MS)
        for ((unit, group) in groups.withIndex()) {
            val (arrived, left) = park.visit(unit, endMs)
            assertEquals("unit $unit left before the gather", null, left)
            assertTrue("unit $unit is away after it arrived", (arrived..endMs step STEP_MS).all { t -> group.all { park.present(it, t) } })
            for (walker in group) {
                val at = park.position(walker, endMs)
                val spot = park.spot(unit)
                assertTrue("w$walker stands ${at.distanceTo(spot)} m from its spot", at.distanceTo(spot) <= Formation.STAND_RADIUS_M + 1e-9)
            }
        }
        val spots = groups.indices.map(park::spot)
        assertEquals("the spots are one crowd at the connect range", spots.size, reachable(spots, CONNECT_M))
    }

    @Test
    fun eachActivityLeavesThePathsAsItsCalibrationSays() {
        // As calibrated: visitors who stay the whole four hours.
        val park = park(singletons(SHARES_WALKERS), STAYING)
        val shares = park.pathShares(FOUR_HOURS_MS)
        for (activity in Activity.entries) {
            val share = checkNotNull(shares[activity]) { "nobody was a $activity" }
            assertTrue(
                "$activity: $share% off the paths against ${activity.offPathTargetPct}%",
                abs(share - activity.offPathTargetPct) <= 3,
            )
        }
    }

    @Test
    fun groupsStayTogetherButForTheirExcursions() {
        fun summary(excursions: Double): Map<String, String> {
            val scenario =
                SimScenario(
                    seed = SEED,
                    walkers = WALKERS,
                    durationMs = TWO_HOURS_MS,
                    fieldM = SIDE_M,
                    radio = BleRadio.NAME,
                    motion = Motion.PARK,
                    groups = GroupSizes.Street.name,
                    excursionsPerHour = excursions,
                )
            return summaryOf(FieldRun(SimWorld(scenario)).play())
        }
        val still = summary(0.0)
        assertEquals("100", still["group_together_pct"])
        assertEquals("0.00", still["group_partings_per_pair_hour"])
        val wandering = summary(ParkConfig.DEFAULT_EXCURSIONS_PER_HOUR)
        val together = wandering.getValue("group_together_pct").toInt()
        assertTrue("groups together $together% of the time with excursions", together in 90..99)
        assertTrue("no excursion parted a pair", wandering.getValue("group_partings_per_pair_hour").toDouble() > 0)
        assertTrue("a parted pair never came back", wandering.getValue("group_rejoined_pct").toInt() >= 95)
    }

    @Test
    fun aWalkingGatherJoinsTheRadiosLinksIntoOneMesh() {
        for (motion in listOf(Motion.PARK, Motion.GROUPS)) {
            val scenario =
                SimScenario(
                    seed = SEED,
                    walkers = WALKERS,
                    durationMs = TEN_MINUTES_MS,
                    fieldM = if (motion == Motion.PARK) SIDE_M else SMALL_FIELD_M,
                    motion = motion,
                    gatherBy = GatherBy.WALK,
                )
            val summary = summaryOf(FieldRun(SimWorld(scenario)).play())
            assertEquals("$motion: ${summary["gather_meshes"]} meshes", "1", summary["gather_meshes"])
            assertNotEquals("$motion: the links never joined everyone", SimSummary.NONE, summary.getValue("gather_connected_s"))
        }
    }

    private fun park(
        groups: List<List<Int>>,
        config: ParkConfig = ParkConfig(),
    ) = ParkMobility(SEED, ParkLayout.generate(SEED, SIDE_M), groups, config)

    private fun singletons(count: Int): List<List<Int>> = (0 until count).map { listOf(it) }

    // How many of [points] the first reaches by hops of at most [rangeM].
    private fun reachable(
        points: List<Point>,
        rangeM: Double,
    ): Int {
        val seen = BooleanArray(points.size)
        val queue = ArrayDeque(listOf(0))
        seen[0] = true
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (other in points.indices) {
                if (!seen[other] && points[at].distanceTo(points[other]) <= rangeM) {
                    seen[other] = true
                    queue += other
                }
            }
        }
        return seen.count { it }
    }

    private fun summaryOf(text: String): Map<String, String> =
        text
            .lineSequence()
            .dropWhile { it != SimSummary.HEADER }
            .drop(1)
            .map { it.trim().split("=", limit = 2) }
            .associate { (key, value) -> key to value }

    private companion object {
        const val SEED = 1L
        const val SIDE_M = 1_000.0
        const val SMALL_FIELD_M = 150.0
        const val WALKERS = 48
        const val PAUSERS = 120
        const val SHARES_WALKERS = 600
        const val CONNECT_M = 25.0
        const val STEP_MS = 5_000L
        const val MS_PER_MINUTE = 60_000L
        const val TEN_MINUTES_MS = 600_000L
        const val TWO_HOURS_MS = 7_200_000L
        const val FOUR_HOURS_MS = 14_400_000L
        const val EIGHT_HOURS_MS = 28_800_000L
        const val DAY_MS = 86_400_000L

        // A park whose visitors never run out of time, as an outing that gathers has it.
        val STAYING = ParkConfig(budgets = false)
    }
}
