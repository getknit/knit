package app.getknit.knit.mesh.sim

import java.util.Locale

/** How the two ends of a DM stood when it was sent, read off the link graph at that moment. */
enum class Reach {
    /** Linked to each other. */
    DIRECT,

    /** No link, but a path through other walkers. */
    PATH,

    /** No path: only custody and somebody's walk can deliver it. */
    PARTITIONED,
    ;

    companion object {
        fun of(hops: Int?): Reach =
            when (hops) {
                null -> PARTITIONED
                1 -> DIRECT
                else -> PATH
            }
    }
}

/** A DM the workload sent and what became of it. [latencyMs] is null for one that never arrived. */
data class DmOutcome(
    val event: SimEvent.Dm,
    val reach: Reach,
    val latencyMs: Long?,
    val arrivedInRun: Boolean,
    val acked: Boolean,
)

/**
 * What the field looked like over a run: how often walkers met and for how long, and how joined-up the crowd
 * was. A scenario's own shape, to read before its delivery numbers: a field that is one component all run
 * long tests the flood, one that is never joined tests custody.
 */
class ContactLog(
    private val walkers: Int,
) {
    private val upSince = HashMap<Link, Long>()
    private val contactsMs = mutableListOf<Long>()
    private var raised = 0
    private var ticks = 0
    private var linkTicks = 0L
    private var joinedTicks = 0
    private var largestSum = 0L
    private var maxDegree = 0

    /** Records one radio step: what it [changes], and the [links] up after it. */
    fun record(
        atMs: Long,
        changes: LinkChanges,
        links: Set<Link>,
    ) {
        changes.raised.forEach { upSince[it] = atMs }
        raised += changes.raised.size
        changes.dropped.forEach { link -> upSince.remove(link)?.let { contactsMs += atMs - it } }
        ticks++
        linkTicks += links.size
        val largest = largestComponent(links, walkers)
        largestSum += largest
        if (largest == walkers) joinedTicks++
        val degrees = IntArray(walkers)
        links.forEach {
            degrees[it.a]++
            degrees[it.b]++
        }
        maxDegree = maxOf(maxDegree, degrees.maxOrNull() ?: 0)
    }

    fun summary(): String {
        val ended = contactsMs.sorted()
        val meanDegree = if (ticks == 0) 0.0 else 2.0 * linkTicks / ticks / walkers
        val meanLargest = if (ticks == 0) 0.0 else largestSum.toDouble() / ticks
        return "contacts: $raised made, ${ended.size} ended (p50 ${seconds(percentile(ended, P50))}, " +
            "p90 ${seconds(percentile(ended, P90))}); degree mean ${"%.2f".format(Locale.ROOT, meanDegree)} max $maxDegree; " +
            "largest component mean ${"%.1f".format(Locale.ROOT, meanLargest)} of $walkers, " +
            "all joined ${percent(joinedTicks, ticks)} of the run"
    }

    /** [summary]'s numbers for [SimSummary]. */
    fun summaryValues(): List<Pair<String, Any?>> {
        val ended = contactsMs.sorted()
        return listOf(
            "contacts_made" to raised,
            "contact_p50_s" to tenths(percentile(ended, P50)),
            "degree_mean" to if (ticks == 0) null else 2.0 * linkTicks / ticks / walkers,
            "degree_max" to maxDegree,
            "largest_component_mean" to if (ticks == 0) null else largestSum.toDouble() / ticks,
            "all_joined_pct" to share(joinedTicks, ticks),
        )
    }
}

/** The size of the biggest set of walkers [links] join into one mesh: union-find, linear in the links. */
fun largestComponent(
    links: Set<Link>,
    walkers: Int,
): Int {
    if (walkers == 0) return 0
    val parent = IntArray(walkers) { it }

    fun root(of: Int): Int {
        var at = of
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    links.forEach { parent[root(it.a)] = root(it.b) }
    val sizes = IntArray(walkers)
    for (walker in 0 until walkers) sizes[root(walker)]++
    return sizes.max()
}

/** The nearest-rank [p]th percentile of [sorted], or null when it is empty. */
fun percentile(
    sorted: List<Long>,
    p: Int,
): Long? {
    if (sorted.isEmpty()) return null
    val rank = (p * sorted.size + PERCENT - 1) / PERCENT
    return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)]
}

/** One line per [Reach]: how many DMs were sent that way, how many arrived inside the run, and how fast. */
fun deliveryLines(outcomes: List<DmOutcome>): List<String> =
    (Reach.entries.map { reach -> reach.name.lowercase() to outcomes.filter { it.reach == reach } } + ("all" to outcomes))
        .map { (label, sent) ->
            val inRun = sent.filter { it.arrivedInRun }
            val latencies = inRun.mapNotNull { it.latencyMs }.sorted()
            "  ${label.padEnd(LABEL_WIDTH)} sent ${sent.size.toString().padStart(COUNT_WIDTH)}  " +
                "arrived in run ${percent(inRun.size, sent.size).padStart(PERCENT_WIDTH)}  " +
                "latency p50 ${seconds(percentile(latencies, P50))} p90 ${seconds(percentile(latencies, P90))} " +
                "max ${seconds(latencies.lastOrNull())}  " +
                "arrived by the end ${percent(sent.count { it.latencyMs != null }, sent.size)}  " +
                "ticked ${percent(sent.count { it.acked }, sent.size)}"
        }

internal fun percent(
    part: Int,
    whole: Int,
): String = if (whole == 0) "n/a" else "${part * PERCENT / whole}%"

// Locale.ROOT: a report reads the same on a decimal-comma workstation, and so does a test that reads it.
internal fun seconds(ms: Long?): String = if (ms == null) "n/a" else "%.1fs".format(Locale.ROOT, ms / MS_PER_S)

internal const val P50 = 50
internal const val P90 = 90
private const val PERCENT = 100
private const val MS_PER_S = 1_000.0
private const val LABEL_WIDTH = 11
private const val COUNT_WIDTH = 4
private const val PERCENT_WIDTH = 4
