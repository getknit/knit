package app.getknit.knit.moderation

import app.getknit.knit.moderation.NameScreen.Audience.MEMBERS
import app.getknit.knit.moderation.NameScreen.Audience.PUBLIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name screen's reading of a name (ADR 2026-11.y4pc), over small lists so each rule is seen alone. */
class NameScreenTest {
    private val screen =
        NameScreen(
            slurs = listOf("pikey", "kike"),
            profanity = listOf("fuck", "dick", "pikey", "kike", "shit"),
            embeddable = listOf("fuck", "pikey"),
            exclusions = listOf("spikey"),
            allowedNames = listOf("dick", "pikey"),
        )

    private fun public(name: String) = screen.check(name, PUBLIC)

    private fun members(name: String) = screen.check(name, MEMBERS)

    @Test
    fun aPlainNameIsAllowed() {
        assertFalse(public("Alice Smith").flagged)
        assertFalse(public("").flagged)
        assertFalse(public("   ").flagged)
    }

    @Test
    fun aTermAsAWholeWordIsFlagged() {
        assertTrue(public("Mr Fuck").flagged)
        assertTrue(public("fuck_lord").flagged)
        assertTrue(public("Fuck69").flagged)
        assertTrue(public("sh1t").flagged)
    }

    @Test
    fun camelCaseAndDigitRunsSplitAHandleIntoWords() {
        // "dick" is allow-listed as a real name, so "shit" carries the split cases.
        assertTrue(public("BigShit").flagged)
        assertTrue(public("xXShitXx").flagged)
        assertTrue(public("Shit4Brains").flagged)
        assertFalse(LexicalTextFilter(listOf("shit")).check("BigShit").flagged) // the message filter alone misses it
    }

    @Test
    fun anEmbeddableTermIsFoundInsideAWordAndANonEmbeddableOneIsNot() {
        assertTrue(public("bigfuckboy").flagged)
        assertTrue(public("pikeyboy").flagged)
        assertFalse(public("bigshitboy").flagged) // "shit" is not embeddable (Akshit)
    }

    @Test
    fun anExclusionCutsTheWordBeforeTheInsideLookAndTheRestIsStillRead() {
        assertFalse(public("spikeyboy").flagged)
        assertTrue(public("spikeyfuck").flagged)
    }

    @Test
    fun aDigitIsALeetLetterInsideAWordOnlyBetweenLetters() {
        val shit = NameScreen(emptyList(), listOf("shit"), listOf("shit"), emptyList(), emptyList())
        assertTrue(shit.check("ash1tlord", PUBLIC).flagged) // 1 between h and t is "i": "ashitlord"
        // Next to another digit, 1 and 7 separate: "ash" and "tripathi", not "ashittripathi".
        assertFalse(shit.check("ash17tripathi", PUBLIC).flagged)
        assertTrue(public("p1keyboy").flagged)
    }

    @Test
    fun aRealNameOnTheAllowListPassesAsAWholeWordButNeverWhenItIsASlur() {
        assertFalse(public("Dick Smith").flagged)
        assertFalse(public("Dick").flagged)
        assertFalse(public("JohnDick").flagged)
        // Only as written: a leet spelling is not a name, so it is read against the list as usual.
        assertTrue(public("BigD1ck69").flagged)
        assertTrue(public("D!ck").flagged)
        // "pikey" is on the allow-list and the slur list: the slur list wins.
        assertEquals(TextVerdict.Category.SLUR, public("Pikey").category)
    }

    @Test
    fun aGroupNameTakesTheSlurListAlone() {
        assertFalse(members("Fuck Mondays").flagged)
        assertEquals(TextVerdict.Category.SLUR, members("Kike Club").category)
        assertEquals(TextVerdict.Category.SLUR, members("pikeyboys").category)
    }

    @Test
    fun aHitReportsWhichListTrippedIt() {
        assertEquals(TextVerdict.Category.SLUR, public("kike").category)
        assertEquals(TextVerdict.Category.PROFANITY, public("fuck").category)
        assertEquals(TextVerdict.Category.NONE, public("Alice").category)
    }
}
