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
}
