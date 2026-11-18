package app.getknit.knit.mesh.sim

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One contact of a pair, from [startMs] to [endMs]. A censored one was already on when its log began or still on when it
 * ended: its length is a lower bound, not a measurement, so durations leave it out.
 */
data class Contact(
    val startMs: Long,
    val endMs: Long,
    val censored: Boolean = false,
) {
    val lengthMs: Long get() = endMs - startMs
}

/** Every pair's contacts over a run, as intervals, from the changes of a set of pairs (a radio's links, or who is near). */
class PairLog {
    private val openSince = HashMap<Link, Long>()
    private val onAtStart = HashSet<Link>()
    private val closed = HashMap<Link, MutableList<Contact>>()

    /** The pairs already on when the log begins, at [atMs]: their first contacts are censored at the start. */
    fun begin(
        on: Set<Link>,
        atMs: Long,
    ) {
        on.forEach { openSince[it] = atMs }
        onAtStart += on
    }

    /** One step's [changes] at [atMs]. */
    fun record(
        atMs: Long,
        changes: LinkChanges,
    ) {
        for (link in changes.dropped) {
            val since = openSince.remove(link) ?: continue
            closed.getOrPut(link) { mutableListOf() } += Contact(since, atMs, censored = onAtStart.remove(link))
        }
        changes.raised.forEach { openSince.putIfAbsent(it, atMs) }
    }

    /** Every pair's contacts so far, in order, with those still on ended at [endMs] and censored there. */
    fun contacts(endMs: Long): Map<Link, List<Contact>> {
        val all = HashMap<Link, MutableList<Contact>>()
        closed.forEach { (link, contacts) -> all[link] = contacts.toMutableList() }
        openSince.forEach { (link, since) -> all.getOrPut(link) { mutableListOf() } += Contact(since, endMs, censored = true) }
        return all.mapValues { (_, contacts) -> contacts.sortedBy { it.startMs } }
    }
}

/**
 * The statistics the proximity literature reads off a contact log, computed its way (Chaintreau et al. 2006, "Impact of
 * human mobility on the design of opportunistic forwarding algorithms"; Karagiannis et al. 2007):
 *
 * - **Contact duration**, of the contacts seen to start and end.
 * - **Inter-contact time (gap)**, from the end of one of a pair's contacts to the start of its next, one sample a gap
 *   (never at a random instant, which weighs long gaps more). [ictCv] is 1 for an exponential; [ictTailLlr] compares
 *   the gaps from the median up as a power law and as an exponential, each fitted by maximum likelihood (Clauset, Shalizi
 *   and Newman 2009), as the mean log-likelihood ratio a gap: above 0 the power law fits better, below it the exponential.
 *   Bounded random waypoint's tail is exponential; every human trace's head is a power law.
 * - **How uneven the pairs are**: [halvesR], the correlation across every pair of the time it spent in contact in the
 *   run's first half and in its second. Pairs that are statistically alike, as random waypoint makes them, score about 0;
 *   friends who stay together, near 1.
 * - **Reach**: [perPairHour] contacts a pair-hour over every pair, the share of pairs that met at all and how many
 *   others each walker met.
 */
class ContactStats private constructor(
    val contacts: Int,
    val durationsMs: List<Long>,
    val ictsMs: List<Long>,
    val perPairHour: Double?,
    val pairsMetPct: Int?,
    val peersMean: Double?,
    val halvesR: Double?,
    val ictCv: Double?,
    val ictTailLlr: Double?,
) {
    /** One report line: [label] padded, then the numbers. */
    fun line(label: String): String {
        val tail = ictTailLlr?.let { "${fmt(it)} (${if (it < 0) "exponential" else "heavy"})" } ?: "n/a"
        return "  ${label.padEnd(LABEL_WIDTH)} ${perPairHour?.let(::fmt) ?: "n/a"} a pair-hour; " +
            "contact p50 ${seconds(percentile(durationsMs, P50))} p90 ${seconds(percentile(durationsMs, P90))}; " +
            "gap p50 ${seconds(percentile(ictsMs, P50))} p90 ${seconds(percentile(ictsMs, P90))} " +
            "cv ${ictCv?.let(::fmt) ?: "n/a"} tail $tail; " +
            "pairs met ${pairsMetPct?.let { "$it%" } ?: "n/a"}, ${peersMean?.let { "%.1f".format(Locale.ROOT, it) } ?: "n/a"} " +
            "peers each; halves r ${halvesR?.let(::fmt) ?: "n/a"}"
    }

    /** The numbers as [SimSummary] keys, each starting [prefix]. */
    fun summaryValues(prefix: String): List<Pair<String, Any?>> =
        listOf(
            "per_pair_hour" to perPairHour,
            "contact_p50_s" to tenths(percentile(durationsMs, P50)),
            "contact_p90_s" to tenths(percentile(durationsMs, P90)),
            "ict_p50_s" to tenths(percentile(ictsMs, P50)),
            "ict_p90_s" to tenths(percentile(ictsMs, P90)),
            "ict_cv" to ictCv,
            "ict_tail_llr" to ictTailLlr,
            "pairs_met_pct" to pairsMetPct,
            "halves_r" to halvesR,
        ).map { (key, value) -> "${prefix}_$key" to value }

    companion object {
        /** The statistics of [contacts] among [walkers], over the log's span from [fromMs] to [toMs]. */
        fun of(
            contacts: Map<Link, List<Contact>>,
            walkers: Int,
            fromMs: Long,
            toMs: Long,
        ): ContactStats {
            val pairs = walkers.toLong() * (walkers - 1) / 2
            val hours = (toMs - fromMs) / MS_PER_HOUR
            val count = contacts.values.sumOf { it.size }
            val durations = lengthsMs(contacts.values)
            val icts = gapsMs(contacts.values)
            val peers = IntArray(walkers)
            contacts.keys.forEach {
                peers[it.a]++
                peers[it.b]++
            }
            return ContactStats(
                contacts = count,
                durationsMs = durations,
                ictsMs = icts,
                perPairHour = if (pairs == 0L || hours <= 0) null else count / pairs.toDouble() / hours,
                pairsMetPct = if (pairs == 0L) null else (contacts.size * PERCENT / pairs).toInt(),
                peersMean = if (walkers == 0) null else peers.average(),
                halvesR = halvesR(contacts, pairs, fromMs, toMs),
                ictCv = cv(icts),
                ictTailLlr = tailLlr(icts),
            )
        }

        /** The lengths of the contacts seen to begin and end, in each pair's ordered [contacts], sorted. */
        internal fun lengthsMs(contacts: Collection<List<Contact>>): List<Long> =
            contacts
                .flatten()
                .filterNot { it.censored }
                .map { it.lengthMs }
                .sorted()

        /** Every gap from one of a pair's contacts to its next, over each pair's ordered [contacts], sorted. */
        internal fun gapsMs(contacts: Collection<List<Contact>>): List<Long> =
            contacts.flatMap { list -> list.zipWithNext { a, b -> b.startMs - a.endMs } }.sorted()

        // Pearson's r across every pair, met or not, of its contact time in the first half against the second.
        private fun halvesR(
            contacts: Map<Link, List<Contact>>,
            pairs: Long,
            fromMs: Long,
            toMs: Long,
        ): Double? {
            if (pairs < 2) return null
            val midMs = (fromMs + toMs) / 2
            var sx = 0.0
            var sy = 0.0
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (list in contacts.values) {
                val x = list.sumOf { overlap(it, fromMs, midMs) }.toDouble()
                val y = list.sumOf { overlap(it, midMs, toMs) }.toDouble()
                sx += x
                sy += y
                sxy += x * y
                sxx += x * x
                syy += y * y
            }
            val n = pairs.toDouble()
            val cov = sxy - sx * sy / n
            val vx = sxx - sx * sx / n
            val vy = syy - sy * sy / n
            return if (vx <= 0 || vy <= 0) null else cov / sqrt(vx * vy)
        }

        private fun overlap(
            contact: Contact,
            fromMs: Long,
            toMs: Long,
        ): Long = (minOf(contact.endMs, toMs) - maxOf(contact.startMs, fromMs)).coerceAtLeast(0)

        private fun cv(values: List<Long>): Double? {
            if (values.size < 2) return null
            val mean = values.average()
            if (mean <= 0) return null
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            return sqrt(variance) / mean
        }

        /**
         * The mean log-likelihood ratio, power law over exponential, of the gaps from the median up ([sortedMs] sorted),
         * each fitted to them by maximum likelihood with the median as its lower bound: α = 1 + n / Σ ln(x / xmin) and
         * λ = 1 / mean(x − xmin). Null with fewer than [MIN_TAIL] gaps in the tail, or a tail with no spread.
         */
        internal fun tailLlr(sortedMs: List<Long>): Double? {
            if (sortedMs.isEmpty()) return null
            val xmin = sortedMs[(sortedMs.size - 1) / 2].toDouble() / MS_PER_S
            if (xmin <= 0) return null
            val tail = sortedMs.map { it / MS_PER_S }.filter { it >= xmin }
            if (tail.size < MIN_TAIL) return null
            val logs = tail.sumOf { ln(it / xmin) }
            val excess = tail.sumOf { it - xmin } / tail.size
            if (logs <= 0 || excess <= 0) return null
            val alpha = 1 + tail.size / logs
            val lambda = 1 / excess
            return tail.sumOf { x -> ln(alpha - 1) - ln(xmin) - alpha * ln(x / xmin) - ln(lambda) + lambda * (x - xmin) } / tail.size
        }

        private fun fmt(value: Double): String = "%.2f".format(Locale.ROOT, value)

        /** Fewer gaps than this in a tail fit nothing worth a number. */
        const val MIN_TAIL = 20
        private const val PERCENT = 100
        private const val MS_PER_S = 1_000.0
        private const val MS_PER_HOUR = 3_600_000.0
        private const val LABEL_WIDTH = 8
    }
}
