@file:Suppress("MatchingDeclarationName") // intentional grab-bag of mention helpers, not one declaration

package app.getknit.knit.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.getknit.knit.mesh.protocol.Mention

/** The "@token" the cursor currently sits inside (see [activeMentionQuery]). */
data class MentionQuery(
    val start: Int,   // index of the '@'
    val end: Int,     // exclusive end == cursor position
    val query: String, // text between '@' and the cursor, e.g. "Joy" for "@Joy|"
)

/**
 * Finds the active mention token for [cursor] in [text], or null if the cursor is not inside one. A
 * token starts at an '@' that is at string start or immediately preceded by whitespace and runs to the
 * cursor; it is broken by any whitespace between the '@' and the cursor. This handles deleting the '@'
 * (no token), multiple '@'s (the nearest before the cursor wins), and '@' mid-word (e.g. an email),
 * which never triggers because the preceding char isn't whitespace.
 */
fun activeMentionQuery(text: CharSequence, cursor: Int): MentionQuery? {
    if (cursor < 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@') {
            val prevOk = i == 0 || text[i - 1].isWhitespace()
            if (!prevOk) return null
            return MentionQuery(start = i, end = cursor, query = text.substring(i + 1, cursor))
        }
        if (c.isWhitespace()) return null // token broken before reaching an '@'
        i--
    }
    return null
}

/**
 * Filters mention [candidates] by [query] (the text after '@'). Matching is case-insensitive and
 * whitespace-stripped on both sides, so "@Joy" matches "Joyful Ferret"; a blank query returns all.
 */
fun filterCandidates(candidates: List<MentionCandidate>, query: String): List<MentionCandidate> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return candidates
    return candidates.filter { it.displayName.replace(" ", "").lowercase().contains(q) }
}

/**
 * Returns [body] with each "@<name>" occurrence styled by [spanStyle]. Names are matched longest-first
 * (with a per-char mask) so a short name ("@Jay") never grabs the prefix of a longer one ("@Jaylene")
 * and overlaps are skipped. Every occurrence of a matched name is highlighted; text without a literal
 * '@' is never styled.
 */
fun highlightMentions(
    body: String,
    mentions: List<Mention>,
    spanStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    append(body)
    if (mentions.isEmpty()) return@buildAnnotatedString
    val styled = BooleanArray(body.length)
    val tokens = mentions
        .map { "@${it.name}" }
        .distinct()
        .sortedByDescending { it.length }
    for (token in tokens) {
        if (token.length == 1) continue // an empty name would match every position
        var from = body.indexOf(token)
        while (from >= 0) {
            val to = from + token.length
            if ((from until to).none { styled[it] }) {
                addStyle(spanStyle, from, to)
                for (k in from until to) styled[k] = true
            }
            from = body.indexOf(token, from + 1)
        }
    }
}
