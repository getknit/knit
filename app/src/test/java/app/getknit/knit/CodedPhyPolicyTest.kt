package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.PhyStepper
import app.getknit.knit.mesh.bluetooth.PhyStepper.Action
import app.getknit.knit.mesh.bluetooth.PhyTuning
import app.getknit.knit.mesh.bluetooth.PromotionPolicy
import app.getknit.knit.mesh.bluetooth.ScanPhys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the BLE Coded PHY experiment (ADR 2026-10.yvn6): scoring, dial choice, and the link's PHY steps. */
class CodedPhyPolicyTest {
    private val tuning = PhyTuning()

    /** A stepper on [phy] (as its first read reported), with [reads] link-RSSI reads one second apart from t=0. */
    private fun stepper(
        phy: LinkPhy = LinkPhy.ONE_M,
        vararg reads: Int,
    ): PhyStepper =
        PhyStepper { tuning }.also { s ->
            s.onPhy(phy, succeeded = true, now = 0)
            reads.forEachIndexed { i, r -> s.onRssi(r, now = i * 1_000L) }
        }

    private fun sighting(
        rssi: Int,
        coded: Boolean,
    ) = BlePresenceTracker.Sighting("p", rssi, protoVersion = 1, capabilities = 0, psm = 128, digestCue = 0, coded = coded)

    // --- scoring ---

    @Test
    fun aCodedReadingIsCreditedOntoTheOneMScale() {
        assertEquals(-85.0, CodedPhyPolicy.effectiveRssi(null, -97.0)!!, 0.0)
        assertEquals(-80.0, CodedPhyPolicy.effectiveRssi(-80.0, -97.0)!!, 0.0) // the stronger wins
        assertEquals(-70.0, CodedPhyPolicy.effectiveRssi(-90.0, -82.0)!!, 0.0)
        assertEquals(-88.0, CodedPhyPolicy.effectiveRssi(-88.0, null)!!, 0.0) // the experiment off: unchanged
        assertNull(CodedPhyPolicy.effectiveRssi(null, null))
    }

    @Test
    fun aPeerHeardOnlyOnCodedAtMinus97IsPromotable() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 13_000 - 7_000)
        t.onSighting(sighting(-97, coded = true), now = 13_000)
        val snap = t.snapshots(13_000).single()
        assertEquals(-85.0, snap.smoothedRssi, 0.0001)
        assertNull(snap.oneMSeenAgoMs)
        assertEquals(0L, snap.codedSeenAgoMs)
        // −85 clears PromotionConfig's −90 floor, which the raw −97 would not.
        assertEquals(listOf("p"), PromotionPolicy.decide(listOf(snap), emptyList(), emptySet()).promote)
    }

    @Test
    fun theTwoPhysKeepSeparateAverages() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-100, coded = true), now = 500)
        // The Coded reading never drags the 1M one down: −70 vs −100+12.
        assertEquals(-70.0, t.snapshots(500).single().smoothedRssi, 0.0001)
    }

    @Test
    fun aPhyNotHeardInTheLatestBurstStopsCounting() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-60, coded = false), now = 0) // close, on 1M
        t.onSighting(sighting(-100, coded = true), now = 7_000)
        t.onSighting(sighting(-100, coded = true), now = 14_000) // walked off: only Coded hears it now
        assertEquals(-88.0, t.snapshots(14_000).single().smoothedRssi, 0.0001)
    }

    @Test
    fun codedHitsSecondsApartAreContinuousPresence() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 20_000) // a far peer's sparse Coded hits
        val snap = t.snapshots(20_000).single()
        assertEquals(20_000L, snap.dwellMs)
        assertEquals(listOf("p"), PromotionPolicy.decide(listOf(snap), emptyList(), emptySet()).promote)
    }

    @Test
    fun oneMHitsKeepTheirEightSecondGap() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-70, coded = false), now = 10_000)
        assertEquals(0L, t.snapshots(10_000).single().dwellMs)
    }

    @Test
    fun aCodedHitAfterTheCodedGapStartsOver() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 40_000)
        assertEquals(0L, t.snapshots(40_000).single().dwellMs)
    }

    @Test
    fun onlyCodedAfterCodedGetsTheWideGap() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 20_000) // the last sighting was 1M: its 8 s gap applies
        assertEquals(0L, t.snapshots(20_000).single().dwellMs)
    }

    @Test
    fun theCodedCreditIsTunable() {
        assertEquals(-79.0, CodedPhyPolicy.effectiveRssi(null, -97.0, creditDb = 18.0)!!, 0.0)
        val t = BlePresenceTracker(codedCreditDb = { 18.0 })
        t.onSighting(sighting(-97, coded = true), now = 0)
        val snap = t.snapshots(0).single()
        assertEquals(-79.0, snap.smoothedRssi, 0.0001)
        assertEquals(-97.0, snap.rssiCoded!!, 0.0001) // the raw reading stays on its own scale
        assertNull(snap.rssi1m)
    }

    // --- dialing and admission ---

    @Test
    fun aDialPrefersAFreshOneMAdvert() {
        assertFalse(CodedPhyPolicy.dialCoded(oneMSeenAgoMs = 2_000, codedSeenAgoMs = 1_000))
        assertTrue(CodedPhyPolicy.dialCoded(oneMSeenAgoMs = 30_000, codedSeenAgoMs = 1_000))
        assertTrue(CodedPhyPolicy.dialCoded(oneMSeenAgoMs = null, codedSeenAgoMs = 1_000))
        assertFalse(CodedPhyPolicy.dialCoded(oneMSeenAgoMs = null, codedSeenAgoMs = null))
        // Both stale together (between scan windows): a close peer, not a Coded-only one.
        assertFalse(CodedPhyPolicy.dialCoded(oneMSeenAgoMs = 30_000, codedSeenAgoMs = 29_000))
    }

    @Test
    fun codedOnlyIsMeasuredByTheOneMLag() {
        assertTrue(CodedPhyPolicy.codedOnly(oneMSeenAgoMs = null, codedSeenAgoMs = 1_000))
        assertTrue(CodedPhyPolicy.codedOnly(oneMSeenAgoMs = 20_000, codedSeenAgoMs = 1_000))
        assertFalse(CodedPhyPolicy.codedOnly(oneMSeenAgoMs = 9_000, codedSeenAgoMs = 1_000))
        assertFalse(CodedPhyPolicy.codedOnly(oneMSeenAgoMs = 60_000, codedSeenAgoMs = 55_000))
        assertFalse(CodedPhyPolicy.codedOnly(oneMSeenAgoMs = 1_000, codedSeenAgoMs = null))
    }

    private fun snapshot(
        oneMSeenAgoMs: Long?,
        codedSeenAgoMs: Long?,
    ) = BlePresenceTracker.Snapshot(
        nodeId = "p",
        protoVersion = 1,
        capabilities = 0,
        psm = 128,
        digestCue = 0,
        smoothedRssi = -95.0,
        dwellMs = 30_000,
        lastSeenAgoMs = minOf(oneMSeenAgoMs ?: Long.MAX_VALUE, codedSeenAgoMs ?: Long.MAX_VALUE),
        oneMSeenAgoMs = oneMSeenAgoMs,
        codedSeenAgoMs = codedSeenAgoMs,
    )

    @Test
    fun aDialerHeardOnCodedAloneIsAdmittedAsUnsighted() {
        val far = snapshot(oneMSeenAgoMs = null, codedSeenAgoMs = 2_000)
        assertFalse(CodedPhyPolicy.sightedForAdmission(far, codedOn = true))
        // The experiment off: judged by presence alone, as always (ADR 2026-09.shzv).
        assertTrue(CodedPhyPolicy.sightedForAdmission(far, codedOn = false))
        // Heard on 1M too: the old tie-break.
        assertTrue(CodedPhyPolicy.sightedForAdmission(snapshot(oneMSeenAgoMs = 2_500, codedSeenAgoMs = 2_000), codedOn = true))
        assertFalse(CodedPhyPolicy.sightedForAdmission(null, codedOn = true))
    }

    // --- scan windows ---

    @Test
    fun aLonePhoneAlternatesCodedOnlyWindows() {
        assertEquals(ScanPhys.CODED, CodedPhyPolicy.scanPhys(codedOn = true, alone = true, codedOnlyUnlinked = false, lastWasCoded = false))
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = true, codedOnlyUnlinked = false, lastWasCoded = true))
        assertEquals(ScanPhys.CODED, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = true, lastWasCoded = false))
    }

    @Test
    fun aLinkedPhoneWithNoFarPeerScansAllPhys() {
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = false, lastWasCoded = false))
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = false, lastWasCoded = true))
    }

    @Test
    fun theExperimentOffScansLegacy() {
        assertEquals(ScanPhys.ONE_M, CodedPhyPolicy.scanPhys(codedOn = false, alone = true, codedOnlyUnlinked = true, lastWasCoded = false))
    }

    @Test
    fun theLargerIdDrives() {
        assertTrue(CodedPhyPolicy.drives("zz", "aa"))
        assertFalse(CodedPhyPolicy.drives("aa", "zz"))
    }

    // --- steps ---

    @Test
    fun nothingIsAskedBeforeTheFirstPhyRead() {
        val s = PhyStepper { tuning }
        repeat(5) { s.onRssi(-95, now = it * 1_000L) }
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 5_000))
    }

    @Test
    fun offNeverActs() {
        assertEquals(Action.STAY, stepper(LinkPhy.ONE_M, -95, -95, -95, -95).decide(CodedPhyMode.OFF, now = 10_000))
    }

    @Test
    fun threeWeakReadsStepDownAndTwoDoNot() {
        assertEquals(Action.STAY, stepper(LinkPhy.ONE_M, -90, -90).decide(CodedPhyMode.AUTO, now = 10_000))
        assertEquals(Action.REQUEST_CODED, stepper(LinkPhy.ONE_M, -90, -90, -90).decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun aStrongReadResetsTheWeakStreak() {
        // Smoothed (α 0.5): −90, −90, −70, −80 — the −70 ends the streak and −80 is not weak enough to restart it.
        val s = stepper(LinkPhy.ONE_M, -90, -90, -50, -90)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun aCodedLinkStepsUpOnlyAfterHoldingStrong() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 29_000))
        s.onRssi(-60, now = 30_000)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
    }

    @Test
    fun aStepUpAnsweredWith2MIsTakenNotGivenUp() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 30_400)) // the controller picked 2M
        assertFalse(s.gaveUp)
        assertEquals(LinkPhy.TWO_M, s.phy)
        // A strong 2M link is already fast: AUTO asks nothing more of it.
        s.onRssi(-55, now = 60_000)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 60_000))
    }

    @Test
    fun aStepUpAnsweredWith1MIsTakenToo() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 30_400)) // the peer has no 2M
        assertFalse(s.gaveUp)
    }

    @Test
    fun thePinnedOneMModeStaysStrict() {
        val s = stepper(LinkPhy.TWO_M, -60)
        assertEquals(Action.REQUEST_ONE_M, s.decide(CodedPhyMode.ONE_M, now = 1_000))
        assertFalse(s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 1_200)) // 1M alone was asked
        assertTrue(s.gaveUp)
    }

    @Test
    fun aWeakDipRestartsTheStepUpHold() {
        val s = stepper(LinkPhy.CODED, -60)
        s.onRssi(-120, now = 20_000) // smoothed −90: no longer strong
        s.onRssi(-50, now = 25_000) // smoothed −70: strong again from here
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 40_000))
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 55_000))
    }

    @Test
    fun noAutomaticSwitchInsideTheMinimumGap() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 30_500))
        // Straight back out of range: weak enough to step down (smoothed −80, −90, −95, −97.5)…
        listOf(31_000L, 32_000L, 33_000L, 34_000L).forEach { s.onRssi(-100, now = it) }
        // …but not within 20 s of the last switch.
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 35_000))
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 50_500))
        assertEquals(1, s.switches)
    }

    @Test
    fun anUnansweredRequestIsGivenUpAndNeverRepeated() {
        val s = stepper(LinkPhy.TWO_M, -90, -90, -90)
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 3_000))
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 5_999))
        assertEquals(Action.GIVE_UP, s.decide(CodedPhyMode.AUTO, now = 6_000))
        assertTrue(s.gaveUp)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.CODED, now = 60_000))
    }

    @Test
    fun aRequestAnsweredWithAnotherPhyIsGivenUp() {
        val s = stepper(LinkPhy.ONE_M, -90, -90, -90)
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 3_000))
        assertFalse(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 3_200)) // the peer cannot do Coded
        assertTrue(s.gaveUp)
        assertEquals(LinkPhy.ONE_M, s.phy)
    }

    @Test
    fun aPeerDrivenUpdateIsFollowedNotGivenUp() {
        val s = stepper(LinkPhy.ONE_M)
        assertTrue(s.onPhy(LinkPhy.CODED, succeeded = true, now = 1_000))
        assertFalse(s.gaveUp)
        assertEquals(LinkPhy.CODED, s.phy)
    }

    @Test
    fun pinnedModesHoldTheirPhyWhateverTheRssi() {
        assertEquals(Action.REQUEST_CODED, stepper(LinkPhy.ONE_M, -40).decide(CodedPhyMode.CODED, now = 1_000))
        assertEquals(Action.REQUEST_ONE_M, stepper(LinkPhy.CODED, -100).decide(CodedPhyMode.ONE_M, now = 1_000))
        assertEquals(Action.STAY, stepper(LinkPhy.CODED, -40).decide(CodedPhyMode.CODED, now = 1_000))
    }

    @Test
    fun theReadCadenceTightensAtTheEdge() {
        assertEquals(tuning.readMs, stepper(LinkPhy.ONE_M, -60).nextReadMs())
        assertEquals(tuning.edgeReadMs, stepper(LinkPhy.ONE_M, -80).nextReadMs())
    }

    @Test
    fun modesRoundTripTheirSpelling() {
        CodedPhyMode.entries.forEach { assertEquals(it, CodedPhyMode.parse(it.wire)) }
        assertNull(CodedPhyMode.parse("long"))
    }
}
