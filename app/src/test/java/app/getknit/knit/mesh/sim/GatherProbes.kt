package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fresh DMs a gather sends to a friend over a link it raised, to time the wait behind every backlog those links serve
 * at once: the head-of-line cost of readers that wait (#331). [nodes] are the outing's, once it has booted.
 */
class GatherProbes(
    private val nodes: () -> List<LabNode>,
    private val groups: List<List<Int>>,
) {
    /** A DM sent to a friend one link away; [arrivedAt] once its row is there. */
    private class Probe(
        val from: Int,
        val to: Int,
        val body: String,
        val sentAt: Long,
    ) {
        @Volatile var arrivedAt: Long? = null
    }

    private val sent = CopyOnWriteArrayList<Probe>()

    /** The thread each probe went in: a lost probe is a strand like any other DM. */
    val pairs: List<Link> get() = sent.map { Link.of(it.from, it.to) }

    /**
     * One probe in each of the first [PROBES] groups with a friend pair the gather [raised], then, up to [PROBES], one on
     * each of the [friends] pairs it raised: a walking gather starts its groups linked and keeps them so, so the links
     * it raises are between friends who walked apart.
     */
    suspend fun send(
        raised: Set<Link>,
        friends: Set<Link> = emptySet(),
    ) {
        val nodes = nodes()
        val inGroups = groups.mapNotNull { group -> SimWorld.pairs(group).firstOrNull { it in raised } }
        val pairs = (inGroups + friends.filter { it in raised }.sortedBy { it.a * nodes.size + it.b }).distinct().take(PROBES)
        for ((i, pair) in pairs.withIndex()) {
            val body = "probe $i"
            val at = System.nanoTime() / NANOS_PER_MS
            if (nodes[pair.a].sendDm(nodes[pair.b], body)) sent += Probe(pair.a, pair.b, body, at)
        }
    }

    /** Notes the probes that have landed; true while any is still missing. */
    suspend fun missing(): Boolean {
        val nodes = nodes()
        val now = System.nanoTime() / NANOS_PER_MS
        for (p in sent.filter { it.arrivedAt == null }) {
            val to = nodes[p.to]
            if (rows(to, to.dmWith(nodes[p.from]))[p.body] != null) p.arrivedAt = now
        }
        return sent.any { it.arrivedAt == null }
    }

    /**
     * The probes' times, read at the gather's 1 s poll and the settle's 15 s one: an upper bound, in whole seconds, into
     * [summary] too. A gather that [walked] sent them once everyone stood at the meeting point.
     */
    suspend fun line(
        after: String,
        walked: Boolean,
        summary: SimSummary,
    ): String {
        if (sent.isEmpty()) return "probes: none sent"
        missing()
        val took = sent.map { p -> p.arrivedAt?.let { (it - p.sentAt) / MS_PER_S } }
        val missing = took.count { it == null }
        summary["probes_sent"] = sent.size
        summary["probes_max_s"] = took.filterNotNull().maxOrNull()
        summary["probes_missing"] = missing
        val how = if (walked) "once everyone stood at the meeting point" else "in as its links came up"
        return "probes: ${sent.size} DMs sent to a friend over a link the gather raised, $how, " +
            "arrived by " + took.filterNotNull().sorted().joinToString(" ") { "${it}s" } + " (polled)" +
            if (missing > 0) "; $missing not in by the end of $after" else ""
    }

    private companion object {
        /** Fresh DMs sent across the gather's new links, to time the wait behind their backlogs. */
        const val PROBES = 8
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
    }
}
