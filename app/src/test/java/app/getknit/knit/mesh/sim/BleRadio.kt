package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.lab.LinkAir
import kotlin.math.exp
import kotlin.math.ln

/**
 * Bluetooth LE on the 1M PHY, as far as the field has measured it, on top of [DiscRadio]'s distances:
 *
 * - **A dial takes as long as a phone's.** Each approach draws its own dial time around the measured relink median
 *   ([dialMedianMs], 4.3 s after the 2026-10 fixes; a first connect took 0.3 to 9.5 s), clamped to [DIAL_MIN_MS] and
 *   [DIAL_MAX_MS]. A pair that leaves dial range starts over, with a new draw.
 * - **A node holds at most [maxLinks] links** (`PromotionConfig`'s budget). A pair whose dial is done waits while either
 *   end is full, nearest pairs first; a held link is never evicted for a nearer one (the real `PromotionPolicy` does
 *   that, a later radio's work).
 * - **Every link airs at [linkBytesPerSecond]** (8.8 to 9.5 KB/s drained at the default 45 ms interval, ADR
 *   2026-10.2da4), **and each phone's radio at [nodeBytesPerSecond]** for all its links in and out: thirteen LE buffers
 *   shared by every connection, about three a link at four links.
 *
 * Range is the disc's: no path loss, no shadowing, no crowd. [seed] fixes every dial draw.
 */
class BleRadio(
    private val seed: Long,
    private val connectM: Double,
    private val dropM: Double,
    initial: Set<Link> = emptySet(),
    private val dialMedianMs: Long = DIAL_MEDIAN_MS,
    private val maxLinks: Int = PromotionConfig.DEFAULT_MAX_LINKS,
    private val linkBytesPerSecond: Long = LINK_BYTES_PER_S,
    private val nodeBytesPerSecond: Long = NODE_BYTES_PER_S,
) : Radio {
    override val name = NAME
    override val air = LinkAir(linkBytesPerSecond)
    override val nodeAirBytesPerSecond = nodeBytesPerSecond

    init {
        require(dropM >= connectM) { "a link must hold at least as far as it is dialled: $dropM < $connectM" }
        val degree =
            initial
                .flatMap { listOf(it.a, it.b) }
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull() ?: 0
        require(degree <= maxLinks) { "a group linked at boot gives a walker $degree links, over the budget of $maxLinks" }
    }

    // A pair in dial range: since when, and how long this approach's dial takes.
    private class Dial(
        val sinceMs: Long,
        val takesMs: Long,
    )

    private val dialing = HashMap<Link, Dial>()
    private val approaches = HashMap<Link, Int>()
    private val up = initial.toMutableSet()

    @Volatile
    override var links: Set<Link> = up.toSet()
        private set

    override fun summaryValues(): List<Pair<String, Any?>> =
        listOf(
            "connect_m" to connectM,
            "drop_m" to dropM,
            "dial_ms" to dialMedianMs,
            "max_links" to maxLinks,
            "link_bps" to linkBytesPerSecond,
            "node_bps" to nodeBytesPerSecond,
        )

    override fun step(
        atMs: Long,
        positions: List<Point>,
    ): LinkChanges {
        val dropped = mutableListOf<Link>()
        val ready = mutableListOf<Pair<Link, Double>>()
        for (a in positions.indices) {
            for (b in a + 1 until positions.size) {
                val link = Link(a, b)
                val distance = positions[a].distanceTo(positions[b])
                when {
                    link in up -> {
                        if (distance > dropM) dropped += link
                    }

                    distance > connectM -> {
                        dialing.remove(link)
                    }

                    else -> {
                        val dial = dialing.getOrPut(link) { Dial(atMs, dialTime(link)) }
                        if (atMs - dial.sinceMs >= dial.takesMs) ready += link to distance
                    }
                }
            }
        }
        up -= dropped.toSet()
        val degree = IntArray(positions.size)
        up.forEach {
            degree[it.a]++
            degree[it.b]++
        }
        val raised = mutableListOf<Link>()
        for ((link, _) in ready.sortedBy { it.second }) {
            if (degree[link.a] >= maxLinks || degree[link.b] >= maxLinks) continue
            degree[link.a]++
            degree[link.b]++
            raised += link
            dialing.remove(link)
        }
        up += raised
        links = up.toSet()
        return LinkChanges(raised, dropped)
    }

    // This approach's dial time: log-normal around the median, the same for a seed, a pair and its nth approach. The four
    // are hashed before they seed the draw: `java.util.Random`'s first gaussian barely moves between adjacent seeds, so a
    // pair's next approach would redraw about the dial it had.
    private fun dialTime(link: Link): Long {
        val n = approaches.merge(link, 1, Int::plus) ?: 1
        val key = listOf(seed, link.a.toLong(), link.b.toLong(), n.toLong()).fold(0L) { h, v -> mix(h * PAIR_MIX + v) }
        val draw = java.util.Random(key).nextGaussian()
        return exp(ln(dialMedianMs.toDouble()) + DIAL_SIGMA * draw).toLong().coerceIn(DIAL_MIN_MS, DIAL_MAX_MS)
    }

    companion object {
        const val NAME = "ble1m"
        const val LINK_BYTES_PER_S = 9_000L
        const val NODE_BYTES_PER_S = 36_000L

        /** The median relink after a drop once the 2026-10 fixes were in (`android-ble-link-trial`): 4.3 s. */
        const val DIAL_MEDIAN_MS = 4_300L

        /** Wide enough that most dials fall in the measured 0.3 to 9.5 s and a few run to the 30 s tail. */
        const val DIAL_SIGMA = 0.8
        const val DIAL_MIN_MS = 300L
        const val DIAL_MAX_MS = 30_000L
        private const val PAIR_MIX = 1_000_003L

        // SplitMix64's finaliser: every input bit moves every output bit.
        private fun mix(x: Long): Long {
            var z = x + GOLDEN
            z = (z xor (z ushr 30)) * MIX_1
            z = (z xor (z ushr 27)) * MIX_2
            return z xor (z ushr 31)
        }

        private const val GOLDEN = -0x61c8864680b583ebL
        private const val MIX_1 = -0x40a7b892e31b1a47L
        private const val MIX_2 = -0x6b2fb644ecceee15L
    }
}
