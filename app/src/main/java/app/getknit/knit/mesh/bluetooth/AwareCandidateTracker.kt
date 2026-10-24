package app.getknit.knit.mesh.bluetooth

/**
 * Which nearby peers this phone has **positive evidence** can run Wi-Fi Aware, for the Aware plane's dormancy (GitHub
 * #44, `mesh/wifiaware/NanDormancyPolicy`): [Sets.near] is what wakes a dormant Aware plane, [Sets.heard] what keeps a
 * waking one attached. Modelled on [SideCapableTracker], and fed the same way, from every presence sighting.
 *
 * - **A candidate** is a peer whose latest **service-data** sighting (either PHY: the Coded set carries the same
 *   payload) had [BleAdvertPayload.FLAG_NO_AWARE] clear. A GATT sighting ([forget]) removes the record: an iPhone
 *   serves no such bit and CoreBluetooth has no Aware. A link peer whose HELLO claims `Protocol.CAP_DOORBELL` (only iOS
 *   does) is never one, and a link peer never scan-sighted has no record, so it is not one either. Each rule can only
 *   exclude: a false claim in the unauthenticated HELLO or advert costs the claimer its own wake, nothing more.
 * - **A link** counts as evidence only while its PHY was read back as 1M or 2M ([LinkFacts.phyNear]). A link on Coded
 *   reaches far past an NDP, and a link with no readback (no PHY handle, or a Pixel 3, which answers a Coded
 *   `setPreferredPhy` with nothing, ADR 2026-10.yvn6) counts as Coded. Either way the link is no evidence, and the
 *   peer's own sightings decide, as an unlinked peer's do. Link RSSI is never compared with an advert floor.
 * - **Near** is a candidate in [ScanPlan.near]'s sighted part (fresh raw ≥ −90, not Coded-only, ADR 2026-09.9dnk), or
 *   on a link that counts. The same snapshots as `near` and `reachable`, so a peer leaves with presence's linger.
 * - **Heard** is a candidate heard on **1M** that has not yet lapsed, or on a link that counts, or near. It lapses
 *   only once both [lingerMs] has passed since its last 1M sighting **and** [MISSES_TO_LAPSE] whole presence windows in
 *   a row that listened on 1M have run since without hearing it ([onWindow]). Two, because one can miss a peer that is
 *   there: a dark window scans 512 ms of every 5120 ms against a 1 s advert, and an all-PHY window time-shares 1M. So
 *   a peer heard in every window never drops out between windows, whatever the gap (60 s on a lit screen, minutes
 *   dark, longer while the CPU sleeps through a gap that stretches): the stay set lingers at least the current gap plus
 *   one window, two of each in practice. A Coded-only window cannot hear a 1M advert and a window a dial cut short may
 *   not have heard it, so neither counts as a miss. A scan that stops running holds the set rather than emptying it:
 *   Aware stays awake, the safe side.
 *
 * A link's end restamps a peer it counted for ([onLinkEnded]): it was provably here until then. [clear] at `stop()`
 * and adapter off. Pure; JVM-tested ([app.getknit.knit.AwareCandidateTrackerTest]).
 */
internal class AwareCandidateTracker(
    private val lingerMs: Long = PresenceConfig().reachableLingerMs,
) {
    /** What the transport knows about one live link, for the two sets. */
    data class LinkFacts(
        /** The link's PHY was read back as 1M or 2M: the link itself is evidence its peer is near. */
        val phyNear: Boolean,
        /** Its HELLO claimed `Protocol.CAP_DOORBELL` (an iPhone): never a candidate. */
        val doorbell: Boolean,
    )

    /** The two published sets; [near] ⊆ [heard]. */
    data class Sets(
        val near: Set<String>,
        val heard: Set<String>,
    )

    private class Entry(
        var candidate: Boolean,
        // Elapsed instant of its last 1M service-data sighting (or a counted link's end), null if never heard on 1M.
        var heard1mAt: Long?,
        // Whole 1M-listening windows that started after [heard1mAt] and did not hear it.
        var missed: Int = 0,
    )

    private val entries = HashMap<String, Entry>()

    /** A service-data sighting of [nodeId], on 1M ([oneM]) or Coded, whose flags said [noAware]. */
    @Synchronized
    fun note(
        nodeId: String,
        noAware: Boolean,
        oneM: Boolean,
        now: Long,
    ) {
        val e = entries.getOrPut(nodeId) { Entry(candidate = !noAware, heard1mAt = null) }
        e.candidate = !noAware
        if (oneM) {
            e.heard1mAt = now
            e.missed = 0
        }
    }

    /** A sighting that is not service data (a GATT payload): no evidence the bit means anything, so no record. */
    @Synchronized
    fun forget(nodeId: String) {
        entries.remove(nodeId)
    }

    /**
     * A presence window that began at [startedAt] ended. [countsAsMiss] when it listened on 1M ([ScanPhys.ONE_M] or
     * [ScanPhys.ALL]), its scan stayed registered throughout, and it ran its full length: then every peer it did not
     * hear has missed one window.
     */
    @Synchronized
    fun onWindow(
        startedAt: Long,
        countsAsMiss: Boolean,
    ) {
        if (!countsAsMiss) return
        entries.values.forEach { e ->
            val at = e.heard1mAt ?: return@forEach
            if (at < startedAt) e.missed++
        }
    }

    /** [nodeId]'s link ended; if it [counted] (a link on 1M/2M), the peer lingers in [Sets.heard] from now. */
    @Synchronized
    fun onLinkEnded(
        nodeId: String,
        counted: Boolean,
        now: Long,
    ) {
        if (!counted) return
        val e = entries[nodeId] ?: return
        e.heard1mAt = now
        e.missed = 0
    }

    /**
     * The two sets, from [snaps] (the same `presence.snapshots(now)` call `near` and `reachable` come from) and the live
     * [links]. Prunes a record no link, no presence and no unlapsed 1M sighting holds.
     */
    @Synchronized
    fun sets(
        now: Long,
        snaps: List<BlePresenceTracker.Snapshot>,
        links: Map<String, LinkFacts>,
    ): Sets {
        val present = snaps.mapTo(HashSet()) { it.nodeId }
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (id, e) = it.next()
            if (id !in links && id !in present && !heardLive(e, now)) it.remove()
        }
        if (entries.isEmpty()) return EMPTY
        val sightedNear = ScanPlan.near(snaps, emptyList()).mapTo(HashSet()) { it.nodeId }
        val near = HashSet<String>()
        val heard = HashSet<String>()
        entries.forEach { (id, e) ->
            val link = links[id]
            if (!e.candidate || link?.doorbell == true) return@forEach
            val byLink = link?.phyNear == true
            if (byLink || id in sightedNear) near += id
            if (byLink || id in near || heardLive(e, now)) heard += id
        }
        return Sets(near, heard)
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun heardLive(
        e: Entry,
        now: Long,
    ): Boolean {
        val at = e.heard1mAt ?: return false
        return e.missed < MISSES_TO_LAPSE || now - at < lingerMs
    }

    companion object {
        val EMPTY = Sets(emptySet(), emptySet())

        /** Whole 1M windows in a row that must miss a heard peer before it lapses (with [lingerMs] passed too). */
        const val MISSES_TO_LAPSE = 2
    }
}
