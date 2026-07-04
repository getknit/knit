package app.getknit.knit

import app.getknit.knit.mesh.SeenSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenSetTest {
    @Test
    fun firstSightIsNewDuplicateIsNot() {
        val seen = SeenSet(clock = { 0L })
        assertTrue(seen.add("x"))
        assertFalse(seen.add("x"))
    }

    @Test
    fun idBecomesNewAgainAfterTtl() {
        var now = 0L
        val seen = SeenSet(ttlMillis = 1_000L, clock = { now })
        assertTrue(seen.add("x"))
        now = 500L
        assertFalse(seen.add("x"))
        now = 1_500L
        assertTrue(seen.add("x"))
    }

    @Test
    fun evictsOldestBeyondMaxSize() {
        val seen = SeenSet(maxSize = 2, clock = { 0L })
        seen.add("a")
        seen.add("b")
        seen.add("c") // evicts "a"
        assertTrue(seen.add("a")) // evicted → treated as new
        assertFalse(seen.add("c")) // still retained
    }
}
