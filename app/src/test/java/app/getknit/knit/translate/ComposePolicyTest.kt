package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the composer suggests translating a draft (ADR 2026-10.t2h6's amendment): a confident draft in one language of
 * the pair, in a chat that is confidently in the other.
 */
class ComposePolicyTest {
    private val es = LanguagePick.Picked(TranslateLang.ES)
    private val en = LanguagePick.Picked(TranslateLang.EN)
    private val unsure = LanguagePick.Unsure

    @Test
    fun aChatsLanguageNeedsEnoughConfidentReadsAndAMajority() {
        assertEquals(TranslateLang.EN, ComposePolicy.threadLanguage(listOf(en, en, en, unsure, unsure)))
        assertNull(ComposePolicy.threadLanguage(listOf(en, en, unsure, unsure)))
        // Three each: no majority, no language.
        assertNull(ComposePolicy.threadLanguage(listOf(en, en, en, es, es, es)))
        assertEquals(TranslateLang.ES, ComposePolicy.threadLanguage(listOf(es, es, es, en)))
        assertNull(ComposePolicy.threadLanguage(emptyList()))
    }

    @Test
    fun aSpanishDraftInAnEnglishChatIsSuggestedEnglish() {
        assertEquals(TranslateDirection.ES_EN, ComposePolicy.suggest(es, TranslateLang.EN))
        assertEquals(TranslateDirection.EN_ES, ComposePolicy.suggest(en, TranslateLang.ES))
    }

    @Test
    fun aDraftInTheChatsLanguageOrUnreadOrAChatOfNoLanguageGetsNothing() {
        assertNull(ComposePolicy.suggest(en, TranslateLang.EN))
        assertNull(ComposePolicy.suggest(unsure, TranslateLang.EN))
        assertNull(ComposePolicy.suggest(es, null))
    }

    @Test
    fun aShortDraftOrOneOfLinksAloneIsNotRead() {
        assertFalse(ComposePolicy.readable("ya voy"))
        assertFalse(ComposePolicy.readable("mira https://getknit.app/"))
        // Twelve letters outside the link, exactly the floor, and eleven, one under it.
        assertTrue(ComposePolicy.readable("vienes al parq https://getknit.app/"))
        assertFalse(ComposePolicy.readable("vienes al par https://getknit.app/"))
    }
}
