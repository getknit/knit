package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode

/** Every store's live ids, and how far they are from all holding the same. */
internal class CustodyGap(
    val held: List<Set<String>>,
) {
    val union: Set<String> = held.flatMapTo(HashSet()) { it }
    val odd: Set<String> = union.filterTo(HashSet()) { id -> held.any { id !in it } }
    val missing: Int = held.sumOf { union.size - it.size }
    val short: Int = held.count { it.size < union.size }
    val parity: Boolean get() = odd.isEmpty()
}

/** Every id some store among [nodes] holds, as `[id, type, sender→recipient@…]` (the first store's row that names it). */
internal suspend fun described(nodes: List<LabNode>): Map<String, List<String>> {
    val described = HashMap<String, List<String>>()
    for (node in nodes) {
        for (row in node.custodyFrames()) {
            val parts = row.split(':', limit = DESCRIBED_PARTS)
            described.putIfAbsent(parts[0], parts)
        }
    }
    return described
}

/**
 * The [gap]'s odd ids, grouped by type, author and recipient: how many stores hold them, and the author's rows and the
 * whole store at the ones that lack them.
 */
internal suspend fun oddLines(
    gap: CustodyGap,
    described: Map<String, List<String>>,
    nodes: List<LabNode>,
): String {
    val groups = gap.odd.groupBy { id -> described[id]?.let { "${it[1]} ${it[2].substringBefore('@')}" } ?: "?" }
    val lines = mutableListOf<String>()
    for ((what, ids) in groups.entries.sortedByDescending { it.value.size }.take(ODD_GROUPS)) {
        val lacking = gap.held.indices.filter { i -> ids.any { it !in gap.held[i] } }
        val holders = ids.map { id -> gap.held.count { id in it } }
        val prefix = described[ids.first()]?.get(2)?.substringBefore('→')
        val author = prefix?.let { p -> nodes.firstOrNull { it.nodeId.startsWith(p) } }
        val authorRows = author?.let { a -> lacking.map { nodes[it].custodiedFrom(a.nodeId) } }.orEmpty()
        val rows = lacking.map { gap.held[it].size }
        lines += "  ${ids.size} × ${named(what, nodes)}: held by ${holders.min()}–${holders.max()} of ${nodes.size}; " +
            "the ${lacking.size} stores lacking one hold ${span(authorRows)} of ${author?.name ?: "its author"}'s rows, " +
            "${span(rows)} rows in all"
    }
    return lines.joinToString("\n")
}

// `chat abc123→def456` with walker names for the node id prefixes.
private fun named(
    what: String,
    nodes: List<LabNode>,
): String = nodes.fold(what) { text, node -> text.replace(node.nodeId.take(NODE_PREFIX), node.name) }

private fun span(values: List<Int>): String = if (values.isEmpty()) "?" else "${values.min()}–${values.max()}"

/** `id:type:sender→recipient@…`, as [LabNode.custodyFrames] writes a row. */
private const val DESCRIBED_PARTS = 3
private const val ODD_GROUPS = 12

/** How much of a node id [LabNode.custodyFrames] prints. */
private const val NODE_PREFIX = 6
