package app.getknit.knit.ui.translate

import app.getknit.knit.call.SttModelState
import app.getknit.knit.translate.MessageTranslation
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a bubble draws for a translation (ADR 2026-10.t2h6): a guessed language keeps its "Not English?" way out. */
class TranslationShownTest {
    private fun done(
        flagged: Boolean = false,
        guessed: Boolean = false,
    ) = MessageTranslation.Done("I'm coming", TranslateLang.EN, TranslateLang.ES, flagged, ms = 10, guessed = guessed)

    @Test
    fun aGuessedTranslationIsShownAsOne() {
        assertEquals(
            TranslationShown.Text("I'm coming", TranslateLang.EN, hidden = false, guessed = true),
            translationShown(done(guessed = true), emptyMap(), filterOn = true),
        )
        assertEquals(
            TranslationShown.Text("I'm coming", TranslateLang.EN, hidden = false, guessed = false),
            translationShown(done(), emptyMap(), filterOn = true),
        )
    }

    @Test
    fun aFlaggedTranslationIsHiddenOnlyWhileFilteringIsOn() {
        assertEquals(true, (translationShown(done(flagged = true), emptyMap(), filterOn = true) as TranslationShown.Text).hidden)
        assertEquals(false, (translationShown(done(flagged = true), emptyMap(), filterOn = false) as TranslationShown.Text).hidden)
    }

    @Test
    fun aMissingModelCarriesItsDownloadsState() {
        val downloading = SttModelState.Downloading(5, 10)
        assertEquals(
            TranslationShown.NeedsModel(TranslateDirection.ES_EN, downloading),
            translationShown(
                MessageTranslation.NeedsModel(TranslateDirection.ES_EN),
                mapOf(TranslateDirection.ES_EN to downloading),
                false,
            ),
        )
        assertNull(translationShown(null, emptyMap(), filterOn = false))
    }
}
