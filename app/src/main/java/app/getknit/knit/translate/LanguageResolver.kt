package app.getknit.knit.translate

/** What settled a message's language. */
enum class LanguageSource {
    /** CLD2 read it from the words, confidently. */
    TEXT,

    /** The words could not tell; the language its author declared on the frame did. */
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
 * A message's language from more than its words (ADR 2026-10.t2h6's short-line amendment). A short line is where an
 * identifier guesses, and every language added gives it more neighbours to confuse, so the words are read first and,
 * when they cannot tell, the language its author declared on the frame, then the author's own usual language in this
 * chat, then the chat's, settles it; only then is the reader asked. A declaration is the sender's claim, so plain words
 * overrule it. Only a language a model translates from ([candidates]) is an answer: a confident read of a language
 * outside them (Spanish read as Galician) is no read at all, and falls to the priors like an unsure one.
 */
object LanguageResolver {
    fun resolve(
        detection: Detection?,
        text: String,
        candidates: Set<TranslateLang>,
        author: TranslateLang?,
        chat: TranslateLang?,
        declared: TranslateLang? = null,
    ): Resolution {
        val ranked = LanguagePolicy.ranked(detection).filter { it in candidates }
        val read = LanguagePolicy.pick(detection, text)
        if (read is LanguagePick.Picked && read.lang in candidates) return Resolution(read, LanguageSource.TEXT, ranked)
        val priors = listOf(LanguageSource.DECLARED to declared, LanguageSource.AUTHOR to author, LanguageSource.CHAT to chat)
        for ((source, lang) in priors) {
            if (lang != null && lang in candidates) return Resolution(LanguagePick.Picked(lang), source, ranked)
        }
        return Resolution(LanguagePick.Unsure, null, ranked)
    }
}
