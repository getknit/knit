package app.getknit.knit

import app.getknit.knit.mesh.DigestTracker
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
}
