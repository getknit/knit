package app.getknit.knit.data.forward

import app.getknit.knit.mesh.CarrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The global cap's choice among the largest buckets' rows (custody-carry-policy §5, keys 2 to 4): each key decides only
 * when every earlier one ties, and with no holders the order is ADR 2026-10.7mqm's.
 */
class CapEvictionTest {
    private fun spreads(vararg pairs: Pair<String, Int>): CarrySnapshot {
        val map = pairs.toMap()
        return CarrySnapshot { map[it] ?: 0 }
    }

    @Test
    fun `no candidates choose nothing`() {
        assertNull(CapEviction.choose(emptyList(), CarrySnapshot.EMPTY))
    }

    @Test
    fun `the row with more holders goes before an older one with fewer`() {
        val rows = listOf(CapCandidate("old", 1L), CapCandidate("mid", 5L), CapCandidate("spread", 9L))
        assertEquals("spread", CapEviction.choose(rows, spreads("spread" to 2, "old" to 1)))
        assertEquals("old", CapEviction.choose(rows.dropLast(1), spreads("old" to 1)))
    }

    @Test
    fun `at equal spread the oldest goes, then the lowest id`() {
        val rows = listOf(CapCandidate("b", 5L), CapCandidate("a", 5L), CapCandidate("c", 4L))
        assertEquals("c", CapEviction.choose(rows, spreads("a" to 1, "b" to 1, "c" to 1)))
        assertEquals("a", CapEviction.choose(rows.dropLast(1), CarrySnapshot.EMPTY))
    }

    @Test
    fun `with no holders the order is the frame-global one`() {
        val rows = listOf(CapCandidate("new", 9L), CapCandidate("old", 2L), CapCandidate("older", 1L))
        assertEquals("older", CapEviction.choose(rows, CarrySnapshot.EMPTY))
        assertEquals(0, CarrySnapshot.EMPTY.spread("older"))
    }

    @Test
    fun `the choice does not depend on the candidates' order`() {
        val rows = listOf(CapCandidate("x", 1L), CapCandidate("y", 2L), CapCandidate("z", 3L), CapCandidate("w", 3L))
        val carry = spreads("w" to 1, "z" to 1)
        val chosen = (0 until 24).map { CapEviction.choose(rows.shuffled(Random(it)), carry) }.toSet()
        assertEquals(setOf("w"), chosen)
    }

    @Test
    fun `ids compare as unsigned UTF-8 bytes, not UTF-16 units`() {
        // U+FF61 is one UTF-16 unit above a surrogate pair's first unit, but its UTF-8 bytes (EF BD A1) sort below the
        // pair's (F0 ...), as SQLite's BINARY and knit-ios order them.
        val bmp = "｡"
        val astral = "😀"
        assertTrue(bmp > astral)
        assertTrue(CapEviction.compareBytes(bmp, astral) < 0)
        assertEquals(bmp, CapEviction.choose(listOf(CapCandidate(astral, 1L), CapCandidate(bmp, 1L)), CarrySnapshot.EMPTY))
        assertTrue(CapEviction.compareBytes("a", "ab") < 0)
        assertEquals(0, CapEviction.compareBytes("same", "same"))
    }
}
