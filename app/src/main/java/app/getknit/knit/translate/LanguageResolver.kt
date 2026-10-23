package app.getknit.knit.translate

/** What settled a message's language. */
enum class LanguageSource {
    /** CLD2 read it from the words, confidently. */
    TEXT,

    /** The words could not tell; the language this author writes in this chat did. */
    AUTHOR,

    /** Neither could; the language the chat is in did. */
    CHAT,
}

/** A message's language as resolved, and what settled it; [source] is null when nothing could. */
data class Resolution(
    val pick: LanguagePick,
    val source: LanguageSource?,
)

/**
 * A message's language from more than its words (ADR 2026-10.t2h6's short-line amendment). A short line is where an
 * identifier guesses, and every language added gives it more neighbours to confuse, so the words are read first and,
 * when they cannot tell, the author's own usual language in this chat, then the chat's, settles it; only then is the
 * reader asked. Only a language a model translates from ([candidates]) is an answer: a confident read of a language
 * outside them (Spanish read as Galician) is no read at all, and falls to the priors like an unsure one.
 */
object LanguageResolver {
    fun resolve(
        detection: Detection?,
        text: String,
        candidates: Set<TranslateLang>,
        author: TranslateLang?,
        chat: TranslateLang?,
    ): Resolution {
        val read = LanguagePolicy.pick(detection, text)
        if (read is LanguagePick.Picked && read.lang in candidates) return Resolution(read, LanguageSource.TEXT)
        author?.takeIf { it in candidates }?.let { return Resolution(LanguagePick.Picked(it), LanguageSource.AUTHOR) }
        chat?.takeIf { it in candidates }?.let { return Resolution(LanguagePick.Picked(it), LanguageSource.CHAT) }
        return Resolution(LanguagePick.Unsure, null)
    }
}
