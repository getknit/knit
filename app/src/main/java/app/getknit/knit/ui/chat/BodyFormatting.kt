package app.getknit.knit.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.StyleSpan

/**
 * How a bubble draws a message's formatting (ADR 2026-10.p95v). Monospace sits on [codeBackground], a faint wash
 * of the bubble's own text colour, so it reads on either bubble fill in either theme.
 */
fun bubbleSpanStyle(
    style: BodyStyle,
    codeBackground: Color,
): SpanStyle =
    when (style) {
        BodyStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        BodyStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        BodyStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        BodyStyle.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    }

/**
 * The alpha of [bubbleSpanStyle]'s monospace wash over the bubble's text colour. The ceiling is the dark theme's
 * incoming bubble, whose text makes 5.5:1 on its fill: 8 % leaves 4.7:1, and 12 % fell to 4.3, under the small-text
 * floor (`CodeWashContrastTest`).
 */
const val CODE_WASH_ALPHA = 0.08f

/**
 * The part of [styles] a bubble draws over [shownBody], the text it shows for the stored [body]: nothing for a
 * jumbo-emoji body ([emojiOnly]), which keeps its plain enlarged style, and nothing when [shownBody] is not a
 * prefix of [body]. The spans index [body], and the one rewrite a bubble makes is cutting a shared position off the
 * end (`GeoUri.strip`), which a sender always puts on a last line of its own. A body some other client wrote with
 * the token elsewhere is drawn plain rather than with its styles on the wrong words. Otherwise each span is clipped
 * to the text shown.
 */
fun bubbleStyles(
    body: String,
    shownBody: String,
    styles: List<StyleSpan>,
    emojiOnly: Boolean,
): List<StyleSpan> {
    if (styles.isEmpty() || emojiOnly || !body.startsWith(shownBody)) return emptyList()
    val limit = shownBody.length
    return styles.mapNotNull { span -> if (span.start >= limit) null else span.copy(end = minOf(span.end, limit)) }
}

/**
 * Splits the link at [start, end) where a struck span starts or ends, each piece marked struck or not, so a link
 * drawn inside struck words keeps its underline and takes the line through as well. A link's style replaces the
 * text's decoration rather than adding to it, so a link half struck has to be two links.
 */
internal fun linkPieces(
    start: Int,
    end: Int,
    struck: List<StyleSpan>,
): List<LinkPiece> {
    val cuts =
        struck
            .flatMap { listOf(it.start, it.end) }
            .filter { it in (start + 1) until end }
            .distinct()
            .sorted()
    val bounds = listOf(start) + cuts + listOf(end)
    return bounds.zipWithNext { from, to -> LinkPiece(from, to, struck.any { it.start <= from && to <= it.end }) }
}

/** One piece of a link from [linkPieces]: [start, end), and whether a struck span covers it. */
internal data class LinkPiece(
    val start: Int,
    val end: Int,
    val struck: Boolean,
)
