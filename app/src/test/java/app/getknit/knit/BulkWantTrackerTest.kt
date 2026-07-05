package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.BulkWantTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkWantTrackerTest {
    private var now = 0L
    private val tracker =
        BulkWantTracker(
            clock = { now },
            ttlMs = 25_000L,
            failCooldownMs = 120_000L,
            maxEntries = 3,
        )

    @Test
    fun notedPeerIsWantedUntilTheTtlExpires() {
        assertTrue(tracker.note("p"))
        assertTrue(tracker.isWanted("p"))
        now = 24_999
        assertTrue(tracker.isWanted("p"))
        now = 25_000
        assertFalse("expires exactly at TTL", tracker.isWanted("p"))
        assertFalse("expired entry stays gone", tracker.isWanted("p"))
    }

    @Test
    fun reNotingSlidesTheTtl() {
        tracker.note("p")
        now = 20_000
        tracker.note("p")
        now = 40_000 // 20s after the re-note, 40s after the first
        assertTrue(tracker.isWanted("p"))
        now = 45_000
        assertFalse(tracker.isWanted("p"))
    }

    @Test
    fun capEvictsTheOldestExpiryFirst() {
        tracker.note("a")
        now = 1_000
        tracker.note("b")
        now = 2_000
        tracker.note("c")
        now = 3_000
        tracker.note("d") // over the cap of 3 → "a" (oldest expiry) evicted
        assertFalse(tracker.isWanted("a"))
        assertTrue(tracker.isWanted("b") && tracker.isWanted("c") && tracker.isWanted("d"))
    }

    @Test
    fun failedInitiateDropsTheMarkAndCoolsReMarking() {
        tracker.note("p")
        tracker.noteFailed("p")
        assertFalse("failure drops the live mark", tracker.isWanted("p"))
        now = 119_999
        assertFalse("re-mark refused during the cooldown", tracker.note("p"))
        assertFalse(tracker.isWanted("p"))
        now = 120_000
        assertTrue("cooldown elapsed → marking resumes", tracker.note("p"))
        assertTrue(tracker.isWanted("p"))
    }

    @Test
    fun linkUpClearLiftsBothTheMarkAndTheCooldown() {
        tracker.noteFailed("p")
        tracker.clear("p") // link formed after all — the failure evidence is stale
        assertTrue(tracker.note("p"))
    }

    @Test
    fun clearAllEmptiesEverything() {
        tracker.note("a")
        tracker.noteFailed("b")
        tracker.clearAll()
        assertTrue(tracker.note("b"))
        assertTrue(tracker.isWanted("b"))
        assertFalse(tracker.isWanted("a"))
    }

    @Test
    fun debugListsLiveMarks() {
        tracker.note("p")
        assertTrue(tracker.debug().contains("p(+25000ms)"))
        assertEquals("", BulkWantTracker(clock = { 0L }).debug())
    }
}
