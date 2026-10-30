package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aMessageDeclaresTheLanguageItWasTranslatedIntoElseTheOneItWasTypedIn() {
        // The composer's translation wins over the keyboard: Greek typed, then translated into Spanish, is Spanish.
        assertEquals("es" to "el", TranslateTarget.declaration(es, "el", "el"))
        // The keyboard's, any registry language: Greek declares before a Greek model ships.
        assertEquals("el" to "en", TranslateTarget.declaration(null, "el", "en"))
        // Pasted or shared in, or a keyboard that says nothing: no language; the reader's own is still said.
        assertEquals(null to "es", TranslateTarget.declaration(null, null, "es"))
        assertEquals(null to null, TranslateTarget.declaration(null, null, null))
    }

    @Test
    fun translateIsOfferedUnlessTheWordsOrADeclarationSayItIsAlreadyTheReaders() {
        fun resolved(
            lang: TranslateLang?,
            source: LanguageSource?,
        ) = Resolution(lang?.let(LanguagePick::Picked) ?: LanguagePick.Unsure, source)
        // English words for an English reader: nothing to offer. Spanish words: offered.
        assertFalse(TranslateTarget.offers(resolved(TranslateLang.EN, LanguageSource.TEXT), TranslateLang.EN))
        assertTrue(TranslateTarget.offers(resolved(es, LanguageSource.TEXT), TranslateLang.EN))
        // A declaration counts like the words; a prior's guess never hides it, nor does a language nobody can tell.
        assertFalse(TranslateTarget.offers(resolved(TranslateLang.EN, LanguageSource.DECLARED), TranslateLang.EN))
        assertTrue(TranslateTarget.offers(resolved(TranslateLang.EN, LanguageSource.AUTHOR), TranslateLang.EN))
        assertTrue(TranslateTarget.offers(resolved(TranslateLang.EN, LanguageSource.CHAT), TranslateLang.EN))
        assertTrue(TranslateTarget.offers(resolved(null, null), TranslateLang.EN))
        // A language no model here translates from has nothing to offer, whatever settled it.
        assertFalse(TranslateTarget.offers(Resolution(LanguagePick.Other("el"), LanguageSource.DECLARED), TranslateLang.EN))
        assertFalse(TranslateTarget.offers(Resolution(LanguagePick.Other("fr"), LanguageSource.TEXT), es))
    }

    @Test
    fun aKeptTranslationIsOfferedAgainOnlyIntoATargetItIsNotInYet() {
        val kept = StoredTranslation("coming?", es, TranslateLang.EN, flagged = false, guessed = false)
        assertFalse(TranslateTarget.offersAgain(kept, TranslateLang.EN))
        // The reader now reads Spanish: a Spanish message has nowhere to go.
        assertFalse(TranslateTarget.offersAgain(kept, es))
        // A reader no model reaches: Spanish goes into English, which it already is.
        assertFalse(TranslateTarget.offersAgain(kept, uk))
        val intoSpanish = StoredTranslation("¿vienes?", TranslateLang.EN, es, flagged = false, guessed = false)
        // Kept in Spanish for a reader who now reads English: an English message has nowhere to go either.
        assertFalse(TranslateTarget.offersAgain(intoSpanish, TranslateLang.EN))
        // Kept in another language than the reader's target (an older choice in Settings): offered again.
        assertTrue(TranslateTarget.offersAgain(kept.copy(to = TranslateLang("de")), TranslateLang.EN))
    }
}
