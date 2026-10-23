package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A message's language from more than its words (ADR 2026-10.t2h6's short-line amendment): the words when they are
 * sure, then the author's usual language in the chat, then the chat's, and only languages a model translates from.
 */
class LanguageResolverTest {
    private val pair = setOf(TranslateLang.EN, TranslateLang.ES)

    private fun cld2(
        code: String,
        percent: Int = 97,
        reliable: Boolean = true,
    ) = Detection(listOf(code to percent, "un" to 0, "un" to 0), reliable, 30)

    private val unread = Detection(listOf("un" to 0, "un" to 0, "un" to 0), false, 6)

    @Test
    fun sureWordsWinOverEveryPrior() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.TEXT),
            LanguageResolver.resolve(cld2("en"), "see you at the park later", pair, TranslateLang.ES, TranslateLang.ES),
        )
    }

    @Test
    fun aLineTheWordsCannotTellTakesTheAuthorsLanguageThenTheChats() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.ES), LanguageSource.AUTHOR),
            LanguageResolver.resolve(unread, "ya voy", pair, TranslateLang.ES, TranslateLang.EN),
        )
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
            LanguageResolver.resolve(unread, "omg yes", pair, null, TranslateLang.EN),
        )
        assertEquals(Resolution(LanguagePick.Unsure, null), LanguageResolver.resolve(unread, "ok", pair, null, null))
    }

    @Test
    fun aSureReadOfALanguageNoModelTranslatesIsNoReadAtAll() {
        // The corpus's own: CLD2 read "@Ana ¿sigues en la estación?" as Galician, reliably.
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.ES), LanguageSource.AUTHOR),
            LanguageResolver.resolve(cld2("gl", 96), "¿sigues en la estación?", pair, TranslateLang.ES, null),
        )
        assertEquals(Resolution(LanguagePick.Unsure, null), LanguageResolver.resolve(cld2("gl", 96), "x", pair, null, null))
        // A language the app knows but no model on hand translates from: a sure read of it is no answer either.
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
            LanguageResolver.resolve(cld2("es"), "¿sigues en la estación?", setOf(TranslateLang.EN), null, TranslateLang.EN),
        )
    }

    @Test
    fun aPriorOutsideTheCandidatesIsIgnored() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
            LanguageResolver.resolve(unread, "ok", setOf(TranslateLang.EN), TranslateLang.ES, TranslateLang.EN),
        )
    }
}
