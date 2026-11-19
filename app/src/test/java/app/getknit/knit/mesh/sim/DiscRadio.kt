package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.BulkRoute

/** A link between two walkers, by index, the lower one first. */
data class Link(
    val a: Int,
    val b: Int,
) {
    init {
        require(a < b) { "a link names its lower walker first: $a, $b" }
    }

    companion object {
        fun of(
            x: Int,
            y: Int,
        ): Link = if (x < y) Link(x, y) else Link(y, x)
    }
}

/** What one [Radio.step] changed: the links that came up and the ones that went down. */
data class LinkChanges(
    val raised: List<Link>,
    val dropped: List<Link>,
    /** Links that stayed up and moved to another rate ([Radio.airOf]): a fast plane coming up beside them or going. */
    val rerated: List<Link> = emptyList(),
    /** How a file between a pair would now go ([BulkRoute]), for the pairs whose answer changed this step. */
    val routes: Map<Link, BulkRoute> = emptyMap(),
)

/**
 * The simplest radio that still churns like one: a link by distance alone, with the three numbers that keep
 * it from flapping the way no radio does.
 *
 * - **A dial takes time.** Two walkers must stay within [connectM] for [dialMs] before the link exists: the
 *   scan that sights the peer and the connect that follows. Two people passing each other never link.
 * - **A link outlives its dial range.** It holds until the two are more than [dropM] apart, further than
 *   [connectM]: an established link survives a weaker signal than a new one is dialled on.
 * - **A dropped link starts over.** Its dial clock is cleared, so a pair hovering at the edge re-dials.
 *
 * No path loss, no shadowing, no link budget and no plane but one: what the spike measures is the mesh under
 * contacts that come and go, not the radio. [initial] are the links that exist before the first step (a group
 * that arrived together).
 */
class DiscRadio(
    private val connectM: Double,
    private val dropM: Double,
    private val dialMs: Long,
    initial: Set<Link> = emptySet(),
) : Radio {
    override val name = NAME

    init {
        require(dropM >= connectM) { "a link must hold at least as far as it is dialled: $dropM < $connectM" }
    }

    private val inRangeSince = HashMap<Link, Long>()
    private val up = initial.toMutableSet()

    @Volatile
    override var links: Set<Link> = up.toSet()
        private set

    override fun summaryValues(): List<Pair<String, Any?>> = listOf("connect_m" to connectM, "drop_m" to dropM, "dial_ms" to dialMs)

    override fun step(
        atMs: Long,
        positions: List<Point>,
        present: BooleanArray?,
    ): LinkChanges {
        val raised = mutableListOf<Link>()
        val dropped = mutableListOf<Link>()
        for (a in positions.indices) {
            for (b in a + 1 until positions.size) {
                val link = Link(a, b)
                val away = present != null && !(present[a] && present[b])
                val distance = positions[a].distanceTo(positions[b])
                when {
                    link in up -> if (away || distance > dropM) dropped += link
                    away || distance > connectM -> inRangeSince.remove(link)
                    atMs - inRangeSince.getOrPut(link) { atMs } >= dialMs -> raised += link
                }
            }
        }
        up -= dropped.toSet()
        up += raised
        inRangeSince.keys.removeAll(raised.toSet())
        links = up.toSet()
        return LinkChanges(raised, dropped)
    }

    companion object {
        const val NAME = "disc"
    }
}

/**
 * How many links apart [from] and [to] are over [links], or null when no path joins them: 1 is a direct link,
 * more is a path through other walkers, null is a partition only a carrier's walk can cross.
 */
fun hops(
    links: Set<Link>,
    from: Int,
    to: Int,
): Int? {
    if (from == to) return 0
    val neighbors = HashMap<Int, MutableList<Int>>()
    links.forEach {
        neighbors.getOrPut(it.a) { mutableListOf() } += it.b
        neighbors.getOrPut(it.b) { mutableListOf() } += it.a
    }
    val depth = hashMapOf(from to 0)
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        for (next in neighbors[at].orEmpty()) {
            if (next in depth) continue
            depth[next] = depth.getValue(at) + 1
            if (next == to) return depth.getValue(next)
            queue += next
        }
    }
    return null
}
