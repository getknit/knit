@file:Suppress("MatchingDeclarationName") // intentional grab-bag of mention helpers, not one declaration

package app.getknit.knit.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
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

/** A URL found in a message body: its display [start]/[end] in the text and the resolved [url] to open. */
data class UrlSpan(val start: Int, val end: Int, val url: String)

// http(s):// or a bare www. link, running to the next whitespace or angle bracket. Case-insensitive.
private val URL_PATTERN = Regex("""(?i)(?:https?://|www\.)[^\s<>]+""")

// Punctuation that commonly trails a URL in prose ("see https://x.com.") and isn't part of the link.
private const val TRAILING_PUNCT = ".,;:!?\"'”’»"

/**
 * Detects http(s) and bare "www." URLs in [text], returning each as a [UrlSpan]. Trailing sentence
 * punctuation and unbalanced closing brackets (e.g. the ')' in "(https://x.com)") are excluded from
 * the span; a "www." link is resolved to an absolute "https://" [UrlSpan.url] for opening while the
 * displayed range stays as written. Pure: no Android dependencies, so it is JVM-unit-testable.
 */
fun findUrls(text: String): List<UrlSpan> {
    val spans = mutableListOf<UrlSpan>()
    for (match in URL_PATTERN.findAll(text)) {
        val start = match.range.first
        val end = trimTrailing(text, start, match.range.last + 1)
        val raw = text.substring(start, end)
        val schemeLen = when {
            raw.startsWith("https://", ignoreCase = true) -> "https://".length
            raw.startsWith("http://", ignoreCase = true) -> "http://".length
            else -> "www.".length
        }
        if (raw.length <= schemeLen) continue // only the scheme/prefix survived trimming
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        spans += UrlSpan(start, end, url)
    }
    return spans
}

/** Shrinks [end] past trailing punctuation and unbalanced closing brackets, never below [start]. */
private fun trimTrailing(text: String, start: Int, end: Int): Int {
    var e = end
    while (e > start) {
        val c = text[e - 1]
        val shrink = when (c) {
            in TRAILING_PUNCT -> true
            ')', ']', '}' -> {
                val open = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                val sub = text.substring(start, e)
                sub.count { it == open } < sub.count { it == c }
            }
            else -> false
        }
        if (!shrink) break
        e--
    }
    return e
}

/**
 * Builds the chat-bubble text: @-mentions highlighted with [mentionStyle] (see [highlightMentions])
 * and detected URLs (see [findUrls]) turned into links styled with [linkStyle]. Tapping a link calls
 * [onLinkClick] with the resolved target; when it is null the link opens via the ambient UriHandler.
 */
fun annotateMessageBody(
    body: String,
    mentions: List<Mention>,
    mentionStyle: SpanStyle,
    linkStyle: SpanStyle,
    onLinkClick: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    append(highlightMentions(body, mentions, mentionStyle))
    val styles = TextLinkStyles(style = linkStyle)
    for (span in findUrls(body)) {
        val listener = onLinkClick?.let { cb -> LinkInteractionListener { cb(span.url) } }
        addLink(LinkAnnotation.Url(span.url, styles, listener), span.start, span.end)
    }
}
