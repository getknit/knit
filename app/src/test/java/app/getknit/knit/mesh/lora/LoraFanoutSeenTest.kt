package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fan-out's dedup (#239): held until the post's own freshness deadline, handed back whole when it never flew. */
class LoraFanoutSeenTest {
    private val fresh = LoraFramePolicy.FRESH_MS

    @Test
    fun anAiredPostIsHeldUntilItsDeadlineAndNoLonger() {
        val seen = LoraFanoutSeen()
        assertTrue(seen.add("k", freshUntil = fresh, now = 0))
        assertFalse("past the router's ten minutes, still fresh: held", seen.add("k", freshUntil = fresh, now = 11 * 60_000L))
        assertFalse("at the deadline itself: held, as `StaleAtSend.STALE` still lets it fly", seen.add("k", fresh, now = fresh))
        assertTrue("past it the post could not air anyway: free", seen.add("k", fresh, now = fresh + 1))
    }

    @Test
    fun aSlotHandedBackIsFreeEveryTime() {
        val seen = LoraFanoutSeen()
        repeat(3) {
            assertTrue(seen.add("k", freshUntil = fresh, now = 0))
            seen.reopen("k")
        }
        assertFalse(seen.contains("k", now = 0))
    }

    @Test
    fun everyAddDropsWhatIsPastItsDeadline() {
        val seen = LoraFanoutSeen()
        seen.add("old", freshUntil = 1_000, now = 0)
        seen.add("new", freshUntil = fresh, now = 0)
        seen.add("newer", freshUntil = fresh + 2_000, now = 2_000)
        assertEquals("the stale one went on the next add", 2, seen.size())
        assertTrue(seen.contains("new", now = 2_000))
    }

    @Test
    fun theBackstopDropsTheOldestRecorded() {
        val seen = LoraFanoutSeen(maxSize = 2)
        seen.add("a", fresh, 0)
        seen.add("b", fresh, 0)
        seen.add("c", fresh, 0)
        assertEquals(2, seen.size())
        assertFalse(seen.contains("a", 0))
        assertTrue(seen.contains("c", 0))
    }
}
