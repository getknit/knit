package app.getknit.knit.data.emoji

/**
 * The emoji the picker can offer: [entries] in Unicode order (already filtered to what this device can
 * render by [EmojiCatalogLoader]), the [browse] subset the grid shows (skin-tone variants collapsed away),
 * that subset [byGroup] in group order, and a name-and-keyword [search] over **all** entries so a tone
 * variant is one query away ("thumbs up: medium skin tone") without cluttering the grid. [localized] lays an
 * app language's names and keywords over the English catalog. Pure Kotlin; the Android half is
 * [AndroidGlyphCheck].
 */
class EmojiCatalog(
    val entries: List<EmojiEntry>,
) {
    val browse: List<EmojiEntry> = entries.filter { !it.toneVariant }

    /** Insertion-ordered (Unicode group order); a group with nothing renderable is simply absent. */
    val byGroup: Map<EmojiGroup, List<EmojiEntry>> = browse.groupBy { it.group }

    /**
     * This catalog in another language: each entry [overlay] names takes that name and puts its English one
     * in [EmojiEntry.englishName]; its keywords are the overlay's, then the English ones. An entry the overlay
     * lacks stays English. Order, groups and tone flags are untouched.
     */
    fun localized(overlay: Map<String, LocalizedEmoji>): EmojiCatalog =
        if (overlay.isEmpty()) {
            this
        } else {
            EmojiCatalog(
                entries.map { e ->
                    val l = overlay[e.emoji] ?: return@map e
                    e.copy(name = l.name, englishName = e.name, keywords = (l.keywords + e.keywords).distinct())
                },
            )
        }

    /**
     * Search over the names (the app language's and, when localized, the English one) and the keywords,
     * case- and accent-insensitive ([EmojiFold]): every whitespace-separated token of [query] must occur in
     * a name or begin a keyword or one of its words (so "red heart" finds "red heart" but not "heart",
     * "100" finds 💯 by its keyword, and "corazon" finds "corazón"). A keyword is prefix-matched, never substring-matched, or
     * "one" would find every "phone" and "bone". Ranked exact name < name prefix < name word prefix <
     * exact keyword < keyword prefix < the rest (a name substring, or tokens matched across name and
     * keywords), ties in Unicode order (a base emoji lands before its tone variants). A query that *is* an
     * entry's emoji — a paste — returns that entry first. Blank → empty. At most [limit] results.
     */
    fun search(
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<EmojiEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pasted = entries.firstOrNull { it.emoji == trimmed }
        val q = EmojiFold.fold(trimmed)
        val tokens = q.split(WHITESPACE).filter { it.isNotEmpty() }
        val ranked =
            entries
                .asSequence()
                .filter { e -> e !== pasted && tokens.all { t -> matches(e, t) } }
                .map { e -> rank(e, q, tokens.first()) to e }
                .sortedBy { it.first } // stable: Unicode order survives within a rank
                .map { it.second }
                .take(if (pasted != null) limit - 1 else limit)
                .toList()
        return if (pasted != null) listOf(pasted) + ranked else ranked
    }

    /**
     * A token matches a name substring, a keyword prefix (a whole keyword with a break character in it:
     * "-1", "1:30", "wi-fi") or the prefix of a keyword's word ("serve" in "soft-serve").
     */
    private fun matches(
        e: EmojiEntry,
        t: String,
    ): Boolean =
        e.searchKey.contains(t) ||
            e.englishKey?.contains(t) == true ||
            e.keywordKeys.any { it.startsWith(t) } ||
            e.keywordWords.any { it.startsWith(t) }

    private fun rank(
        e: EmojiEntry,
        q: String,
        firstToken: String,
    ): Int =
        minOf(nameRank(e.searchKey, q, firstToken), e.englishKey?.let { nameRank(it, q, firstToken) } ?: RANK_REST).let { byName ->
            when {
                byName < RANK_REST -> byName
                e.keywordKeys.any { it == q } -> RANK_KEYWORD
                e.keywordKeys.any { it.startsWith(q) } -> RANK_KEYWORD_PREFIX
                else -> RANK_REST
            }
        }

    private fun nameRank(
        key: String,
        q: String,
        firstToken: String,
    ): Int =
        when {
            key == q -> RANK_EXACT
            key.startsWith(q) -> RANK_PREFIX
            key.split(WORD_BREAK).any { it.startsWith(firstToken) } -> RANK_WORD
            else -> RANK_REST
        }

    companion object {
        val EMPTY = EmojiCatalog(emptyList())

        /** Enough to fill several grid screens; a query this broad wants refining, not scrolling. */
        const val SEARCH_LIMIT = 120

        private const val RANK_EXACT = 0
        private const val RANK_PREFIX = 1
        private const val RANK_WORD = 2
        private const val RANK_KEYWORD = 3
        private const val RANK_KEYWORD_PREFIX = 4
        private const val RANK_REST = 5
        private val WHITESPACE = Regex("\\s+")
        internal val WORD_BREAK = Regex("[\\s:,\\-]+")
    }
}
