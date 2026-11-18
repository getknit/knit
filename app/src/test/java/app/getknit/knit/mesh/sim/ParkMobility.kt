package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.ParkLayout.Kind
import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What a park visitor came to do: how fast it goes, how often a leg leaves the paths ([offPath]), whether it stops and
 * whether it sits on the grass. [offPath] is calibrated so the share of walking time more than [ParkMobility.OFF_PATH_M]
 * from a path lands near [offPathTargetPct]: Korpilo et al. 2018 measured 13.3% for runners in Helsinki Central Park,
 * and the walkers' and dog walkers' shares, which its GPS tracks barely held, are guesses. The [share]s of visitors are
 * guesses too, named so a run that leans on them says so.
 */
enum class Activity(
    val share: Double,
    val speedMps: ClosedFloatingPointRange<Double>,
    val offPath: Double,
    val offPathTargetPct: Int,
    val pauses: Boolean,
    val lawns: Boolean,
) {
    /** 1.14 m/s is the recreational walker's mean (Ohio State, 2020). */
    WALKER(0.70, 1.0..1.3, 0.38, 20, pauses = true, lawns = true),
    DOG_WALKER(0.20, 0.8..1.2, 0.45, 25, pauses = true, lawns = true),
    RUNNER(0.10, 2.5..3.3, 0.31, 13, pauses = false, lawns = false),
}

/** How a [ParkMobility] runs: the scenario's knobs ([of]) and the guesses nobody has measured, named. */
data class ParkConfig(
    /** How long a unit stands at its gate before it sets off, as every motion's groups do. */
    val departMs: Long = 10_000,
    /** Units arrive at a time drawn evenly over this window; 0 has everyone at a gate at the start. */
    val arrivalWindowMs: Long = 0,
    /** LATP's exponent: the next place is chosen in proportion to its popularity over its distance to this power. */
    val latp: Double = DEFAULT_LATP,
    /** Excursions a member of a group starts an hour, on average. */
    val excursionsPerHour: Double = DEFAULT_EXCURSIONS_PER_HOUR,
    /** When everyone heads for the meeting lawn ([GatherBy.WALK]); null when nobody gathers by walking. */
    val gatherAtMs: Long? = null,
    /** Whether a visit ends when its budget runs out. Off for an outing that ends in a gather: nobody leaves before it. */
    val budgets: Boolean = true,
) {
    init {
        require(departMs >= 1) { "a unit stands at its gate a moment at least: $departMs" }
        require(gatherAtMs == null || arrivalWindowMs < gatherAtMs) { "everyone arrives before the gather: $arrivalWindowMs, $gatherAtMs" }
        require(excursionsPerHour >= 0) { "a rate: $excursionsPerHour" }
    }

    companion object {
        /** SLAW's fits run from 1.5 (campuses) to 3 (a theme park, a fair): 2 is between. */
        const val DEFAULT_LATP = 2.0

        /** A guess: no study has measured how often a member of a walking group wanders off and comes back. */
        const val DEFAULT_EXCURSIONS_PER_HOUR = 2.0

        /** The scenario's park: its knobs, the gather it walks to, and budgets only when it never gathers. */
        fun of(scenario: SimScenario) =
            ParkConfig(
                departMs = scenario.departMs,
                arrivalWindowMs = scenario.arrivalWindowMs,
                latp = scenario.latp,
                excursionsPerHour = scenario.excursionsPerHour,
                gatherAtMs = scenario.durationMs.takeIf { GatherBy.walks(scenario) },
                budgets = !scenario.gather,
            )
    }
}

/**
 * Visitors to a sparse park ([ParkLayout]), after the report's park model, SLAW's attraction logic on the ONE
 * simulator's map-based movement. The unit that walks is a group ([groups], singletons included):
 *
 * - **It arrives** at a gate, at a time drawn over [ParkConfig.arrivalWindowMs] ([Purpose.ARRIVAL]); before that it is
 *   not in the field. It stands there [ParkConfig.departMs], as every motion's groups do.
 * - **It plans a few places** ([PLACES_PER_VISIT], drawn by popularity), as SLAW's walkers plan a subset of the
 *   hot spots: each daily trace touches only 5 to 10% of a site's (Lee et al.). So two groups that planned the same
 *   places keep meeting there and two that did not rarely meet at all, the unevenness every human trace has.
 * - **It goes from place to place** among its own, choosing each next one it has not yet been to by least-action trip
 *   planning (LATP): in proportion to the place's popularity over its distance to the power [ParkConfig.latp] (SLAW).
 *   It follows the shortest path there, unless a leg cuts across the grass ([Activity.offPath]), and crosses the grass
 *   to a spot on a lawn. Once it has been to all of them, it goes round them again.
 * - **It stops** at each for a pause drawn from a truncated power law, exponent [PAUSE_BETA] from [PAUSE_MIN_MS] to
 *   [PAUSE_MAX_MS] (the truncated Lévy walk's pauses, cut to a visit's length). Runners don't stop.
 * - **It leaves** by the nearest gate once its visit budget runs out (lognormal, median [BUDGET_MEDIAN_MS], a guess),
 *   unless the outing gathers ([ParkConfig.budgets]).
 * - **It gathers**, under [ParkConfig.gatherAtMs]: the leg under way is cut there, and the unit walks the paths to the
 *   meeting lawn ([ParkLayout.meeting]) and stands at its own spot ([GatherSpots]).
 *
 * Members stand at their group's [Formation] offsets, and each member of a group goes on excursions of its own
 * ([Purpose.EXCURSION]): [ParkConfig.excursionsPerHour] an hour, [EXCURSION_M] out at [EXCURSION_MPS], away
 * [EXCURSION_HOLD_MS], then back. A unit's plan is drawn lazily, leg by leg, from its own streams, so a position is a
 * pure function of the seed, the walker and the time, whatever order it is asked in.
 */
class ParkMobility(
    private val seed: Long,
    val layout: ParkLayout,
    groups: List<List<Int>>,
    val config: ParkConfig = ParkConfig(),
) : Mobility,
    Gathering {
    private enum class Way { ABSENT, STAND, PATH, OFF }

    // One straight stretch of a unit's plan; a path leg that ends at a junction says which.
    private class Leg(
        val fromMs: Long,
        val toMs: Long,
        val from: Point,
        val to: Point,
        val way: Way,
        val toJunction: Int = NONE,
    ) {
        val walking: Boolean get() = way == Way.PATH || way == Way.OFF

        fun at(atMs: Long): Point {
            if (from == to || toMs == Long.MAX_VALUE) return from
            val done = ((atMs - fromMs).toDouble() / (toMs - fromMs)).coerceIn(0.0, 1.0)
            return Point(from.x + (to.x - from.x) * done, from.y + (to.y - from.y) * done)
        }

        fun heading(): Point {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val length = sqrt(dx * dx + dy * dy)
            return Point(dx / length, dy / length)
        }
    }

    private val sizes = groups.map { it.size }
    private val unitOf: IntArray
    private val slotOf: IntArray

    init {
        val walkers = groups.sumOf { it.size }
        unitOf = IntArray(walkers)
        slotOf = IntArray(walkers)
        for ((unit, group) in groups.withIndex()) {
            for ((slot, walker) in group.withIndex()) {
                unitOf[walker] = unit
                slotOf[walker] = slot
            }
        }
    }

    private val spots = GatherSpots.vogel(groups.size, layout.meeting.at, layout.sideM)
    private val plans = groups.indices.map { Plan(it) }
    private val excursions = unitOf.indices.map { walker -> if (sizes[unitOf[walker]] > 1) Excursions(walker) else null }

    /** Each unit's activity, by unit. */
    val activities: List<Activity> get() = plans.map { it.activity }

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        val unit = unitOf[walker]
        val plan = plans[unit]
        val index = plan.legIndex(atMs)
        val leg = plan.legs[index]
        val point = leg.at(atMs)
        if (sizes[unit] == 1) return clamp(point)
        val heading = if (leg.walking) leg.heading() else null
        val formation = Formation.offset(seed, walker, slotOf[walker], sizes[unit], heading, index.toLong())
        val away = excursions[walker]?.offset(atMs) ?: ORIGIN
        return clamp(Point(point.x + formation.x + away.x, point.y + formation.y + away.y))
    }

    override fun present(
        walker: Int,
        atMs: Long,
    ): Boolean = plans[unitOf[walker]].let { it.legs[it.legIndex(atMs)].way != Way.ABSENT }

    /** When the last unit reaches its spot on the meeting lawn. */
    override val gatheredByMs: Long by lazy {
        checkNotNull(config.gatherAtMs) { "this park never gathers" }
        plans.maxOf { it.gatheredAt() }
    }

    /**
     * Of each activity's walking time up to [untilMs], sampled every [stepMs], the share in whole percent its units spent
     * more than [OFF_PATH_M] from a path: the measure Korpilo et al. read off Helsinki's GPS tracks. Null for an
     * activity nobody walked.
     */
    fun pathShares(
        untilMs: Long,
        stepMs: Long = PATH_SAMPLE_MS,
    ): Map<Activity, Int?> {
        val walking = LongArray(Activity.entries.size)
        val off = LongArray(Activity.entries.size)
        for (plan in plans) {
            var atMs = 0L
            while (atMs <= untilMs) {
                val leg = plan.legs[plan.legIndex(atMs)]
                if (leg.walking) {
                    walking[plan.activity.ordinal]++
                    if (layout.distanceToPath(leg.at(atMs)) > OFF_PATH_M) off[plan.activity.ordinal]++
                }
                atMs += stepMs
            }
        }
        return Activity.entries.associateWith {
            if (walking[it.ordinal] ==
                0L
            ) {
                null
            } else {
                (off[it.ordinal] * PERCENT / walking[it.ordinal]).toInt()
            }
        }
    }

    /** The report's line about the park and the walk through it up to [untilMs], and its summary keys. */
    fun report(untilMs: Long): Pair<String, List<Pair<String, Any?>>> {
        val shares = pathShares(untilMs)
        val kinds = layout.pois.groupingBy { it.kind }.eachCount()
        val doing = activities.groupingBy { it }.eachCount()
        val left = plans.indices.count { visit(it, untilMs).second != null }
        val trail = "%.0f".format(Locale.ROOT, layout.trailMPerHa)
        val line =
            "park: ${layout.junctions.size} junctions, ${layout.edges.size} paths ($trail m a hectare), ${layout.gates.size} gates, " +
                "${layout.pois.size} places (" + Kind.entries.joinToString(", ") { "${kinds[it] ?: 0} ${it.name.lowercase()}" } + "); " +
                "groups " + Activity.entries.joinToString(", ") { "${doing[it] ?: 0} ${label(it)}" } + "; walking over " +
                "${OFF_PATH_M.toInt()} m from a path: " +
                Activity.entries.joinToString(", ") { "${label(it)} ${shares[it]?.let { share -> "$share%" } ?: "n/a"}" } +
                "; $left of ${plans.size} groups had left"
        val values =
            listOf<Pair<String, Any?>>("park_trail_m_per_ha" to layout.trailMPerHa) +
                Activity.entries.map { "park_off_path_${it.name.lowercase()}_pct" to shares[it] } +
                ("park_groups_left" to left)
        return line to values
    }

    private fun label(activity: Activity) = activity.name.lowercase().replace('_', ' ')

    /** When [unit] arrived and, if it left by [untilMs], when it went out of the gate. */
    internal fun visit(
        unit: Int,
        untilMs: Long,
    ): Pair<Long, Long?> {
        val plan = plans[unit]
        plan.legIndex(untilMs)
        val left = plan.legs.firstOrNull { it.way == Way.ABSENT && it.toMs == Long.MAX_VALUE }?.fromMs
        return plan.arriveMs to left?.takeIf { it <= untilMs }
    }

    /** The places [unit] went to by [untilMs], in order, and the pauses it drew for them. */
    internal fun stops(
        unit: Int,
        untilMs: Long,
    ): Pair<List<Int>, List<Long>> {
        val plan = plans[unit]
        plan.legIndex(untilMs)
        return plan.stops.toList() to plan.pausesMs.toList()
    }

    /** The spot on the meeting lawn [unit] gathers at. */
    internal fun spot(unit: Int): Point = spots[unit]

    private fun clamp(point: Point) = Point(point.x.coerceIn(0.0, layout.sideM), point.y.coerceIn(0.0, layout.sideM))

    // One unit's plan, drawn as far as it has been asked for.
    private inner class Plan(
        val unit: Int,
    ) {
        private val random = SimSeeds.stream(seed, Purpose.PARK_TRIP, unit.toLong())
        val activity: Activity
        private val speedMps: Double
        val arriveMs: Long
        val departMs: Long
        private val budgetEndMs: Long
        val legs = mutableListOf<Leg>()
        val stops = mutableListOf<Int>()
        val pausesMs = mutableListOf<Long>()
        private var at: Point
        private var junction: Int
        private var here = NONE
        private val visited = BooleanArray(layout.pois.size)
        private val places: List<Int>
        private var gatheredAtMs: Long? = null
        private var done = false

        init {
            val draws = SimSeeds.stream(seed, Purpose.PARK_UNIT, unit.toLong())
            activity = drawActivity(draws, sizes[unit])
            val band = GroupWalk.slower(activity.speedMps, sizes[unit])
            speedMps = band.start + draws.nextDouble() * (band.endInclusive - band.start)
            junction = layout.gates[draws.nextInt(layout.gates.size)]
            val budgetMs = BUDGET_MEDIAN_MS * StrictMath.exp(BUDGET_SIGMA * gaussian(draws))
            places = planPlaces(draws)
            arriveMs =
                if (config.arrivalWindowMs > 0) {
                    SimSeeds.stream(seed, Purpose.ARRIVAL, unit.toLong()).nextLong(config.arrivalWindowMs + 1)
                } else {
                    0
                }
            departMs = arriveMs + config.departMs
            budgetEndMs = if (config.budgets) departMs + budgetMs.toLong() else Long.MAX_VALUE
            at = layout.junctions[junction]
            if (arriveMs > 0) legs += Leg(0, arriveMs, at, at, Way.ABSENT)
            legs += Leg(arriveMs, departMs, at, at, Way.STAND)
        }

        // The leg holding [atMs], by binary search over the legs' ends, drawing more of the plan while it falls short.
        fun legIndex(atMs: Long): Int {
            while (!done && legs.last().toMs <= atMs) extend()
            var lo = 0
            var hi = legs.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (legs[mid].toMs > atMs) hi = mid else lo = mid + 1
            }
            return lo
        }

        fun gatheredAt(): Long {
            while (!done) extend()
            return checkNotNull(gatheredAtMs) { "unit $unit left instead of gathering" }
        }

        // The next place and the pause there, or the way out once the budget has run out.
        private fun extend() {
            val startMs = legs.last().toMs
            val step = mutableListOf<Leg>()
            if (startMs >= budgetEndMs) {
                leave(step, startMs)
            } else {
                val poi = choose()
                val arrivedMs = walkTo(step, startMs, poi)
                if (activity.pauses) {
                    val pauseMs = pauseMs()
                    pausesMs += pauseMs
                    val endMs = minOf(arrivedMs + pauseMs, budgetEndMs)
                    if (endMs > arrivedMs) step += Leg(arrivedMs, endMs, at, at, Way.STAND)
                }
            }
            if (step.isEmpty()) step += Leg(startMs, startMs + 1, at, at, Way.STAND)
            commit(step)
        }

        // A step's legs, or the part of them before the gather and the walk to the meeting lawn after it.
        private fun commit(step: List<Leg>) {
            val gatherAtMs = config.gatherAtMs
            if (gatherAtMs == null || step.last().toMs <= gatherAtMs) {
                legs += step
                done = step.last().toMs == Long.MAX_VALUE
                return
            }
            val cut = step.indexOfFirst { it.toMs > gatherAtMs }
            legs += step.take(cut)
            val leg = step[cut]
            check(leg.way != Way.ABSENT) { "unit $unit is away when the gather begins" }
            val point = leg.at(gatherAtMs)
            if (gatherAtMs > leg.fromMs) legs += Leg(leg.fromMs, gatherAtMs, leg.from, point, leg.way)
            gather(gatherAtMs, point, leg)
        }

        // From [point], where the gather found it: on along its path, or to the nearest junction, then to its spot.
        private fun gather(
            fromMs: Long,
            point: Point,
            leg: Leg,
        ) {
            val start = if (leg.way == Way.PATH && leg.toJunction != NONE) leg.toJunction else layout.nearestJunction(point)
            val way = if (leg.way == Way.PATH && leg.toJunction != NONE) Way.PATH else Way.OFF
            var atMs = walk(legs, fromMs, point, layout.junctions[start], way, start)
            atMs = walkPath(legs, atMs, start, layout.meeting.access)
            val spot = spots[unit]
            atMs = walk(legs, atMs, layout.junctions[layout.meeting.access], spot, Way.OFF)
            legs += Leg(atMs, Long.MAX_VALUE, spot, spot, Way.STAND)
            gatheredAtMs = atMs
            done = true
        }

        // Out of the place it stands at to its junction, then the shortest path to the nearest gate, and gone.
        private fun leave(
            step: MutableList<Leg>,
            fromMs: Long,
        ) {
            var atMs = walk(step, fromMs, at, layout.junctions[junction], exitWay(), junction)
            val gate = layout.nearestGate(junction)
            atMs = walkPath(step, atMs, junction, gate)
            step += Leg(atMs, Long.MAX_VALUE, layout.junctions[gate], layout.junctions[gate], Way.ABSENT)
        }

        // To place [poi]: across the grass, or out to its junction and along the paths, then onto its spot.
        private fun walkTo(
            step: MutableList<Leg>,
            fromMs: Long,
            poi: Int,
        ): Long {
            val place = layout.pois[poi]
            val spot = if (place.kind == Kind.LAWN) restingSpot(place) else place.at
            var atMs = fromMs
            if (random.nextDouble() < activity.offPath) {
                atMs = walk(step, atMs, at, spot, Way.OFF)
            } else {
                atMs = walk(step, atMs, at, layout.junctions[junction], exitWay(), junction)
                atMs = walkPath(step, atMs, junction, place.access)
                atMs = walk(step, atMs, layout.junctions[place.access], spot, if (place.kind == Kind.LAWN) Way.OFF else Way.PATH)
            }
            at = spot
            junction = place.access
            here = poi
            visited[poi] = true
            stops += poi
            return atMs
        }

        private fun exitWay(): Way = if (here != NONE && layout.pois[here].kind == Kind.LAWN) Way.OFF else Way.PATH

        private fun walkPath(
            into: MutableList<Leg>,
            fromMs: Long,
            from: Int,
            to: Int,
        ): Long {
            var atMs = fromMs
            for ((a, b) in layout.route(from, to).zipWithNext()) {
                atMs =
                    walk(into, atMs, layout.junctions[a], layout.junctions[b], Way.PATH, b)
            }
            return atMs
        }

        // A straight walk at the unit's pace, its time rounded up so the pace is never beaten; none when already there.
        private fun walk(
            into: MutableList<Leg>,
            fromMs: Long,
            from: Point,
            to: Point,
            way: Way,
            toJunction: Int = NONE,
        ): Long {
            val distanceM = from.distanceTo(to)
            if (distanceM == 0.0) return fromMs
            val arriveMs = fromMs + ceil(distanceM / speedMps * MS_PER_S).toLong().coerceAtLeast(1)
            into += Leg(fromMs, arriveMs, from, to, way, toJunction)
            return arriveMs
        }

        // The places this visit is for: [PLACES_PER_VISIT] of those open to the unit, each drawn by popularity.
        private fun planPlaces(draws: Random): List<Int> {
            val open =
                layout.pois.indices
                    .filter { activity.lawns || layout.pois[it].kind != Kind.LAWN }
                    .ifEmpty { layout.pois.indices.toList() }
            val count = minOf(open.size, PLACES_PER_VISIT.first + draws.nextInt(PLACES_PER_VISIT.last - PLACES_PER_VISIT.first + 1))
            val left = open.toMutableList()
            return List(count) { _ ->
                var draw = draws.nextDouble() * left.sumOf { layout.pois[it].weight }
                var pick = left.last()
                for (poi in left) {
                    if (draw < layout.pois[poi].weight) {
                        pick = poi
                        break
                    }
                    draw -= layout.pois[poi].weight
                }
                left.remove(pick)
                pick
            }
        }

        // LATP over its places not yet visited (all of them again once every one has been).
        private fun choose(): Int {
            var candidates = places.filter { it != here && !visited[it] }
            if (candidates.isEmpty()) {
                visited.fill(false)
                candidates = places.filter { it != here }.ifEmpty { places }
            }
            val weights =
                candidates.map {
                    val distanceM = maxOf(MIN_DISTANCE_M, at.distanceTo(layout.pois[it].at))
                    layout.pois[it].weight / StrictMath.pow(distanceM, config.latp)
                }
            var draw = random.nextDouble() * weights.sum()
            for ((i, weight) in weights.withIndex()) {
                if (draw < weight) return candidates[i]
                draw -= weight
            }
            return candidates.last()
        }

        // Inverse of the truncated power law's CDF: xmin·(1 − u·(1 − (xmin/xmax)^k))^(−1/k), k = β − 1.
        private fun pauseMs(): Long {
            val k = PAUSE_BETA - 1
            val ratio = StrictMath.pow(PAUSE_MIN_MS.toDouble() / PAUSE_MAX_MS, k)
            val x = PAUSE_MIN_MS * StrictMath.pow(1 - random.nextDouble() * (1 - ratio), -1 / k)
            return x.toLong().coerceIn(PAUSE_MIN_MS, PAUSE_MAX_MS)
        }

        private fun restingSpot(lawn: ParkLayout.Poi): Point {
            while (true) {
                val x = (random.nextDouble() * 2 - 1) * lawn.radiusM
                val y = (random.nextDouble() * 2 - 1) * lawn.radiusM
                if (sqrt(x * x + y * y) <= lawn.radiusM) return clamp(Point(lawn.at.x + x, lawn.at.y + y))
            }
        }
    }

    // One member's excursions, drawn as far as they have been asked for: out, a while away, back.
    // One excursion: from [startMs], [outMs] out to [reach] off the group, [holdMs] there, [outMs] back.
    private class Trip(
        val startMs: Long,
        val outMs: Long,
        val holdMs: Long,
        val reach: Point,
    )

    private inner class Excursions(
        walker: Int,
    ) {
        private val random = SimSeeds.stream(seed, Purpose.EXCURSION, walker.toLong())
        private val trips = mutableListOf<Trip>()
        private val meanGapMs = if (config.excursionsPerHour > 0) MS_PER_HOUR / config.excursionsPerHour else 0.0
        private var over = config.excursionsPerHour <= 0
        private var nextMs = if (over) 0 else plans[unitOf[walker]].departMs + gapMs()

        fun offset(atMs: Long): Point {
            while (!over && nextMs <= atMs) draw()
            val trip = trips.lastOrNull { it.startMs <= atMs } ?: return ORIGIN
            val into = atMs - trip.startMs
            val out =
                when {
                    into < trip.outMs -> into.toDouble() / trip.outMs
                    into < trip.outMs + trip.holdMs -> 1.0
                    into < 2 * trip.outMs + trip.holdMs -> 1 - (into - trip.outMs - trip.holdMs).toDouble() / trip.outMs
                    else -> return ORIGIN
                }
            return Point(trip.reach.x * out, trip.reach.y * out)
        }

        private fun draw() {
            val distanceM = EXCURSION_M.start + random.nextDouble() * (EXCURSION_M.endInclusive - EXCURSION_M.start)
            val direction = ParkLayout.unitVector(random)
            val outMs = ceil(distanceM / EXCURSION_MPS * MS_PER_S).toLong()
            val lo = StrictMath.log(EXCURSION_HOLD_MS.first.toDouble())
            val hi = StrictMath.log(EXCURSION_HOLD_MS.last.toDouble())
            val holdMs = StrictMath.exp(lo + random.nextDouble() * (hi - lo)).toLong()
            val endMs = nextMs + 2 * outMs + holdMs
            val gatherAtMs = config.gatherAtMs
            if (gatherAtMs != null && endMs > gatherAtMs) {
                over = true
                return
            }
            trips += Trip(nextMs, outMs, holdMs, Point(direction.x * distanceM, direction.y * distanceM))
            nextMs = endMs + gapMs()
        }

        private fun gapMs(): Long = ceil(-StrictMath.log(1 - random.nextDouble()) * meanGapMs).toLong().coerceAtLeast(1)
    }

    companion object {
        /** Off the path, as Korpilo et al. drew it: more than 15 m from a formal trail. */
        const val OFF_PATH_M = 15.0

        /** How many places a visit plans: a guess, a handful of a park's score. */
        val PLACES_PER_VISIT = 3..6
        const val PAUSE_BETA = 1.8
        const val PAUSE_MIN_MS = 30_000L
        const val PAUSE_MAX_MS = 3_600_000L
        const val BUDGET_MEDIAN_MS = 2_700_000.0
        const val BUDGET_SIGMA = 0.6
        val EXCURSION_M = 10.0..50.0
        const val EXCURSION_MPS = 1.5
        val EXCURSION_HOLD_MS = 30_000L..300_000L
        private const val PATH_SAMPLE_MS = 10_000L
        private const val MIN_DISTANCE_M = 1.0
        private const val NONE = -1
        private const val MS_PER_S = 1_000.0
        private const val MS_PER_HOUR = 3_600_000.0
        private const val PERCENT = 100
        private val ORIGIN = Point(0.0, 0.0)

        // A group of two or more walks or walks a dog; anyone alone may also run.
        private fun drawActivity(
            random: Random,
            size: Int,
        ): Activity {
            val open = Activity.entries.filter { size == 1 || it != Activity.RUNNER }
            var draw = random.nextDouble() * open.sumOf { it.share }
            for (activity in open) {
                if (draw < activity.share) return activity
                draw -= activity.share
            }
            return open.last()
        }

        // A standard normal by Box and Muller, on `StrictMath`.
        private fun gaussian(random: Random): Double {
            val u = 1 - random.nextDouble()
            val v = random.nextDouble()
            return sqrt(-2 * StrictMath.log(u)) * StrictMath.cos(2 * Math.PI * v)
        }
    }
}
