package app.getknit.knit.translate

/**
 * When the composer suggests translating a draft before it is sent (ADR 2026-10.t2h6's amendment): the draft is
 * confidently in one language and the chat reads another a model reaches. What the chat reads is what its other people
 * declared on their frames ([declaredReaders], the 2026-10-29 amendment), else the language their recent messages are
 * in, never this phone's own, which are what the draft would match; that needs [MIN_THREAD_PICKS] confident reads and
 * more than half of them. A suggestion is a hint and nothing else: the person taps to translate, and reads the result
 * before sending it.
 */
object ComposePolicy {
    /** Other people's newest messages read for the chat's language. */
    const val THREAD_SAMPLE = 20

    const val MIN_THREAD_PICKS = 3

    /** A draft shorter than this, in letters outside its links and mentions, is not read: CLD2 cannot call it. */
    const val MIN_DRAFT_LETTERS = 12

    /** In a group, fewer authors than this who declared what they read say nothing about the chat. */
    const val MIN_DECLARED_AUTHORS = 2

    /**
     * What one of other people's messages declared on its frame (ADR 2026-10.t2h6's 2026-10-29 amendment): the body's
     * language and the language its author reads, registry codes; both null for nothing declared, which is an English
     * reader on a build that declares, or a build that does not.
     */
    data class Declared(
        val author: String,
        val lang: String?,
        val reads: String?,
    ) {
        /** What the author reads by their own word: what they declared reading, else the language they wrote in. */
        val reader: TranslateLang? get() = (reads ?: lang)?.let(TranslateLang::ofCode)
    }

    /**
     * The language the chat's other people read by their own declarations, newest message first, or null when they do
     * not say. In a one-to-one chat the peer's newest message says it; in a group every author among them must have
     * declared, at least [MIN_DECLARED_AUTHORS] of them and all the same language: a member who declared nothing may
     * read English or run a build that never declares, so one silence leaves the chat to its words.
     */
    fun declaredReaders(
        newestFirst: List<Declared>,
        oneToOne: Boolean,
    ): TranslateLang? {
        val byAuthor = newestFirst.distinctBy { it.author }
        if (oneToOne) return byAuthor.singleOrNull()?.reader
        if (byAuthor.size < MIN_DECLARED_AUTHORS) return null
        val readers = byAuthor.map { it.reader }
        if (readers.any { it == null }) return null
        return readers.distinct().singleOrNull()
    }

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
