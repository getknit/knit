package app.getknit.knit.mesh.sim

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
) : Mobility {
    private class Leg(
        val fromMs: Long,
        val toMs: Long,
        val from: Point,
        val to: Point,
    )

    private val randoms = starts.indices.map { Random(seed * SEED_STRIDE + it) }
    private val legs = starts.map { start -> mutableListOf(Leg(0, departMs, start, start)) }

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

    // One walk and the stand that follows it. The walk's time is rounded up, so the drawn speed is never exceeded, and
    // it takes at least a millisecond, so the path always grows.
    private fun extend(walker: Int) {
        val random = randoms[walker]
        val path = legs[walker]
        val last = path.last()
        val target = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
        val speeds = speedOf(walker)
        val speed = speeds.start + random.nextDouble() * (speeds.endInclusive - speeds.start)
        val walkMs = ceil(last.to.distanceTo(target) / speed * MS_PER_S).toLong().coerceAtLeast(1)
        val arriveMs = last.toMs + walkMs
        path += Leg(last.toMs, arriveMs, last.to, target)
        val pauseMs = if (maxPauseMs > 0) random.nextLong(maxPauseMs + 1) else 0
        if (pauseMs > 0) path += Leg(arriveMs, arriveMs + pauseMs, target, target)
    }

    companion object {
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

    /** Groups that walk together all outing ([GroupWalk]). */
    const val GROUPS = "groups"

    val NAMES = listOf(WAYPOINT, GROUPS)

    fun of(
        scenario: SimScenario,
        groups: List<List<Int>>,
        gates: List<Point>,
    ): Mobility =
        when (scenario.motion) {
            WAYPOINT -> {
                val gateOf = IntArray(scenario.walkers)
                groups.forEachIndexed { g, group -> group.forEach { gateOf[it] = g } }
                RandomWaypoint(
                    seed = scenario.seed,
                    widthM = scenario.fieldM,
                    heightM = scenario.fieldM,
                    starts = (0 until scenario.walkers).map { gates[gateOf[it]] },
                    departMs = scenario.departMs,
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
