package app.getknit.knit.translate

/** What settled a message's language. */
enum class LanguageSource {
    /** CLD2 read it from the words, confidently. */
    TEXT,

    /** The words could not tell; the language its sender typed it in, declared on the frame, did. */
    DECLARED,

    /** The words could not tell and nothing was declared; the language this author writes in this chat did. */
    AUTHOR,

    /** None of those could; the language the chat is in did. */
    CHAT,
}

/**
 * A message's language as resolved, and what settled it; [source] is null when nothing could. [ranked] is CLD2's own
 * order of the languages it named, for a picker to put first when the reader is asked. [contested] is the other reading
 * when the words were sure of one language and the sender declared another a model translates from (ADR 2026-11.tj49),
 * a registry code: the declared language on a read of the words, the words' language on a read the declaration
 * overruled ([TranslateTarget.forReader] picks between them).
 */
data class Resolution(
    val pick: LanguagePick,
    val source: LanguageSource?,
    val ranked: List<TranslateLang> = emptyList(),
    val contested: String? = null,
) {
    /**
     * A prior about the person or the chat settled it, or the sender's declaration overruled sure words: not the words
     * or an uncontested declaration, so the reader may say otherwise.
     */
    val guessed: Boolean
        get() =
            source == LanguageSource.AUTHOR || source == LanguageSource.CHAT || (source == LanguageSource.DECLARED && contested != null)
}

/**
 * A message's language from more than its words (ADR 2026-10.t2h6's short-line amendment and its 2026-10-29
 * amendment). A short line is where an identifier guesses, and every language added gives it more neighbours to
 * confuse, so the words are read first, with the likeliest prior already handed to CLD2 as a hint, which turns a
 * neighbour's misread back into the language the prior names. Then:
 *
 * 1. Words sure of a language a model translates from ([candidates]): that language.
 * 2. Words sure of another registry language, even with the hint behind them: that language, with no model to offer
 *    ([LanguagePick.Other]), never a prior's guess in its place.
 * 3. Words unsure: the language its sender typed it in, declared on the frame ([declared], a registry code, which may
 *    name a language with no model here), then the author's usual language in this chat, then the chat's.
 * 4. Only then is the reader asked.
 *
 * Sure words that disagree with a declared language a model translates from are still the answer, with the declaration
 * kept beside them ([Resolution.contested]): which one a reader translates from depends on the reader (ADR 2026-11.tj49,
 * which overturns the 2026-10-29 amendment's "sure words win" for that case).
 */
object LanguageResolver {
    fun resolve(
        detection: Detection?,
        text: String,
        candidates: Set<TranslateLang>,
        author: TranslateLang?,
        chat: TranslateLang?,
        declared: String? = null,
    ): Resolution {
        val ranked = LanguagePolicy.ranked(detection).filter { it in candidates }
        sureRead(detection, text, candidates)?.let { (pick, code) ->
            // The sender's declaration, kept beside sure words of another language when a model translates from it.
            val rival = declared?.let(TranslateLang::ofCode)?.takeIf { it in candidates && it.code != code }
            return Resolution(pick, LanguageSource.TEXT, ranked, rival?.code)
        }
        declared?.let { code ->
            val lang = TranslateLang.ofCode(code)?.takeIf { it in candidates }
            return Resolution(lang?.let(LanguagePick::Picked) ?: LanguagePick.Other(code), LanguageSource.DECLARED, ranked)
        }
        val priors = listOf(LanguageSource.AUTHOR to author, LanguageSource.CHAT to chat)
        for ((source, lang) in priors) {
            if (lang != null && lang in candidates) return Resolution(LanguagePick.Picked(lang), source, ranked)
        }
        return Resolution(LanguagePick.Unsure, null, ranked)
    }

    // Steps 1 and 2: what sure words name, with its registry code; null when the words are unsure.
    private fun sureRead(
        detection: Detection?,
        text: String,
        candidates: Set<TranslateLang>,
    ): Pair<LanguagePick, String>? {
        val read = LanguagePolicy.pick(detection, text)
        if (read is LanguagePick.Picked && read.lang in candidates) return read to read.lang.code
        val named = LanguagePolicy.named(detection, text) ?: return null
        val lang = TranslateLang.ofCode(named)?.takeIf { it in candidates }
        return (lang?.let(LanguagePick::Picked) ?: LanguagePick.Other(named)) to named
    }
}
