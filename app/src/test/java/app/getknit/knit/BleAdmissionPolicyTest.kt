package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.REPLACE_MIN_HOLD_MS
import app.getknit.knit.mesh.bluetooth.BleAdmissionPolicy.Verdict
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import org.junit.Assert.assertEquals
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
}
