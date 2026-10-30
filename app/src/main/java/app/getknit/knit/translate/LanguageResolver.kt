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
 * order of the languages it named, for a picker to put first when the reader is asked.
 */
data class Resolution(
    val pick: LanguagePick,
    val source: LanguageSource?,
    val ranked: List<TranslateLang> = emptyList(),
) {
    /** A prior about the person or the chat settled it, not the words or a declaration: the reader may say otherwise. */
    val guessed: Boolean get() = source == LanguageSource.AUTHOR || source == LanguageSource.CHAT
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
        val read = LanguagePolicy.pick(detection, text)
        if (read is LanguagePick.Picked && read.lang in candidates) return Resolution(read, LanguageSource.TEXT, ranked)
        LanguagePolicy.named(detection, text)?.let { named ->
            val lang = TranslateLang.ofCode(named)?.takeIf { it in candidates }
            return Resolution(lang?.let(LanguagePick::Picked) ?: LanguagePick.Other(named), LanguageSource.TEXT, ranked)
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
}
