package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun aNeighboursReadTheLettersContradictFallsToThePriors() {
        // CLD2 sure of Serbian over Ukrainian's і (ADR 2026-10.t2h6's 2026-11-01 amendment): not "Serbian, nothing to
        // offer", which would hide Translate, but unsure, so the author's Ukrainian answers; with no prior the reader picks.
        val uk = TranslateLang("uk")
        val candidates = setOf(TranslateLang.EN, uk)
        val line = "не можу зараз говорити, я на нараді"
        assertEquals(
            Resolution(LanguagePick.Picked(uk), LanguageSource.AUTHOR),
            LanguageResolver.resolve(cld2("sr", 98), line, candidates, author = uk, chat = null),
        )
        assertEquals(Resolution(LanguagePick.Unsure, null), LanguageResolver.resolve(cld2("sr", 98), line, candidates, null, null))
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
    fun sureWordsKeepAContraryDeclarationBesideThemOnlyWhenAModelTranslatesIt() {
        // ADR 2026-11.tj49, from the emulator trial: plain Spanish CLD2 reads as English, and plain Ukrainian it reads as
        // Serbian, each declared by its sender.
        assertEquals(
            Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.TEXT, listOf(TranslateLang.EN), contested = "es"),
            LanguageResolver.resolve(cld2("en", 95), "que fotos tan bonitas", pair, null, null, declared = "es"),
        )
        val uk = TranslateLang("uk")
        assertEquals(
            Resolution(LanguagePick.Other("sr"), LanguageSource.TEXT, contested = "uk"),
            LanguageResolver.resolve(cld2("sr", 95), "нема за що", setOf(TranslateLang.EN, uk), null, null, declared = "uk"),
        )
        // The words' own language declared is no contest; nor is one no model here translates from.
        assertNull(LanguageResolver.resolve(cld2("en"), "see you later", pair, null, null, declared = "en").contested)
        assertNull(LanguageResolver.resolve(cld2("en"), "see you later", pair, null, null, declared = "el").contested)
        // Unsure words leave nothing to contest: the declaration answers, as before.
        assertNull(LanguageResolver.resolve(unread, "ya voy", pair, null, null, declared = "es").contested)
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
        // Plain words are the read: English typed on a Spanish keyboard is English, with the keyboard's Spanish kept
        // beside it for a reader the English has nowhere to go for (ADR 2026-11.tj49).
        val read = LanguageResolver.resolve(cld2("en"), "see you at the park later", pair, null, null, declared = "es")
        assertEquals(LanguagePick.Picked(TranslateLang.EN), read.pick)
        assertEquals(LanguageSource.TEXT, read.source)
        assertEquals("es", read.contested)
        assertEquals(false, read.guessed)
        // Typed in a language no model here translates from: that language, not the author's usual one in its place.
        val other = LanguageResolver.resolve(unread, "ok", pair, author = es, chat = null, declared = "el")
        assertEquals(LanguagePick.Other("el"), other.pick)
        assertEquals(LanguageSource.DECLARED, other.source)
    }
}
