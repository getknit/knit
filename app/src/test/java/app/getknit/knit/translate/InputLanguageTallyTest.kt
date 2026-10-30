package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language a draft was typed in, by the keyboard each edit was typed with (ADR 2026-10.t2h6's 2026-10-29
 * amendment): what most of it was typed with, and nothing for a draft mostly pasted or shared in.
 */
class InputLanguageTallyTest {
    private fun tally(vararg edits: Pair<Int, String?>) = InputLanguageTally().apply { edits.forEach { (n, kb) -> onEdit(n, kb) } }

    @Test
    fun aDraftTypedOnOneKeyboardIsInItsLanguage() {
        val t = tally(1 to "el", 1 to "el", 6 to "el-GR", 1 to "el")
        assertEquals("el", t.language(9))
    }

    @Test
    fun theKeyboardMostOfItWasTypedWithWinsAndACloseSplitSaysNothing() {
        assertEquals("es", tally(8 to "es-MX", 2 to "en-US").language(10))
        assertNull(tally(5 to "es", 5 to "en").language(10))
        // The edge: two thirds names the language, a character short of it does not.
        assertEquals("es", tally(20 to "es", 10 to "en").language(30))
        assertNull(tally(19 to "es", 11 to "en").language(30))
    }

    @Test
    fun anEditOfTwentyFourIsTypingAndOneMoreIsNot() {
        assertEquals("en", tally(InputLanguageTally.LARGE_INSERT to "en").language(InputLanguageTally.LARGE_INSERT))
        assertNull(tally(InputLanguageTally.LARGE_INSERT + 1 to "en").language(InputLanguageTally.LARGE_INSERT + 1))
    }

    @Test
    fun typedCharactersMustBeMoreThanHalfOfTheDraft() {
        assertEquals("en", tally(5 to "en").language(9))
        assertNull(tally(5 to "en").language(10))
    }

    @Test
    fun aMostlyPastedOrSharedDraftSaysNothing() {
        // 40 characters at once is a paste (or a restored draft, an applied translation, a phrase of voice typing).
        assertNull(tally(40 to "en", 5 to "en").language(45))
        // A pasted line (30) with more typed around it (40, a word at a time) is still the keyboard's.
        assertEquals("en", tally(30 to "en", 20 to "en", 20 to "en").language(70))
    }

    @Test
    fun aKeyboardThatNamesNoLanguageOrOneTheRegistryLacksCountsForNone() {
        assertNull(tally(10 to null).language(10))
        assertNull(tally(10 to "tlh").language(10))
        assertNull(tally(3 to "", 7 to "x-unknown").language(10))
    }

    @Test
    fun aClearedDraftStartsAgain() {
        val t = tally(10 to "el")
        t.clear()
        t.onEdit(4, "fr")
        assertEquals("fr", t.language(4))
    }

    @Test
    fun deletingKeepsWhatWasTypedSoAnUndoneTranslationIsStillTheKeyboards() {
        // Typed in Spanish; translated (the field emptied, then a large insert); undone (emptied again, the original put
        // back): a deletion takes nothing from the tally, so the draft is still the Spanish it was typed in.
        val t = tally(12 to "es", -12 to "es", 30 to "en", -30 to "en", 12 to "es")
        assertEquals("es", t.language(12))
        t.onEdit(0, "fr")
        t.onEdit(-5, "fr")
        assertEquals("es", t.language(7))
    }
}
