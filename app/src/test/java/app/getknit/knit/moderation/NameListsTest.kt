package app.getknit.knit.moderation

import app.getknit.knit.moderation.NameScreen.Audience.MEMBERS
import app.getknit.knit.moderation.NameScreen.Audience.PUBLIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the three generated name lists (`scripts/gen-name-screen-lists.py`, ADR 2026-11.y4pc) and the
 * shipped screen built from them. The lists are generated from name data that is not in the repository, so these
 * checks are what stop a regeneration from shipping an allow-listed slur, a dead entry or a lost case.
 */
class NameListsTest {
    private val slurs by lazy { ShippedWordList.read(ShippedWordList.SLURS).toSet() }
    private val profanity by lazy { ShippedWordList.read(ShippedWordList.PROFANITY).toSet() }
    private val embeddable by lazy { ShippedWordList.read(ShippedWordList.NAME_EMBEDDABLE) }
    private val exclusions by lazy { ShippedWordList.read(ShippedWordList.NAME_EXCLUSIONS) }
    private val allowed by lazy { ShippedWordList.read(ShippedWordList.NAME_ALLOWED) }
    private val screen by lazy { ShippedWordList.nameScreen() }

    @Test
    fun everyListIsASortedUniqueSetOfLowercaseTokens() {
        listOf(embeddable, exclusions, allowed).forEach { list ->
            assertTrue("a name list should not be empty", list.isNotEmpty())
            assertEquals(list.sorted().distinct(), list)
            list.forEach { assertTrue("not a single [a-z]+ token: '$it'", it.matches(TOKEN)) }
        }
    }

    @Test
    fun anEmbeddableTermIsAListTermOfFourLettersOrMore() {
        embeddable.forEach { term ->
            assertTrue("'$term' is on neither word list", term in slurs || term in profanity)
            assertTrue("'$term' is too short to look for inside a word", term.length >= 4)
        }
    }

    @Test
    fun noAllowedNameIsASlurAndEveryOneIsAProfanityTerm() {
        allowed.forEach { name ->
            assertFalse("a slur is never allowed as a name: '$name'", name in slurs)
            assertTrue("'$name' is not a profanity term, so allowing it does nothing", name in profanity)
        }
    }

    @Test
    fun everyExclusionHoldsAnEmbeddableTerm() {
        exclusions.forEach { word ->
            assertTrue("dead exclusion, it holds no embeddable term: '$word'", embeddable.any { word.contains(it) })
        }
    }

    @Test
    fun realNamesPass() {
        listOf("Dick Smith", "Shota Tanaka", "Haji Ali", "Mary Cumming", "Cassandra", "Benedick", "Dickson", "Hancock")
            .forEach { assertFalse("a real name was flagged: '$it'", screen.check(it, PUBLIC).flagged) }
    }

    @Test
    fun theBenignWordsHandLabellingFoundPass() {
        listOf("spikeyboy", "darkeyes", "stranny2004", "Dildora", "phonkyd", "2 x 3")
            .forEach { assertFalse("'$it' was flagged", screen.check(it, PUBLIC).flagged) }
    }

    @Test
    fun handleShapesAreCaught() {
        listOf("BigD1ck69", "big_d1ck", "xXFuckXx", "fuck_lord", "bigfuckboy", "Sh1tHead")
            .forEach { assertTrue("'$it' passed", screen.check(it, PUBLIC).flagged) }
    }

    @Test
    fun aGroupNameIsReadAgainstTheSlurListAlone() {
        assertFalse(screen.check("Fuck Mondays", MEMBERS).flagged)
        assertTrue(screen.check("Fuck Mondays", PUBLIC).flagged)
    }

    private companion object {
        val TOKEN = Regex("^[a-z]+$")
    }
}
