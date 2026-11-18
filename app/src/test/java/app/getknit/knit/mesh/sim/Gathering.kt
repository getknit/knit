package app.getknit.knit.mesh.sim

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** How an outing's gather brings everyone together ([SimScenario.gatherBy], `-Pknit.meshSimGather`). */
object GatherBy {
    /** Links laid by hand: every group whole again and a chordal ring across the crowd, the first outings' gather. */
    const val RING = "ring"

    /**
     * Everyone walks to a meeting point ([Gathering]) and the radio raises the links as they come in range, its budget
     * and all: so the oracle also tests whether the radio's own links bridge the crowd, not a link set handed to it.
     */
    const val WALK = "walk"

    val NAMES = listOf(RING, WALK)

    /** Whether [scenario] ends in a gather everyone walks to. */
    fun walks(scenario: SimScenario): Boolean = scenario.gather && scenario.gatherBy == WALK
}

/** A motion whose walkers walk to a meeting point when the walk ends ([GatherBy.WALK]). */
interface Gathering {
    /** When the last of them gets there. */
    val gatheredByMs: Long
}

/** Where the groups of a gather stand: a Vogel spiral around a meeting point, about [SPACING_M] between neighbours. */
object GatherSpots {
    /** Close enough that the gathered crowd is one proximity graph at a 25 m connect range, with room to stand. */
    const val SPACING_M = 10.0

    /**
     * [count] spots around [centre], moved in from the fence as far as the spiral needs, inside a [sideM] square field.
     * The k-th lies at c·√(k + ½) from the centre, k golden angles round: c makes each spot's share of the disc a
     * hexagonal cell [SPACING_M] across.
     */
    fun vogel(
        count: Int,
        centre: Point,
        sideM: Double,
    ): List<Point> {
        val c = SPACING_M * sqrt(HEX_CELL / Math.PI)
        val radius = c * sqrt(count + 0.5) + SPACING_M
        val middle =
            Point(
                centre.x.coerceIn(minOf(radius, sideM / 2), maxOf(sideM - radius, sideM / 2)),
                centre.y.coerceIn(minOf(radius, sideM / 2), maxOf(sideM - radius, sideM / 2)),
            )
        return List(count) { k ->
            val r = c * sqrt(k + 0.5)
            val angle = k * GOLDEN_ANGLE
            Point(
                (middle.x + r * StrictMath.cos(angle)).coerceIn(0.0, sideM),
                (middle.y + r * StrictMath.sin(angle)).coerceIn(0.0, sideM),
            )
        }
    }

    // √3 / 2: a hexagonal cell's area over its width squared.
    private const val HEX_CELL = 0.8660254037844386
    private const val GOLDEN_ANGLE = 2.399963229728653
}

/**
 * The walking gather for a motion with no places of its own ([Motion.WAYPOINT], [Motion.GROUPS]): up to [gatherAtMs]
 * the walkers move as [inner] moves them; then each walks straight at [speedMps] to its group's spot ([GatherSpots])
 * around the field's centre, plus, in a group, a standing spot of its own ([Formation]), and stays.
 */
class GatherWalk(
    seed: Long,
    private val inner: Mobility,
    private val gatherAtMs: Long,
    groups: List<List<Int>>,
    sideM: Double,
    private val speedMps: Double = DEFAULT_SPEED_MPS,
) : Mobility,
    Gathering {
    private val spotOf: List<Point>
    private val fromOf: List<Point>
    private val arriveMs: LongArray

    init {
        val walkers = groups.sumOf { it.size }
        val groupSpots = GatherSpots.vogel(groups.size, Point(sideM / 2, sideM / 2), sideM)
        val spots = arrayOfNulls<Point>(walkers)
        for ((g, group) in groups.withIndex()) {
            for (walker in group) {
                val off = if (group.size > 1) Formation.standingSpot(seed, walker, GATHER_STAND) else Point(0.0, 0.0)
                val spot = groupSpots[g]
                spots[walker] = Point((spot.x + off.x).coerceIn(0.0, sideM), (spot.y + off.y).coerceIn(0.0, sideM))
            }
        }
        spotOf = spots.map { checkNotNull(it) }
        fromOf = spotOf.indices.map { inner.position(it, gatherAtMs) }
        arriveMs =
            LongArray(walkers) { gatherAtMs + ceil(fromOf[it].distanceTo(spotOf[it]) / speedMps * MS_PER_S).toLong() }
    }

    override fun position(
        walker: Int,
        atMs: Long,
    ): Point {
        if (atMs <= gatherAtMs) return inner.position(walker, atMs)
        val (from, to) = fromOf[walker] to spotOf[walker]
        if (atMs >= arriveMs[walker]) return to
        val done = (atMs - gatherAtMs).toDouble() / (arriveMs[walker] - gatherAtMs)
        return Point(from.x + (to.x - from.x) * done, from.y + (to.y - from.y) * done)
    }

    override fun present(
        walker: Int,
        atMs: Long,
    ): Boolean = atMs > gatherAtMs || inner.present(walker, atMs)

    override val gatheredByMs: Long = arriveMs.maxOrNull() ?: gatherAtMs

    companion object {
        const val DEFAULT_SPEED_MPS = 1.2

        // The stand index of a member's spot at the gather: never a leg's.
        private const val GATHER_STAND = -1L
        private const val MS_PER_S = 1_000.0
    }
}

/**
 * What a walking gather did, stepped with the radio from the moment the walk ended ([startMs]): when the radio's links
 * first joined every walker into one mesh, and how many pieces it held at the end.
 */
class GatherWatch(
    private val walkers: Int,
    private val startMs: Long,
) {
    private var connectedAtMs: Long? = null

    /** The radio's [links] at [atMs]. */
    fun step(
        atMs: Long,
        links: Set<Link>,
    ) {
        if (connectedAtMs == null && largestComponent(links, walkers) == walkers) connectedAtMs = atMs
    }

    /** The report's line, once the last walker arrived at [gatheredByMs] and the radio holds [links]. */
    fun line(
        gatheredByMs: Long,
        links: Set<Link>,
    ): String {
        val sizes = componentSizes(links, walkers).filter { it > 0 }.sortedDescending()
        val connected = connectedAtMs?.let { "one mesh ${"%.1f".format(Locale.ROOT, (it - startMs) / MS_PER_S)} s after the walk ended" }
        return "gather walk: everyone at the meeting point ${(gatheredByMs - startMs) / MS_PER_S.toLong()} s after the walk ended; " +
            (connected ?: "the radio's links never joined everyone") +
            "; at the end ${sizes.size} meshes (${sizes.take(
                SIZES_SHOWN,
            ).joinToString(", ")}${if (sizes.size > SIZES_SHOWN) ", …" else ""})"
    }

    fun summaryValues(
        gatheredByMs: Long,
        links: Set<Link>,
    ): List<Pair<String, Any?>> =
        listOf(
            "gather_walk_s" to (gatheredByMs - startMs) / MS_PER_S.toLong(),
            "gather_connected_s" to connectedAtMs?.let { (it - startMs) / MS_PER_S },
            "gather_meshes" to componentSizes(links, walkers).count { it > 0 },
            "gather_largest" to largestComponent(links, walkers),
        )

    private companion object {
        const val MS_PER_S = 1_000.0
        const val SIZES_SHOWN = 5
    }
}
