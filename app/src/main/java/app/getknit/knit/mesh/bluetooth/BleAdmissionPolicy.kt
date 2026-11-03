package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag

/**
 * Whether the L2CAP responder keeps a dialer whose HELLO it just read, and what a held link scores for
 * eviction. Pure, so the table is a JVM test ([app.getknit.knit.BleAdmissionPolicyTest]).
 *
 * The larger node id dials, and the responder closes a dialer that sorts below it. That settles a cross-dial
 * between two phones that see each other, and every dialer the scan can see is still judged exactly that way —
 * a foreground iPhone too, once its GATT payload is read (companion change A3, ADR 2026-09.kwq2). A dialer this
 * phone has **not** sighted is different: nothing on this side will dial it (a backgrounded iPhone, whose UUID
 * only iOS can read, or any iPhone while the reader is dark), so refusing it leaves the pair unlinked for good.
 * Such a dialer is admitted whatever the order, and a second link from it replaces the first once the held one
 * is [REPLACE_MIN_HOLD_MS] old: that is its fresh link after the old one died on its side before ours noticed
 * (ADR 2026-09.shzv, which stays whole under A3).
 *
 * A peer whose HELLO carries `CAP_DOORBELL` ([DoorbellPolicy.serves] — an iPhone; Android never claims it) can stop
 * being heard while presence still holds it: backgrounded, its UUID moves to Apple's overflow area, and the presence
 * linger keeps the stale GATT-payload sighting for 90 s. For that peer "sighted" means heard within
 * [DOORBELL_HEARD_MS] (interop audit AND-3, paired with the iOS port), and its link scores the floor, not −127, out
 * of presence. Every other peer — the whole Android mesh — is judged and scored exactly as before.
 */
internal object BleAdmissionPolicy {
    enum class Verdict {
        /** Reply to the HELLO and register the link; no link to this node id is held. */
        Admit,

        /** Reply, register, and close the link already held to the same node id. */
        Replace,

        /** Close the socket without a reply. */
        Refuse,

        /**
         * The held-link row (ADR 2026-11.xp6d, [heldLinkRow]): close the socket without a reply, and resend my current
         * form over the held link as a `TAG_NEXT` (the resend is the writer's, not built yet). Its own verdict so it is
         * logged and counted apart from [Refuse].
         */
        RefuseHeld,
        ;

        /** Whether the dialer is closed without a reply. */
        val refuses: Boolean get() = this == Refuse || this == RefuseHeld
    }

    /**
     * The verdict on a dialer with node id [dialer], given ours ([local]), whether the scan has it in presence
     * right now ([sighted]), and the age of the link we already hold to it ([heldLinkAgeMs], null if none).
     *
     * A dialer we see is judged by the rule every Android build has had: below us it is refused, because the
     * tie-break makes us its dialer; linked, it is refused too. The HELLO is unauthenticated, so that second
     * refusal is also what keeps a device claiming a sighted peer's id from cutting that peer's link. An
     * unsighted dialer's held link is replaced only once it is [REPLACE_MIN_HOLD_MS] old, so a device claiming
     * its id can cut it at most that often (ADR 2026-09.shzv).
     *
     * A [doorbell] dialer (its HELLO carries `CAP_DOORBELL` and [doorbellTrusted] backs it: ADR 2026-09.kwq2's
     * iPhone) counts as sighted only while [heardAgoMs] — the age of its last sighting, null if none — is within
     * [DOORBELL_HEARD_MS] ([sightedForAdmission]). A foreground iPhone we just heard is still refused below us and over a held link,
     * because we dial it; one gone quiet (backgrounded) falls through to the unsighted rows, so it is admitted, and
     * its redial replaces a held link 30 s old, instead of being refused for the rest of the 90 s linger.
     *
     * [atCap] is the debug link cap (`SettingsStore.debugBleLinkCap`) being full: a dialer that would add a
     * link is refused rather than admitted and shed by eviction twenty seconds later. A replacement does not
     * grow the set, so it stands. Always false in release, where the table is exactly the one above.
     *
     * Link proofs (ADR 2026-11.264w, knit-next #232) add three rows, and leave the table above exactly as it was for a
     * dialer that offers no proof against a held link that is unproven or absent, the id not being [proofCapable]:
     * - **R1** ([refusesClaim]) first, ahead of every other row: a dialer whose HELLO lacks `CAP_LINK_PROOF`
     *   ([offersProof]) claiming a [proofCapable] id is refused, whatever else holds.
     * - **R3**: a dialer that offers a proof, against a held link that is not [heldProven], is admitted as a
     *   [Verdict.Replace] whatever its sighting, the held link's age or the id order. It is pending its proof: the held
     *   link is closed only once the proof verifies, and stays if it fails. A squatter's link would otherwise keep the
     *   real peer out for good, since this side never dials an id a link already claims.
     * - **R2**: a [heldProven] link is never replaced by a dialer without the bit, and by one with it only under the
     *   rows above (sighted: refused; younger than [REPLACE_MIN_HOLD_MS]: refused; else replaced).
     *
     * Blinded adverts (ADR 2026-11.xp6d) change two things, both inert for a phone that never blinds:
     * - the "sighted and out of order" row reads [outOfOrder], the order judged on advertised forms ([outOfOrder]'s
     *   function); its default is the classic `local > dialer`, which is what the forms answer for two clear phones;
     * - the held-link row ([heldLinkRow]), after R2: [heldRow] true refuses with [Verdict.RefuseHeld].
     */
    @Suppress("LongParameterList") // the table's inputs, each a row's
    fun decide(
        local: String,
        dialer: String,
        sighted: Boolean,
        heldLinkAgeMs: Long?,
        atCap: Boolean = false,
        doorbell: Boolean = false,
        heardAgoMs: Long? = null,
        heldProven: Boolean = false,
        offersProof: Boolean = false,
        proofCapable: Boolean = false,
        outOfOrder: Boolean = local > dialer,
        heldRow: Boolean = false,
    ): Verdict =
        when {
            dialer == local -> Verdict.Refuse
            else -> proofRow(heldLinkAgeMs != null, heldProven, offersProof, proofCapable)
        } ?: (if (heldRow) Verdict.RefuseHeld else null)
            ?: unprovenTable(outOfOrder, sightedForAdmission(sighted, doorbell, heardAgoMs), heldLinkAgeMs, atCap)

    /**
     * The responder's order check on advertised forms (ADR 2026-11.xp6d, [AdvertOrder.responderShouldHaveDialed]):
     * whether I, airing [mine], should have dialed the dialer whose HELLO names [dialerId], tells [told] (its `t`, the
     * form it airs) and says it dialed [addressed] (its `a`). True is [decide]'s "sighted and out of order" refusal.
     *
     * `t` and `a` are honoured only when [honoured]: on a HELLO that offers a proof (`offersProof` before the proof, and
     * `proven` after it). Every build that airs a tag proves itself, so honest peers lose nothing; without the gate, any
     * no-proof dialer writing a `t` would skip the row and squat a sighted smaller legacy id (security design pass,
     * #275). A `t` other than the id counts as a tag only when it carries [BlindTag.hasPrefix]; a prefixless one is
     * absent. A dialer with neither field, or not [honoured], is judged as `Form.clear(dialerId)`: for a clear [mine]
     * that is exactly the classic `local > dialer`.
     */
    fun outOfOrder(
        mine: AdvertOrder.Mine,
        dialerId: String,
        told: String?,
        addressed: String?,
        honoured: Boolean,
    ): Boolean {
        val t = told?.takeIf { honoured && (it == dialerId || isTag(it)) }
        val a = addressed?.takeIf { honoured }
        return AdvertOrder.responderShouldHaveDialed(mine.current, mine.previous, a, AdvertOrder.Form.fromHello(dialerId, t))
    }

    /**
     * The held-link row (ADR 2026-11.xp6d's responder row, bluetooth design finding 1): a dialer whose proven link this
     * phone already holds ([heldProven]) and whose honoured HELLO `a` ([addressed], honoured as in [outOfOrder]) names a
     * **blinded** form of mine, current or previous, missed my rotation: it is refused with no reply and my current form
     * goes to it over the held link, so a dial-then-duplicate cannot close both links.
     *
     * Gated on a blinded form, never on any form of mine: every clear dial carries `a` = the dialed id, so a row on "a
     * names mine" would fire on every redial after a link died on the dialer's side only (#203), and take away ADR
     * 2026-09.shzv's replace (minutes of `LinkSilence` on Android, for good on a `CAP_DOORBELL` link). So it never fires
     * while [mine] is clear. A [heldDoorbell] link never takes it, and nor does a [heldQuiet] one (no byte in for
     * `LinkSilence.QUIET_MS`): a link dead on the dialer's side reaches shzv's replace instead. The writer may refine
     * "quiet" (5b's brief); the row stays inert until a phone blinds.
     */
    @Suppress("LongParameterList") // the row's inputs, each a different source in the transport
    fun heldLinkRow(
        mine: AdvertOrder.Mine,
        addressed: String?,
        honoured: Boolean,
        heldProven: Boolean,
        heldQuiet: Boolean,
        heldDoorbell: Boolean,
    ): Boolean {
        val live = heldProven && !heldQuiet && !heldDoorbell
        return honoured && live && AdvertOrder.dialedForm(mine.current, mine.previous, addressed)?.blinded == true
    }

    /** Whether [form] is a canonical 26-char form whose bytes carry the tag prefix. */
    private fun isTag(form: String): Boolean = NodeId.toBytesOrNull(form)?.let(BlindTag::hasPrefix) == true

    /**
     * The proof rows of [decide], or null where they leave the verdict to [unprovenTable]: R1 refuses an unproven claim
     * on a proof-capable id; R3 replaces an unproven [held] link for a dialer offering a proof, pending it; R2 refuses a
     * dialer without the bit against a proven one.
     */
    private fun proofRow(
        held: Boolean,
        heldProven: Boolean,
        offersProof: Boolean,
        proofCapable: Boolean,
    ): Verdict? =
        when {
            refusesClaim(offersProof, proofCapable) -> Verdict.Refuse
            held && !heldProven && offersProof -> Verdict.Replace
            held && heldProven && !offersProof -> Verdict.Refuse
            else -> null
        }

    /**
     * The table every build had before link proofs ([decide]'s KDoc), [seen] already through [sightedForAdmission] and
     * the order through [outOfOrder] (the classic `local > dialer` for two clear phones).
     */
    private fun unprovenTable(
        outOfOrder: Boolean,
        seen: Boolean,
        heldLinkAgeMs: Long?,
        atCap: Boolean,
    ): Verdict =
        when {
            seen && outOfOrder -> Verdict.Refuse
            heldLinkAgeMs == null -> if (atCap) Verdict.Refuse else Verdict.Admit
            seen -> Verdict.Refuse
            heldLinkAgeMs < REPLACE_MIN_HOLD_MS -> Verdict.Refuse
            else -> Verdict.Replace
        }

    /**
     * R1 (ADR 2026-11.264w): a HELLO that offers no proof ([offersProof] false) claiming an id this phone knows can prove
     * itself ([proofCapable]: its stored profile carries `CAP_LINK_PROOF`, or a link proved it this run). The transport
     * asks this before [decide] so the refusal is counted and logged as a claim (`LinkClaims.refused`); [decide] carries
     * the same row first, so no caller can reach the old rows around it.
     */
    fun refusesClaim(
        offersProof: Boolean,
        proofCapable: Boolean,
    ): Boolean = !offersProof && proofCapable

    /**
     * Whether [decide] judges a dialer as sighted: presence holds it ([sighted]) and, for a [doorbell] dialer, it was
     * heard within [DOORBELL_HEARD_MS] ([heardAgoMs] is the age of its last sighting; null counts as not heard).
     */
    fun sightedForAdmission(
        sighted: Boolean,
        doorbell: Boolean,
        heardAgoMs: Long?,
    ): Boolean = sighted && !(doorbell && (heardAgoMs == null || heardAgoMs > DOORBELL_HEARD_MS))

    /**
     * Whether a dialer's HELLO claim of `CAP_DOORBELL` ([helloDoorbell]) is backed by this phone's own evidence, so
     * [decide] may give it the doorbell rows. The HELLO is unauthenticated (shzv): without this, a device claiming a
     * sighted Android peer's id and the bit would fall into the unsighted rows whenever that peer went 12 s unheard,
     * and could cut its link every 30 s. Both checks hold together: a link held to the id must have carried the bit in
     * its own HELLO ([heldLinkDoorbell]), and a peer presence holds ([inPresence]) must have been last sighted through
     * its GATT payload ([sightedViaGatt], the iPhone path of ADR 2026-09.kwq2), never service data, so a spoofed link
     * that once got in under an absent peer's id loses the rows once the real peer is back. A dialer neither link nor
     * presence knows has nothing to protect: the unsighted rows apply to it with or without the bit.
     */
    fun doorbellTrusted(
        helloDoorbell: Boolean,
        heldLinkDoorbell: Boolean?,
        inPresence: Boolean,
        sightedViaGatt: Boolean,
    ): Boolean = helloDoorbell && heldLinkDoorbell != false && (!inPresence || sightedViaGatt)

    /**
     * What a held link scores in [PromotionPolicy]'s weakest-first eviction: the scan's smoothed RSSI while the
     * peer is in presence. A peer that has left presence scores [ABSENT_LINK_RSSI] so it is shed first, as it
     * always was. A peer the scan has not once sighted for the whole life of the link ([neverSighted] — an
     * inbound iPhone whose GATT payload this phone has not read) scores the promotion floor instead: it has no
     * RSSI to lose, and at −127 it would be the first link shed every time (ADR 2026-09.shzv).
     *
     * A [doorbell] link (its HELLO carried `CAP_DOORBELL`) out of presence scores the floor too: a foreground
     * iPhone sighted through its GATT payload (ADR 2026-09.kwq2) leaves presence 90 s after it is backgrounded,
     * though the link still carries, and must not be the first shed at the cap. A −82 dBm candidate still beats it.
     */
    fun linkRssi(
        sightedRssi: Double?,
        neverSighted: Boolean,
        doorbell: Boolean = false,
    ): Double = sightedRssi ?: if (neverSighted || doorbell) UNSIGHTED_LINK_RSSI else ABSENT_LINK_RSSI

    /** How old a held link must be before a second dial from an unsighted peer may replace it. */
    const val REPLACE_MIN_HOLD_MS = 30_000L

    /**
     * How recently a `CAP_DOORBELL` dialer must have been heard to count as sighted for admission: 12 s, equal to
     * [PromotionConfig.dwellThresholdMs]'s default, the iOS port's matching window (interop audit AND-3).
     */
    const val DOORBELL_HEARD_MS = 12_000L

    /** [PromotionConfig]'s default floor, in dBm. */
    const val UNSIGHTED_LINK_RSSI = -90.0

    /** A sighted peer that has left presence: the weakest link, first to evict. */
    const val ABSENT_LINK_RSSI = -127.0
}
