package app.getknit.knit

import app.getknit.knit.moderation.LexicalTextFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalTextFilterTest {
    private val filter = LexicalTextFilter(blockedWords = listOf("shit", "fuck", "ass", "bullshit"))

    private fun flagged(text: String) = filter.check(text).flagged

    @Test
    fun cleanTextIsAllowed() {
        assertFalse(flagged("hello there, how are you?"))
        assertFalse(flagged(""))
        assertFalse(flagged("    "))
    }

    @Test
    fun plainProfanityIsFlagged() {
        assertTrue(flagged("this is shit"))
        assertTrue(flagged("FUCK that"))
        assertTrue(flagged("bullshit"))
    }

    @Test
    fun leetspeakIsFlagged() {
        assertTrue(flagged("sh1t")) // 1 -> i
        assertTrue(flagged("\$h!t")) // $ -> s, ! -> i
        assertTrue(flagged("a\$\$")) // $ -> s
        assertTrue(flagged("5h1t")) // 5 -> s, 1 -> i
    }

    @Test
    fun diacriticsAndHomoglyphsAreFolded() {
        assertTrue(flagged("shít"))
        assertTrue(flagged("ＦＵＣＫ")) // full-width characters fold under NFKD
    }

    @Test
    fun stretchedLettersAreCollapsed() {
        assertTrue(flagged("shiiiit"))
        assertTrue(flagged("fuuuuck"))
    }

    @Test
    fun spacedOutLettersAreRejoined() {
        assertTrue(flagged("f u c k you"))
        assertTrue(flagged("s.h.i.t"))
        assertTrue(flagged("f-u-c-k"))
    }

    @Test
    fun wholeWordMatchAvoidsFalsePositives() {
        // The "Scunthorpe problem": substrings of clean words must not trip the filter.
        assertFalse(flagged("classic assistant in the grass"))
        assertFalse(flagged("pass the salt"))
        assertFalse(flagged("as soon as possible")) // "as" must not collide with "ass"
        assertFalse(flagged("Scunthorpe"))
    }

    @Test
    fun standaloneShortWordStillMatches() {
        assertTrue(flagged("you ass"))
    }

    @Test
    fun allowListOverridesAMatch() {
        val tuned = LexicalTextFilter(blockedWords = listOf("ass"), allowedTerms = listOf("ass"))
        assertFalse(tuned.check("you ass").flagged)
    }

    @Test
    fun emptyBlockListAllowsEverything() {
        val empty = LexicalTextFilter(blockedWords = emptyList())
        assertFalse(empty.check("shit fuck ass").flagged)
    }
}
