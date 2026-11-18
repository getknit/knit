package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlin.math.sqrt

/**
 * How the walkers split into the groups they arrive and walk with, by index in order. A name picks one: a number is a
 * fixed size, `street` the sizes people walk in on a street.
 */
sealed interface GroupSizes {
    /** The word that names it in a report's summary and in `-Pknit.meshSimGroups`. */
    val name: String

    /** [walkers] walkers in groups, the first group from walker 0. */
    fun partition(
        seed: Long,
        walkers: Int,
    ): List<List<Int>>

    /** Every group the same size, the last one what is left: friends in threes, as the first outings had them. */
    data class Fixed(
        val size: Int,
    ) : GroupSizes {
        init {
            require(size >= 1) { "a group holds someone: $size" }
        }

        override val name = size.toString()

        override fun partition(
            seed: Long,
            walkers: Int,
        ): List<List<Int>> = (0 until walkers).chunked(size)
    }

    /**
     * The sizes of a weekday street: of the people walking, 46% alone, 37% in twos, 10% in threes, 4% in fours and 3% in
     * fives or more, drawn as five (Moussaïd et al. 2010, Toulouse; PLoS ONE 5(4): e10047). A share of people becomes a
     * share of groups by dividing by the size. The last group is cut to the walkers left.
     */
    data object Street : GroupSizes {
        override val name = "street"

        /** Percent of people walking in a group of each size, from one to five. */
        val PEOPLE_PERCENT = listOf(46, 37, 10, 4, 3)

        override fun partition(
            seed: Long,
            walkers: Int,
        ): List<List<Int>> {
            val weights = PEOPLE_PERCENT.mapIndexed { i, percent -> percent.toDouble() / (i + 1) }
            val total = weights.sum()
            val random = SimSeeds.stream(seed, Purpose.GROUP_SIZES)
            val groups = mutableListOf<List<Int>>()
            var next = 0
            while (next < walkers) {
                var draw = random.nextDouble() * total
                var size = weights.size
                for ((i, weight) in weights.withIndex()) {
                    if (draw < weight) {
                        size = i + 1
                        break
                    }
                    draw -= weight
                }
                val end = minOf(walkers, next + size)
                groups += (next until end).toList()
                next = end
            }
            return groups
        }
    }

    companion object {
        /** The scenario's own fixed size ([SimScenario.groupSize]). */
        const val FIXED = "fixed"

        /** Every name [of] takes besides a number. */
        val NAMES = listOf(FIXED, Street.name)

        /** The sizes [name] gives: [FIXED] is [fixedSize], a number that size, `street` [Street]. */
        fun of(
            name: String,
            fixedSize: Int = 3,
        ): GroupSizes =
            when {
                name == FIXED -> Fixed(fixedSize)
                name == Street.name -> Street
                name.toIntOrNull() != null -> Fixed(name.toInt())
                else -> throw IllegalArgumentException("no group sizes named $name; a number or one of $NAMES")
            }
    }
}

/**
 * Groups that walk together the whole outing: Reference Point Group Mobility (Hong et al. 1999). Each group's reference
 * point walks a [RandomWaypoint] of its own over the field, at a pace slower by [SLOWER_PER_MEMBER_MPS] for every member
 * past the first (Moussaïd et al. 2010), and each member stands at an offset from it:
 *
 * - **Walking, side by side** across the heading, [ABREAST_M] apart, in the order the group lists them.
 * - **Standing, scattered** within [STAND_RADIUS_M] of the point: a spot drawn afresh at every stand, the same for a
 *   seed, the member and the stand.
 *
 * A group starts at its gate ([gates], one per group) and leaves it at [departMs], as [RandomWaypoint]'s walkers do, but
 * nobody leaves their group: wandering off and finding each other again is a later model's. Members move from one
 * formation to the other in one step, a jump of a few metres at most. Every place is kept inside the field.
 */
class GroupWalk(
    private val seed: Long,
    private val widthM: Double,
    private val heightM: Double,
    groups: List<List<Int>>,
    gates: List<Point>,
    departMs: Long,
    speedMps: ClosedFloatingPointRange<Double> = RandomWaypoint.DEFAULT_SPEED_MPS,
    maxPauseMs: Long = RandomWaypoint.DEFAULT_MAX_PAUSE_MS,
) : Mobility {
    private val groupOf: IntArray
    private val slotOf: IntArray
    private val sizes = groups.map { it.size }

    init {
        require(gates.size == groups.size) { "one gate a group: ${gates.size} gates for ${groups.size} groups" }
        val walkers = groups.sumOf { it.size }
        groupOf = IntArray(walkers)
        slotOf = IntArray(walkers)
        for ((g, group) in groups.withIndex()) {
            for ((slot, walker) in group.withIndex()) {
                groupOf[walker] = g
                slotOf[walker] = slot
            }
        }
    }

    private val reference =
        RandomWaypoint(
            seed = SimSeeds.key(seed, Purpose.GROUP_ROUTE.code),
            widthM = widthM,
            heightM = heightM,
            starts = gates,
            departMs = departMs,
            speedMps = speedMps,
            maxPauseMs = maxPauseMs,
            speedOf = { g -> slower(speedMps, sizes[g]) },
        )

    /** Where group [group]'s reference point is at [atMs]: what its members stand around. */
    internal fun centre(
        group: Int,
        atMs: Long,
    ): Point = reference.position(group, atMs)

    /** The group [walker] walks with. */
    internal fun groupOf(walker: Int): Int = groupOf[walker]

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        val group = groupOf[walker]
        val centre = reference.position(group, atMs)
        val size = sizes[group]
        if (size == 1) return centre
        val leg = reference.legAt(group, atMs)
        val heading = leg.heading
        val offset =
            if (heading != null) {
                // Across the heading: the heading turned a quarter, left of the walk.
                val across = (slotOf[walker] - (size - 1) / 2.0) * ABREAST_M
                Point(-heading.y * across, heading.x * across)
            } else {
                standingSpot(walker, leg.index)
            }
        return Point((centre.x + offset.x).coerceIn(0.0, widthM), (centre.y + offset.y).coerceIn(0.0, heightM))
    }

    // A spot in the disc of [STAND_RADIUS_M] for one member's one stand, drawn by rejection from its square: no trigonometry.
    private fun standingSpot(
        walker: Int,
        stand: Int,
    ): Point {
        val random = SimSeeds.stream(seed, Purpose.FORMATION, walker.toLong(), stand.toLong())
        while (true) {
            val x = (random.nextDouble() * 2 - 1) * STAND_RADIUS_M
            val y = (random.nextDouble() * 2 - 1) * STAND_RADIUS_M
            if (sqrt(x * x + y * y) <= STAND_RADIUS_M) return Point(x, y)
        }
    }

    companion object {
        const val ABREAST_M = 0.8
        const val STAND_RADIUS_M = 3.0
        const val SLOWER_PER_MEMBER_MPS = 0.04

        // No group strolls slower than this, however big.
        private const val SLOWEST_MPS = 0.3

        private fun slower(
            speeds: ClosedFloatingPointRange<Double>,
            size: Int,
        ): ClosedFloatingPointRange<Double> {
            val by = SLOWER_PER_MEMBER_MPS * (size - 1)
            return (speeds.start - by).coerceAtLeast(SLOWEST_MPS)..(speeds.endInclusive - by).coerceAtLeast(SLOWEST_MPS)
        }
    }
}

/**
 * Friends who came separately: the walking groups, shuffled for the seed, paired into circles of two groups, the odd one
 * out joining the last pair. Each entry is one circle's groups, by index.
 */
object Circles {
    fun of(
        seed: Long,
        groupCount: Int,
    ): List<List<Int>> {
        if (groupCount <= 1) return listOf((0 until groupCount).toList())
        val shuffled = (0 until groupCount).shuffled(SimSeeds.stream(seed, Purpose.CIRCLES))
        val circles = shuffled.chunked(2).map { it.toMutableList() }.toMutableList()
        if (circles.last().size == 1) circles[circles.lastIndex - 1] += circles.removeAt(circles.lastIndex)
        return circles
    }
}

/** Which walkers each walker DMs ([Workload]): its own group's, or its circle's too. */
sealed interface Friends {
    /** The word that names it in a report's summary and in `-Pknit.meshSimFriends`. */
    val name: String

    /** Friends are the group a walker came with, and nobody else: the first outings' workload. */
    data object Group : Friends {
        override val name = "group"
    }

    /**
     * A walker's friends are its group's and its circle's other groups' ([Circles]): a DM goes to someone in another
     * group of the circle with probability [awayShare], and to a companion otherwise. One with no companions DMs away.
     */
    data class Circle(
        val circles: List<List<Int>>,
        val awayShare: Double = DEFAULT_AWAY_SHARE,
    ) : Friends {
        init {
            require(awayShare in 0.0..1.0) { "a share is a probability: $awayShare" }
        }

        override val name = NAME

        companion object {
            const val NAME = "circles"
            const val DEFAULT_AWAY_SHARE = 0.5
        }
    }

    companion object {
        val NAMES = listOf(Group.name, Circle.NAME)

        /** The workload [name] gives the walkers in [groups], on [seed]. */
        fun of(
            name: String,
            seed: Long,
            groups: List<List<Int>>,
        ): Friends =
            when (name) {
                Group.name -> Group
                Circle.NAME -> Circle(Circles.of(seed, groups.size))
                else -> throw IllegalArgumentException("no friends named $name; there are $NAMES")
            }
    }
}
