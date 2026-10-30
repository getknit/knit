package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A message's language from more than its words (ADR 2026-10.t2h6's short-line and 2026-10-29 amendments): the words
 * when they are sure, any registry language (one with no model has nothing to offer); then the language it was typed
 * in, declared on the frame; then the author's usual language in the chat, then the chat's.
 */
class LanguageResolverTest {
    private val pair = setOf(TranslateLang.EN, TranslateLang("es"))

    private fun cld2(
        code: String,
        percent: Int = 97,
        reliable: Boolean = true,
    ) = Detection(listOf(code to percent, "un" to 0, "un" to 0), reliable, 30)

    private val unread = Detection(listOf("un" to 0, "un" to 0, "un" to 0), false, 6)

    @Test
    fun sureWordsWinOverEveryPrior() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.TEXT, ranked = listOf(TranslateLang.EN)),
            LanguageResolver.resolve(cld2("en"), "see you at the park later", pair, TranslateLang("es"), TranslateLang("es")),
        )
    }

    @Test
    fun aLineTheWordsCannotTellTakesTheAuthorsLanguageThenTheChats() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang("es")), LanguageSource.AUTHOR),
            LanguageResolver.resolve(unread, "ya voy", pair, TranslateLang("es"), TranslateLang.EN),
        )
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
            LanguageResolver.resolve(unread, "omg yes", pair, null, TranslateLang.EN),
        )
        assertEquals(Resolution(LanguagePick.Unsure, null), LanguageResolver.resolve(unread, "ok", pair, null, null))
    }

    @Test
    fun aSureReadOfALanguageNoModelTranslatesIsThatLanguageWithNothingToOffer() {
        // The hint has had its say inside CLD2 (an author's Spanish turns a Galician misread back into Spanish), so a read
        // still sure of Galician stands: no model, and no prior's guess in its place.
        assertEquals(
            Resolution(LanguagePick.Other("gl"), LanguageSource.TEXT),
            LanguageResolver.resolve(cld2("gl", 96), "¿sigues en la estación?", pair, TranslateLang("es"), null),
        )
        // A language the table knows but no model on hand translates from: the same.
        assertEquals(
            Resolution(LanguagePick.Other("es"), LanguageSource.TEXT),
            LanguageResolver.resolve(cld2("es"), "¿sigues en la estación?", setOf(TranslateLang.EN), null, TranslateLang.EN),
        )
        // An unsure read is still no read: the priors answer.
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang("es")), LanguageSource.AUTHOR),
            LanguageResolver.resolve(cld2("gl", 96, reliable = false), "x", pair, TranslateLang("es"), null),
        )
    }

    @Test
    fun aPriorOutsideTheCandidatesIsIgnored() {
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
            LanguageResolver.resolve(unread, "ok", setOf(TranslateLang.EN), TranslateLang("es"), TranslateLang.EN),
        )
    }

    @Test
    fun theLanguageItWasTypedInSettlesWhatTheWordsCannotAndYieldsToWhatTheyCan() {
        val es = TranslateLang("es")
        val typed = LanguageResolver.resolve(unread, "ya voy", pair, author = TranslateLang.EN, chat = TranslateLang.EN, declared = "es")
        assertEquals(LanguagePick.Picked(es), typed.pick)
        assertEquals(LanguageSource.DECLARED, typed.source)
        assertEquals(false, typed.guessed)
        // Plain words overrule the keyboard: English typed on a Spanish keyboard is English.
        val read = LanguageResolver.resolve(cld2("en"), "see you at the park later", pair, null, null, declared = "es")
        assertEquals(LanguagePick.Picked(TranslateLang.EN), read.pick)
        assertEquals(LanguageSource.TEXT, read.source)
        // Typed in a language no model here translates from: that language, not the author's usual one in its place.
        val other = LanguageResolver.resolve(unread, "ok", pair, author = es, chat = null, declared = "el")
        assertEquals(LanguagePick.Other("el"), other.pick)
        assertEquals(LanguageSource.DECLARED, other.source)
    }
}
