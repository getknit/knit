package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenSetTest {
    @Test
    fun anIdIsNewAgainOnceItsWindowHasPassed() {
        var now = 0L
        val seen = SeenSet(ttlMillis = 1_000, clock = { now })
        assertTrue(seen.add("a"))
        assertFalse("inside the window", seen.add("a"))
        now = 1_000
        assertTrue("the window lapsed", seen.add("a"))
    }

    @Test
    fun aRestoredSetRemembersWhatTheLastProcessSaw() {
        // The LoRa plane's profile re-fan gate is the case: a 12-hour window kept by a process that may live
        // minutes, so without this every launch re-fanned every profile it heard.
        var now = 0L
        val before = SeenSet(ttlMillis = 10_000, clock = { now })
        before.add("published-once")
        now = 5_000

        val after = SeenSet(ttlMillis = 10_000, clock = { now })
        after.restore(before.stamps())

        assertFalse("still inside the original window", after.add("published-once"))
        now = 10_001
        assertTrue("and expires on the original stamp, not the restore", after.add("published-once"))
    }

    @Test
    fun anExpiredEntryIsNeitherExportedNorRestored() {
        var now = 0L
        val before = SeenSet(ttlMillis = 1_000, clock = { now })
        before.add("stale")
        now = 2_000
        assertEquals("an expired entry is new again by definition", emptyList<Pair<String, Long>>(), before.stamps())

        val after = SeenSet(ttlMillis = 1_000, clock = { now })
        after.restore(listOf("stale" to 0L))
        assertTrue(after.add("stale"))
    }

    @Test
    fun aRestoreLongerThanTheSetKeepsTheNewest() {
        val now = 1_000L
        val seen = SeenSet(maxSize = 2, ttlMillis = 10_000, clock = { now })
        seen.restore(listOf("oldest" to 0L, "middle" to 100L, "newest" to 200L))
        assertTrue("the oldest was evicted, as it would have been while running", seen.add("oldest"))
        assertTrue("the newest is what a bounded set keeps", seen.contains("newest"))
    }

    @Test
    fun anIdCanBeReopenedOnceInsideItsWindowAndNotAgain() {
        var now = 1_000L
        val set = SeenSet(ttlMillis = 10_000L, clock = { now })
        assertTrue(set.add("dm"))
        assertFalse("the copy custody served is a duplicate", set.add("dm"))
        assertTrue("the first reopen admits the sender's re-seal", set.reopen("dm"))
        assertTrue(set.add("dm"))
        assertFalse("a second reopen inside the window is refused", set.reopen("dm"))
        assertFalse(set.add("dm"))
        assertFalse("an id the set never saw has nothing to reopen", set.reopen("other"))
        now += 10_001L
        assertTrue("a new window, a new reopen", set.add("dm"))
        assertTrue(set.reopen("dm"))
    }
}
