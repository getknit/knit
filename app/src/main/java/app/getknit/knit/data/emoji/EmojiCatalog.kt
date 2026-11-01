package app.getknit.knit.data.emoji

/**
 * The emoji the picker can offer: [entries] in Unicode order (already filtered to what this device can
 * render by [EmojiCatalogLoader]), the [browse] subset the grid shows (skin-tone variants collapsed away),
 * that subset [byGroup] in group order, and a name-and-keyword [search] over **all** entries so a tone
 * variant is one query away ("thumbs up: medium skin tone") without cluttering the grid. Pure Kotlin; the Android half
 * is [AndroidGlyphCheck].
 */
class EmojiCatalog(
    val entries: List<EmojiEntry>,
) {
    val browse: List<EmojiEntry> = entries.filter { !it.toneVariant }

    /** Insertion-ordered (Unicode group order); a group with nothing renderable is simply absent. */
    val byGroup: Map<EmojiGroup, List<EmojiEntry>> = browse.groupBy { it.group }

    /**
     * Case-insensitive search over the name and the CLDR keywords: every whitespace-separated token of
     * [query] must occur in the name or begin a keyword or one of its words (so "red heart" finds "red heart" but not
     * "heart", and "100" finds 💯 by its keyword). A keyword is prefix-matched, never substring-matched, or
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
        val q = trimmed.lowercase()
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
    ): Boolean = e.searchKey.contains(t) || e.keywords.any { it.startsWith(t) } || e.keywordWords.any { it.startsWith(t) }

    private fun rank(
        e: EmojiEntry,
        q: String,
        firstToken: String,
    ): Int {
        val key = e.searchKey
        return when {
            key == q -> RANK_EXACT
            key.startsWith(q) -> RANK_PREFIX
            key.split(WORD_BREAK).any { it.startsWith(firstToken) } -> RANK_WORD
            e.keywords.any { it == q } -> RANK_KEYWORD
            e.keywords.any { it.startsWith(q) } -> RANK_KEYWORD_PREFIX
            else -> RANK_REST
        }
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
