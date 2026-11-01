package app.getknit.knit.data.emoji

/**
 * One catalog row: a fully-qualified RGI [emoji], its [group], its CLDR short [name] in the app's language
 * (the search key and the accessibility label), whether it is a skin-tone variant of another entry
 * ([toneVariant] — hidden from the browse grid, still reachable by search), its lower-cased search
 * [keywords] ("100", "score" for 💯; empty on a tone variant, which search reaches by name), and — when
 * [name] was localized by [EmojiCatalog.localized] — the [englishName] it replaced, so an English query still
 * finds it.
 */
data class EmojiEntry(
    val emoji: String,
    val group: EmojiGroup,
    val name: String,
    val toneVariant: Boolean,
    val keywords: List<String> = emptyList(),
    val englishName: String? = null,
) {
    /** [EmojiFold]ed [name], computed once so search never re-folds 4,000 strings per keystroke. */
    val searchKey: String = EmojiFold.fold(name)

    /** [EmojiFold]ed [englishName]; null in English. */
    val englishKey: String? = englishName?.let(EmojiFold::fold)

    /** [EmojiFold]ed [keywords]. */
    val keywordKeys: List<String> = keywords.map(EmojiFold::fold).distinct()

    /** Every word of every keyword ("thumbs up" → "thumbs", "up"), the set a query token prefix-matches. */
    val keywordWords: List<String> = keywordKeys.flatMap { it.split(EmojiCatalog.WORD_BREAK) }.filter { it.isNotEmpty() }.distinct()
}

/** One overlay row (`emoji/emoji_<tag>.tsv`): an emoji's CLDR [name] and [keywords] in another language. */
data class LocalizedEmoji(
    val name: String,
    val keywords: List<String> = emptyList(),
)
