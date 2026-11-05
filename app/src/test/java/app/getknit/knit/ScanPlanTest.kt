package app.getknit.knit

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.ScanPlan
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ScanPlan]: far peers, when Coded-only windows run and why, and the reachable set. */
class ScanPlanTest {
    private fun snap(
        nodeId: String,
        codedLagMs: Long? = null,
        caps: Long = 0L,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = caps,
            psm = 100,
            digestCue = 0,
            smoothedRssi = -60.0,
            dwellMs = 15_000,
            lastSeenAgoMs = 0,
            codedLagMs = codedLagMs,
        )

    @Test
    fun aFarPeerIsUnlinkedAndHeardOnCodedAlone() {
        val stale = CodedPhyPolicy.ONE_M_FRESH_MS + 1
        val snaps =
            listOf(
                snap("near"), // 1M heard
                snap("fresh", codedLagMs = CodedPhyPolicy.ONE_M_FRESH_MS), // its 1M is still fresh
                snap("far", codedLagMs = stale),
                snap("linked", codedLagMs = stale),
            )
        assertEquals(listOf("far"), ScanPlan.far(snaps, linked = setOf("linked")))
    }

    @Test
    fun codedWindowsRunOnlyWithCodedOnAndAReasonForThem() {
        assertFalse(ScanPlan.codedWindows(on = false, alone = true, far = listOf("x"), hunt = true, chased = true))
        assertFalse("linked, nothing far, no hunt, no chase", ScanPlan.codedWindows(true, false, emptyList(), false, false))
        assertTrue(ScanPlan.codedWindows(true, alone = true, far = emptyList(), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = listOf("x"), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = true, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = false, chased = true))
    }

    @Test
    fun theWindowsLineNamesTheFirstReason() {
        val chase = { listOf("c1") }
        assertEquals("alone", ScanPlan.codedWindowsWhy(alone = true, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("hunt", ScanPlan.codedWindowsWhy(alone = false, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("codedOnly=[x]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = listOf("x"), chasedCoded = chase))
        assertEquals("chase=[c1]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = emptyList(), chasedCoded = chase))
    }

    @Test
    fun theChaseIsReadOnlyWhenItIsTheReason() {
        var reads = 0
        ScanPlan.codedWindowsWhy(alone = true, hunt = false, far = emptyList()) { reads++.let { emptyList() } }
        assertEquals(0, reads)
    }

    @Test
    fun reachableIsEverySightingAndEveryLinkAndALinksPeerWins() {
        val got =
            ScanPlan.reachable(
                listOf(snap("seen", caps = 1L), snap("both", caps = 1L)),
                listOf(Peer("both", 2, 2L), Peer("unsighted", 2, 2L)),
            )
        assertEquals(setOf(Peer("seen", 1, 1L), Peer("both", 2, 2L), Peer("unsighted", 2, 2L)), got)
    }

    private fun before(
        adapterOn: Boolean = true,
        dialing: Boolean = false,
        busy: Boolean = false,
        nextWindowAt: Long = 0,
        now: Long = 1_000,
    ) = ScanPlan.beforeWindow(adapterOn, dialing, busy, nextWindowAt, now, connectPauseMs = 28_000, adapterOffWaitMs = 60_000)

    @Test
    fun theScanWaitsForTheEventThatEndsItsPause() {
        assertNull("free radio, no slot held: scan now", before())
        assertEquals("adapter off, whatever else", 60_000L, before(adapterOn = false, dialing = true, busy = true))
        assertEquals("a dial in flight", 28_000L, before(dialing = true))
        assertEquals("the arbiter held", 28_000L, before(busy = true))
    }

    @Test
    fun aWindowADialCutShortKeepsItsSlot() {
        assertEquals(400L, before(nextWindowAt = 1_400, now = 1_000))
        assertNull("its slot has come", before(nextWindowAt = 1_000, now = 1_000))
        assertEquals("a pause wins over the slot", 28_000L, before(dialing = true, nextWindowAt = 1_400, now = 1_000))
    }

    @Test
    fun aHuntingGapKeepsWallTimeAndNeverAsksForTheFloor() {
        var floorReads = 0
        val gap = ScanPlan.gapAfterWindow(PowerState(), hunt = true, floor = { floorReads++ > 0 }, links = 2, aloneForMs = 0)
        assertEquals(ScanPlan.Gap(PowerPolicy.HUNT_IDLE_MS, keepsWallTime = true, posture = ScanPlan.Posture.HUNT), gap)
        assertEquals(0, floorReads)
    }

    @Test
    fun aSettledOrDutyGapIsAPowerBudgetThatStretches() {
        val lit = PowerState(interactive = true)
        assertEquals(
            ScanPlan.Gap(PowerPolicy.settledIdleAfterScan(lit, 2, 0), keepsWallTime = false, posture = ScanPlan.Posture.FLOOR),
            ScanPlan.gapAfterWindow(lit, hunt = false, floor = { true }, links = 2, aloneForMs = 0),
        )
        assertEquals(
            ScanPlan.Gap(PowerPolicy.idleAfterScan(lit, 2, 0), keepsWallTime = false, posture = ScanPlan.Posture.BOOST),
            ScanPlan.gapAfterWindow(lit, hunt = false, floor = { false }, links = 2, aloneForMs = 0),
        )
    }

    @Test
    fun aLinklessPhoneThatHasNotRelaxedKeepsWallTimeOutsideAHunt() {
        val dark = PowerState(interactive = false)
        val gap = ScanPlan.gapAfterWindow(dark, hunt = false, floor = { false }, links = 0, aloneForMs = 0)
        assertEquals(PowerPolicy.hunting(dark, 0, 0), gap.keepsWallTime)
        assertTrue("alone and not yet relaxed", gap.keepsWallTime)
        assertEquals(ScanPlan.Posture.LONELY, gap.posture)
        assertFalse(gap.lonelyRelaxed)
        // Floored while alone and not yet relaxed (an audio floor): still wall time, as before the hold.
        val floored = ScanPlan.gapAfterWindow(dark, hunt = false, floor = { true }, links = 0, aloneForMs = 0)
        assertEquals(ScanPlan.Posture.FLOOR, floored.posture)
        assertTrue("a floored linkless gap keeps wall time", floored.keepsWallTime)
    }

    @Test
    fun aLinklessPhoneOnBatteryRelaxesPastTheLonelyWindow() {
        val dark = PowerState(interactive = false, batteryLow = true)
        val alone = PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS
        val gap = ScanPlan.gapAfterWindow(dark, hunt = false, floor = { false }, links = 0, aloneForMs = alone)
        assertEquals(ScanPlan.Gap(PowerPolicy.idleAfterScan(dark, 0, alone), false, ScanPlan.Posture.RELAXED, true), gap)
        // Audio's floor alone still reads relaxed on the lonely edge, as the transport's line always has.
        val floored = ScanPlan.gapAfterWindow(dark, hunt = false, floor = { true }, links = 0, aloneForMs = alone)
        assertEquals(ScanPlan.Posture.FLOOR, floored.posture)
        assertTrue(floored.lonelyRelaxed)
        assertFalse("a hunt is never the relaxed edge", ScanPlan.gapAfterWindow(dark, true, { false }, 0, alone).lonelyRelaxed)
    }

    @Test
    fun aHeldScanTakesTheHuntsGapWhateverThePolicySaysAndNeverAsksForTheFloor() {
        val held = ScanPlan.Gap(PowerPolicy.HUNT_IDLE_MS, keepsWallTime = true, posture = ScanPlan.Posture.HELD)
        var floorReads = 0
        val floor = { floorReads++ >= 0 }
        val dark = PowerState(interactive = false, batteryLow = true)
        val alone = PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS * 10
        // Settled with links (the floor), alone past the lonely window (relaxed), and hunting: held each time.
        assertEquals(held, ScanPlan.gapAfterWindow(dark, hunt = false, floor = floor, links = 3, aloneForMs = 0, held = true))
        assertEquals(held, ScanPlan.gapAfterWindow(dark, hunt = false, floor = floor, links = 0, aloneForMs = alone, held = true))
        assertEquals(held, ScanPlan.gapAfterWindow(dark, hunt = true, floor = floor, links = 0, aloneForMs = alone, held = true))
        assertEquals("no floor edge is logged while held", 0, floorReads)
        // Released, the same inputs fall back to the policy's own gaps.
        assertEquals(ScanPlan.Posture.FLOOR, ScanPlan.gapAfterWindow(dark, false, floor, 3, 0).posture)
        assertEquals(ScanPlan.Posture.RELAXED, ScanPlan.gapAfterWindow(dark, false, { false }, 0, alone).posture)
    }

    private fun heard(
        nodeId: String,
        rssi1m: Double? = null,
        rssiCoded: Double? = null,
        oneMFresh: Boolean = rssi1m != null,
        codedFresh: Boolean = rssiCoded != null,
        codedLagMs: Long? = null,
    ): BlePresenceTracker.Snapshot =
        snap(nodeId, codedLagMs).copy(
            smoothedRssi = -60.0, // never read by near: the raw readings are
            rssi1m = rssi1m,
            rssiCoded = rssiCoded,
            oneMFresh = oneMFresh,
            codedFresh = codedFresh,
        )

    @Test
    fun nearIsEveryLinkAndEverySightingWhoseFreshRawReadingReachesTheFloor() {
        val stale = CodedPhyPolicy.ONE_M_FRESH_MS + 1
        val snaps =
            listOf(
                heard("strong1m", rssi1m = -60.0),
                heard("edge1m", rssi1m = -90.0),
                heard("weak1m", rssi1m = -91.0),
                // A walk-away peer: its last 1M reading is strong but 1M went silent, and Coded hears it weakly.
                heard("stale1m", rssi1m = -60.0, oneMFresh = false, rssiCoded = -95.0, codedLagMs = 1_000),
                heard("codedOnly", rssi1m = -60.0, oneMFresh = false, rssiCoded = -60.0, codedLagMs = stale),
                // The incident's reading: raw −101 on Coded, which its credit scores −89 on the 1M scale.
                heard("farCoded", rssiCoded = -101.0, codedLagMs = 0),
                // A close peer heard in a Coded-only window: 1M not fresh, but not Coded-only either.
                heard("closeCoded", rssi1m = -65.0, oneMFresh = false, rssiCoded = -70.0, codedLagMs = 0),
            )
        val link = Peer("linked", protoVersion = 1, capabilities = 7L)
        val near = ScanPlan.near(snaps, linkPeers = listOf(link))
        assertEquals(setOf("strong1m", "edge1m", "closeCoded", "linked"), near.map { it.nodeId }.toSet())
        assertTrue("the link's Peer, with no snapshot behind it", link in near)
        assertTrue("near is within reachable", ScanPlan.reachable(snaps, listOf(link)).containsAll(near))
        assertTrue("the credit would have passed −101", -101.0 + CodedPhyPolicy.CODED_RSSI_CREDIT_DB >= -90.0)
    }

    // An edge peer as the tracker hears it in one all-PHY window: 1M hits smoothed at −95, Coded at a raw −85 between.
    private fun edgePeer(tracker: BlePresenceTracker): BlePresenceTracker.Snapshot {
        tracker.onOneMListening(true, 0)
        for (at in 0L..9_000L step 3_000L) {
            tracker.onSighting(BlePresenceTracker.Sighting("edge", -95, 1, 0L, 100, 0), at + 1_000)
            tracker.onSighting(BlePresenceTracker.Sighting("edge", -85, 1, 0L, 100, 0, coded = true), at + 2_000)
        }
        return tracker.snapshots(11_000).single()
    }

    @Test
    fun aPeerHeardOnlyWeaklyOn1mIsFarAndLeavesNear() {
        // The 2026-10-20 amendment. Under the old rule each weak 1M hit reset the clock, so the peer was never Coded-only
        // and its fresh raw Coded reading (≥ −90) put it in near; now it ripens Coded-only, which moves it into far and
        // out of near. The conservative way for Wi-Fi Aware's Tier-2 corroboration (ADR 2026-09.9dnk).
        val snap = edgePeer(BlePresenceTracker())
        assertTrue(snap.oneMFresh && snap.codedFresh)
        assertTrue(CodedPhyPolicy.codedOnly(snap.codedLagMs))
        assertEquals(listOf("edge"), ScanPlan.far(listOf(snap), linked = emptySet()))
        assertTrue(ScanPlan.near(listOf(snap), linkPeers = emptyList()).isEmpty())

        val before = edgePeer(BlePresenceTracker(oneMWeakDbm = { -200.0 }))
        assertFalse("the old rule", CodedPhyPolicy.codedOnly(before.codedLagMs))
        assertTrue(ScanPlan.far(listOf(before), linked = emptySet()).isEmpty())
        assertEquals(setOf("edge"), ScanPlan.near(listOf(before), linkPeers = emptyList()).map { it.nodeId }.toSet())
    }
}
