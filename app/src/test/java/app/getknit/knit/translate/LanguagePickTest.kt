package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language read (ADR 2026-10.t2h6): only a confident answer from CLD2 in a language a model translates counts, a
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
        // Portuguese has a model of its own: a sure read is Portuguese, still never Spanish. A Spanish "de nada" comes
        // back to Spanish only through the hint the resolver hands CLD2 (the author's or the declared language).
        assertEquals(LanguagePick.Picked(TranslateLang("pt")), LanguagePolicy.pick(cld2("pt", 88), "de nada"))
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

    @Test
    fun theWordsNameAnyRegistryLanguageTheyAreSureOfNotOnlyOnesWithAModel() {
        // No Italian model ships: the translation's read names nothing, the named read names Italian.
        assertEquals(LanguagePick.Unsure, LanguagePolicy.pick(cld2("it", 96), "ci vediamo alla stazione domani"))
        assertEquals("it", LanguagePolicy.named(cld2("it", 96), "ci vediamo alla stazione domani"))
        assertEquals("es", LanguagePolicy.named(cld2("es", 97), "nos vemos mañana"))
        // CLD2's own spellings: a script that is its own model, and the retired code for Hebrew.
        assertEquals("zh_hant", LanguagePolicy.named(cld2("zh-Hant", 99), "明天見"))
        assertEquals("he", LanguagePolicy.named(cld2("iw", 99), "נתראה מחר"))
    }

    @Test
    fun aCyrillicNeighbourOverUkrainianLettersIsUnsure() {
        // CLD2's own answers on the Ukrainian corpus, the uk hint given: sure, and contradicted by і, ї, є or ґ.
        val stem = "Думаю, нам треба піти, поки не стемніло."
        assertNull(LanguagePolicy.named(cld2("sr", 98), stem))
        assertNull(LanguagePolicy.named(cld2("bg", 96), "на добраніч"))
        assertNull(LanguagePolicy.named(cld2("ru", 95), "Ґанок"))
        assertNull(LanguagePolicy.named(cld2("ru", 95), "ЄВРОПА"))
        // Belarusian writes і but never ї, є or ґ.
        assertEquals("be", LanguagePolicy.named(cld2("be", 96), "гарна спроба і ўсё"))
        assertNull(LanguagePolicy.named(cld2("be", 96), "пиши, як приїдеш"))
        // Letters every one of them writes contradict nothing: plain Russian stands, as does "нема за що" read as Serbian.
        assertEquals("ru", LanguagePolicy.named(cld2("ru", 97), "увидимся завтра на вокзале"))
        assertEquals("sr", LanguagePolicy.named(cld2("sr", 95), "нема за що"))
        // And a sure Ukrainian read over them stands.
        assertEquals(LanguagePick.Picked(TranslateLang("uk")), LanguagePolicy.pick(cld2("uk", 96), stem))
    }

    @Test
    fun anUnsureOrUnnamedReadNamesNothing() {
        assertNull(LanguagePolicy.named(cld2("fr", 96, reliable = false), "à demain"))
        assertNull(LanguagePolicy.named(cld2("fr", 60), "à demain"))
        assertNull(LanguagePolicy.named(cld2("un", 0), "ok"))
        assertNull(LanguagePolicy.named(cld2("xx", 99), "something"))
        assertNull(LanguagePolicy.named(null, "anything"))
        // The English contradiction holds here too.
        assertNull(LanguagePolicy.named(cld2("en", 95), "qué fotos tan bonitas"))
        assertEquals("en", LanguagePolicy.named(cld2("en", 95), "see you tomorrow"))
    }
}
