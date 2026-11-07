package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.ConnectFailReason

/**
 * What a failed dial tells the device caches about the address it went to (#309). After a peer's Bluetooth toggle it airs
 * from a new address, and for a blinded peer under a new tag; a claim that resolved its old tag keeps the old address
 * as its dial target for presence's linger, and nothing ties the new tag to it, so every dial there ran its watchdog
 * while the paused scan kept the new advert unseen. A tag dial over 1M that timed out forgets that address instead. Pure and
 * free of Android, so the transport's `failConnect` and the JVM tests run the same rule.
 */
internal object DialTargets {
    /**
     * Forgets what a dial [record] that ended for [reason] showed dead, under the record's [DialPlan.InFlight.peerKey] only:
     * a tag dial over 1M that timed out ([DialPlan.forgetsDialedAddress]) loses its [oneM] entry at the dialed address and
     * the [coded] entry heard under that same tag, since a toggle restarts both sets on one new tag and [DialPlan.target]
     * would otherwise fall back to the dead Coded address with Coded's longer watchdog. Nothing else forgets: a clear form
     * is the node id, the same across a toggle, so its timeout says nothing of a move and #148's Coded fallback stands
     * (every peer of a release build is clear); and a Coded dial's timeout is range, its ordinary cause (yvn6), so its
     * address stays for the next one. The dial went over 1M when [oneM] holds the dialed address. Each removal compares
     * the entry it removes, so a sighting that replaced it during the dial stays. [address] and [form] read an entry.
     * Returns whether anything went; a null [record] (a link's end counted as a failed dial) forgets nothing.
     */
    @Suppress("LongParameterList") // the record, its reason, the two caches and how to read an entry
    fun <H : Any> forgetDialed(
        reason: ConnectFailReason,
        record: DialPlan.InFlight?,
        oneM: MutableMap<String, H>,
        coded: MutableMap<String, H>,
        address: (H) -> String,
        form: (H) -> AdvertOrder.Form,
    ): Boolean {
        val dialed = record?.address ?: return false
        if (!DialPlan.forgetsDialedAddress(reason)) return false
        val key = record.peerKey
        val dead = oneM[key]?.takeIf { address(it) == dialed }?.let(form)?.takeIf { it.blinded } ?: return false
        var forgot = false
        oneM.computeIfPresent(key) { _, held -> if (address(held) == dialed) null.also { forgot = true } else held }
        coded.computeIfPresent(key) { _, held -> if (form(held) == dead) null.also { forgot = true } else held }
        return forgot
    }
}
