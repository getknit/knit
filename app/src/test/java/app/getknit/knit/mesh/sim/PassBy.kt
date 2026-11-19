package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose

/**
 * Pairs of walkers passing on a sidewalk, each pair on a lane of its own: walker `2p` starts [startM] west of the
 * meeting point and walks east, walker `2p + 1` starts as far east on the other side of the sidewalk, [lateralM] away,
 * and walks west. Each pair sets off at a moment drawn in [startWindowMs], so the pairs do not all meet in one tick,
 * and each walker at a speed drawn within [spreadMps] of [speedMps]; both stand at their starts until then and walk on
 * past each other until the run ends.
 *
 * Lanes are [laneM] apart, far enough that no two pairs ever reach each other ([PassBy.of] puts them four drop ranges
 * apart), so one run is [pairs] independent passes. Pure and seeded: a walker's place depends on its own draws alone.
 */
class PassBy(
    seed: Long,
    val pairs: Int,
    /** How far from the meeting point each walker starts, along the sidewalk: out of range of the other. */
    val startM: Double,
    val laneM: Double,
    val lateralM: Double = DEFAULT_LATERAL_M,
    speedMps: Double = DEFAULT_SPEED_MPS,
    spreadMps: Double = DEFAULT_SPREAD_MPS,
    startWindowMs: Long = DEFAULT_START_WINDOW_MS,
) : Mobility {
    init {
        require(pairs > 0) { "a sidewalk needs a pair on it: $pairs" }
        require(speedMps - spreadMps > 0) { "every walker walks: $speedMps ± $spreadMps m/s" }
    }

    /** When each pair sets off, ms into the run. */
    val departsMs: LongArray =
        LongArray(pairs) { p ->
            if (startWindowMs == 0L) 0 else SimSeeds.stream(seed, Purpose.PASS_START, p.toLong()).nextLong(startWindowMs)
        }

    /** Each walker's speed, m/s. */
    val speedsMps: DoubleArray =
        DoubleArray(pairs * 2) { w ->
            if (spreadMps == 0.0) {
                speedMps
            } else {
                SimSeeds.stream(seed, Purpose.PASS_SPEED, w.toLong()).nextDouble(speedMps - spreadMps, speedMps + spreadMps)
            }
        }

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        val pair = walker / 2
        val east = walker % 2 == 0
        val walkedM = speedsMps[walker] * (atMs - departsMs[pair]).coerceAtLeast(0) / MS_PER_S
        val laneY = pair * laneM
        return if (east) Point(-startM + walkedM, laneY) else Point(startM - walkedM, laneY + lateralM)
    }

    /** The two walkers of [pair]. */
    fun walkers(pair: Int): Pair<Int, Int> = 2 * pair to 2 * pair + 1

    /** When [pair] is [apartM] apart again after passing, ms into the run: its pass is over once no radio reaches that far. */
    fun apartAfterMs(
        pair: Int,
        apartM: Double,
    ): Long {
        val (a, b) = walkers(pair)
        val closing = speedsMps[a] + speedsMps[b]
        return departsMs[pair] + ((2 * startM + apartM) / closing * MS_PER_S).toLong()
    }

    companion object {
        const val DEFAULT_LATERAL_M = 2.0
        const val DEFAULT_SPEED_MPS = 1.3
        const val DEFAULT_SPREAD_MPS = 0.15
        const val DEFAULT_START_WINDOW_MS = 20_000L

        /** How far past the drop range each walker starts: out of reach by a margin, not by a step. */
        const val START_MARGIN_M = 10.0

        /** Lanes are this many drop ranges apart: two walkers of different pairs are never within [dropM] of each other. */
        const val LANES_PER_DROP = 4
        private const val MS_PER_S = 1_000.0

        /** [pairs] passes for a radio that holds a link to [dropM], each walker starting out of its reach. */
        fun of(
            seed: Long,
            pairs: Int,
            dropM: Double,
            lateralM: Double = DEFAULT_LATERAL_M,
            speedMps: Double = DEFAULT_SPEED_MPS,
            spreadMps: Double = DEFAULT_SPREAD_MPS,
            startWindowMs: Long = DEFAULT_START_WINDOW_MS,
            startMarginM: Double = START_MARGIN_M,
        ) = PassBy(
            seed = seed,
            pairs = pairs,
            startM = dropM + startMarginM,
            laneM = LANES_PER_DROP * dropM,
            lateralM = lateralM,
            speedMps = speedMps,
            spreadMps = spreadMps,
            startWindowMs = startWindowMs,
        )
    }
}
