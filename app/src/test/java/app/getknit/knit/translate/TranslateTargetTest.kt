package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a translation goes (ADR 2026-10.t2h6's 2026-10-29 amendment): into the reader's language when a model reaches
 * it, else into English, and nowhere for a message already in the reader's language. Nothing pivots through English.
 * What a message declares about its own language is here too.
 */
class TranslateTargetTest {
    private val es = TranslateLang("es")

    // A language of the registry no model translates into: read off the table, so a new model never turns this red.
    private val uk = LanguageRegistry.CODES.map(::TranslateLang).first { it !in TranslateDirection.targets }

    @Test
    fun aMessageInTheReadersLanguageGoesNowhere() {
        assertNull(TranslateTarget.of(TranslateLang.EN, TranslateLang.EN))
        assertNull(TranslateTarget.of(es, es))
    }

    @Test
    fun aMessageGoesIntoTheReadersLanguageWhenAModelReachesIt() {
        assertEquals(es, TranslateTarget.of(TranslateLang.EN, es))
        assertEquals(TranslateLang.EN, TranslateTarget.of(es, TranslateLang.EN))
    }

    @Test
    fun withNoModelIntoTheReadersLanguageAMessageGoesIntoEnglish() {
        assertEquals(TranslateLang.EN, TranslateTarget.of(es, uk))
        assertEquals(TranslateLang.EN, TranslateTarget.of(es, null))
    }

    @Test
    fun englishForAReaderNoModelReachesGoesNowhere() {
        assertNull(TranslateTarget.of(TranslateLang.EN, uk))
        assertNull(TranslateTarget.of(TranslateLang.EN, null))
    }

    @Test
    fun aDraftGoesIntoTheChatsLanguageElseEnglishElseTheOneThereIs() {
        assertEquals(TranslateLang.EN, TranslateTarget.forDraft(es, TranslateLang.EN))
        assertEquals(es, TranslateTarget.forDraft(TranslateLang.EN, es))
        assertEquals(TranslateLang.EN, TranslateTarget.forDraft(es, null))
        assertEquals(TranslateLang.EN, TranslateTarget.forDraft(es, uk))
        // An English draft with nothing said of the chat: the one language English goes into, when there is one.
        val fromEnglish = TranslateTarget.draftChoices(TranslateLang.EN)
        assertEquals(fromEnglish.singleOrNull(), TranslateTarget.forDraft(TranslateLang.EN, null))
        assertEquals(TranslateDirection.ALL.filter { it.from == TranslateLang.EN }.map { it.to }, fromEnglish)
        assertEquals(listOf(TranslateLang.EN), TranslateTarget.draftChoices(es))
    }

    @Test
    fun aMessageDeclaresTheLanguageItWasTranslatedIntoElseWhatItsWordsReadNeverAGuess() {
        val read = LanguagePick.Picked(es)
        // The composer's translation wins over the words.
        assertEquals("fr" to "es", TranslateTarget.declaration(TranslateLang("fr"), read, es))
        assertEquals("es" to "es", TranslateTarget.declaration(null, read, es))
        // Words the identifier could not tell declare no language; the reader's own is still said.
        assertEquals(null to "es", TranslateTarget.declaration(null, LanguagePick.Unsure, es))
        assertEquals(null to null, TranslateTarget.declaration(null, LanguagePick.Unsure, null))
    }
}
