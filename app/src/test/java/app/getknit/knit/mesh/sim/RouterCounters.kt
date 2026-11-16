package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode
import java.lang.reflect.Modifier

/** The router's counters summed over the field, and the frames that crossed a link. */
internal data class RouterCounters(
    val originated: Long,
    val relayed: Long,
    val deduped: Long,
    val suppressed: Long,
    val crossedLinks: Long,
) {
    operator fun minus(other: RouterCounters) =
        RouterCounters(
            originated - other.originated,
            relayed - other.relayed,
            deduped - other.deduped,
            suppressed - other.suppressed,
            crossedLinks - other.crossedLinks,
        )

    override fun toString(): String =
        "originated $originated, relayed $relayed, deduped $deduped, suppressed $suppressed, crossed a link $crossedLinks"

    companion object {
        fun of(nodes: List<LabNode>): RouterCounters {
            val frames = nodes.map { it.metrics.frames() }
            return RouterCounters(
                originated = frames.sumOf { it.framesOriginated },
                relayed = frames.sumOf { it.framesRelayed },
                deduped = frames.sumOf { it.framesDeduped },
                suppressed = frames.sumOf { it.framesSuppressed },
                crossedLinks = nodes.sumOf { it.transport.framesReceived },
            )
        }
    }
}

/**
 * Every counter in the routers' `frames`, `keys`, `seals` and `receipts` metrics, summed over the field, by `group.field`
 * (a map's entries as `group.field[key]`). Read by reflection, so a counter added to a group shows up unasked. Each is
 * an `AtomicLong` that only counts up.
 */
internal fun counterTotals(nodes: List<LabNode>): Map<String, Long> {
    val totals = sortedMapOf<String, Long>()
    for (node in nodes) {
        val snapshot = node.metrics.snapshot()
        val groups =
            listOf("frames" to snapshot.frames, "keys" to snapshot.keys, "seals" to snapshot.seals, "receipts" to snapshot.receipts)
        groups.flatMap { (name, group) -> counters(name, group) }.forEach { (key, n) -> totals.merge(key, n, Long::plus) }
    }
    return totals
}

private fun counters(
    name: String,
    group: Any,
): List<Pair<String, Long>> =
    group.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.flatMap { field ->
        field.isAccessible = true
        val value: Any? = field.get(group)
        val count = value as? Long
        val counts = value as? Map<*, *>
        if (count != null) {
            listOf("$name.${field.name}" to count)
        } else {
            counts.orEmpty().mapNotNull { (key, n) -> (n as? Number)?.let { "$name.${field.name}[$key]" to it.toLong() } }
        }
    }

/**
 * The counters that moved between [since] and [now], in key order: what the outing did, without what booting the nodes
 * did. `frames.framesDropped` is the sum of the `frames.dropsByReason` entries, so a drop shows in both.
 */
internal fun movedCounters(
    now: Map<String, Long>,
    since: Map<String, Long>,
): String =
    "counters that moved since the walk began, summed over the field: " +
        now
            .mapValues { (key, n) -> n - (since[key] ?: 0L) }
            .filterValues { it != 0L }
            .entries
            .joinToString { "${it.key} ${it.value}" }
