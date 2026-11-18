package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/** A place in the simulated field, in metres. */
data class Point(
    val x: Double,
    val y: Double,
) {
    // `StrictMath`, so a path is the same on every JVM and platform (`Math.hypot` already delegates to it).
    fun distanceTo(other: Point): Double = StrictMath.hypot(x - other.x, y - other.y)
}

/**
 * Where every walker is at a simulated time. Pure and seeded: one seed walks the same paths every run, so a
 * build is compared with another over the same crowd. A model's arithmetic is `+ − × ÷`, `sqrt` and `StrictMath`,
 * never `Math`'s platform-dependent functions, and its draws come from [SimSeeds] streams of their own.
 */
fun interface Mobility {
    fun position(
        walker: Int,
        atMs: Long,
    ): Point

    /**
     * Whether [walker] is in the field at [atMs]. One who has not arrived yet or has left holds no link and is dialled by
     * nobody; its node still runs and still sends, as a phone out of everyone's range does. Everyone is, unless a model
     * says otherwise.
     */
    fun present(
        walker: Int,
        atMs: Long,
    ): Boolean = true
}

/**
 * Random waypoint over a rectangular field: a walker stands at its start until [departMs] (the group it came
 * with is still together), then walks to a point drawn anywhere in the field at a speed drawn from
 * [speedMps], stands there up to [maxPauseMs], and draws the next. The classic open-space model: no paths,
 * no attraction, every walker alone once it has left. A park at its emptiest.
 *
 * With [steady], nobody starts at a gate: each walker starts where the walk itself would have it after a long while
 * ([steadyStart]), so a run measures the model's long-run contacts from its first second, as the literature's analyses
 * of it do, not the drift from the gates towards its crowded middle. [starts] then only counts the walkers.
 *
 * Each walker draws from its own generator, so adding a walker moves none of the others.
 */
class RandomWaypoint(
    seed: Long,
    private val widthM: Double,
    private val heightM: Double,
    starts: List<Point>,
    departMs: Long,
    private val speedMps: ClosedFloatingPointRange<Double> = DEFAULT_SPEED_MPS,
    private val maxPauseMs: Long = DEFAULT_MAX_PAUSE_MS,
    /** Each walker's own speed range, [speedMps] for all unless given: a group walks at its size's pace. */
    private val speedOf: (Int) -> ClosedFloatingPointRange<Double> = { speedMps },
    steady: Boolean = false,
) : Mobility {
    private class Leg(
        val fromMs: Long,
        val toMs: Long,
        val from: Point,
        val to: Point,
    )

    private val randoms = starts.indices.map { Random(seed * SEED_STRIDE + it) }
    private val legs =
        starts.mapIndexed { walker, start ->
            if (steady) steadyStart(seed, walker) else mutableListOf(Leg(0, departMs, start, start))
        }

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        val leg = legs[walker][legIndex(walker, atMs)]
        if (leg.from == leg.to) return leg.from
        val done = (atMs - leg.fromMs).toDouble() / (leg.toMs - leg.fromMs)
        return Point(leg.from.x + (leg.to.x - leg.from.x) * done, leg.from.y + (leg.to.y - leg.from.y) * done)
    }

    /**
     * Which of [walker]'s legs it is on at [atMs], counted from its first stand at the gate, and the unit vector it walks
     * along, or null while it stands: what a group's formation is laid out from.
     */
    internal fun legAt(
        walker: Int,
        atMs: Long,
    ): LegAt {
        val index = legIndex(walker, atMs)
        val leg = legs[walker][index]
        if (leg.from == leg.to) return LegAt(index, heading = null)
        val dx = leg.to.x - leg.from.x
        val dy = leg.to.y - leg.from.y
        val length = sqrt(dx * dx + dy * dy)
        return LegAt(index, Point(dx / length, dy / length))
    }

    internal class LegAt(
        val index: Int,
        val heading: Point?,
    )

    // The leg holding [atMs], by binary search over the legs' ends (they only grow): an hour's walk holds thousands.
    private fun legIndex(
        walker: Int,
        atMs: Long,
    ): Int {
        val path = legs[walker]
        while (path.last().toMs <= atMs) extend(walker)
        var lo = 0
        var hi = path.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (path[mid].toMs > atMs) hi = mid else lo = mid + 1
        }
        return lo
    }

    // One walk and the stand that follows it.
    private fun extend(walker: Int) {
        val random = randoms[walker]
        val path = legs[walker]
        val last = path.last()
        val target = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
        val speeds = speedOf(walker)
        val speed = speeds.start + random.nextDouble() * (speeds.endInclusive - speeds.start)
        walk(walker, path, last.toMs, last.to, target, speed)
    }

    // A walk from [from] to [to] at [speed], then a stand drawn from the walker's own generator. The walk's time is rounded
    // up, so the speed is never exceeded, and it takes at least a millisecond, so the path always grows.
    private fun walk(
        walker: Int,
        path: MutableList<Leg>,
        fromMs: Long,
        from: Point,
        to: Point,
        speed: Double,
    ) {
        val arriveMs = fromMs + ceil(from.distanceTo(to) / speed * MS_PER_S).toLong().coerceAtLeast(1)
        path += Leg(fromMs, arriveMs, from, to)
        val pauseMs = if (maxPauseMs > 0) randoms[walker].nextLong(maxPauseMs + 1) else 0
        if (pauseMs > 0) path += Leg(arriveMs, arriveMs + pauseMs, to, to)
    }

    /**
     * Where [walker] is at time 0 when the walk has been going for ever, drawn exactly rather than run in (Navidi and Camp
     * 2004, "Stationary distributions for the random waypoint mobility model", IEEE TMC 3(1)). A walker stands with the
     * share of time the walk spends standing, mean stand over mean stand plus mean walk, the mean walk being the mean
     * distance between two points of the field ([meanDistanceM]) times the mean of one over the speed. A stander stands
     * anywhere in the field for what is left of a stand caught in the middle: a long stand is likelier to be caught, so
     * the remainder of a uniform stand has the density 2(1 − r/max)/max. A walker is caught on a leg likelier the longer
     * it is (a leg kept with probability its length over the diagonal), anywhere along it, at a speed likelier the slower
     * it is (density in proportion to one over the speed, drawn as `min·(max/min)^u`). From there on it walks as any
     * walker does, from its own generator. The draws come from a stream of their own ([Purpose.STEADY_START]).
     */
    private fun steadyStart(
        seed: Long,
        walker: Int,
    ): MutableList<Leg> {
        val speeds = speedOf(walker)
        require(speeds.start > 0) { "a walk caught at a speed of zero never ends: $speeds" }
        val random = SimSeeds.stream(seed, Purpose.STEADY_START, walker.toLong())
        val walkS = meanDistanceM(widthM, heightM) * meanInverse(speeds)
        val standS = maxPauseMs / 2.0 / MS_PER_S
        val path = mutableListOf<Leg>()
        if (random.nextDouble() * (walkS + standS) < standS) {
            val at = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
            val leftMs = ceil(maxPauseMs * (1 - sqrt(1 - random.nextDouble()))).toLong().coerceAtLeast(1)
            path += Leg(0, leftMs, at, at)
            return path
        }
        val diagonal = sqrt(widthM * widthM + heightM * heightM)
        var from: Point
        var to: Point
        do {
            from = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
            to = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
        } while (random.nextDouble() * diagonal >= from.distanceTo(to))
        val done = random.nextDouble()
        val at = Point(from.x + (to.x - from.x) * done, from.y + (to.y - from.y) * done)
        val speed = speeds.start * StrictMath.exp(random.nextDouble() * StrictMath.log(speeds.endInclusive / speeds.start))
        walk(walker, path, 0, at, to, speed)
        return path
    }

    companion object {
        /**
         * The mean distance between two points drawn uniformly in a [w] by [h] rectangle (Ghosh 1951, "Random distances
         * within a rectangle and between two rectangles", Bull. Calcutta Math. Soc. 43): about 0.5214 of a square's side.
         */
        fun meanDistanceM(
            w: Double,
            h: Double,
        ): Double {
            val d = sqrt(w * w + h * h)
            val ends = w * w * w / (h * h) + h * h * h / (w * w) + d * (3 - w * w / (h * h) - h * h / (w * w))
            val logs = h * h / w * StrictMath.log((w + d) / h) + w * w / h * StrictMath.log((h + d) / w)
            return (ends + LOG_WEIGHT * logs) / GHOSH_DIVISOR
        }

        // The mean of one over a speed drawn uniformly from [speeds], in seconds a metre.
        private fun meanInverse(speeds: ClosedFloatingPointRange<Double>): Double {
            val (lo, hi) = speeds.start to speeds.endInclusive
            return if (hi == lo) 1 / lo else StrictMath.log(hi / lo) / (hi - lo)
        }

        private const val LOG_WEIGHT = 2.5
        private const val GHOSH_DIVISOR = 15.0

        /** A stroll to a brisk walk. */
        val DEFAULT_SPEED_MPS = 0.8..1.6
        const val DEFAULT_MAX_PAUSE_MS = 30_000L
        private const val SEED_STRIDE = 1_000_003L
        private const val MS_PER_S = 1_000.0
    }
}

/** The mobility a scenario names ([SimScenario.motion], `-Pknit.meshSimMotion`), over its groups and their gates. */
object Motion {
    /** Every walker for itself once its group has left its gate ([RandomWaypoint]): the first outings' crowd. */
    const val WAYPOINT = "rwp"

    /**
     * [WAYPOINT] from its long-run spread at time 0 ([RandomWaypoint]'s steady start): no gates, nobody with their group.
     * The instruments' baseline, the model as the literature analysed it.
     */
    const val WAYPOINT_STEADY = "rwp-steady"

    /** Groups that walk together all outing ([GroupWalk]). */
    const val GROUPS = "groups"

    /** Visitors to a sparse park, on its paths and at its places ([ParkMobility] over a [ParkLayout]). */
    const val PARK = "park"

    /** Walkers replayed from a recorded or generated trace ([TraceMobility]): never built by name, read from its file. */
    const val TRACE = "trace"

    val NAMES = listOf(WAYPOINT, WAYPOINT_STEADY, GROUPS, PARK)

    /** Whether [motion] starts each group together at its gate, linked: every motion but a steady start and a trace. */
    fun startsTogether(motion: String): Boolean = motion != WAYPOINT_STEADY && motion != TRACE

    /** The motion [scenario] names, walking to a meeting point once the walk ends if it gathers so ([GatherBy.WALK]). */
    fun of(
        scenario: SimScenario,
        groups: List<List<Int>>,
        gates: List<Point>,
    ): Mobility {
        if (scenario.motion == PARK) {
            val layout = ParkLayout.generate(scenario.seed, scenario.fieldM)
            return ParkMobility(scenario.seed, layout, groups, ParkConfig.of(scenario))
        }
        val walk = walk(scenario, groups, gates)
        if (!GatherBy.walks(scenario)) return walk
        return GatherWalk(scenario.seed, walk, scenario.durationMs, groups, scenario.fieldM)
    }

    private fun walk(
        scenario: SimScenario,
        groups: List<List<Int>>,
        gates: List<Point>,
    ): Mobility =
        when (scenario.motion) {
            WAYPOINT, WAYPOINT_STEADY -> {
                val gateOf = IntArray(scenario.walkers)
                groups.forEachIndexed { g, group -> group.forEach { gateOf[it] = g } }
                RandomWaypoint(
                    seed = scenario.seed,
                    widthM = scenario.fieldM,
                    heightM = scenario.fieldM,
                    starts = (0 until scenario.walkers).map { gates[gateOf[it]] },
                    departMs = scenario.departMs,
                    steady = scenario.motion == WAYPOINT_STEADY,
                )
            }

            GROUPS -> {
                GroupWalk(scenario.seed, scenario.fieldM, scenario.fieldM, groups, gates, scenario.departMs)
            }

            else -> {
                throw IllegalArgumentException("no motion named ${scenario.motion}; there are $NAMES")
            }
        }
}
