package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.ABSENT_LINK_RSSI
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.DOORBELL_HEARD_MS
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.REPLACE_MIN_HOLD_MS
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.Verdict
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BleAdmissionPolicy] — who the L2CAP responder keeps (ADR 2026-09.shzv). */
class BleAdmissionPolicyTest {
    private val low = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val high = "zzzzzzzzzzzzzzzzzzzzzzzzzz"

    private fun verdict(
        local: String,
        dialer: String,
        sighted: Boolean,
        heldLinkAgeMs: Long? = null,
        atCap: Boolean = false,
    ) = BleAdmissionPolicy.decide(local, dialer, sighted, heldLinkAgeMs, atCap)

    // A dialer whose HELLO carries CAP_DOORBELL (an iPhone), in presence and last heard [heardAgoMs] ago (AND-3).
    private fun doorbellVerdict(
        local: String,
        dialer: String,
        heardAgoMs: Long?,
        heldLinkAgeMs: Long? = null,
        atCap: Boolean = false,
        sighted: Boolean = true,
    ) = BleAdmissionPolicy.decide(local, dialer, sighted, heldLinkAgeMs, atCap, doorbell = true, heardAgoMs = heardAgoMs)

    private val stale = DOORBELL_HEARD_MS + 1

    @Test
    fun aLargerDialerIsAdmittedSeenOrNot() {
        assertEquals(Verdict.Admit, verdict(local = low, dialer = high, sighted = true))
        assertEquals(Verdict.Admit, verdict(local = low, dialer = high, sighted = false))
    }

    @Test
    fun aSmallerDialerWeCanSeeIsRefusedBecauseWeDialIt() {
        // The cross-dial breaker every Android build has had: our own dial to it is the one to keep.
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = true))
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = true, heldLinkAgeMs = 0L))
    }

    @Test
    fun aSmallerDialerWeNeverSawIsAdmitted() {
        // An iPhone advertises no service data, so this side never dials it; refusing it would leave the pair unlinked.
        assertEquals(Verdict.Admit, verdict(local = high, dialer = low, sighted = false))
    }

    @Test
    fun aSecondLinkFromADialerWeCanSeeIsRefusedHoweverOldTheHeldOne() {
        // Unchanged from every Android build, and what keeps a claimed id from cutting a sighted peer's link.
        val old = REPLACE_MIN_HOLD_MS * 100
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = high, sighted = true, heldLinkAgeMs = old))
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = true, heldLinkAgeMs = old))
    }

    @Test
    fun aSecondLinkFromADialerWeNeverSawReplacesAHeldLinkOldEnough() {
        // Its old link died on its side first; it has no other way back.
        assertEquals(Verdict.Replace, verdict(local = high, dialer = low, sighted = false, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
        assertEquals(Verdict.Replace, verdict(local = low, dialer = high, sighted = false, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
    }

    // Link proofs (ADR 2026-11.264w): [offersProof] is the dialer's HELLO carrying CAP_LINK_PROOF, [heldProven] the held
    // link's verdict, [proofCapable] whether this phone knows the id can prove itself (R1).
    private fun proofVerdict(
        local: String,
        dialer: String,
        sighted: Boolean,
        heldLinkAgeMs: Long? = null,
        heldProven: Boolean = false,
        offersProof: Boolean = false,
        proofCapable: Boolean = false,
        atCap: Boolean = false,
    ) = BleAdmissionPolicy.decide(
        local,
        dialer,
        sighted,
        heldLinkAgeMs,
        atCap,
        heldProven = heldProven,
        offersProof = offersProof,
        proofCapable = proofCapable,
    )

    @Test
    fun anUnprovenDialerClaimingAProofCapableIdIsRefusedAheadOfEveryRow() {
        // R1: refused even where the old rows admit (larger, unsighted, nothing held) or would refuse anyway (below us
        // and sighted, already linked), so the claim is the refusal that counts.
        val old = REPLACE_MIN_HOLD_MS * 10
        assertEquals(Verdict.Refuse, proofVerdict(local = low, dialer = high, sighted = false, proofCapable = true))
        assertEquals(Verdict.Refuse, proofVerdict(local = high, dialer = low, sighted = false, heldLinkAgeMs = old, proofCapable = true))
        assertEquals(Verdict.Refuse, proofVerdict(local = high, dialer = low, sighted = true, proofCapable = true))
        assertTrue(BleAdmissionPolicy.refusesClaim(offersProof = false, proofCapable = true))
        // A dialer offering a proof is never an R1 refusal: its proof decides.
        assertFalse(BleAdmissionPolicy.refusesClaim(offersProof = true, proofCapable = true))
        assertFalse(BleAdmissionPolicy.refusesClaim(offersProof = false, proofCapable = false))
        assertEquals(Verdict.Admit, proofVerdict(local = low, dialer = high, sighted = true, offersProof = true, proofCapable = true))
    }

    @Test
    fun aProvenHeldLinkIsNeverReplacedByAnUnprovenDialer() {
        // R2, even with R1 not asked (an id the LRU forgot): unsighted and old enough is the old table's Replace.
        val old = REPLACE_MIN_HOLD_MS * 10
        assertEquals(Verdict.Refuse, proofVerdict(local = high, dialer = low, sighted = false, heldLinkAgeMs = old, heldProven = true))
        assertEquals(Verdict.Refuse, proofVerdict(local = low, dialer = high, sighted = false, heldLinkAgeMs = old, heldProven = true))
    }

    @Test
    fun aDialerOfferingAProofReplacesAnUnprovenHeldLinkWhateverItsSightingAgeOrOrder() {
        // R3: the old rows refuse each of these (sighted; younger than the floor; below us in the tie-break). The held
        // link is a squatter's or a legacy claim, and only a verified proof closes it.
        for (sighted in listOf(true, false)) {
            for (age in listOf(0L, REPLACE_MIN_HOLD_MS - 1, REPLACE_MIN_HOLD_MS * 10)) {
                for ((local, dialer) in listOf(high to low, low to high)) {
                    assertEquals(
                        "sighted=$sighted age=$age local=$local",
                        Verdict.Replace,
                        proofVerdict(local, dialer, sighted, heldLinkAgeMs = age, offersProof = true, atCap = true),
                    )
                }
            }
        }
    }

    @Test
    fun aDialerOfferingAProofAgainstAProvenHeldLinkFollowsTheOldRows() {
        // R2: a proven link is replaced only by one that proves the same id, under the old rows.
        val old = REPLACE_MIN_HOLD_MS
        val young = REPLACE_MIN_HOLD_MS - 1

        fun v(
            local: String,
            dialer: String,
            sighted: Boolean,
            age: Long,
        ) = proofVerdict(local, dialer, sighted, heldLinkAgeMs = age, heldProven = true, offersProof = true, proofCapable = true)
        assertEquals(Verdict.Refuse, v(high, low, sighted = true, age = old * 10)) // sighted, below us: we dial it
        assertEquals(Verdict.Refuse, v(low, high, sighted = true, age = old * 10)) // sighted and linked
        assertEquals(Verdict.Refuse, v(high, low, sighted = false, age = young)) // too young
        assertEquals(Verdict.Replace, v(high, low, sighted = false, age = old))
        assertEquals(Verdict.Replace, v(low, high, sighted = false, age = old))
    }

    @Test
    fun aHeldLinkYoungerThanTheFloorIsNeverReplaced() {
        // Bounds how often a device claiming an unsighted peer's id can cut that peer's link.
        val young = REPLACE_MIN_HOLD_MS - 1
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = false, heldLinkAgeMs = young))
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = high, sighted = false, heldLinkAgeMs = 0L))
    }

    @Test
    fun aFullDebugCapRefusesADialerThatWouldAddALink() {
        // Refused at the door rather than admitted and shed by eviction twenty seconds later.
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = high, sighted = true, atCap = true))
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = false, atCap = true))
    }

    @Test
    fun aFullDebugCapStillLetsAReplacementThroughAndRefusesNothingNew() {
        // A replacement doesn't grow the set; every refusal is the one the uncapped table gives.
        val old = REPLACE_MIN_HOLD_MS
        assertEquals(Verdict.Replace, verdict(local = high, dialer = low, sighted = false, heldLinkAgeMs = old, atCap = true))
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = true, atCap = true))
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = high, sighted = false, heldLinkAgeMs = 0L, atCap = true))
    }

    @Test
    fun ourOwnNodeIdIsNeverAdmitted() {
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = low, sighted = false))
        assertEquals(Verdict.Refuse, verdict(local = low, dialer = low, sighted = true, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
    }

    @Test
    fun aSightedLinkScoresItsRssiAndAnAbsentOneTheOldFloor() {
        assertEquals(-62.5, BleAdmissionPolicy.linkRssi(-62.5, neverSighted = false), 0.0)
        assertEquals(-62.5, BleAdmissionPolicy.linkRssi(-62.5, neverSighted = true), 0.0)
        // A peer the scan saw during the link and has since lost is shed first, exactly as before.
        assertEquals(-127.0, BleAdmissionPolicy.linkRssi(null, neverSighted = false), 0.0)
    }

    @Test
    fun aNeverSightedLinkScoresThePromotionFloor() {
        assertEquals(PromotionConfig().rssiFloorDbm.toDouble(), BleAdmissionPolicy.linkRssi(null, neverSighted = true), 0.0)
    }

    @Test
    fun aDoorbellDialerHeardWithinTheWindowBelowUsIsRefusedBecauseWeDialIt() {
        // A foreground iPhone sighted through its GATT payload: judged as any sighted dialer (kwq2).
        assertEquals(Verdict.Refuse, doorbellVerdict(local = high, dialer = low, heardAgoMs = 0L))
        assertEquals(Verdict.Refuse, doorbellVerdict(local = high, dialer = low, heardAgoMs = DOORBELL_HEARD_MS))
        assertEquals(DOORBELL_HEARD_MS, PromotionConfig().dwellThresholdMs)
    }

    @Test
    fun aDoorbellDialerUnheardPastTheWindowBelowUsIsAdmitted() {
        // Backgrounded: presence still lingers on its stale sighting, but nothing hears it, so nothing dials it.
        assertEquals(Verdict.Admit, doorbellVerdict(local = high, dialer = low, heardAgoMs = stale))
        assertEquals(Verdict.Admit, doorbellVerdict(local = high, dialer = low, heardAgoMs = 89_000L))
        assertEquals(Verdict.Admit, doorbellVerdict(local = high, dialer = low, heardAgoMs = null))
    }

    @Test
    fun aDoorbellDialerAboveUsIsAdmittedHeardOrNot() {
        assertEquals(Verdict.Admit, doorbellVerdict(local = low, dialer = high, heardAgoMs = 0L))
        assertEquals(Verdict.Admit, doorbellVerdict(local = low, dialer = high, heardAgoMs = stale))
    }

    @Test
    fun aHeldLinkToADoorbellDialerHeardRecentlyIsNeverReplaced() {
        val old = REPLACE_MIN_HOLD_MS * 100
        assertEquals(Verdict.Refuse, doorbellVerdict(local = high, dialer = low, heardAgoMs = 0L, heldLinkAgeMs = old))
        assertEquals(Verdict.Refuse, doorbellVerdict(local = low, dialer = high, heardAgoMs = DOORBELL_HEARD_MS, heldLinkAgeMs = old))
    }

    @Test
    fun aHeldLinkToAStaleDoorbellDialerIsReplacedOnlyPastTheHold() {
        // Its old link died on its side when it was backgrounded; it is judged as unsighted (shzv).
        assertEquals(Verdict.Replace, doorbellVerdict(local = high, dialer = low, heardAgoMs = stale, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
        assertEquals(Verdict.Replace, doorbellVerdict(local = low, dialer = high, heardAgoMs = stale, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
        val young = REPLACE_MIN_HOLD_MS - 1
        assertEquals(Verdict.Refuse, doorbellVerdict(local = high, dialer = low, heardAgoMs = stale, heldLinkAgeMs = young))
        assertEquals(Verdict.Refuse, doorbellVerdict(local = low, dialer = high, heardAgoMs = stale, heldLinkAgeMs = 0L))
    }

    @Test
    fun aFullDebugCapRefusesANewDoorbellDialerAndLetsItsReplacementThrough() {
        assertEquals(Verdict.Refuse, doorbellVerdict(local = high, dialer = low, heardAgoMs = stale, atCap = true))
        assertEquals(Verdict.Refuse, doorbellVerdict(local = low, dialer = high, heardAgoMs = 0L, atCap = true))
        assertEquals(
            Verdict.Replace,
            doorbellVerdict(local = high, dialer = low, heardAgoMs = stale, heldLinkAgeMs = REPLACE_MIN_HOLD_MS, atCap = true),
        )
    }

    @Test
    fun anUnsightedDoorbellDialerIsJudgedAsAnyUnsightedOne() {
        // The heard age only ever narrows "sighted"; it never makes an unsighted dialer sighted.
        assertEquals(Verdict.Admit, doorbellVerdict(local = high, dialer = low, heardAgoMs = 0L, sighted = false))
        assertFalse(BleAdmissionPolicy.sightedForAdmission(sighted = false, doorbell = true, heardAgoMs = 0L))
    }

    @Test
    fun theHeardAgeIsIgnoredForEveryOtherDialer() {
        // The Android mesh does not move: a dialer without the bit is sighted however long since it was heard.
        assertTrue(BleAdmissionPolicy.sightedForAdmission(sighted = true, doorbell = false, heardAgoMs = 89_000L))
        assertTrue(BleAdmissionPolicy.sightedForAdmission(sighted = true, doorbell = false, heardAgoMs = null))
        val v = BleAdmissionPolicy.decide(high, low, sighted = true, heldLinkAgeMs = null, doorbell = false, heardAgoMs = 89_000L)
        assertEquals(Verdict.Refuse, v)
    }

    @Test
    fun aDoorbellLinkScoresItsRssiInPresenceAndTheFloorOutOfIt() {
        assertEquals(-62.5, BleAdmissionPolicy.linkRssi(-62.5, neverSighted = false, doorbell = true), 0.0)
        assertEquals(
            PromotionConfig().rssiFloorDbm.toDouble(),
            BleAdmissionPolicy.linkRssi(null, neverSighted = false, doorbell = true),
            0.0,
        )
        assertEquals(
            PromotionConfig().rssiFloorDbm.toDouble(),
            BleAdmissionPolicy.linkRssi(null, neverSighted = true, doorbell = true),
            0.0,
        )
        // Every other link keeps today's rule.
        assertEquals(ABSENT_LINK_RSSI, BleAdmissionPolicy.linkRssi(null, neverSighted = false, doorbell = false), 0.0)
        assertEquals(
            PromotionConfig().rssiFloorDbm.toDouble(),
            BleAdmissionPolicy.linkRssi(null, neverSighted = true, doorbell = false),
            0.0,
        )
    }

    @Test
    fun aDoorbellClaimNeedsThisPhonesOwnEvidence() {
        // The HELLO is unauthenticated: a held link must have carried the bit, and presence must have it through a GATT payload.
        assertTrue(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = true, inPresence = true, sightedViaGatt = true))
        assertTrue(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = true, inPresence = false, sightedViaGatt = false))
        // A link that carried the bit doesn't outvote presence: the real peer, back by service data, takes its id back.
        assertFalse(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = true, inPresence = true, sightedViaGatt = false))
        assertFalse(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = false, inPresence = true, sightedViaGatt = true))
        assertTrue(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = null, inPresence = true, sightedViaGatt = true))
        assertFalse(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = null, inPresence = true, sightedViaGatt = false))
        // Neither link nor presence knows it: nothing to protect, the unsighted rows apply either way.
        assertTrue(BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = null, inPresence = false, sightedViaGatt = false))
        // No claim, no doorbell rows, whatever the evidence.
        assertFalse(BleAdmissionPolicy.doorbellTrusted(false, heldLinkDoorbell = true, inPresence = true, sightedViaGatt = true))
    }

    @Test
    fun aDialerClaimingTheDoorbellUnderASightedAndroidPeersIdIsRefused() {
        // The peer's link carried no bit, or presence holds it by service data: a claimed id can't cut it, however
        // long since it was heard (a screen-off phone's scan gaps run past 12 s).
        val old = REPLACE_MIN_HOLD_MS * 100
        val heldWithoutBit = BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = false, inPresence = true, sightedViaGatt = false)
        assertEquals(Verdict.Refuse, BleAdmissionPolicy.decide(high, low, true, old, doorbell = heldWithoutBit, heardAgoMs = 89_000L))
        assertEquals(Verdict.Refuse, BleAdmissionPolicy.decide(low, high, true, old, doorbell = heldWithoutBit, heardAgoMs = 89_000L))
        val serviceData = BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = null, inPresence = true, sightedViaGatt = false)
        assertEquals(Verdict.Refuse, BleAdmissionPolicy.decide(high, low, true, null, doorbell = serviceData, heardAgoMs = 89_000L))
        // A genuine iPhone, sighted through its payload or linked with the bit, keeps its doorbell rows.
        val gatt = BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = null, inPresence = true, sightedViaGatt = true)
        assertEquals(Verdict.Admit, BleAdmissionPolicy.decide(high, low, true, null, doorbell = gatt, heardAgoMs = 89_000L))
        val linked = BleAdmissionPolicy.doorbellTrusted(true, heldLinkDoorbell = true, inPresence = true, sightedViaGatt = true)
        assertEquals(Verdict.Replace, BleAdmissionPolicy.decide(high, low, true, old, doorbell = linked, heardAgoMs = 89_000L))
    }

    @Test
    fun aLinkedDialerHeardOnlyWeaklyOn1mReplacesItsHeldLinkPastTheHold() {
        // The 2026-10-20 amendment moves no code here, only who counts as Coded alone: a peer whose weak 1M hits no
        // longer reset its clock is unsighted while Coded runs (yvn6), so its redial replaces a held link 30 s old.
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        for (at in 0L..9_000L step 3_000L) {
            t.onSighting(BlePresenceTracker.Sighting(low, -95, 1, 0L, 100, 0), at + 1_000)
            t.onSighting(BlePresenceTracker.Sighting(low, -100, 1, 0L, 100, 0, coded = true), at + 2_000)
        }
        val sighted = CodedPhyPolicy.sightedForAdmission(t.snapshots(11_000).single(), codedOn = true)
        assertFalse(sighted)
        assertEquals(Verdict.Replace, verdict(local = high, dialer = low, sighted = sighted, heldLinkAgeMs = REPLACE_MIN_HOLD_MS))
        assertEquals(Verdict.Refuse, verdict(local = high, dialer = low, sighted = sighted, heldLinkAgeMs = REPLACE_MIN_HOLD_MS - 1))
    }
}
