package app.getknit.knit

import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.displayNameFor
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasTest {

    private val format = Regex("^[A-Z][a-z]+[A-Z][a-z]+$")

    private fun randomNodeId(rng: Random): String =
        (1..8).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")

    @Test
    fun aliasIsDeterministic() {
        // Same id -> same alias on every call (and therefore on every device).
        repeat(50) {
            val id = randomNodeId(Random(it.toLong()))
            assertEquals(Alias.aliasFor(id), Alias.aliasFor(id))
        }
    }

    @Test
    fun goldenAliasesAreStable() {
        // Regression guard: any change to the hash, the index split, or list ordering moves these.
        assertEquals("JoyfulFerret", Alias.aliasFor("node123"))
        assertEquals("GleamingTopaz", Alias.aliasFor("k3p9xq2a"))
        assertEquals("CozyJade", Alias.aliasFor("aaaaaaaa"))
        assertEquals("SharpOnyx", Alias.aliasFor("zzzzzzzz"))
    }

    @Test
    fun aliasIsAlwaysTwoCapitalizedWordsNoDigits() {
        repeat(5_000) {
            val id = randomNodeId(Random(it.toLong()))
            val alias = Alias.aliasFor(id)
            assertTrue("'$alias' must be two PascalCase words", format.matches(alias))
        }
    }

    @Test
    fun aliasNeverEqualsRawNodeId() {
        repeat(1_000) {
            val id = randomNodeId(Random(it.toLong()))
            assertNotEquals(id, Alias.aliasFor(id))
        }
    }

    @Test
    fun distributionDoesNotCollapse() {
        // A sign-extension or modulo bug would collapse the range to a handful of aliases. Over many
        // distinct ids we expect almost-as-many distinct aliases.
        val rng = Random(99)
        val ids = (1..3_000).map { randomNodeId(rng) }.toSet()
        val aliases = ids.map { Alias.aliasFor(it) }.toSet()
        assertTrue(
            "expected wide spread, got ${aliases.size} aliases from ${ids.size} ids",
            aliases.size > ids.size * 2 / 3,
        )
    }

    @Test
    fun blockedCandidatesAreRerolledDeterministically() {
        val id = "node123"
        val normal = Alias.aliasFor(id)
        // Excluding the normal result forces the deterministic re-roll path.
        val rerolled = Alias.aliasForExcluding(id, setOf(normal))
        assertNotEquals(normal, rerolled)
        assertTrue(format.matches(rerolled))
        // Still deterministic with the same exclusion set.
        assertEquals(rerolled, Alias.aliasForExcluding(id, setOf(normal)))
    }

    @Test
    fun displayNameForUsesAliasOnlyWhenNameBlank() {
        val id = "node123"
        val alias = Alias.aliasFor(id)
        assertEquals(alias, displayNameFor(null, id))
        assertEquals(alias, displayNameFor("", id))
        assertEquals(alias, displayNameFor("   ", id))
        assertEquals("Alice", displayNameFor("Alice", id))
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
