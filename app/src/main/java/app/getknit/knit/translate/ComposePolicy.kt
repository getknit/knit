package app.getknit.knit.translate

/**
 * When the composer suggests translating a draft before it is sent (ADR 2026-10.t2h6's amendment): the draft is
 * confidently in one language of the pair and the chat is confidently in the other. The chat's language is read from
 * other people's recent messages, never this phone's own, which are what the draft would match; it needs
 * [MIN_THREAD_PICKS] confident reads and more than half of them. A suggestion is a hint and nothing else: the reader
 * taps to translate, and reads the result before sending it.
 */
object ComposePolicy {
    /** Other people's newest messages read for the chat's language. */
    const val THREAD_SAMPLE = 20

    const val MIN_THREAD_PICKS = 3

    /** A draft shorter than this, in letters outside its links and mentions, is not read: CLD2 cannot call it. */
    const val MIN_DRAFT_LETTERS = 12

    /** The language most of [picks] are in, or null when too few are confident or none holds a majority. */
    fun threadLanguage(picks: List<LanguagePick>): TranslateLang? = LanguagePolicy.majority(picks, MIN_THREAD_PICKS)

    /** Whether [draft] holds enough words for the identifier to read. */
    fun readable(
        draft: String,
        mentions: List<IntRange> = emptyList(),
    ): Boolean = VerbatimSpans.wordsOnly(draft, mentions).count { it.isLetter() } >= MIN_DRAFT_LETTERS

    /** The direction to suggest for a draft read as [draft] in a chat written in [thread], or null for none. */
    fun suggest(
        draft: LanguagePick,
        thread: TranslateLang?,
    ): TranslateDirection? {
        val from = (draft as? LanguagePick.Picked)?.lang ?: return null
        if (thread == null || thread == from) return null
        return TranslateDirection.of(from, thread)
    }
}
