package app.getknit.knit

import app.getknit.knit.mesh.CustodyProgress
import app.getknit.knit.mesh.DigestTracker
import app.getknit.knit.mesh.DigestTracker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DigestTracker] — the pure "is a data-path sync with this peer worth it?" decision. */
class DigestTrackerTest {
    @Test
    fun noCueHeardIsNotWanted() {
        val t = DigestTracker()
        assertFalse("we hold no digest for a peer we've never heard from", t.reconcileWanted("p", 100L))
    }

    @Test
    fun divergentFirstContactIsWanted() {
        val t = DigestTracker()
        assertTrue("first cue moves the peer version", t.onCue("p", 5L))
        assertTrue("digests differ and we've never synced → sync", t.reconcileWanted("p", 100L))
    }

    @Test
    fun identicalDigestsAreSkippedEvenOnFirstContact() {
        // The decisive win: two peers already holding the same store never bring up an NDP.
        val t = DigestTracker()
        t.onCue("p", 42L)
        assertFalse("matching digests ⇒ nothing to exchange", t.reconcileWanted("p", 42L))
    }

    @Test
    fun afterAConvergedSyncThePairIsQuiet() {
        val t = DigestTracker()
        t.onCue("p", 100L) // peer advertises the converged version
        t.onReconciled("p", 100L) // we synced; both at 100
        assertFalse(t.reconcileWanted("p", 100L))
    }

    @Test
    fun peerGainingSomethingReTriggers() {
        val t = DigestTracker()
        t.onCue("p", 100L)
        t.onReconciled("p", 100L)
        assertTrue("peer cues a new version", t.onCue("p", 101L))
        assertTrue(t.reconcileWanted("p", 100L))
    }

    @Test
    fun weGainingSomethingReTriggers() {
        val t = DigestTracker()
        t.onCue("p", 100L)
        t.onReconciled("p", 100L)
        assertTrue("our store moved past the synced point → push", t.reconcileWanted("p", 200L))
    }

    @Test
    fun versionsAreComparedForEqualityNotOrder() {
        // A content digest is a hash, not a counter: a *lower* value is still "changed", so it must re-trigger.
        val t = DigestTracker()
        t.onCue("p", 100L)
        t.onReconciled("p", 100L)
        t.onCue("p", 3L)
        assertTrue(t.reconcileWanted("p", 100L))
    }

    @Test
    fun reCueingTheSameVersionIsNotAMove() {
        val t = DigestTracker()
        t.onCue("p", 7L)
        assertFalse(t.onCue("p", 7L))
    }

    @Test
    fun forgetDropsAllState() {
        val t = DigestTracker()
        t.onCue("p", 5L)
        t.onReconciled("p", 100L)
        t.forget("p")
        assertFalse("a forgotten peer has no cue → nothing to pull", t.reconcileWanted("p", 100L))
    }

    // --- No-progress throttle (docs/NAN_CONCURRENCY_REAUDIT.md §5.3; convergence-only reset) ---

    /** A fixed-clock tracker plus a helper that completes one non-convergent sync round. */
    private class ThrottleRig {
        var now = 0L
        val t = DigestTracker { now }

        /** One completed-but-non-convergent sync: the peer cues [peerV], we reconcile at a different local version. */
        fun nonConvergentRound(peerV: Long) {
            t.onCue("p", peerV)
            t.onReconciled("p", peerV + 1_000L) // local ≠ peer ⇒ no convergence
        }
    }

    @Test
    fun throttleEngagesAfterThreeNonConvergentSyncsInWindow() {
        val r = ThrottleRig()
        repeat(2) { i ->
            r.nonConvergentRound(i.toLong())
            assertTrue("still free before the third round", r.t.reconcileWanted("p", 500L))
        }
        r.nonConvergentRound(2L)
        assertFalse("third non-convergent completion engages the cooldown", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun windowExpiryDecaysThePreEngagementCount() {
        val r = ThrottleRig()
        r.nonConvergentRound(0L)
        r.nonConvergentRound(1L)
        r.now += DigestTracker.THROTTLE_WINDOW_MS + 1 // the two stale rounds age out
        r.nonConvergentRound(2L)
        assertTrue("a fresh window restarts the tally — no engagement", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun cooldownExpiryAllowsOneAttemptThenReThrottlesDoubled() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) } // engage: cooldown = initial
        r.now += DigestTracker.COOLDOWN_INITIAL_MS + 1
        assertTrue("cooldown expired → one attempt allowed", r.t.reconcileWanted("p", 500L))
        r.nonConvergentRound(9L) // engaged: re-arms immediately, doubled
        assertFalse(r.t.reconcileWanted("p", 500L))
        r.now += DigestTracker.COOLDOWN_INITIAL_MS + 1 // only the *initial* span — doubled cooldown still holds
        assertFalse("re-armed cooldown is doubled", r.t.reconcileWanted("p", 500L))
        r.now += DigestTracker.COOLDOWN_INITIAL_MS
        assertTrue("doubled cooldown expires", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun cooldownGrowthIsCapped() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) }
        // Keep failing until the backoff must have hit the cap, then measure the next span.
        repeat(10) { i ->
            r.now += DigestTracker.COOLDOWN_MAX_MS + 1
            r.nonConvergentRound(100L + i)
        }
        r.now += DigestTracker.COOLDOWN_MAX_MS - 1
        assertFalse("still inside the capped cooldown", r.t.reconcileWanted("p", 500L))
        r.now += 2
        assertTrue("capped cooldown expires at COOLDOWN_MAX_MS", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun convergenceViaOnReconciledFullyResets() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) } // engaged
        r.t.onCue("p", 500L)
        r.t.onReconciled("p", 500L) // converged sync → full reset
        r.t.onCue("p", 501L)
        assertTrue("post-reset divergence is wanted immediately", r.t.reconcileWanted("p", 400L))
        // And the engagement is gone: three fresh non-convergent rounds are needed again.
        r.nonConvergentRound(600L)
        assertTrue("reset also cleared the engaged latch", r.t.reconcileWanted("p", 400L))
    }

    @Test
    fun convergenceObservedByReconcileWantedResets() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) } // engaged; last cue = 2
        assertFalse("equal digests read as convergence even mid-cooldown", r.t.reconcileWanted("p", 2L))
        r.t.onCue("p", 77L)
        assertTrue("the equality check itself cleared the throttle", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun forgetResetsTheThrottle() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) } // engaged
        r.t.forget("p")
        r.t.onCue("p", 5L)
        assertTrue("a forgotten peer restarts with no cooldown", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun aWedgeNeverEngagesTheThrottle() {
        // Syncs that never *complete* (no onReconciled) must never throttle — the wedge watchdog's owed
        // signal rides reconcileWanted staying true.
        val r = ThrottleRig()
        r.t.onCue("p", 5L)
        repeat(20) {
            assertTrue("owed forever while no sync completes", r.t.reconcileWanted("p", 500L))
            r.now += 30_000L
        }
    }

    @Test
    fun aTtlBoundaryFlipConvergesOnTheNextCueWithNoSync() {
        // Work item #8: with a live-only StoreDigest, a custody frame expiring moves EVERY node's digest to the
        // same new value at (nearly) the same instant. Between our own fold and the peer's next cue there is a
        // legitimate skew window where a sync reads as wanted — but the peer's post-boundary cue must settle it
        // to identical-skip (no NDP, throttle reset), with no data-path exchange having happened.
        val t = DigestTracker()
        t.onCue("p", 1L)
        t.onReconciled("p", 1L) // converged and synced at v1

        assertTrue("our side folded the boundary first — the skew window reads sync-wanted", t.reconcileWanted("p", 2L))
        t.onCue("p", 2L) // the peer's own fold arrives with its next cue
        assertFalse("identical digests → skip, no NDP", t.reconcileWanted("p", 2L))
    }

    @Test
    fun clearDropsEveryPeersCooldownAndVersions() {
        // Transport teardown of the whole plane: a peer heard again afterwards starts as a first contact, not throttled.
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) }
        r.t.onCue("p", 9L)
        assertFalse("engaged before the clear", r.t.reconcileWanted("p", 500L))

        r.t.clear()

        assertEquals("no peer's versions survive the clear", "", r.t.debug())
        assertFalse("nothing heard since the clear", r.t.reconcileWanted("p", 500L))
        r.t.onCue("p", 9L)
        assertTrue("no cooldown survives the clear", r.t.reconcileWanted("p", 500L))
    }

    @Test
    fun theDiagnosticNamesEachPeersVersionsAndARunningCooldown() {
        val r = ThrottleRig()
        repeat(3) { i -> r.nonConvergentRound(i.toLong()) } // engaged at now = 0: the last round cued 2 and synced at 1002
        r.t.onCue("q", 7L)

        assertEquals("p(pv=2,sv=1002,cd=${DigestTracker.COOLDOWN_INITIAL_MS}) q(pv=7,sv=null)", r.t.debug())

        r.now += DigestTracker.COOLDOWN_INITIAL_MS
        assertEquals("a spent cooldown is not shown", "p(pv=2,sv=1002) q(pv=7,sv=null)", r.t.debug())
    }

    // --- The hold-off keyed on progress (custody-carry-policy android order, step 8) ---

    /** Custody's word on peer "p", moved by hand: the marks a [DigestTracker] reads at an exchange's open and its end. */
    private class Custody : CustodyProgress {
        var progress = 0L
        var digests = 0L
        var fresh = false
        var watches = 0

        override fun progressMark(peer: String) = progress

        override fun digestSeen(peer: String) = digests

        override fun watchFresh(peer: String) {
            watches++
            fresh = false
        }

        override fun fresh(peer: String) = fresh
    }

    /** A tracker over [custody] on a test clock, and one exchange with "p" at a time, judged as a path would be. */
    private class Rig {
        var now = 1_000_000L
        val custody = Custody()
        val t = DigestTracker(custody) { now }

        init {
            t.onCue("p", 1L) // the peer's digest differs from ours (500) throughout: two full stores
        }

        /** One path: opened, then closed with custody's marks moved as [moved] / [digest] say, judged as [initiator]. */
        fun exchange(
            moved: Boolean = false,
            digest: Boolean = true,
            digestWanted: Boolean = true,
            initiator: Boolean = true,
        ): Verdict {
            val opened = t.open("p", digestWanted)
            if (digest) custody.digests++
            if (moved) custody.progress++
            return t.onReconciled("p", 500L, opened, initiator)
        }

        fun wanted(initiator: Boolean = true) = t.reconcileWanted("p", 500L, initiator)

        /** Three exchanges that moved nothing, a second apart: the tally engages the hold-off. */
        fun engage(initiator: Boolean = true) =
            repeat(DigestTracker.THROTTLE_SYNCS) {
                if (it > 0) now += 1_000
                exchange(initiator = initiator)
            }
    }

    @Test
    fun theDefaultCustodyKeepsTheThrottleAsItWas() {
        // CustodyProgress.NONE: never moved, never fresh — every exchange short of equal digests counts, as before.
        var now = 0L
        val t = DigestTracker { now }
        t.onCue("p", 1L)
        repeat(2) {
            assertEquals(Verdict.NO_WORD, t.onReconciled("p", 500L, t.open("p", digestWanted = true)))
            assertTrue(t.reconcileWanted("p", 500L))
        }
        assertEquals(Verdict.NO_WORD, t.onReconciled("p", 500L, t.open("p", digestWanted = false)))
        assertFalse("the third engages, a bulk-only path included", t.reconcileWanted("p", 500L))
        now += DigestTracker.COOLDOWN_INITIAL_MS
        assertTrue(t.reconcileWanted("p", 500L))
    }

    @Test
    fun anExchangeThatMovedNothingCountsTowardTheHoldOffAndWatchesForAFreshFrame() {
        val r = Rig()
        assertEquals(Verdict.STILL, r.exchange())
        assertEquals("the watch starts at the exchange's end", 1, r.custody.watches)
        assertTrue("one is the converge-in-two dance, not yet a hold-off", r.wanted())
        r.exchange()
        assertTrue(r.wanted())
        r.exchange()
        assertFalse("three in a minute engage it", r.wanted())
        r.now += DigestTracker.COOLDOWN_INITIAL_MS
        assertTrue(r.wanted())
    }

    @Test
    fun theHoldOffDoublesToItsCeilingWhileNothingMoves() {
        // The real cadence: each path comes the moment the hold-off ends and moves nothing again.
        val r = Rig()
        r.engage()
        val spans = mutableListOf<Long>()
        var span = DigestTracker.COOLDOWN_INITIAL_MS
        repeat(7) {
            spans += span
            r.now += span - 1
            assertFalse("held off for the whole span", r.wanted())
            r.now += 1
            assertTrue(r.wanted())
            r.exchange()
            span = minOf(span * 2, DigestTracker.COOLDOWN_MAX_MS)
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), spans)
    }

    @Test
    fun anExchangeThatMovedSomethingClearsTheHoldOff() {
        val r = Rig()
        r.engage()
        r.now += DigestTracker.COOLDOWN_INITIAL_MS
        assertEquals(Verdict.MOVED, r.exchange(moved = true))
        assertTrue("cleared at once", r.wanted())
        r.exchange()
        assertTrue("and the tally starts over", r.wanted())
    }

    @Test
    fun equalDigestsStillClearTheHoldOffAndSkipTheSync() {
        val r = Rig()
        r.engage()
        assertFalse("equal digests: nothing to exchange", r.t.reconcileWanted("p", 1L))
        assertTrue("and the hold-off is gone", r.wanted())
        r.engage()
        r.t.onCue("p", 500L)
        assertEquals(Verdict.CONVERGED, r.t.onReconciled("p", 500L, r.t.open("p", true)))
        r.t.onCue("p", 2L)
        assertTrue(r.wanted())
    }

    @Test
    fun aFreshFrameLiftsTheInitiatorsHoldOff() {
        val r = Rig()
        r.engage()
        assertFalse(r.wanted())
        r.custody.fresh = true // custody newly keeps a frame the peer's last digest lacked
        assertTrue("the side that brings the path up goes", r.wanted(initiator = true))
        r.exchange()
        assertFalse("the exchange it raised moved nothing: watched afresh and held again", r.wanted())
    }

    @Test
    fun noHoldOffWithoutThePeersCustodyDigestInTheExchange() {
        val r = Rig()
        r.custody.digests = 7 // custody has the peer's word from earlier paths
        repeat(5) { assertEquals(Verdict.UNJUDGED, r.exchange(digest = false)) }
        assertTrue("an exchange that carried no digest says nothing about progress", r.wanted())
        r.engage()
        r.now += 1_000
        assertEquals(Verdict.UNJUDGED, r.exchange(digest = false))
        r.now += DigestTracker.COOLDOWN_INITIAL_MS
        assertTrue("nor does it re-arm a running hold-off doubled", r.wanted())
        assertEquals("no watch for an exchange that judged nothing", DigestTracker.THROTTLE_SYNCS, r.custody.watches)
    }

    @Test
    fun noHoldOffOnAPathOpenedBulkOnly() {
        val r = Rig()
        repeat(5) { assertEquals(Verdict.UNJUDGED, r.exchange(digestWanted = false)) }
        assertTrue(r.wanted())
    }

    @Test
    fun aPeerCustodyKeepsNoWordForIsThrottledAsBefore() {
        // An unproven link (or a build older than the proof): custody's marks read 0, so the old throttle applies.
        val r = Rig()
        repeat(DigestTracker.THROTTLE_SYNCS) { assertEquals(Verdict.NO_WORD, r.exchange(digest = false)) }
        assertFalse(r.wanted())
        assertEquals("nothing to watch for a peer custody keeps no word for", 0, r.custody.watches)
    }

    @Test
    fun theMarksAreTheLinksOwnSoADisplacedLinksNeverJudgeTheNewcomer() {
        val r = Rig()
        r.engage()
        val displaced = r.t.open("p", digestWanted = true)
        r.custody.progress++ // kept off the displaced link before the newcomer came up
        r.custody.digests++
        val newcomer = r.t.open("p", digestWanted = true)
        r.custody.digests++
        assertEquals("the newcomer moved nothing since its own open", Verdict.STILL, r.t.onReconciled("p", 500L, newcomer))
        assertFalse(r.wanted())
        assertEquals("the displaced link's own verdict would have read it", Verdict.MOVED, r.t.onReconciled("p", 500L, displaced))
    }

    @Test
    fun aMarkThatReadsLowerMovedNothing() {
        // Custody dropped the peer's word mid-exchange (its memo evicted): marks restart lower, never higher.
        val r = Rig()
        r.custody.progress = 40
        r.custody.digests = 40
        val opened = r.t.open("p", digestWanted = true)
        r.custody.progress = 3
        r.custody.digests = 41
        assertEquals(Verdict.STILL, r.t.onReconciled("p", 500L, opened))
    }

    @Test
    fun anUnjudgedPathAFreshFrameOpenedSpendsTheLift() {
        val r = Rig()
        r.engage()
        r.custody.fresh = true
        assertTrue(r.wanted())
        assertEquals(Verdict.UNJUDGED, r.exchange(digest = false)) // the lifted path carried no digest of the peer's
        assertFalse("the lift is spent: no loop of digest-less paths on one fresh frame", r.wanted())
        assertEquals(DigestTracker.THROTTLE_SYNCS + 1, r.custody.watches)
    }

    @Test
    fun theResponderKeepsNoHoldOffAndTrustsTheLinkAfterANonConvergentPath() {
        val r = Rig()
        assertTrue("divergent, no path yet, no start grace: owed, as before", r.wanted(initiator = false))
        assertEquals(Verdict.STILL, r.exchange(initiator = false))
        assertEquals("the responder starts no watch", 0, r.custody.watches)
        assertTrue("and counts nothing: the initiator's gate is untouched", r.wanted(initiator = true))
        assertFalse("but trusts the link it just had", r.wanted(initiator = false))
        r.t.forget("p") // the 150 s prune: the peer heard again resumes its trust
        r.t.onCue("p", 1L)
        r.now += DigestTracker.RESPONDER_TRUST_MS - 1
        assertFalse(r.wanted(initiator = false))
        r.now += 1
        assertTrue("the trust lapses: the wedge detection comes back", r.wanted(initiator = false))
    }

    @Test
    fun aConvergedPathOrAClearLeavesNoTrust() {
        val r = Rig()
        r.t.onPlaneStarted() // as production runs it: the restart grace is live
        assertFalse("inside the grace", r.wanted(initiator = false))
        r.exchange(initiator = false)
        r.t.onCue("p", 500L)
        assertEquals(Verdict.CONVERGED, r.t.onReconciled("p", 500L, r.t.open("p", true), initiator = false))
        r.t.onCue("p", 2L)
        assertTrue("a new divergence after a converged path is owed at once, grace or not", r.wanted(initiator = false))
        r.exchange(initiator = false)
        r.t.clear()
        r.t.onCue("p", 1L)
        assertTrue("a stopped plane forgets it; its next start grants the grace instead", r.wanted(initiator = false))
        r.t.onPlaneStarted()
        assertFalse(r.wanted(initiator = false))
    }

    @Test
    fun equalDigestsReadByTheGateEndThePeersRestartGrace() {
        val r = Rig()
        r.t.onPlaneStarted()
        r.t.onCue("q", 1L)
        assertFalse(r.t.reconcileWanted("p", 1L, initiator = false)) // equal: converged, seen by the gate
        assertFalse("another peer's grace stands", r.t.reconcileWanted("q", 500L, initiator = false))
        assertTrue("diverged again: owed at once, inside the grace", r.wanted(initiator = false))
    }

    @Test
    fun aPathComingUpRenewsTheTrust() {
        val r = Rig()
        r.exchange(initiator = false) // the last non-convergent close
        r.now += DigestTracker.RESPONDER_TRUST_MS - 1_000
        r.t.onPathUp("p") // the larger's dial landed late, behind a busy NDI: the link is alive
        r.now += DigestTracker.RESPONDER_TRUST_MS - 1
        assertFalse("trusted from the path's start", r.wanted(initiator = false))
        r.now += 1
        assertTrue(r.wanted(initiator = false))
    }
}
