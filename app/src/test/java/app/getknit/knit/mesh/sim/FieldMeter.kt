package app.getknit.knit.mesh.sim

import java.util.Locale

/**
 * What the field did over a run, recorded every radio step and measured the way the proximity literature measured its
 * traces, so a mobility model is judged by shapes a study can confirm before any delivery number is read:
 *
 * - **Five contact logs** ([ContactStats] each): the radio's links after dial and cap, everyone within [NEAR_M] (the 500 ms
 *   truth a scanner would sample), and that truth seen through each [ScanProfile]'s [VirtualScanner]. A trace's scanner
 *   logged inquiry responses, not connections, so it is compared with its scanned log, never with the links; the gap
 *   between `near30` and `link` is what the dial and the cap cost.
 * - **The link budget**: the share of walker-time spent at the radio's [maxLinks], where a new neighbour waits.
 * - **Groups**, over every pair of walkers in one [groups] entry: the share of time they stood within [TOGETHER_M], how
 *   often they parted beyond [APART_M] a pair-hour, and how long a parted pair took to come back within [TOGETHER_M].
 *   Street counts find 55 to 70% of pedestrians walking in groups that last the whole trip.
 * - **DM pairs ever linked**: of the pairs the workload DMs between ([dmPairs]), the share the radio ever linked directly.
 * - **Against the published traces**: each scanner's one-sided [VirtualScanner.sightings] scored against every
 *   [ReferenceTable] its profile matches, by [ReferenceTable.distance]: gaps from [GAP_FLOOR_SCANS] scan periods, below
 *   which the scanning rule rather than the walk decides them, to half the run; contact lengths up to half the run.
 *   Compare shapes, not rates: a 30 m iMote among 48 walkers in a field is not 41 people at a conference.
 *
 * [contacts] keeps the first outings' aggregate line and keys beside these.
 */
class FieldMeter(
    private val walkers: Int,
    seed: Long,
    groups: List<List<Int>>?,
    private val maxLinks: Int?,
    private val dmPairs: Set<Link>,
    initialLinks: Set<Link>,
) {
    val contacts = ContactLog(walkers)
    private val links = PairLog()
    private val near = PairLog()
    private var nearUp = emptySet<Link>()
    private val scanners = ScanProfile.ALL.map { VirtualScanner(it, seed, walkers) }
    private val scanRangeM = ScanProfile.ALL.maxOf { it.rangeM }
    private val references = scanners.map { scanner -> scanner to ReferenceTable.ALL.filter { it.profile == scanner.profile.name } }
    private val initial = initialLinks
    private var firstMs: Long? = null
    private var lastMs = 0L
    private var capWalkerTicks = 0L
    private var presentWalkerTicks = 0L
    private val groupPairs = groups.orEmpty().flatMap { group -> group.flatMapIndexed { i, a -> group.drop(i + 1).map { Link.of(a, it) } } }
    private val parted = HashMap<Link, Long?>()
    private var groupPairTicks = 0L
    private var togetherTicks = 0L
    private var partings = 0
    private val rejoinsMs = mutableListOf<Long>()

    /** One radio step at [atMs]: where everyone stood, who was in the field, what the radio [changes], and its [up] links. */
    fun record(
        atMs: Long,
        positions: List<Point>,
        present: BooleanArray,
        changes: LinkChanges,
        up: Set<Link>,
    ) {
        val first = firstMs == null
        if (first) {
            firstMs = atMs
            links.begin(initial, atMs)
        }
        lastMs = atMs
        contacts.record(atMs, changes, up)
        links.record(atMs, changes)
        scanners.forEach { it.tick(atMs) }
        val nowNear = survey(positions, present)
        if (first) {
            near.begin(nowNear, atMs)
        } else {
            near.record(atMs, LinkChanges(raised = (nowNear - nearUp).toList(), dropped = (nearUp - nowNear).toList()))
        }
        nearUp = nowNear
        budget(present, up)
        groupStep(atMs, positions, present, first)
    }

    // Every present pair's distance, to the scanners; returns the pairs within [NEAR_M].
    private fun survey(
        positions: List<Point>,
        present: BooleanArray,
    ): Set<Link> {
        val nowNear = HashSet<Link>()
        for (a in positions.indices) {
            for (b in a + 1 until positions.size) {
                val distance = if (present[a] && present[b]) positions[a].distanceTo(positions[b]) else Double.MAX_VALUE
                if (distance <= NEAR_M) nowNear += Link(a, b)
                if (distance <= scanRangeM) scanners.forEach { it.near(a, b, distance) }
            }
        }
        return nowNear
    }

    private fun budget(
        present: BooleanArray,
        up: Set<Link>,
    ) {
        val cap = maxLinks ?: return
        val degree = IntArray(walkers)
        up.forEach {
            degree[it.a]++
            degree[it.b]++
        }
        presentWalkerTicks += present.count { it }
        capWalkerTicks += degree.count { it >= cap }
    }

    private fun groupStep(
        atMs: Long,
        positions: List<Point>,
        present: BooleanArray,
        first: Boolean,
    ) {
        for (pair in groupPairs) {
            if (present[pair.a] && present[pair.b]) groupPair(pair, atMs, positions[pair.a].distanceTo(positions[pair.b]), first)
        }
    }

    private fun groupPair(
        pair: Link,
        atMs: Long,
        distance: Double,
        first: Boolean,
    ) {
        groupPairTicks++
        if (distance <= TOGETHER_M) togetherTicks++
        when {
            // A pair already apart when the field is first read parted at a time nobody saw.
            first -> {
                if (distance > APART_M) parted[pair] = null
            }

            pair !in parted -> {
                if (distance > APART_M) {
                    parted[pair] = atMs
                    partings++
                }
            }

            distance <= TOGETHER_M -> {
                parted.remove(pair)?.let { rejoinsMs += atMs - it }
            }
        }
    }

    private fun spanMs(): Long = lastMs - (firstMs ?: lastMs)

    /** Every log's statistics, by its summary prefix and label. */
    private fun stats(): List<Triple<String, String, ContactStats>> {
        val from = firstMs ?: 0
        val logs =
            listOf(
                Triple("link", "the radio's links, every step", links.contacts(lastMs)),
                Triple("near30", "anyone within ${NEAR_M.toInt()} m, every step", near.contacts(lastMs)),
            ) +
                scanners.map { Triple(it.profile.name, it.profile.description(), it.contacts()) }
        return logs.map { (prefix, label, log) -> Triple(prefix, label, ContactStats.of(log, walkers, from, lastMs)) }
    }

    /** One scanner's sightings scored against one trace's. */
    private class Comparison(
        val scanner: ScanProfile,
        val table: ReferenceTable,
        val gap: ReferenceTable.Fit?,
        val contact: ReferenceTable.Fit?,
    ) {
        fun line(): String {
            fun fit(fit: ReferenceTable.Fit?) =
                fit?.let {
                    "${"%.2f".format(Locale.ROOT, it.distance)} over ${seconds(it.fromS)}-${seconds(it.toS)} " +
                        "(${it.samples} against the trace's ${it.referenceSamples})"
                } ?: "n/a"
            return "  ${scanner.name} ~ ${table.name}: gap ${fit(gap)}; contact ${fit(contact)}"
        }

        fun summaryValues(): List<Pair<String, Any?>> =
            listOf("gap" to gap?.distance, "contact" to contact?.distance).map { (kind, distance) ->
                "${scanner.name}_ks_${table.name}_$kind" to distance
            }

        private fun seconds(s: Double) = "%.0fs".format(Locale.ROOT, s)
    }

    private fun comparisons(): List<Comparison> {
        val spanMs = spanMs()
        val toS = spanMs / 2 / MS_PER_S
        return references.flatMap { (scanner, tables) ->
            if (tables.isEmpty()) return@flatMap emptyList()
            val sightings = scanner.sightings().values
            val gaps = ContactStats.gapsMs(sightings)
            val lengths = ContactStats.lengthsMs(sightings)
            val floorS = GAP_FLOOR_SCANS * scanner.profile.periodMs / MS_PER_S
            tables.map { table ->
                Comparison(
                    scanner.profile,
                    table,
                    gap = table.distance(ReferenceTable.Kind.GAP, gaps, spanMs, floorS, toS),
                    contact = table.distance(ReferenceTable.Kind.CONTACT, lengths, spanMs, fromS = null, toS),
                )
            }
        }
    }

    /** The report's lines: the aggregate, each log, the published traces, then the budget, the groups and the DM pairs. */
    fun lines(): List<String> {
        val all = stats()
        val legend = all.joinToString("; ") { (prefix, label, _) -> "$prefix, $label" }
        val traces =
            "KS distance of each scanner's one-sided sightings from the published traces seen the same way " +
                "(gaps from $GAP_FLOOR_SCANS scans, each run's values weighted for the run's length):"
        return listOf(contacts.summary(), "contact logs as each instrument saw the field ($legend); a gap is an inter-contact time:") +
            all.map { (prefix, _, stats) -> stats.line(prefix) } +
            traces +
            comparisons().map { it.line() } +
            fieldLine()
    }

    private fun fieldLine(): String {
        val cap = share(capWalkerTicks, presentWalkerTicks)?.let { "phones at the $maxLinks-link budget $it% of the time" }
        val group =
            if (groupPairs.isEmpty()) {
                null
            } else {
                "group pairs within ${TOGETHER_M.toInt()} m ${share(togetherTicks, groupPairTicks) ?: "n/a"}% of the time, " +
                    "${partingsPerPairHour()?.let { "%.2f".format(Locale.ROOT, it) } ?: "n/a"} partings beyond ${APART_M.toInt()} m " +
                    "a pair-hour, ${share(rejoinsMs.size.toLong(), partings.toLong()) ?: "n/a"}% back within ${TOGETHER_M.toInt()} m " +
                    "(p50 ${seconds(percentile(rejoinsMs.sorted(), P50))})"
            }
        val dm = dmEverLinked()?.let { "DM pairs ever linked $it%" }
        return "field: " + listOfNotNull(cap, group, dm).joinToString("; ").ifEmpty { "nothing more to measure" }
    }

    private fun partingsPerPairHour(): Double? {
        val hours = spanMs() / MS_PER_HOUR
        return if (groupPairs.isEmpty() || hours <= 0) null else partings / groupPairs.size.toDouble() / hours
    }

    private fun dmEverLinked(): Int? {
        if (dmPairs.isEmpty()) return null
        val linked = links.contacts(lastMs).keys
        return dmPairs.count { it in linked } * PERCENT / dmPairs.size
    }

    /** [lines]' numbers for [SimSummary], after [contacts]' own. */
    fun summaryValues(): List<Pair<String, Any?>> =
        contacts.summaryValues() +
            stats().flatMap { (prefix, _, stats) -> stats.summaryValues(prefix) } +
            comparisons().flatMap { it.summaryValues() } +
            listOf(
                "cap_time_pct" to share(capWalkerTicks, presentWalkerTicks),
                "group_together_pct" to share(togetherTicks, groupPairTicks),
                "group_partings_per_pair_hour" to partingsPerPairHour(),
                "group_rejoined_pct" to share(rejoinsMs.size.toLong(), partings.toLong()).takeIf { groupPairs.isNotEmpty() },
                "group_rejoin_p50_s" to tenths(percentile(rejoinsMs.sorted(), P50)),
                "dm_pairs_ever_linked_pct" to dmEverLinked(),
            )

    private fun share(
        part: Long,
        whole: Long,
    ): Int? = if (whole == 0L) null else (part * PERCENT / whole).toInt()

    companion object {
        /** The truth a 30 m scanner samples: between the BLE radio's dial and drop ranges. */
        const val NEAR_M = 30.0

        /** A group's members are together within this: about a phone's dial range. */
        const val TOGETHER_M = 25.0

        /** And parted beyond this: past where a link drops. */
        const val APART_M = 35.0

        /** A scanned trace's gaps are trusted from this many scan periods up: 10 minutes for an iMote. */
        const val GAP_FLOOR_SCANS = 5
        private const val MS_PER_S = 1_000.0
        private const val PERCENT = 100
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
