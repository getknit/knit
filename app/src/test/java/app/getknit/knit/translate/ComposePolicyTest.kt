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
    private val es = LanguagePick.Picked(TranslateLang("es"))
    private val en = LanguagePick.Picked(TranslateLang.EN)
    private val unsure = LanguagePick.Unsure

    @Test
    fun aChatsLanguageNeedsEnoughConfidentReadsAndAMajority() {
        assertEquals(TranslateLang.EN, ComposePolicy.threadLanguage(listOf(en, en, en, unsure, unsure)))
        assertNull(ComposePolicy.threadLanguage(listOf(en, en, unsure, unsure)))
        // Three each: no majority, no language.
        assertNull(ComposePolicy.threadLanguage(listOf(en, en, en, es, es, es)))
        assertEquals(TranslateLang("es"), ComposePolicy.threadLanguage(listOf(es, es, es, en)))
        assertNull(ComposePolicy.threadLanguage(emptyList()))
    }

    @Test
    fun aSpanishDraftInAnEnglishChatIsSuggestedEnglish() {
        assertEquals(TranslateDirections.ES_EN, ComposePolicy.suggest(es, TranslateLang.EN))
        assertEquals(TranslateDirections.EN_ES, ComposePolicy.suggest(en, TranslateLang("es")))
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

    private fun declared(
        author: String,
        lang: String? = null,
        reads: String? = null,
    ) = ComposePolicy.Declared(author, lang, reads)

    @Test
    fun aPeersNewestDeclarationSaysWhatTheyRead() {
        val es = TranslateLang("es")
        // Wrote Spanish, said nothing else: they read Spanish.
        assertEquals(es, ComposePolicy.declaredReaders(listOf(declared("ana", lang = "es")), oneToOne = true))
        // Wrote English (nothing declared for it) but said they read Spanish: a translated draft.
        assertEquals(es, ComposePolicy.declaredReaders(listOf(declared("ana", reads = "es")), oneToOne = true))
        // Their newest message is what counts.
        assertEquals(
            TranslateLang.EN,
            ComposePolicy.declaredReaders(
                listOf(declared("ana", lang = "es", reads = "en"), declared("ana", lang = "es")),
                oneToOne = true,
            ),
        )
        // Nothing declared on the newest: English, or a build that never declares. The words decide.
        assertNull(ComposePolicy.declaredReaders(listOf(declared("ana"), declared("ana", lang = "es")), oneToOne = true))
        assertNull(ComposePolicy.declaredReaders(emptyList(), oneToOne = true))
    }

    @Test
    fun aGroupReadsALanguageOnlyWhenEveryAuthorDeclaredIt() {
        val es = TranslateLang("es")
        assertEquals(
            es,
            ComposePolicy.declaredReaders(listOf(declared("ana", lang = "es"), declared("luis", reads = "es"), declared("ana")), false),
        )
        // One member declared nothing: they may read English.
        assertNull(ComposePolicy.declaredReaders(listOf(declared("ana", lang = "es"), declared("sam")), oneToOne = false))
        // Two languages: no hint.
        assertNull(ComposePolicy.declaredReaders(listOf(declared("ana", lang = "es"), declared("kim", reads = "en")), oneToOne = false))
        // One author alone is too few to speak for a group.
        assertNull(ComposePolicy.declaredReaders(listOf(declared("ana", lang = "es")), oneToOne = false))
    }
}
