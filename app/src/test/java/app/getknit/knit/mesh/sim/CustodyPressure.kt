package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.protocol.FrameType
import java.util.Locale

/** What a custody row carries, as the simulator can tell from outside the store. */
enum class RowKind {
    /** A workload DM its recipient has not got yet: the row custody exists for. */
    DM_UNDELIVERED,

    /** A workload DM already in its recipient's thread. A sealed receipt purges nothing, so it stays for its TTL. */
    DM_DELIVERED,

    /**
     * A recipient-addressed chat frame that is no workload DM: sealed receipts, session hellos, ratchet traffic, the
     * gather's probe DMs, and the DMs of a backlog ([Workload.backlog]).
     */
    SEALED_OTHER,

    /** A cleartext post in the Nearby room. */
    ROOM,

    /** A post in a party's group chat ([GroupChats]), the boot's and the backlog's too: no receipt purges it. */
    GROUP,
    PROFILE,
    OTHER,
}

/** One live custody row: its frame id, what it carries and when its author sent it. */
data class HeldRow(
    val id: String,
    val kind: RowKind,
    val sentAt: Long,
)

/**
 * One row as `LabNode.custodyFrames` writes it (`id:type:sender→recipient@sentAt#…`), classed; [pending] says, for each
 * workload DM's frame id, whether it is still on its way, and [groupPosts] are the workload's group-chat posts.
 */
fun heldRow(
    described: String,
    pending: Map<String, Boolean>,
    groupPosts: Set<String> = emptySet(),
): HeldRow {
    val (id, type, rest) = described.split(':', limit = ROW_PARTS)
    val recipient = rest.substringAfter('→').substringBefore('@')
    val sentAt = rest.substringAfter('@').substringBefore('#').toLong()
    val kind =
        when {
            id in pending -> if (pending.getValue(id)) RowKind.DM_UNDELIVERED else RowKind.DM_DELIVERED
            id in groupPosts -> RowKind.GROUP
            type == FrameType.CHAT -> if (recipient == "null") RowKind.ROOM else RowKind.SEALED_OTHER
            type == FrameType.PROFILE -> RowKind.PROFILE
            else -> RowKind.OTHER
        }
    return HeldRow(id, kind, sentAt)
}

/** `id`, `type` and the rest of a described row. */
private const val ROW_PARTS = 3

/**
 * Custody under load, read from samples of every store: what fills the stores, how far back each one reaches, and
 * what leaves them. Nothing in an outing expires (the TTL is 24 h, a room post's 6 h), so a row gone since the last
 * sample was evicted by a quota, or, for a delivered DM, purged by a cleartext receipt (`ForwardSync.onAck`, when a
 * receipt could not be sealed). What matters is an undelivered DM's copies going: custody is the only thing that can
 * still deliver it, and its sender's own copy is bounded with the rest (ADR 006).
 *
 * Samples are minutes apart, so the counts are lower bounds: a row stored and evicted between two samples is never
 * seen. [cap] is custody's row cap; a store holding it is "at the cap".
 */
class CustodyPressure(
    private val cap: Int,
) {
    /** One reading of the field, [atS] seconds into the outing, in [phase]. */
    data class Sample(
        val atS: Long,
        val phase: String,
        val meanRows: Int,
        val atCap: Int,
        val stores: Int,
        val oldestMedianS: Long?,
        val oldestMinS: Long?,
        val held: Map<RowKind, Int>,
        val gone: Map<RowKind, Int>,
    )

    private var previous: List<Map<String, RowKind>> = emptyList()
    private val samples = mutableListOf<Sample>()
    private val copiesEvicted = HashMap<String, Int>()
    private val senderEvicted = HashSet<String>()
    private val everHeld = HashSet<String>()
    private val unheld = HashSet<String>()
    private var lostThenArrived = 0

    /**
     * Reads one sample: [stores] are every node's live rows, by node index; [undelivered] maps each workload DM not yet
     * in its recipient's thread to its sender's node index, for the DMs whose frame id is known.
     */
    fun sample(
        atS: Long,
        phase: String,
        now: Long,
        stores: List<List<HeldRow>>,
        undelivered: Map<String, Int>,
    ): Sample {
        val held = RowKind.entries.associateWith { 0 }.toMutableMap()
        val gone = RowKind.entries.associateWith { 0 }.toMutableMap()
        val current = stores.map { rows -> rows.associate { it.id to it.kind } }
        for ((i, rows) in current.withIndex()) {
            rows.values.forEach { held.merge(it, 1, Int::plus) }
            everHeld += rows.keys.filter { it in undelivered }
            val left = previous.getOrNull(i).orEmpty().filterKeys { it !in rows }
            for ((id, kind) in left) gone.merge(goneAs(id, kind, store = i, undelivered), 1, Int::plus)
        }
        // Held once and by nobody now: custody lost it. One never held yet (parked for a key at its sender) is not.
        for (id in undelivered.keys) if (id in everHeld && current.none { id in it }) unheld += id
        previous = current
        val oldest = stores.mapNotNull { rows -> rows.minOfOrNull { it.sentAt }?.let { (now - it) / MS_PER_S } }.sorted()
        return Sample(
            atS = atS,
            phase = phase,
            meanRows = if (stores.isEmpty()) 0 else stores.sumOf { it.size } / stores.size,
            atCap = stores.count { it.size >= cap },
            stores = stores.size,
            oldestMedianS = oldest.getOrNull(oldest.size / 2),
            oldestMinS = oldest.firstOrNull(),
            held = held,
            gone = gone,
        ).also { samples += it }
    }

    // What a row that left store [store] was when it left, noting an undelivered DM's lost copy. A DM is judged by what
    // it is now: one that arrived since the last sample left as delivered, and is counted apart.
    private fun goneAs(
        id: String,
        kind: RowKind,
        store: Int,
        undelivered: Map<String, Int>,
    ): RowKind {
        if (id !in undelivered) {
            if (kind != RowKind.DM_UNDELIVERED) return kind
            lostThenArrived++
            return RowKind.DM_DELIVERED
        }
        copiesEvicted.merge(id, 1, Int::plus)
        if (undelivered[id] == store) senderEvicted += id
        return RowKind.DM_UNDELIVERED
    }

    /**
     * The report: one line per sample, then what custody took from the DMs it was still carrying. [neverArrived] are the
     * DMs still missing at the end, [untallied] the DMs whose frame id was never read, [slowestSampleMs] what reading
     * one sample cost at most.
     */
    fun lines(
        neverArrived: Set<String>,
        untallied: Int,
        slowestSampleMs: Long,
    ): List<String> {
        val lines = mutableListOf("custody pressure, one line per sample (rows by kind, % of all held; gone since the last):")
        samples.mapTo(lines, ::line)
        lines += "custody evicted copies of at least ${copiesEvicted.size} DMs still undelivered at the next sample " +
            "(${copiesEvicted.values.sum()} copies), ${senderEvicted.size} from their sender's own store; " +
            "$lostThenArrived more copies left DMs that arrived before the next sample; ${unheld.size} DMs custody had " +
            "held were held by no store at a sample, ${unheld.count { it in neverArrived }} of them never arrived; " +
            "$untallied DMs untallied (no frame id); a sample took at most ${slowestSampleMs}ms"
        return lines
    }

    /**
     * [lines]' numbers for [SimSummary]: when a store first reached the cap and when every store had (seconds into the
     * outing, at the sample that first saw it), the most rows a sample's mean reached, and what custody took from DMs
     * it was still carrying.
     */
    fun summaryValues(neverArrived: Set<String>): List<Pair<String, Any?>> =
        listOf(
            "custody_first_at_cap_s" to samples.firstOrNull { it.atCap > 0 }?.atS,
            "custody_all_at_cap_s" to samples.firstOrNull { it.stores > 0 && it.atCap == it.stores }?.atS,
            "custody_mean_rows_max" to samples.maxOfOrNull { it.meanRows },
            "custody_dms_copies_evicted" to copiesEvicted.size,
            "custody_dms_sender_evicted" to senderEvicted.size,
            "custody_dms_unheld" to unheld.size,
            "custody_dms_lost_for_good" to unheld.count { it in neverArrived },
        )

    /** One sample's line of the report. */
    fun line(s: Sample): String {
        val total =
            s.held.values
                .sum()
                .coerceAtLeast(1)
        val shares = RowKind.entries.joinToString(" ") { "${it.label} ${percent(s.held.getValue(it), total)}" }
        val reach = s.oldestMedianS?.let { "oldest row median ${it}s, min ${s.oldestMinS}s" } ?: "stores empty"
        val gone = RowKind.entries.filter { s.gone.getValue(it) > 0 }.joinToString { "${it.label} ${s.gone.getValue(it)}" }
        return "  +${s.atS}s ${s.phase}: mean ${s.meanRows} rows, ${s.atCap}/${s.stores} at the cap; $reach; " +
            "$shares; gone ${gone.ifEmpty { "none" }}"
    }

    private val RowKind.label: String
        get() =
            when (this) {
                RowKind.DM_UNDELIVERED -> "dm-pending"
                RowKind.DM_DELIVERED -> "dm-delivered"
                RowKind.SEALED_OTHER -> "sealed-other"
                RowKind.ROOM -> "room"
                RowKind.GROUP -> "group"
                RowKind.PROFILE -> "profile"
                RowKind.OTHER -> "other"
            }

    private fun percent(
        part: Int,
        whole: Int,
    ): String = "%.0f%%".format(Locale.ROOT, PERCENT * part / whole)

    private companion object {
        const val MS_PER_S = 1_000L
        const val PERCENT = 100.0
    }
}
