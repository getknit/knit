package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader's language by the rule ADR 2026-10.t2h6 proposes: what they write in first, the system's list after, and
 * never the app's own language.
 */
class ReaderLanguagesTest {
    private val es = LanguagePick.Picked(TranslateLang.ES)
    private val en = LanguagePick.Picked(TranslateLang.EN)

    @Test
    fun someoneOnAnEnglishPhoneWhoWritesSpanishReadsSpanish() {
        val estimate = ReaderLanguages.estimate(listOf(es, es, en, es, LanguagePick.Unsure), listOf("en-US"))
        assertEquals(TranslateLang.ES, estimate.target)
        assertEquals(mapOf(TranslateLang.ES to 3, TranslateLang.EN to 1), estimate.written)
        assertEquals(1, estimate.unsure)
    }

    @Test
    fun tooFewOwnMessagesFallBackToTheSystemList() {
        assertEquals(TranslateLang.ES, ReaderLanguages.estimate(listOf(en, en), listOf("fr-FR", "es-MX", "en-US")).target)
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(emptyList(), listOf("EN-gb")).target)
    }

    @Test
    fun aReaderOfNeitherLanguageHasNoTarget() {
        assertNull(ReaderLanguages.estimate(listOf(es), listOf("fr-FR", "de-DE")).target)
    }
}
