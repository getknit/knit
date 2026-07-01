package app.getknit.knit

import app.getknit.knit.mesh.CueTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueTrackerTest {

    @Test
    fun `a peer we have never heard a cue from is not sync-wanted`() {
        val t = CueTracker()
        assertFalse(t.syncWanted("peer", localEpoch = 5))
    }

    @Test
    fun `a first cue makes the peer sync-wanted`() {
        val t = CueTracker()
        assertTrue(t.onCue("peer", epoch = 0)) // became sync-wanted
        assertTrue(t.syncWanted("peer", localEpoch = 0))
    }

    @Test
    fun `after a sync the peer is quiet until an epoch advances`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 3)
        t.onSynced("peer", localEpoch = 7)
        assertFalse(t.syncWanted("peer", localEpoch = 7))
    }

    @Test
    fun `a peer epoch advance re-triggers a sync`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 3)
        t.onSynced("peer", localEpoch = 7)
        assertTrue(t.onCue("peer", epoch = 4)) // their store grew → sync-wanted again
        assertTrue(t.syncWanted("peer", localEpoch = 7))
    }

    @Test
    fun `our own epoch advance re-triggers a sync`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 3)
        t.onSynced("peer", localEpoch = 7)
        assertFalse(t.syncWanted("peer", localEpoch = 7))
        assertTrue(t.syncWanted("peer", localEpoch = 8)) // we gained a message to push
    }

    @Test
    fun `onCue reports not-newly-wanted when it does not advance past the synced point`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 5)
        t.onSynced("peer", localEpoch = 0)
        assertFalse(t.onCue("peer", epoch = 5)) // same epoch, already synced → not newly wanted
        assertFalse(t.onCue("peer", epoch = 4)) // stale/reordered cue → ignored (monotonic), not wanted
    }

    @Test
    fun `epochs are tracked monotonically against reordered cues`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 9)
        t.onCue("peer", epoch = 2) // best-effort message reordering must not lower the known epoch
        t.onSynced("peer", localEpoch = 0)
        assertFalse(t.syncWanted("peer", localEpoch = 0)) // synced at 9, so 2 doesn't resurrect it
    }

    @Test
    fun `forget clears per-peer state`() {
        val t = CueTracker()
        t.onCue("peer", epoch = 3)
        t.forget("peer")
        assertFalse(t.syncWanted("peer", localEpoch = 5))
    }
}
