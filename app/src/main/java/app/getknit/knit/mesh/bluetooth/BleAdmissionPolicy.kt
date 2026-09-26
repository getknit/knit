package app.getknit.knit.mesh.bluetooth

/**
 * Whether the L2CAP responder keeps a dialer whose HELLO it just read, and what a held link scores for
 * eviction. Pure, so the table is a JVM test ([app.getknit.knit.BleAdmissionPolicyTest]).
 *
 * The larger node id dials, and the responder closes a dialer that sorts below it. That settles a cross-dial
 * between two phones that see each other, and every dialer the scan can see is still judged exactly that way.
 * A dialer this phone has **not** sighted is different: nothing on this side will ever dial it (an iPhone
 * cannot advertise the service data [BleScanner] filters on), so refusing it leaves the pair unlinked for
 * good. Such a dialer is admitted whatever the order, and a second link from it replaces the first once the
 * held one is [REPLACE_MIN_HOLD_MS] old: that is its fresh link after the old one died on its side before ours
 * noticed (ADR 2026-09.shzv).
 */
internal object BleAdmissionPolicy {
    enum class Verdict {
        /** Reply to the HELLO and register the link; no link to this node id is held. */
        Admit,

        /** Reply, register, and close the link already held to the same node id. */
        Replace,

        /** Close the socket without a reply. */
        Refuse,
    }

    /**
     * The verdict on a dialer with node id [dialer], given ours ([local]), whether the scan has it in presence
     * right now ([sighted]), and the age of the link we already hold to it ([heldLinkAgeMs], null if none).
     *
     * A dialer we see is judged by the rule every Android build has had: below us it is refused, because the
     * tie-break makes us its dialer; linked, it is refused too. The HELLO is unauthenticated, so that second
     * refusal is also what keeps a device claiming a sighted peer's id from cutting that peer's link. An
     * unsighted dialer's held link is replaced only once it is [REPLACE_MIN_HOLD_MS] old, so a device claiming
     * its id can cut it at most that often.
     *
     * [atCap] is the debug link cap (`SettingsStore.debugBleLinkCap`) being full: a dialer that would add a
     * link is refused rather than admitted and shed by eviction twenty seconds later. A replacement does not
     * grow the set, so it stands. Always false in release, where the table is exactly the one above.
     */
    fun decide(
        local: String,
        dialer: String,
        sighted: Boolean,
        heldLinkAgeMs: Long?,
        atCap: Boolean = false,
    ): Verdict =
        when {
            dialer == local -> Verdict.Refuse
            sighted && local > dialer -> Verdict.Refuse
            heldLinkAgeMs == null -> if (atCap) Verdict.Refuse else Verdict.Admit
            sighted -> Verdict.Refuse
            heldLinkAgeMs < REPLACE_MIN_HOLD_MS -> Verdict.Refuse
            else -> Verdict.Replace
        }

    /**
     * What a held link scores in [PromotionPolicy]'s weakest-first eviction: the scan's smoothed RSSI while the
     * peer is in presence. A peer that has left presence scores [ABSENT_LINK_RSSI] so it is shed first, as it
     * always was. A peer the scan has not once sighted for the whole life of the link ([neverSighted] — every
     * inbound iPhone) scores the promotion floor instead: it has no RSSI to lose, and at −127 it would be the
     * first link shed every time.
     */
    fun linkRssi(
        sightedRssi: Double?,
        neverSighted: Boolean,
    ): Double = sightedRssi ?: if (neverSighted) UNSIGHTED_LINK_RSSI else ABSENT_LINK_RSSI

    /** How old a held link must be before a second dial from an unsighted peer may replace it. */
    const val REPLACE_MIN_HOLD_MS = 30_000L

    /** [PromotionConfig]'s default floor, in dBm. */
    const val UNSIGHTED_LINK_RSSI = -90.0

    /** A sighted peer that has left presence: the weakest link, first to evict. */
    const val ABSENT_LINK_RSSI = -127.0
}
