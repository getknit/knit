package app.getknit.knit.ui.translate

import app.getknit.knit.call.SttModelState
import app.getknit.knit.translate.MessageTranslation
import app.getknit.knit.translate.StoredTranslation
import app.getknit.knit.translate.TranslateDirections
import app.getknit.knit.translate.TranslateLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a bubble draws for a translation (ADR 2026-10.t2h6): a guessed language keeps its "Not English?" way out, one
 * kept on the row is shown with nothing asked, and what this process holds for the message wins over it.
 */
class TranslationShownTest {
    private val es = TranslateLang("es")

    private fun done(
        flagged: Boolean = false,
        guessed: Boolean = false,
    ) = MessageTranslation.Done("I'm coming", TranslateLang.EN, es, flagged, ms = 10, guessed = guessed)

    private fun stored(flagged: Boolean = false) = StoredTranslation("We're by the tree", es, TranslateLang.EN, flagged, guessed = true)

    private fun shown(
        state: MessageTranslation?,
        stored: StoredTranslation? = null,
        filterOn: Boolean = true,
        reader: TranslateLang? = TranslateLang.EN,
    ) = translationShown(state, stored, emptyMap(), filterOn, reader)

    @Test
    fun aGuessedTranslationIsShownAsOne() {
        assertEquals(TranslationShown.Text("I'm coming", TranslateLang.EN, es, hidden = false, guessed = true), shown(done(guessed = true)))
        assertEquals(TranslationShown.Text("I'm coming", TranslateLang.EN, es, hidden = false, guessed = false), shown(done()))
    }

    @Test
    fun aFlaggedTranslationIsHiddenOnlyWhileFilteringIsOn() {
        assertEquals(true, (shown(done(flagged = true), filterOn = true) as TranslationShown.Text).hidden)
        assertEquals(false, (shown(done(flagged = true), filterOn = false) as TranslationShown.Text).hidden)
        assertEquals(true, (shown(null, stored(flagged = true), filterOn = true) as TranslationShown.Text).hidden)
        assertEquals(false, (shown(null, stored(flagged = true), filterOn = false) as TranslationShown.Text).hidden)
    }

    @Test
    fun aTranslationKeptOnTheRowIsShownWithNothingAsked() {
        assertEquals(
            TranslationShown.Text("We're by the tree", es, TranslateLang.EN, hidden = false, guessed = true),
            shown(null, stored()),
        )
    }

    @Test
    fun whatThisProcessHoldsWinsOverTheRow() {
        assertEquals(TranslationShown.Running, shown(MessageTranslation.Running, stored()))
        assertEquals("I'm coming", (shown(done(), stored()) as TranslationShown.Text).text)
        assertEquals(
            TranslationShown.Failed(unusable = false),
            shown(MessageTranslation.Failed(MessageTranslation.Failed.Reason.ENGINE), stored()),
        )
    }

    @Test
    fun nowhereToGoSaysWhetherItIsTheReadersLanguageAlready() {
        assertEquals(TranslationShown.NoTarget(TranslateLang.EN, already = true), shown(MessageTranslation.NoTarget(TranslateLang.EN)))
        assertEquals(
            TranslationShown.NoTarget(TranslateLang.EN, already = false),
            shown(MessageTranslation.NoTarget(TranslateLang.EN), reader = TranslateLang("uk")),
        )
    }

    @Test
    fun aMissingModelCarriesItsDownloadsState() {
        val downloading = SttModelState.Downloading(5, 10)
        assertEquals(
            TranslationShown.NeedsModel(TranslateDirections.ES_EN, downloading),
            translationShown(
                MessageTranslation.NeedsModel(TranslateDirections.ES_EN),
                null,
                mapOf(TranslateDirections.ES_EN to downloading),
                false,
                null,
            ),
        )
        assertNull(shown(null))
    }
}
