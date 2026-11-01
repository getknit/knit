package app.getknit.knit.data.emoji

/**
 * One catalog row: a fully-qualified RGI [emoji], its [group], its English CLDR short [name] (the search
 * key and the accessibility label), whether it is a skin-tone variant of another entry ([toneVariant] —
 * hidden from the browse grid, still reachable by search), and its lower-cased CLDR search [keywords]
 * ("100", "score" for 💯; empty on a tone variant, which search reaches by name).
 */
data class EmojiEntry(
    val emoji: String,
    val group: EmojiGroup,
    val name: String,
    val toneVariant: Boolean,
    val keywords: List<String> = emptyList(),
) {
    /** Lower-cased [name], computed once so search never re-folds 4,000 strings per keystroke. */
    val searchKey: String = name.lowercase()

    /** Every word of every keyword ("thumbs up" → "thumbs", "up"), the set a query token prefix-matches. */
    val keywordWords: List<String> = keywords.flatMap { it.split(EmojiCatalog.WORD_BREAK) }.filter { it.isNotEmpty() }.distinct()
}
