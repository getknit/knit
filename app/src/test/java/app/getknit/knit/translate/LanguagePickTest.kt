package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The spike's language read (ADR 2026-10.t2h6): only a confident English or Spanish answer from CLD2 counts, a
 * neighbour of Spanish is never taken for it, and an English answer over Spanish marks is unsure. The cases are the
 * corpus's own (scripts/translate-corpus/).
 */
class LanguagePickTest {
    private fun cld2(
        code: String,
        percent: Int,
        reliable: Boolean = true,
    ) = Detection(listOf(code to percent, "un" to 0, "un" to 0), reliable, 30)

    @Test
    fun theNativeAnswerParses() {
        assertEquals(
            Detection(listOf("es" to 97, "un" to 0, "un" to 0), true, 38),
            Detection.parse("es:97 un:0 un:0|1|38"),
        )
        assertEquals(Detection(listOf("un" to 0, "un" to 0, "un" to 0), false, 6), Detection.parse("un:0 un:0 un:0|0|6"))
    }

    @Test
    fun anythingElseIsNoAnswer() {
        assertNull(Detection.parse(""))
        assertNull(Detection.parse("es:97|1"))
        assertNull(Detection.parse("es:x un:0 un:0|1|3"))
        assertNull(Detection.parse("es:97 un:0 un:0|1|x"))
        assertNull(Detection.parse(":97 un:0 un:0|1|3"))
    }

    @Test
    fun aConfidentAnswerInThePairIsPicked() {
        assertEquals(LanguagePick.Picked(TranslateLang("es")), LanguagePolicy.pick(cld2("es", 97), "¿Vienes al parque más tarde?"))
        assertEquals(LanguagePick.Picked(TranslateLang.EN), LanguagePolicy.pick(cld2("en", 97), "Are you coming to the park later?"))
    }

    @Test
    fun noAnswerAnUnreliableOneOrAWeakOneIsUnsure() {
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(null, "hola"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("un", 0, reliable = false), "hola"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("es", 97, reliable = false), "ya voy"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("es", LanguagePolicy.MIN_PERCENT - 1), "algo"))
        assertEquals(LanguagePick.Picked(TranslateLang("es")), LanguagePolicy.pick(cld2("es", LanguagePolicy.MIN_PERCENT), "algo"))
    }

    @Test
    fun aNeighbourOfSpanishIsNeverTakenForIt() {
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("gl", 96), "@Ana ¿sigues en la estación?"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("ca", 97), "El tren a Barcelona sale del andén 4."))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("pt", 88), "de nada"))
    }

    @Test
    fun anEnglishAnswerOverSpanishMarksIsUnsure() {
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("en", 95), "qué fotos tan bonitas"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("en", 95), "mañana"))
        // A Spanish answer over the same marks stands.
        assertEquals(LanguagePick.Picked(TranslateLang("es")), LanguagePolicy.pick(cld2("es", 95), "qué fotos tan bonitas"))
    }

    @Test
    fun anEnglishAnswerOverAnyLetterEnglishDoesNotWriteIsUnsure() {
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("en", 95), "¡vamos!"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("en", 95), "see you at the café later"))
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("en", 95), "привіт, see you soon"))
        // An emoji or a curly quote is no letter: plain English with them stands.
        assertEquals(LanguagePick.Picked(TranslateLang.EN), LanguagePolicy.pick(cld2("en", 95), "see you soon \uD83D\uDE00 it\u2019s fine"))
    }

    @Test
    fun theRankedReadsAreTheLanguagesTheTableKnowsInCld2sOrder() {
        val detection = Detection(listOf("gl" to 60, "es" to 30, "en" to 10), reliable = false, bytes = 30)
        assertEquals(listOf(TranslateLang("es"), TranslateLang.EN), LanguagePolicy.ranked(detection))
        assertEquals(emptyList<TranslateLang>(), LanguagePolicy.ranked(null))
        assertEquals(emptyList<TranslateLang>(), LanguagePolicy.ranked(Detection(listOf("es" to 0, "un" to 0, "un" to 0), false, 3)))
    }
}
