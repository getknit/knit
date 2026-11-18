package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mobility models and what feeds them, plain JVM: [RandomWaypoint]'s draws pinned through every refactor, [GroupWalk]'s
 * formation and persistence, the group sizes, the friend circles, and a radio that honours who is in the field.
 */
class MobilityTest {
    /**
     * Seed 7's two walkers at six moments, as the first outings walked them (captured 2026-11-17 before the leg lookup,
     * `StrictMath` and seed streams went in): none of those may move a single draw.
     */
    @Test
    fun theWaypointWalkStillDrawsTheSamePaths() {
        val walk =
            RandomWaypoint(
                seed = 7,
                widthM = 100.0,
                heightM = 100.0,
                starts = listOf(Point(10.0, 10.0), Point(90.0, 90.0)),
                departMs = 5_000,
            )
        val expected =
            listOf(
                Triple(0, 60_000L, Point(51.41330332103098, 39.63455849056628)),
                Triple(0, 123_456L, Point(98.62971658443858, 86.268895725402)),
                Triple(0, 400_000L, Point(2.665221012547977, 41.195946323916466)),
                Triple(0, 900_000L, Point(43.84886441181997, 63.20329148619908)),
                Triple(0, 3_600_000L, Point(70.24141580274457, 58.33348527360598)),
                Triple(1, 60_000L, Point(20.603837654232322, 52.28755465441885)),
                Triple(1, 123_456L, Point(51.085403460537734, 57.5132691847143)),
                Triple(1, 400_000L, Point(58.78279632651766, 42.04580887701859)),
                Triple(1, 900_000L, Point(37.28405878167046, 45.804327499697145)),
                Triple(1, 3_600_000L, Point(49.44953322330638, 53.11873213613204)),
            )
        // Read out of order, latest first, so the binary search finds legs it did not just extend.
        for ((walker, atMs, place) in expected.reversed()) assertEquals("w$walker at $atMs", place, walk.position(walker, atMs))
        assertEquals(Point(10.0, 10.0), walk.position(0, 0))
    }

    /** The Bluetooth dial's draws, keyed through [SimSeeds] now: the tick each of five seeds first raised a pair, as before. */
    @Test
    fun theBluetoothDialStillDrawsTheSameTimes() {
        val near = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        val raisedAt =
            (1L..5L).map { seed ->
                val radio = BleRadio(seed, connectM = 25.0, dropM = 35.0)
                (0L..BleRadio.DIAL_MAX_MS step TICK_MS).first { radio.step(it, near).raised.isNotEmpty() }
            }
        assertEquals(listOf(500L, 1_500L, 3_000L, 4_500L, 10_500L), raisedAt)
    }

    /** Today's workload, friends from the walker's own group: the same schedule as before friend circles existed. */
    @Test
    fun theGroupWorkloadIsTheScheduleItWas() {
        val groups = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6))
        val events = Workload.schedule(seed = 11, groups = groups, durationMs = 600_000, dmMeanMs = 20_000, roomMeanMs = 60_000)
        assertEquals(264, events.size)
        assertEquals(-413_593_964, events.hashCode())
    }

    @Test
    fun aGroupWalkIsTheSameForASeedAndAnotherForAnother() {
        val times = (0L..1_200_000L step 7_000L).toList()
        val paths = { seed: Long -> groupWalk(seed).let { walk -> times.map { t -> (0 until WALKERS).map { walk.position(it, t) } } } }
        assertEquals(paths(3), paths(3))
        assertNotEquals(paths(3), paths(4))
    }

    /** Nobody leaves their group: every member is within its formation of the group's point, all outing, inside the field. */
    @Test
    fun everyMemberStaysWithItsGroupAllOuting() {
        val walk = groupWalk(seed = 3)
        for (atMs in 0L..3_600_000L step 1_000L) {
            for (walker in 0 until WALKERS) {
                val place = walk.position(walker, atMs)
                val group = walk.groupOf(walker)
                // Walking, the line abreast; standing, the scattered spot: a member is only ever in one of the two. A walking
                // point moves every millisecond and a standing one never does, so one millisecond on tells them apart.
                val centre = walk.centre(group, atMs)
                val walking = centre != walk.centre(group, atMs + 1)
                val reach = (if (walking) GroupWalk.ABREAST_M * (GROUPS[group].size - 1) / 2 else GroupWalk.STAND_RADIUS_M) + EPSILON
                assertTrue(
                    "w$walker at $atMs is ${place.distanceTo(walk.centre(group, atMs))} m from its group",
                    place.distanceTo(walk.centre(group, atMs)) <= reach,
                )
                assertTrue("w$walker at $atMs left the field: $place", place.x in 0.0..FIELD_M && place.y in 0.0..FIELD_M)
            }
        }
    }

    /** A pair walks side by side: 0.8 m apart, across the way it walks. Standing, each keeps one spot the whole stand. */
    @Test
    fun aPairWalksAbreastAndStandsStill() {
        val walk = groupWalk(seed = 3)
        var walking = 0
        var standing = 0
        for (atMs in 0L..1_800_000L step 2_000L) {
            val here = walk.centre(PAIR_GROUP, atMs)
            val soon = walk.centre(PAIR_GROUP, atMs + LOOK_MS)
            val a = walk.position(PAIR[0], atMs)
            val b = walk.position(PAIR[1], atMs)
            if (minOf(here.x, here.y, FIELD_M - here.x, FIELD_M - here.y) < EDGE_M) continue
            val before = walk.centre(PAIR_GROUP, atMs - LOOK_MS)
            // Walking straight through [atMs]: moving before and after, the same way. A sample on a turn is left out.
            val inX = here.x - before.x
            val inY = here.y - before.y
            val outX = soon.x - here.x
            val outY = soon.y - here.y
            val straight = here != before && here != soon && abs(inX * outY - inY * outX) < EPSILON && inX * outX + inY * outY > 0
            if (straight) {
                walking++
                assertEquals("apart at $atMs", GroupWalk.ABREAST_M, a.distanceTo(b), EPSILON)
                val across = (a.x - b.x) * (soon.x - here.x) + (a.y - b.y) * (soon.y - here.y)
                assertEquals("across the walk at $atMs", 0.0, across, EPSILON)
            } else if (here == soon && walk.centre(PAIR_GROUP, atMs + 2 * LOOK_MS) == here) {
                // Still for twice the look: a stand that ends exactly at the look would hand it the next walk's start.
                standing++
                assertEquals("a's spot at $atMs", a, walk.position(PAIR[0], atMs + LOOK_MS))
                assertEquals("b's spot at $atMs", b, walk.position(PAIR[1], atMs + LOOK_MS))
            }
        }
        assertTrue("the pair never walked ($walking) or never stood ($standing)", walking > 0 && standing > 0)
    }

    /** One more group arriving moves no group that was already there: each group's draws are its own. */
    @Test
    fun anotherGroupMovesNoOther() {
        val fewer = GroupWalk(3, FIELD_M, FIELD_M, GROUPS.dropLast(1), GATES.dropLast(1), DEPART_MS)
        val more = groupWalk(seed = 3)
        val walkers = GROUPS.dropLast(1).flatten()
        for (atMs in 0L..1_200_000L step 5_000L) {
            for (walker in walkers) assertEquals("w$walker at $atMs", fewer.position(walker, atMs), more.position(walker, atMs))
        }
    }

    /** Over ten thousand walkers the street's sizes hold its people in the published shares, every walker once, in order. */
    @Test
    fun theStreetsGroupsHoldItsPeopleInTheirShares() {
        val groups = GroupSizes.Street.partition(seed = 1, walkers = 10_000)
        assertEquals((0 until 10_000).toList(), groups.flatten())
        assertTrue(groups.all { it.size in 1..GroupSizes.Street.PEOPLE_PERCENT.size })
        val people =
            GroupSizes.Street.PEOPLE_PERCENT.indices.map { i ->
                groups.filter { it.size == i + 1 }.sumOf { it.size } * PERCENT /
                    10_000.0
            }
        for ((i, share) in people.withIndex()) {
            assertEquals("people in groups of ${i + 1}", GroupSizes.Street.PEOPLE_PERCENT[i].toDouble(), share, SHARE_POINTS)
        }
        assertEquals(groups, GroupSizes.Street.partition(seed = 1, walkers = 10_000))
    }

    @Test
    fun groupSizesAreNamed() {
        assertEquals(GroupSizes.Fixed(4), GroupSizes.of(GroupSizes.FIXED, fixedSize = 4))
        assertEquals(GroupSizes.Fixed(2), GroupSizes.of("2"))
        assertEquals(GroupSizes.Street, GroupSizes.of("street"))
        assertEquals((0 until 7).chunked(3), GroupSizes.Fixed(3).partition(seed = 9, walkers = 7))
        assertThrows(IllegalArgumentException::class.java) { GroupSizes.of("crowd") }
    }

    /** Every group is in one circle; circles are pairs of groups, and an odd one out joins the last pair. */
    @Test
    fun theCirclesPairTheGroups() {
        val circles = Circles.of(seed = 5, groupCount = 5)
        assertEquals((0 until 5).toList(), circles.flatten().sorted())
        assertEquals(listOf(2, 3), circles.map { it.size })
        assertEquals(circles, Circles.of(seed = 5, groupCount = 5))
        assertEquals(listOf(listOf(0)), Circles.of(seed = 5, groupCount = 1))
    }

    /**
     * Under circles a DM goes to the sender's circle only, half of them away from its own group; one with no companions
     * DMs only away; and a walker with companions DMs at the same moments as under its group alone.
     */
    @Test
    fun aCircleDmsItsOtherGroupsAtTheAwayShare() {
        val groups = listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5), listOf(6, 7))
        val circles = listOf(listOf(0, 2), listOf(1, 3))
        val circleOf =
            mapOf(0 to setOf(0, 1, 2, 5), 1 to setOf(3, 4, 6, 7)).let { m ->
                (0..7).associateWith { w -> m.values.first { w in it } }
            }
        val groupOf = groups.withIndex().flatMap { (g, group) -> group.map { it to g } }.toMap()
        val schedule = { friends: Friends ->
            Workload.schedule(
                seed = 2,
                groups = groups,
                durationMs = 6 * HOUR_MS,
                dmMeanMs = 20_000,
                roomMeanMs = HOUR_MS,
                friends = friends,
            )
        }
        val dms = schedule(Friends.Circle(circles)).filterIsInstance<SimEvent.Dm>()

        assertTrue(dms.all { it.to != it.from && it.to in circleOf.getValue(it.from) })
        assertTrue("the singleton DMed its own group", dms.filter { it.from == 5 }.all { groupOf[it.to] != groupOf[5] })
        val withCompanions = dms.filter { groups[groupOf.getValue(it.from)].size > 1 }
        val awayShare = withCompanions.count { groupOf[it.to] != groupOf[it.from] }.toDouble() / withCompanions.size
        assertEquals(Friends.Circle.DEFAULT_AWAY_SHARE, awayShare, AWAY_SHARE_SLACK)

        val alone = schedule(Friends.Group).filterIsInstance<SimEvent.Dm>()
        assertEquals(alone.map { it.from to it.atMs }, withCompanions.map { it.from to it.atMs })
    }

    @Test
    fun friendsAreNamed() {
        assertEquals(Friends.Group, Friends.of("group", seed = 1, groups = GROUPS))
        assertEquals(Friends.Circle(Circles.of(seed = 1, groupCount = GROUPS.size)), Friends.of("circles", seed = 1, groups = GROUPS))
        assertThrows(IllegalArgumentException::class.java) { Friends.of("everyone", seed = 1, groups = GROUPS) }
    }

    /** A scenario's motion builds its model, every walker at its group's gate until it sets off; an unknown one is refused. */
    @Test
    fun aScenarioNamesItsMotion() {
        val scenario = SimScenario(seed = 1, walkers = WALKERS, durationMs = 60_000, fieldM = FIELD_M)
        val waypoint = Motion.of(scenario, GROUPS, GATES)
        val together = Motion.of(scenario.copy(motion = Motion.GROUPS), GROUPS, GATES)
        assertTrue(waypoint is RandomWaypoint && together is GroupWalk)
        for ((g, group) in GROUPS.withIndex()) group.forEach { assertEquals(GATES[g], waypoint.position(it, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Motion.of(scenario.copy(motion = "levy"), GROUPS, GATES) }
    }

    /** A walker who leaves drops its link at once and is dialled by nobody while away; back, it dials anew. */
    @Test
    fun aRadioDropsAndSkipsWhoeverIsOutOfTheField() {
        val near = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        val both = booleanArrayOf(true, true)
        val radios =
            listOf(
                { DiscRadio(connectM = 25.0, dropM = 35.0, dialMs = 1_000) },
                { BleRadio(seed = 1, connectM = 25.0, dropM = 35.0, dialMedianMs = 1_000) },
            )
        // Either end may be the one who left.
        for ((make, gone) in radios.flatMap { make ->
            listOf(booleanArrayOf(true, false), booleanArrayOf(false, true)).map { make to it }
        }) {
            val radio = make()
            var atMs = 0L
            while (radio.step(atMs, near, both).raised.isEmpty()) atMs += TICK_MS
            atMs += TICK_MS
            assertEquals("${radio.name}, ${gone.toList()}", listOf(Link(0, 1)), radio.step(atMs, near, gone).dropped)
            val away = (1..BleRadio.DIAL_MAX_MS / TICK_MS).map { radio.step(atMs + it * TICK_MS, near, gone) }
            assertTrue("${radio.name} dialled a walker who was not there", away.all { it.raised.isEmpty() } && radio.links.isEmpty())
            atMs += BleRadio.DIAL_MAX_MS + TICK_MS
            assertTrue("${radio.name} raised a link on the step its walker came back", radio.step(atMs, near, both).raised.isEmpty())
            val back = (1..BleRadio.DIAL_MAX_MS / TICK_MS).firstOrNull { radio.step(atMs + it * TICK_MS, near, both).raised.isNotEmpty() }
            assertTrue("${radio.name} never dialled the walker back", back != null)
        }
    }

    /** The link log is a function of the seed: ten minutes of a group walk through the Bluetooth radio, twice, step for step. */
    @Test
    fun aSeedGivesTheSameLinkLog() {
        val log = {
            val walk = groupWalk(seed = 3, fieldM = SMALL_FIELD_M)
            val radio =
                BleRadio(
                    seed = 3,
                    connectM = 25.0,
                    dropM = 35.0,
                    initial =
                        GROUPS
                            .flatMap { g ->
                                g.flatMapIndexed { i, a ->
                                    g.drop(i + 1).map { Link(a, it) }
                                }
                            }.toSet(),
                )
            (0L..600_000L step TICK_MS).map { t -> radio.step(t, (0 until WALKERS).map { walk.position(it, t) }) }
        }
        val first = log()
        assertTrue("nothing changed in ten minutes", first.any { it.raised.isNotEmpty() || it.dropped.isNotEmpty() })
        assertEquals(first, log())
    }

    private fun groupWalk(
        seed: Long,
        fieldM: Double = FIELD_M,
    ) = GroupWalk(seed, fieldM, fieldM, GROUPS, GATES.map { Point(it.x * fieldM / FIELD_M, it.y * fieldM / FIELD_M) }, DEPART_MS)

    private companion object {
        val GROUPS = listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5), listOf(6, 7, 8, 9, 10))
        val GATES = listOf(Point(50.0, 50.0), Point(250.0, 250.0), Point(150.0, 150.0), Point(60.0, 240.0))
        const val PAIR_GROUP = 1
        val PAIR = listOf(3, 4)
        const val WALKERS = 11
        const val FIELD_M = 300.0
        const val SMALL_FIELD_M = 80.0
        const val DEPART_MS = 10_000L
        const val TICK_MS = 500L
        const val LOOK_MS = 100L

        // Far enough from the field's edge that no offset is clamped.
        const val EDGE_M = 5.0
        const val EPSILON = 1e-9
        const val PERCENT = 100.0
        const val SHARE_POINTS = 2.0
        const val AWAY_SHARE_SLACK = 0.04
        const val HOUR_MS = 3_600_000L
    }
}
