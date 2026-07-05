@file:Suppress("MatchingDeclarationName") // pure emoji-detection helper, colocated with MentionText

package app.getknit.knit.ui.chat

import java.text.BreakIterator

/** Max emoji in an emoji-only body still rendered enlarged, Signal-style ("jumbomoji"). */
const val JUMBO_EMOJI_MAX = 5

private const val ZWJ = 0x200D // zero-width joiner (glues a ZWJ emoji sequence into one grapheme)
private const val VS15 = 0xFE0E // variation selector-15: force text presentation
private const val VS16 = 0xFE0F // variation selector-16: force emoji presentation
private const val KEYCAP = 0x20E3 // combining enclosing keycap (e.g. "1️⃣")

/**
 * The number of emoji in [body] when it consists ONLY of emoji — surrounding and inter-emoji
 * whitespace ignored — otherwise 0. Used to render a short emoji-only message larger, like Signal's
 * "jumbomoji". Returns 0 once the count passes [JUMBO_EMOJI_MAX], so a long wall of emoji renders at
 * normal size (and the scan short-circuits).
 *
 * Splits [body] into extended grapheme clusters ([BreakIterator], as `avatarInitial` does) so a
 * ZWJ/skin-tone/flag sequence counts as one emoji, then classifies each cluster by Unicode emoji
 * code-point ranges. Deliberately avoids `Character.isEmoji*` (added only at API 36; minSdk is 33) so
 * it behaves identically on every supported device and on the host JVM. Pure — no Android deps.
 */
fun emojiOnlyCount(body: String): Int {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return 0
    val boundary = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
    var start = boundary.first()
    var end = boundary.next()
    var count = 0
    while (end != BreakIterator.DONE) {
        val cluster = trimmed.substring(start, end)
        start = end
        end = boundary.next()
        if (cluster.isBlank()) continue // whitespace between/around emoji doesn't disqualify
        if (!isEmojiCluster(cluster)) return 0
        count++
        if (count > JUMBO_EMOJI_MAX) return 0
    }
    return count
}

/**
 * True when [cluster] (a single grapheme) renders as emoji: it "wants" emoji presentation AND every
 * code point is emoji-related. The all-emoji-related guard rejects bare digits / `#` / `*`, which are
 * emoji-related only as keycap bases and are plain text on their own.
 */
private fun isEmojiCluster(cluster: String): Boolean {
    val codePoints = cluster.codePoints().toArray()
    if (codePoints.isEmpty()) return false
    var wantsEmoji = false
    for (cp in codePoints) {
        if (!isEmojiRelated(cp)) return false
        if (forcesEmojiPresentation(cp)) wantsEmoji = true
    }
    return wantsEmoji
}

/** True for an emoji code point or one of the joiners/selectors/bases that legitimately appear in an
 *  emoji grapheme cluster (so a non-emoji code point in the cluster disqualifies the whole message). */
private fun isEmojiRelated(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF || // SMP emoji blocks (emoticons, pictographs, transport, flags, skin tones…)
        cp in 0x2600..0x27BF || // Misc Symbols + Dingbats (☀ ✂ ✅ …)
        cp in 0x2300..0x23FF || // Misc Technical emoji (⌚ ⌛ ⏰ ⏳ …)
        cp in 0x2B00..0x2BFF || // geometric shapes / stars (⭐ ⬛ ⬜ …)
        cp == ZWJ ||
        cp == VS15 ||
        cp == VS16 ||
        cp == KEYCAP ||
        isKeycapBase(cp)

/** True when [cp] forces or defaults to emoji (not text) presentation: an SMP-emoji code point
 *  (default emoji presentation for the common blocks), VS16, or a keycap combiner. A bare BMP symbol
 *  (e.g. `☀`, `⭐`) only counts as emoji when its cluster carries VS16. */
private fun forcesEmojiPresentation(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp == VS16 || cp == KEYCAP

/** The keycap bases `0`-`9`, `#`, `*` — emoji only inside a keycap sequence (base + VS16 + [KEYCAP]). */
private fun isKeycapBase(cp: Int): Boolean = cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code
