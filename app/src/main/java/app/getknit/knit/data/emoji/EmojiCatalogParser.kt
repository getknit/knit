package app.getknit.knit.data.emoji

/**
 * Parses the `emoji/emoji_en.tsv` asset (see `scripts/gen-emoji-catalog.py` for the format): blank lines
 * and `#` lines with no tab are skipped (a comment never has one; the keycap #️⃣ row starts with `#` and
 * does); a data line is `emoji ⇥ group-id ⇥ tone(0|1) ⇥ name`, optionally `⇥ kw|kw|…` (the
 * CLDR keywords, absent rather than empty when an entry has none). A malformed line — or one whose
 * group id this build doesn't know — is dropped rather than thrown, so a newer asset never crashes an
 * older build; the asset contract test pins "parsed == data lines" so a drop can't ship unnoticed.
 */
object EmojiCatalogParser {
    private const val MIN_COLUMNS = 4
    private const val MAX_COLUMNS = 5

    /** A header line. The keycap #️⃣ row also starts with `#`; only a data line carries a tab. */
    fun isComment(line: String): Boolean = line.startsWith("#") && '\t' !in line

    fun parse(lines: Sequence<String>): List<EmojiEntry> =
        lines
            .mapNotNull { line ->
                if (line.isBlank() || isComment(line)) return@mapNotNull null
                val parts = line.trimEnd('\r').split('\t', limit = MAX_COLUMNS)
                if (parts.size < MIN_COLUMNS) return@mapNotNull null
                val group = parts[1].toIntOrNull()?.let(EmojiGroup::fromId) ?: return@mapNotNull null
                val emoji = parts[0]
                val name = parts[3]
                if (emoji.isEmpty() || name.isEmpty()) return@mapNotNull null
                val keywords =
                    parts
                        .getOrNull(4)
                        ?.split('|')
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                EmojiEntry(emoji = emoji, group = group, name = name, toneVariant = parts[2] == "1", keywords = keywords)
            }.toList()
}
