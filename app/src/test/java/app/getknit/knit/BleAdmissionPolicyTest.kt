package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.ABSENT_LINK_RSSI
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.DOORBELL_HEARD_MS
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.REPLACE_MIN_HOLD_MS
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.Verdict
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.crypto.BlindTag
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

    // --- Blinded adverts (ADR 2026-11.xp6d): the order on forms, and the held-link row ---

    private val reader = AdvertOrder.Mine.clear(high)
    private val contactTag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 7 }, slot = 1, restart = 0))
    private val myTag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 9 }, slot = 1, restart = 0))
    private val myPreviousTag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 9 }, slot = 0, restart = 0))

    // A garbage `t`: a canonical form other than the dialer's id, without the tag prefix.
    private val garbage = "bbbbbbbbbbbbbbbbbbbbbbbbba"

    private fun outOfOrder(
        mine: AdvertOrder.Mine = reader,
        dialer: String = low,
        told: String? = null,
        addressed: String? = null,
        honoured: Boolean = true,
    ) = BleAdmissionPolicy.outOfOrder(mine, dialer, told, addressed, honoured)

    @Test
    fun aReaderAdmitsABlindingContactsProvenDialThoughItsRealIdSortsBelowAndItIsSighted() {
        val order = outOfOrder(told = contactTag, addressed = high)
        assertFalse("a clear phone never dials a blinded advert, so it should not have dialed this one", order)
        val v = BleAdmissionPolicy.decide(high, low, sighted = true, heldLinkAgeMs = null, offersProof = true, outOfOrder = order)
        assertEquals(Verdict.Admit, v)
    }

    @Test
    fun aDialerWithNoTOrAIsJudgedExactlyAsToday() {
        assertTrue(outOfOrder())
        assertFalse(outOfOrder(dialer = high, mine = AdvertOrder.Mine.clear(low)))
        // A clear dial carries `a` = the id it dialed (mine): the same answer.
        assertTrue(outOfOrder(addressed = high))
        // An `a` naming no form of mine is not honoured.
        assertTrue(outOfOrder(addressed = low))
        assertEquals(
            Verdict.Refuse,
            BleAdmissionPolicy.decide(high, low, sighted = true, heldLinkAgeMs = null, outOfOrder = outOfOrder()),
        )
    }

    @Test
    fun aNoProofDialerClaimingASightedSmallerIdWithATagIsStillRefused() {
        // The security pass's Blocking case (#275): `t` and `a` count only on a HELLO that offers a proof.
        val order = outOfOrder(told = contactTag, addressed = high, honoured = false)
        assertTrue(order)
        val v = BleAdmissionPolicy.decide(high, low, sighted = true, heldLinkAgeMs = null, offersProof = false, outOfOrder = order)
        assertEquals(Verdict.Refuse, v)
    }

    @Test
    fun theHelloFieldsAreHonouredOnTheFirstPassOnlyWithAProofOfferedAndOnTheLateOnlyWhenProven() {
        // The security design pass's Blocking gate, pinned here and not in which boolean each transport call hands over.
        data class Row(
            val offersProof: Boolean,
            val proven: Boolean,
            val late: Boolean,
            val honoured: Boolean,
        )
        listOf(
            Row(offersProof = false, proven = false, late = false, honoured = false),
            Row(offersProof = true, proven = false, late = false, honoured = true),
            Row(offersProof = true, proven = false, late = true, honoured = false),
            Row(offersProof = true, proven = true, late = true, honoured = true),
            Row(offersProof = false, proven = false, late = true, honoured = false),
            // A late pass is never reached proven without the offer; read on `proven` alone, it stays honest.
            Row(offersProof = false, proven = true, late = true, honoured = true),
            Row(offersProof = false, proven = true, late = false, honoured = false),
            Row(offersProof = true, proven = true, late = false, honoured = true),
        ).forEach { r ->
            assertEquals(r.toString(), r.honoured, BleAdmissionPolicy.honours(r.offersProof, r.proven, r.late))
        }
        // And what it guards: a no-proof dialer's tag `t` never skips "sighted and out of order".
        assertTrue(outOfOrder(told = contactTag, addressed = high, honoured = BleAdmissionPolicy.honours(false, false, late = false)))
    }

    @Test
    fun aGarbageToldIsJudgedClear() {
        assertTrue("a prefixless t is absent", outOfOrder(told = garbage))
        assertTrue("a t equal to the id is clear", outOfOrder(told = low))
    }

    @Test
    fun theHeldLinkRowNeverFiresWhileMineIsClear() {
        // Every clear dial carries `a` = my id: a row on "a names mine" would take shzv's replace away (design finding 1).
        assertFalse(heldRow(mine = reader, addressed = high))
        assertEquals(
            Verdict.Replace,
            BleAdmissionPolicy.decide(
                high,
                low,
                sighted = false,
                heldLinkAgeMs = REPLACE_MIN_HOLD_MS,
                heldProven = true,
                offersProof = true,
                heldRow = heldRow(mine = reader, addressed = high),
            ),
        )
    }

    @Test
    fun theHeldLinkRowRefusesADialerWhoseAddressedFormIsABlindedOneOfMine() {
        val blinding = AdvertOrder.Mine(AdvertOrder.Form(myTag, blinded = true), AdvertOrder.Form(myPreviousTag, blinded = true))
        assertTrue(heldRow(mine = blinding, addressed = myTag))
        assertTrue("my previous tag too", heldRow(mine = blinding, addressed = myPreviousTag))
        assertFalse("a form not mine", heldRow(mine = blinding, addressed = contactTag))
        assertFalse("not honoured", heldRow(mine = blinding, addressed = myTag, honoured = false))
        assertFalse("an unproven held link", heldRow(mine = blinding, addressed = myTag, heldProven = false))
        assertFalse("a quiet held link reaches shzv's replace", heldRow(mine = blinding, addressed = myTag, heldQuiet = true))
        assertFalse("a doorbell link never takes it", heldRow(mine = blinding, addressed = myTag, heldDoorbell = true))
        // Clear now, blinded before: a dial made against my previous tag still takes the row.
        val flipped = AdvertOrder.Mine(AdvertOrder.Form.clear(high), AdvertOrder.Form(myPreviousTag, blinded = true))
        assertTrue(heldRow(mine = flipped, addressed = myPreviousTag))
        assertFalse(heldRow(mine = flipped, addressed = high))

        val v =
            BleAdmissionPolicy.decide(
                high,
                low,
                sighted = true,
                heldLinkAgeMs = REPLACE_MIN_HOLD_MS,
                heldProven = true,
                offersProof = true,
                heldRow = true,
            )
        assertEquals(Verdict.RefuseHeld, v)
        assertTrue(v.refuses)
        assertFalse(Verdict.Admit.refuses)
        assertFalse(Verdict.Replace.refuses)
    }

    @Test
    fun theProofRowsStillComeBeforeTheHeldLinkRow() {
        // R1: an unproven claim on a proof-capable id is refused as a claim, never as the held-link row.
        assertEquals(Verdict.Refuse, BleAdmissionPolicy.decide(high, low, false, 40_000L, proofCapable = true, heldRow = true))
        // R3: a proof offered against an unproven held link replaces it.
        assertEquals(Verdict.Replace, BleAdmissionPolicy.decide(high, low, true, 1L, offersProof = true, heldRow = true))
    }

    @Suppress("LongParameterList") // the row's inputs, named per case
    private fun heldRow(
        mine: AdvertOrder.Mine,
        addressed: String?,
        honoured: Boolean = true,
        heldProven: Boolean = true,
        heldQuiet: Boolean = false,
        heldDoorbell: Boolean = false,
    ) = BleAdmissionPolicy.heldLinkRow(mine, addressed, honoured, heldProven, heldQuiet, heldDoorbell)

    @Test
    fun whileBlindingADialerThatReadsNoTagsIsRefusedWithNoReplyAheadOfEveryRow() {
        // Step 5b's W6: the blinding-only row. `refuses` is the transport's "close with no reply": no HELLO is written.
        val v = BleAdmissionPolicy.decide(high, low, sighted = false, heldLinkAgeMs = null, blinding = true, readsTags = false)
        assertEquals(Verdict.RefuseLegacy, v)
        assertTrue(v.refuses)
        // Ahead of the proof rows and the held-link row: a legacy dialer offering a proof against an unproven link.
        assertEquals(
            Verdict.RefuseLegacy,
            BleAdmissionPolicy.decide(high, low, true, 1L, offersProof = true, heldRow = true, blinding = true, readsTags = false),
        )
        // Never while this phone airs its id, and never for a tag-aware dialer.
        assertEquals(Verdict.Admit, BleAdmissionPolicy.decide(high, low, false, null, blinding = false, readsTags = false))
        assertEquals(Verdict.Admit, BleAdmissionPolicy.decide(high, low, false, null, blinding = true, readsTags = true))
    }

    @Test
    fun aForgedDialOnAProvenHeldLinkIsRefusedWhileTheLinkAnswersAndOnceQuietReachesOnlyAReplaceThatAwaitsItsProof() {
        // Step 5b's W5, the security pass: a forged dial claiming X, X's link proven and held. While the held link is not
        // quiet the row refuses (RefuseHeld) and the probe is poked; the probe going unanswered makes the link quiet, and
        // the forged dial then reaches shzv's replace, which the transport applies only after the dialer's proof verifies
        // (R3/R2: a proven held link is displaced only by a verified proof). What a failed proof then does is not this
        // policy's. `LinkHandshake.answer` returns `ProofFailed`, pinned by
        //   LinkHandshakeTest.aDialersMissingWrongOrMisplacedProofFailsTheResponder,
        // and the responder returns before its late pass, leaving the held link: no JVM test pins that last step (the
        // transport's `Answered.ProofFailed` branch). Should the late pass ever run on an unproven dialer,
        //   aProvenHeldLinkIsNeverReplacedByAnUnprovenDialer
        // pins its refusal.
        val mine = AdvertOrder.Mine(AdvertOrder.Form(myTag, blinded = true))
        val live = heldRow(mine = mine, addressed = myTag, heldQuiet = false)
        assertTrue(live)
        assertEquals(
            Verdict.RefuseHeld,
            BleAdmissionPolicy.decide(high, low, false, REPLACE_MIN_HOLD_MS, heldProven = true, offersProof = true, heldRow = live),
        )
        val quiet = heldRow(mine = mine, addressed = myTag, heldQuiet = true)
        assertFalse(quiet)
        val first =
            BleAdmissionPolicy.decide(high, low, false, REPLACE_MIN_HOLD_MS, heldProven = true, offersProof = true, heldRow = quiet)
        assertEquals("a replace pending the proof, never a close of the held link by itself", Verdict.Replace, first)
        // A dialer that offers no proof never displaces a proven held link (R2), whatever its probe did.
        assertEquals(
            Verdict.Refuse,
            BleAdmissionPolicy.decide(high, low, false, REPLACE_MIN_HOLD_MS, heldProven = true, offersProof = false, heldRow = quiet),
        )
    }

    @Test
    fun aHundredFreshIdsBuyOneLegacyBoostPerCooldown() {
        val boost =
            app.getknit.knit.mesh.bluetooth
                .LegacyDialerBoost(cooldownMs = 300_000L)
        var now = 1_000L
        var boosts = 0
        repeat(100) { i ->
            if (boost.take(now, "fresh$i")) boosts++
            now += 1_000 // a fresh id and address a second
        }
        assertEquals("phone-wide, never per claimed id", 1, boosts)
        assertTrue(boost.take(1_000L + 300_000L, "another"))
    }

    @Test
    fun oneUnlinkableLegacyDialerBuysOneBoostPerHalfHour() {
        // The bluetooth review: an old phone this one cannot link keeps dialing; it must not boost every five minutes.
        val boost =
            app.getknit.knit.mesh.bluetooth
                .LegacyDialerBoost()
        val cooldown = app.getknit.knit.mesh.bluetooth.LegacyDialerBoost.COOLDOWN_MS
        var boosts = 0
        var now = 0L
        while (now < 3_600_000L) {
            if (boost.take(now, "old-android")) boosts++
            now += 60_000
        }
        assertEquals("two in an hour, not twelve", 2, boosts)
        assertTrue("another dialer is not held back by its bound", boost.take(3_600_000L + cooldown, "another"))
    }
}
