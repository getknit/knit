package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order languages are offered in when the reader names one (ADR 2026-10.t2h6's 2026-10-29 amendment). */
class LanguageChoicesTest {
    private val en = TranslateLang.EN
    private val es = TranslateLang("es")
    private val fr = TranslateLang("fr")
    private val de = TranslateLang("de")
    private val all = setOf(en, es, fr, de)
    private val names = mapOf(en to "English", es to "Spanish", fr to "French", de to "German")

    @Test
    fun theLikelyComeFirstInTheirOrderThenTheRestByName() {
        assertEquals(listOf(fr, es, en, de), LanguageChoices.order(listOf(fr, null, es, fr), all, names::getValue))
    }

    @Test
    fun theLanguageTheReaderSaidItIsNotIsLeftOut() {
        assertEquals(listOf(es, en, de), LanguageChoices.order(listOf(fr, es), all, names::getValue, exclude = fr))
    }

    @Test
    fun aLikelyLanguageNoModelTranslatesIsNotOffered() {
        assertEquals(listOf(en, es), LanguageChoices.order(listOf(TranslateLang("gl")), setOf(en, es), names::getValue))
    }
}
