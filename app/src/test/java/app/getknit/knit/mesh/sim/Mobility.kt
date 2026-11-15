package app.getknit.knit.mesh.sim

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/** A place in the simulated field, in metres. */
data class Point(
    val x: Double,
    val y: Double,
) {
    fun distanceTo(other: Point): Double = hypot(x - other.x, y - other.y)
}

/**
 * Where every walker is at a simulated time. Pure and seeded: one seed walks the same paths every run, so a
 * build is compared with another over the same crowd.
 */
fun interface Mobility {
    fun position(
        walker: Int,
        atMs: Long,
    ): Point
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
        val path = legs[walker]
        while (path.last().toMs <= atMs) extend(walker)
        val leg = path.first { atMs < it.toMs }
        if (leg.from == leg.to) return leg.from
        val done = (atMs - leg.fromMs).toDouble() / (leg.toMs - leg.fromMs)
        return Point(leg.from.x + (leg.to.x - leg.from.x) * done, leg.from.y + (leg.to.y - leg.from.y) * done)
    }

    // One walk and the stand that follows it. The walk's time is rounded up, so the drawn speed is never exceeded, and
    // it takes at least a millisecond, so the path always grows.
    private fun extend(walker: Int) {
        val random = randoms[walker]
        val path = legs[walker]
        val last = path.last()
        val target = Point(random.nextDouble() * widthM, random.nextDouble() * heightM)
        val speed = speedMps.start + random.nextDouble() * (speedMps.endInclusive - speedMps.start)
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
