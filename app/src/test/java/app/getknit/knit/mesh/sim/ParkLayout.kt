package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A synthetic park, laid out from the seed alone: the paths a walk follows, the gates it comes and goes by, and the
 * places it stops at.
 *
 * - **Paths:** a square lattice of junctions, every one but the fence's moved up to a quarter of the spacing, the
 *   fence's only along the fence. A seeded spanning tree keeps every junction reachable; [CUT_SHARE] of the other
 *   lattice edges are cut, so loops remain. The spacing is solved for [trailMPerHa]: Helsinki Central Park has about
 *   90 m of formal trail a hectare (Korpilo et al. 2018).
 * - **Gates:** up to [GATES] junctions on the fence.
 * - **Places** ([Poi]): benches along a path, amenities (a café, a playground) at a junction, and lawns, discs set back
 *   from a junction that walkers cross the grass to. Each has a popularity drawn from a Pareto law, so a few places
 *   draw most visits, as the hot spots of human walks do (Lee et al., "Demystifying Lévy walk patterns"). The most
 *   popular lawn is where a gather meets ([meeting]).
 *
 * All of it uses `+ − × ÷`, `sqrt` and `StrictMath`, and draws from [Purpose.PARK_LAYOUT], so a seed lays out one park
 * on every JVM.
 */
class ParkLayout private constructor(
    val sideM: Double,
    val junctions: List<Point>,
    /** The paths, each between two [junctions]. */
    val edges: List<Edge>,
    /** The junctions on the fence that walkers come in and leave by. */
    val gates: List<Int>,
    val pois: List<Poi>,
) {
    /** A path from junction [a] to junction [b]. */
    data class Edge(
        val a: Int,
        val b: Int,
    )

    enum class Kind { BENCH, AMENITY, LAWN }

    /**
     * A place walkers go to: [at], reached from the junction [access] along a path (a [Kind.BENCH] on one of its
     * edges, an [Kind.AMENITY] at the junction itself) or across the grass ([Kind.LAWN], a disc of [radiusM] around
     * [at]); chosen in proportion to [weight].
     */
    data class Poi(
        val kind: Kind,
        val at: Point,
        val access: Int,
        val weight: Double,
        val radiusM: Double = 0.0,
    )

    // Shortest path lengths between junctions, and the next junction on each.
    private val distM: Array<DoubleArray>
    private val nextHop: Array<IntArray>

    init {
        val n = junctions.size
        distM = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else Double.POSITIVE_INFINITY } }
        nextHop = Array(n) { IntArray(n) { NONE } }
        for (edge in edges) {
            val length = junctions[edge.a].distanceTo(junctions[edge.b])
            distM[edge.a][edge.b] = length
            distM[edge.b][edge.a] = length
            nextHop[edge.a][edge.b] = edge.b
            nextHop[edge.b][edge.a] = edge.a
        }
        for (i in 0 until n) nextHop[i][i] = i
        // Floyd–Warshall: a park of a few dozen junctions.
        for (k in 0 until n) {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val through = distM[i][k] + distM[k][j]
                    if (through < distM[i][j]) {
                        distM[i][j] = through
                        nextHop[i][j] = nextHop[i][k]
                    }
                }
            }
        }
    }

    /** The trail a hectare: every path's length over the park's area. */
    val trailMPerHa: Double = edges.sumOf { junctions[it.a].distanceTo(junctions[it.b]) } / (sideM * sideM / M2_PER_HA)

    /** Where a gather meets: the most popular lawn, or the most popular place if the park has no lawn. */
    val meeting: Poi = pois.filter { it.kind == Kind.LAWN }.maxByOrNull { it.weight } ?: pois.maxBy { it.weight }

    /** The junctions from [from] to [to] along the shortest path, both ends included. */
    fun route(
        from: Int,
        to: Int,
    ): List<Int> {
        check(distM[from][to].isFinite()) { "no path from junction $from to $to" }
        val path = mutableListOf(from)
        var at = from
        while (at != to) {
            at = nextHop[at][to]
            path += at
        }
        return path
    }

    /** The shortest path's length from junction [from] to junction [to]. */
    fun pathM(
        from: Int,
        to: Int,
    ): Double = distM[from][to]

    /** Whether every junction can reach every other. */
    fun connected(): Boolean = distM.all { row -> row.all { it.isFinite() } }

    /** The junction nearest [point] as the crow flies. */
    fun nearestJunction(point: Point): Int = junctions.indices.minBy { junctions[it].distanceTo(point) }

    /** The gate nearest junction [from] along the paths. */
    fun nearestGate(from: Int): Int = gates.minBy { distM[from][it] }

    /** How far [point] lies from the nearest path. */
    fun distanceToPath(point: Point): Double = edges.minOf { segmentDistance(point, junctions[it.a], junctions[it.b]) }

    companion object {
        /** The share of the lattice's edges beyond its spanning tree that are cut. */
        const val CUT_SHARE = 0.25
        const val GATES = 6
        const val DEFAULT_TRAIL_M_PER_HA = 90.0
        const val DEFAULT_POIS_PER_KM2 = 20.0

        /** Pareto exponent of the places' popularity: under 2, so the heaviest few hold much of it. */
        const val POPULARITY_ALPHA = 1.2

        private const val NONE = -1
        private const val M2_PER_HA = 10_000.0
        private const val M2_PER_KM2 = 1_000_000.0
        private const val MIN_SIDE_JUNCTIONS = 3
        private const val MIN_POIS = 4
        private const val JITTER = 0.25
        private const val BENCH_SHARE = 0.5
        private const val AMENITY_SHARE = 0.2
        private const val BENCH_FROM = 0.2
        private const val BENCH_SPAN = 0.6
        private val LAWN_SET_BACK_M = 30.0..60.0
        private val LAWN_RADIUS_M = 10.0..25.0

        /**
         * A park [sideM] on a side with about [trailMPerHa] of path a hectare and [poisPerKm2] places a square
         * kilometre. A lattice n junctions a side, its spacing s = side / (n − 1), keeps 1 − [CUT_SHARE] of its 2n(n − 1)
         * edges, so its trail a hectare is about 2·10⁴·(1 − cut)·n / side; n is solved from that.
         */
        fun generate(
            seed: Long,
            sideM: Double,
            trailMPerHa: Double = DEFAULT_TRAIL_M_PER_HA,
            poisPerKm2: Double = DEFAULT_POIS_PER_KM2,
        ): ParkLayout {
            val random = SimSeeds.stream(seed, Purpose.PARK_LAYOUT)
            val kept = 1 - CUT_SHARE
            val n = maxOf(MIN_SIDE_JUNCTIONS, (trailMPerHa * sideM / (2 * M2_PER_HA * kept)).roundToInt())
            val junctions = lattice(random, n, sideM)
            val edges = paths(random, n, kept)
            val perimeter = junctions.indices.filter { onFence(it, n) && !corner(it, n) }
            val gates = perimeter.shuffled(random).take(GATES).sorted()
            val count = maxOf(MIN_POIS, (poisPerKm2 * sideM * sideM / M2_PER_KM2).roundToInt())
            val pois = List(count) { poi(random, junctions, edges, sideM) }
            return ParkLayout(sideM, junctions, edges, gates, pois)
        }

        private fun lattice(
            random: Random,
            n: Int,
            sideM: Double,
        ): List<Point> {
            val spacing = sideM / (n - 1)
            return List(n * n) { index ->
                val row = index / n
                val col = index % n
                // Two draws a junction, used or not, so the fence's rule never shifts the rest.
                val dx = (random.nextDouble() * 2 - 1) * spacing * JITTER
                val dy = (random.nextDouble() * 2 - 1) * spacing * JITTER
                val alongX = row == 0 || row == n - 1
                val alongY = col == 0 || col == n - 1
                val x = col * spacing + if (alongY) 0.0 else dx
                val y = row * spacing + if (alongX) 0.0 else dy
                Point(x.coerceIn(0.0, sideM), y.coerceIn(0.0, sideM))
            }
        }

        // The lattice's edges in a seeded order; a spanning tree of them (Kruskal), then the first of the rest.
        private fun paths(
            random: Random,
            n: Int,
            kept: Double,
        ): List<Edge> {
            val all = mutableListOf<Edge>()
            for (index in 0 until n * n) {
                if (index % n < n - 1) all += Edge(index, index + 1)
                if (index / n < n - 1) all += Edge(index, index + n)
            }
            val order = all.shuffled(random)
            val parent = IntArray(n * n) { it }

            fun root(of: Int): Int {
                var at = of
                while (parent[at] != at) {
                    parent[at] = parent[parent[at]]
                    at = parent[at]
                }
                return at
            }
            val tree = mutableListOf<Edge>()
            val rest = mutableListOf<Edge>()
            for (edge in order) {
                val (a, b) = root(edge.a) to root(edge.b)
                if (a != b) {
                    parent[a] = b
                    tree += edge
                } else {
                    rest += edge
                }
            }
            val extra = (ceil(kept * all.size).toInt() - tree.size).coerceIn(0, rest.size)
            return tree + rest.take(extra)
        }

        private fun poi(
            random: Random,
            junctions: List<Point>,
            edges: List<Edge>,
            sideM: Double,
        ): Poi {
            val kind = random.nextDouble()
            // A Pareto draw at least 1: (1 − u)^(−1/α).
            val weight = StrictMath.pow(1 - random.nextDouble(), -1 / POPULARITY_ALPHA)
            return when {
                kind < BENCH_SHARE -> {
                    val edge = edges[random.nextInt(edges.size)]
                    val along = BENCH_FROM + random.nextDouble() * BENCH_SPAN
                    val (a, b) = junctions[edge.a] to junctions[edge.b]
                    val at = Point(a.x + (b.x - a.x) * along, a.y + (b.y - a.y) * along)
                    Poi(Kind.BENCH, at, if (along < 0.5) edge.a else edge.b, weight)
                }

                kind < BENCH_SHARE + AMENITY_SHARE -> {
                    val junction = random.nextInt(junctions.size)
                    Poi(Kind.AMENITY, junctions[junction], junction, weight)
                }

                else -> {
                    val junction = random.nextInt(junctions.size)
                    val direction = unitVector(random)
                    val back = LAWN_SET_BACK_M.start + random.nextDouble() * (LAWN_SET_BACK_M.endInclusive - LAWN_SET_BACK_M.start)
                    val radius = LAWN_RADIUS_M.start + random.nextDouble() * (LAWN_RADIUS_M.endInclusive - LAWN_RADIUS_M.start)
                    val from = junctions[junction]
                    val at =
                        Point(
                            (from.x + direction.x * back).coerceIn(radius, sideM - radius),
                            (from.y + direction.y * back).coerceIn(radius, sideM - radius),
                        )
                    Poi(Kind.LAWN, at, junction, weight, radius)
                }
            }
        }

        private fun onFence(
            index: Int,
            n: Int,
        ): Boolean = index / n == 0 || index / n == n - 1 || index % n == 0 || index % n == n - 1

        private fun corner(
            index: Int,
            n: Int,
        ): Boolean = (index / n == 0 || index / n == n - 1) && (index % n == 0 || index % n == n - 1)

        /** A direction drawn evenly, by rejection from the square: no trigonometry. */
        internal fun unitVector(random: Random): Point {
            while (true) {
                val x = random.nextDouble() * 2 - 1
                val y = random.nextDouble() * 2 - 1
                val length = sqrt(x * x + y * y)
                if (length in MIN_LENGTH..1.0) return Point(x / length, y / length)
            }
        }

        private const val MIN_LENGTH = 1e-6

        /** How far [point] lies from the segment [a]–[b]. */
        internal fun segmentDistance(
            point: Point,
            a: Point,
            b: Point,
        ): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length2 = dx * dx + dy * dy
            if (length2 == 0.0) return point.distanceTo(a)
            val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
            return point.distanceTo(Point(a.x + dx * t, a.y + dy * t))
        }
    }
}
