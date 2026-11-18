package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import java.util.Locale

/**
 * How a published proximity trace looked for its neighbours: each phone or badge runs an inquiry [windowMs] long every
 * [periodMs], give or take [jitterMs], and sights whoever is within [rangeM] during it. [synchronized] scanners all scan
 * at once from time 0; the others start at a phase of their own. [mutual] keeps only the contacts both ends sighted.
 * [code] keys a profile's draws ([SimSeeds]): never renumber one.
 */
data class ScanProfile(
    val name: String,
    val code: Long,
    val periodMs: Long,
    val jitterMs: Long,
    val windowMs: Long,
    val rangeM: Double,
    val synchronized: Boolean,
    val mutual: Boolean,
) {
    init {
        require(windowMs in 1..periodMs - jitterMs) {
            "$name: a window fits in the shortest period: $windowMs, $periodMs ± $jitterMs"
        }
        require(!synchronized || jitterMs == 0L) { "$name: scanners in step have no jitter of their own" }
    }

    /** What it scans, for a report: its inquiry and period, range, and whether both ends must sight. */
    fun description(): String {
        val period = "${periodMs / MS_PER_S} s"
        val every = if (windowMs == periodMs) "$period windows" else "a ${windowMs / MS_PER_S} s inquiry every $period"
        val jitter = if (jitterMs > 0) " ± ${jitterMs / MS_PER_S} s" else ""
        return "$every$jitter, ${"%.1f".format(Locale.ROOT, rangeM)} m${if (mutual) ", mutual" else ""}"
    }

    companion object {
        private const val MS_PER_S = 1_000L

        /**
         * Haggle's iMotes (Infocom 2005 and after): a 5 s inquiry every 120 s ± 12 s, unsynchronised, about 30 m, only
         * mutual sightings (Chaintreau et al., Cambridge TR-617; the CRAWDAD `cambridge/haggle` documentation).
         */
        val IMOTE = ScanProfile("imote", 1, 120_000, 12_000, 5_000, 30.0, synchronized = false, mutual = true)

        /** Reality Mining's and the Copenhagen study's phones: a Bluetooth inquiry every 5 minutes at about 10 m. */
        val FIVE_MINUTE = ScanProfile("scan300", 2, 300_000, 0, 10_000, 10.0, synchronized = false, mutual = false)

        /** SocioPatterns' badges: face to face, about 1.5 m, resolved in 20 s windows (Cattuto et al. 2010). */
        val TWENTY_SECOND = ScanProfile("scan20", 3, 20_000, 0, 20_000, 1.5, synchronized = true, mutual = false)

        val ALL = listOf(IMOTE, FIVE_MINUTE, TWENTY_SECOND)
    }
}

/**
 * A [ScanProfile]'s observed contact log, made from where the walkers stand every tick: the 500 ms truth seen the way a
 * published trace saw its own, so the two can be compared at all (Clauset and Eagle 2012: a network sampled at the wrong
 * rate gives "highly biased estimates" of it). Each tick [tick] moves every walker's scanner on, and [near] reports one
 * present pair and how far apart they stand; a walker in its inquiry window sights whoever is in range.
 *
 * A walker's sightings of one other walker run as a contact over its consecutive scans, from the first scan that
 * sighted to the last (one sighting is a contact of no length), and one scan that misses ends it: so one end's own gaps
 * are two periods at the least, as the Haggle documentation warns of its own ("no inter-contact time of less than two
 * intervals could have been observed"). The two ends' runs of one pair are then one contact where they lie within a
 * period and its jitter of each other, so no pair's gap is shorter than that. A contact seen at a walker's first scan
 * may have started before it, and one seen at its last scan may go on: both are censored.
 */
class VirtualScanner(
    val profile: ScanProfile,
    seed: Long,
    private val walkers: Int,
) {
    // Each walker's scan starts so far, its next one, and the scan it is inside now (or -1).
    private val starts = List(walkers) { mutableListOf<Long>() }
    private val jitter = List(walkers) { SimSeeds.stream(seed, Purpose.SCAN, profile.code, it.toLong()) }
    private val nextStart =
        LongArray(walkers) { if (profile.synchronized) 0 else jitter[it].nextLong(profile.periodMs) }
    private val scanning = IntArray(walkers) { NOT_SCANNING }

    // Who sighted whom, at which of the sighter's scans: by sighter × walkers + sighted.
    private val sighted = HashMap<Long, MutableList<Int>>()

    /** Moves every walker's scanner to [atMs]. */
    fun tick(atMs: Long) {
        for (walker in 0 until walkers) {
            val own = starts[walker]
            while (nextStart[walker] <= atMs) {
                own += nextStart[walker]
                val wobble = if (profile.jitterMs > 0) jitter[walker].nextLong(-profile.jitterMs, profile.jitterMs + 1) else 0
                nextStart[walker] += profile.periodMs + wobble
            }
            scanning[walker] = if (own.isNotEmpty() && atMs < own.last() + profile.windowMs) own.lastIndex else NOT_SCANNING
        }
    }

    /** Walkers [a] and [b], both in the field, stand [distanceM] apart this tick. */
    fun near(
        a: Int,
        b: Int,
        distanceM: Double,
    ) {
        if (distanceM > profile.rangeM) return
        sight(a, b)
        sight(b, a)
    }

    private fun sight(
        by: Int,
        of: Int,
    ) {
        val scan = scanning[by]
        if (scan == NOT_SCANNING) return
        val scans = sighted.getOrPut(by.toLong() * walkers + of) { mutableListOf() }
        if (scans.lastOrNull() != scan) scans += scan
    }

    /**
     * Each walker's runs of sightings of each other walker, keyed by sighter and sighted, in order: what a trace's own
     * files record ("A saw B"), before the two ends are matched into contacts. [ReferenceTable]s are read this way.
     */
    fun sightings(): Map<Pair<Int, Int>, List<Contact>> {
        val out = HashMap<Pair<Int, Int>, List<Contact>>()
        for ((key, scans) in sighted) {
            val by = (key / walkers).toInt()
            val own = starts[by]
            val runs = mutableListOf<Contact>()
            var first = 0
            for (i in 1..scans.size) {
                if (i < scans.size && scans[i] == scans[i - 1] + 1) continue
                val censored = scans[first] == 0 || scans[i - 1] == own.lastIndex
                runs += Contact(own[scans[first]], own[scans[i - 1]], censored)
                first = i
            }
            out[by to (key % walkers).toInt()] = runs
        }
        return out
    }

    /** The observed contacts, by pair, in order: both ends' [sightings] matched. */
    fun contacts(): Map<Link, List<Contact>> {
        val runs = HashMap<Link, MutableList<Run>>()
        for ((pair, list) in sightings()) {
            val (by, of) = pair
            runs.getOrPut(Link.of(by, of)) { mutableListOf() } += list.map { Run(it, by) }
        }
        return runs
            .mapValues { (_, list) -> merge(list) }
            .filterValues { it.isNotEmpty() }
    }

    private class Run(
        val contact: Contact,
        val by: Int,
    )

    // A pair's runs from both ends, as contacts: runs within a period and its jitter of each other are one contact.
    private fun merge(runs: List<Run>): List<Contact> {
        val joinMs = profile.periodMs + profile.jitterMs
        val merged = mutableListOf<Contact>()
        var start = 0L
        var end = 0L
        var censored = false
        val ends = HashSet<Int>()

        fun flush() {
            if (ends.isNotEmpty() && (!profile.mutual || ends.size == 2)) merged += Contact(start, end, censored)
            ends.clear()
        }
        for (run in runs.sortedBy { it.contact.startMs }) {
            if (ends.isEmpty() || run.contact.startMs > end + joinMs) {
                flush()
                start = run.contact.startMs
                end = run.contact.endMs
                censored = run.contact.censored
            } else {
                end = maxOf(end, run.contact.endMs)
                censored = censored || run.contact.censored
            }
            ends += run.by
        }
        flush()
        return merged
    }

    private companion object {
        const val NOT_SCANNING = -1
    }
}
