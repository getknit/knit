@file:Suppress("MatchingDeclarationName") // intentional grab-bag of mention helpers, not one declaration

package app.getknit.knit.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.StyleSpan

/** The "@token" the cursor currently sits inside (see [activeMentionQuery]). */
data class MentionQuery(
    val start: Int, // index of the '@'
    val end: Int, // exclusive end == cursor position
    val query: String, // text between '@' and the cursor, e.g. "Joy" for "@Joy|"
)

/**
 * Finds the active mention token for [cursor] in [text], or null if the cursor is not inside one. A
 * token starts at an '@' that is at string start or immediately preceded by whitespace and runs to the
 * cursor; it is broken by any whitespace between the '@' and the cursor. This handles deleting the '@'
 * (no token), multiple '@'s (the nearest before the cursor wins), and '@' mid-word (e.g. an email),
 * which never triggers because the preceding char isn't whitespace.
 */
fun activeMentionQuery(
    text: CharSequence,
    cursor: Int,
): MentionQuery? {
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
 * whitespace-stripped on both sides, so "@Joy" matches "Joyful Ferret"; a candidate's alias matches too,
 * so "@really" narrows two Alices to the one with that alias (ADR 058) — space-stripped as well, since an
 * alias grown past one token carries spaces and a query never can; a blank query returns all.
 */
fun filterCandidates(
    candidates: List<MentionCandidate>,
    query: String,
): List<MentionCandidate> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return candidates
    return candidates.filter {
        it.displayName
            .replace(" ", "")
            .lowercase()
            .contains(q) ||
            it.alias
                .replace(" ", "")
                .lowercase()
                .contains(q)
    }
}

/** The `@everyone` token's name: ASCII, lowercase, frozen in the sender's body, never localised (ADR 2026-10.6h2g). */
const val EVERYONE_NAME = "everyone"

/** Whether the `@everyone` row answers [query]: a blank one, or one `everyone` starts with (trimmed, case-insensitive). */
fun matchesEveryone(query: String): Boolean {
    val q = query.trim().lowercase()
    return q.isEmpty() || EVERYONE_NAME.startsWith(q)
}

/**
 * What picking `@everyone` mentions: one [Mention] named [EVERYONE_NAME] for each of [members] but [me] and the
 * [blocked], in ascending node id, each id once. Null when nobody is left, so the picker offers no row. Reads
 * the roster only — the picker's other candidates also take senders.
 */
fun everyoneMentions(
    members: Collection<String>,
    me: String?,
    blocked: Set<String>,
): List<Mention>? =
    members
        .filter { it != me && it !in blocked }
        .distinct()
        .sorted()
        .map { Mention(nodeId = it, name = EVERYONE_NAME) }
        .ifEmpty { null }

/** The [pending] mentions whose "@name" is still in [text]: edit a token away and its entries drop with it. */
fun mentionsStillIn(
    text: String,
    pending: List<Mention>,
): List<Mention> = pending.filter { text.contains("@${it.name}") }

/**
 * Returns [body] with each "@<name>" occurrence styled by [spanStyle], at the spans [mentionSpans] finds.
 * Every occurrence of a matched name is highlighted; text without a literal '@' is never styled.
 */
fun highlightMentions(
    body: String,
    mentions: List<Mention>,
    spanStyle: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        append(body)
        for (span in mentionSpans(body, mentions)) addStyle(spanStyle, span.start, span.end)
    }

/**
 * Where each "@<name>" of [mentions] sits in [text], sorted by start. Names are matched longest-first (with
 * a per-char mask) so a short name ("@Jay") never grabs the prefix of a longer one ("@Jaylene") and spans
 * never overlap. One rule for the bubble's highlight and the composer's chips, so the two always agree.
 */
fun mentionSpans(
    text: CharSequence,
    mentions: List<Mention>,
): List<TextRange> {
    if (mentions.isEmpty() || text.isEmpty()) return emptyList()
    val taken = BooleanArray(text.length)
    val spans = mutableListOf<TextRange>()
    val tokens =
        mentions
            .map { "@${it.name}" }
            .distinct()
            .sortedByDescending { it.length }
    for (token in tokens) {
        if (token.length == 1) continue // an empty name would match every position
        var from = text.indexOf(token)
        while (from >= 0) {
            val to = from + token.length
            if ((from until to).none { taken[it] }) {
                spans += TextRange(from, to)
                for (k in from until to) taken[k] = true
            }
            from = text.indexOf(token, from + 1)
        }
    }
    return spans.sortedBy { it.start }
}

/** One replacement in the text before an edit: its [start, end) became [text]. */
data class MentionEdit(
    val start: Int,
    val end: Int,
    val text: CharSequence,
)

/**
 * Widens [edits] (offsets in the text before them) so none cuts into one of [chips]: an edit that removes
 * or replaces any part of a chip takes the whole chip — one backspace at "@Walter|" deletes "@Walter" — and
 * an insertion strictly inside one moves to its end. Edits the widening makes touch are merged. Null when
 * every edit already leaves every chip whole or wholly gone, so the caller can keep the edit as it came.
 */
fun keepChipsWhole(
    chips: List<TextRange>,
    edits: List<MentionEdit>,
): List<MentionEdit>? {
    var widened = false
    val moved =
        edits.map { edit ->
            var start = edit.start
            var end = edit.end
            for (chip in chips) {
                if (start == end) {
                    if (chip.start < start && start < chip.end) {
                        start = chip.end
                        end = chip.end
                    }
                } else if (start < chip.end && chip.start < end) {
                    start = minOf(start, chip.start)
                    end = maxOf(end, chip.end)
                }
            }
            if (start != edit.start || end != edit.end) widened = true
            MentionEdit(start, end, edit.text)
        }
    if (!widened) return null
    val merged = mutableListOf<MentionEdit>()
    for (edit in moved.sortedBy { it.start }) {
        val prev = merged.lastOrNull()
        if (prev != null && edit.start <= prev.end) {
            merged[merged.lastIndex] = MentionEdit(prev.start, maxOf(prev.end, edit.end), "${prev.text}${edit.text}")
        } else {
            merged += edit
        }
    }
    return merged
}

/**
 * Moves [selection] out of the inside of any of [chips], keeping its direction. A cursor that stepped in from
 * a chip's edge ([from], the cursor before a selection-only move) crosses to the other edge, so the arrow
 * keys step over a chip; any other cursor goes to the nearer edge (the end on a tie). A range grows to take
 * every chip it cuts, so a cut or a paste over it never leaves half a name behind.
 */
fun snapOutOfChips(
    chips: List<TextRange>,
    selection: TextRange,
    from: TextRange?,
): TextRange {
    fun inside(offset: Int) = chips.firstOrNull { it.start < offset && offset < it.end }
    if (selection.collapsed) {
        val chip = inside(selection.start) ?: return selection
        val edge =
            when {
                from?.collapsed == true && from.start == chip.end -> chip.start
                from?.collapsed == true && from.start == chip.start -> chip.end
                selection.start - chip.start < chip.end - selection.start -> chip.start
                else -> chip.end
            }
        return TextRange(edge)
    }
    val min = inside(selection.min)?.start ?: selection.min
    val max = inside(selection.max)?.end ?: selection.max
    return if (selection.reversed) TextRange(max, min) else TextRange(min, max)
}

/**
 * The composer's mention chips: each autocompleted "@name" ([mentions], read on every edit) is one unit. A
 * backspace, a forward delete or a replacement that reaches into one takes it whole, typing never lands
 * inside it, and the cursor steps over it. The text stays the plain "@name" the send filters its mentions
 * against and the bubble highlights, so nothing about what goes out changes.
 */
@OptIn(ExperimentalFoundationApi::class)
class MentionChips(
    private val mentions: () -> List<Mention>,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val current = mentions()
        if (current.isEmpty()) return
        val before = mentionSpans(originalText, current)
        if (before.isNotEmpty() && changes.changeCount > 0) {
            val edits =
                (0 until changes.changeCount).map { i ->
                    val was = changes.getOriginalRange(i)
                    val now = changes.getRange(i)
                    MentionEdit(was.min, was.max, asCharSequence().subSequence(now.min, now.max).toString())
                }
            keepChipsWhole(before, edits)?.let { widened ->
                revertAllChanges()
                for ((start, end, text) in widened.asReversed()) replace(start, end, text)
                val last = widened.last()
                val shift = widened.dropLast(1).sumOf { it.text.length - (it.end - it.start) }
                placeCursorBeforeCharAt(last.start + shift + last.text.length)
            }
        }
        // A selection-only move (a tap, an arrow key, a handle drag) is the one whose old cursor says which
        // way it went; after a text change the old offsets no longer line up.
        val from = if (changes.changeCount == 0) originalSelection else null
        val snapped = snapOutOfChips(mentionSpans(asCharSequence(), current), selection, from)
        if (snapped != selection) selection = snapped
    }
}

/** Draws the composer's mention chips ([mentions]) in [style]. Styling only: the text and offsets are untouched. */
class MentionChipStyle(
    private val mentions: () -> List<Mention>,
    private val style: SpanStyle,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for (span in mentionSpans(asCharSequence(), mentions())) addStyle(style, span.start, span.end)
    }
}

/** A URL found in a message body: its display [start]/[end] in the text and the resolved [url] to open. */
data class UrlSpan(
    val start: Int,
    val end: Int,
    val url: String,
)

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
        val schemeLen =
            when {
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
private fun trimTrailing(
    text: String,
    start: Int,
    end: Int,
): Int {
    var e = end
    while (e > start) {
        val c = text[e - 1]
        val shrink =
            when (c) {
                in TRAILING_PUNCT -> {
                    true
                }

                ')', ']', '}' -> {
                    val open =
                        when (c) {
                            ')' -> '('
                            ']' -> '['
                            else -> '{'
                        }
                    val sub = text.substring(start, e)
                    sub.count { it == open } < sub.count { it == c }
                }

                else -> {
                    false
                }
            }
        if (!shrink) break
        e--
    }
    return e
}

/**
 * Builds the chat-bubble text: @-mentions highlighted with [mentionStyle] (see [highlightMentions]), the
 * message's formatting [styles] drawn over them ([bubbleSpanStyle], monospace on [codeBackground]; ADR
 * 2026-10.p95v), and detected URLs (see [findUrls]) turned into links styled with [linkStyle]. A link inside
 * struck words keeps [linkStyle]'s decoration and adds the line through ([linkPieces]). Tapping a link calls
 * [onLinkClick] with the resolved target; when it is null the link opens via the ambient UriHandler.
 */
fun annotateMessageBody(
    body: String,
    mentions: List<Mention>,
    mentionStyle: SpanStyle,
    linkStyle: SpanStyle,
    onLinkClick: ((String) -> Unit)? = null,
    styles: List<StyleSpan> = emptyList(),
    codeBackground: Color = Color.Unspecified,
): AnnotatedString =
    buildAnnotatedString {
        append(highlightMentions(body, mentions, mentionStyle))
        // After the mentions, so a bold span over a mention is bold rather than the mention's semibold.
        for (span in styles) addStyle(bubbleSpanStyle(span.style, codeBackground), span.start, span.end)
        val struck = styles.filter { it.style == BodyStyle.STRIKETHROUGH }
        val plain = TextLinkStyles(style = linkStyle)
        val crossed =
            TextLinkStyles(
                style =
                    linkStyle.copy(
                        textDecoration =
                            linkStyle.textDecoration?.let { TextDecoration.combine(listOf(it, TextDecoration.LineThrough)) }
                                ?: TextDecoration.LineThrough,
                    ),
            )
        for (span in findUrls(body)) {
            val listener = onLinkClick?.let { cb -> LinkInteractionListener { cb(span.url) } }
            for (piece in linkPieces(span.start, span.end, struck)) {
                addLink(LinkAnnotation.Url(span.url, if (piece.struck) crossed else plain, listener), piece.start, piece.end)
            }
        }
    }
