package app.getknit.knit.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.getknit.knit.location.GeoUri
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a bubble draws a message's formatting (ADR 2026-10.p95v): which spans it draws, and over which words. */
class BodyFormattingTest {
    private val link = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline)
    private val mention = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test
    fun aBodyShownWholeDrawsEverySpan() {
        val spans = listOf(StyleSpan(0, 3, BodyStyle.BOLD))
        assertEquals(spans, bubbleStyles("See you", "See you", spans, emojiOnly = false))
    }

    @Test
    fun aPositionCutOffTheEndClipsTheSpansToTheWordsShown() {
        val body = "See you at the gate\ngeo:37.421998,-122.084000;u=12"
        val shown = GeoUri.strip(body)
        // The sender clips at the text part, but a hand-made span over the token must not draw past the words either.
        val spans = listOf(StyleSpan(0, 3, BodyStyle.BOLD), StyleSpan(15, body.length, BodyStyle.ITALIC))

        assertEquals(
            listOf(StyleSpan(0, 3, BodyStyle.BOLD), StyleSpan(15, shown.length, BodyStyle.ITALIC)),
            bubbleStyles(body, shown, spans, emojiOnly = false),
        )
    }

    @Test
    fun aSpanWhollyPastTheWordsShownIsDropped() {
        val body = "Here\ngeo:1,2"
        assertEquals(emptyList<StyleSpan>(), bubbleStyles(body, "Here", listOf(StyleSpan(5, 12, BodyStyle.BOLD)), emojiOnly = false))
    }

    @Test
    fun aBodyRewrittenOtherThanAtItsEndIsDrawnPlain() {
        // A token another client put first: the offsets index the body, not what the strip leaves.
        val body = "geo:1,2 meet here"
        val spans = listOf(StyleSpan(8, 12, BodyStyle.BOLD))
        assertEquals(emptyList<StyleSpan>(), bubbleStyles(body, "meet here", spans, emojiOnly = false))
    }

    @Test
    fun jumboEmojiStayPlain() {
        val spans = listOf(StyleSpan(0, 2, BodyStyle.BOLD))
        assertEquals(emptyList<StyleSpan>(), bubbleStyles("🎉", "🎉", spans, emojiOnly = true))
    }

    @Test
    fun eachStyleDrawsItsOneProperty() {
        val wash = Color.Black.copy(alpha = CODE_WASH_ALPHA)
        assertEquals(FontWeight.Bold, bubbleSpanStyle(BodyStyle.BOLD, wash).fontWeight)
        assertEquals(FontStyle.Italic, bubbleSpanStyle(BodyStyle.ITALIC, wash).fontStyle)
        assertEquals(TextDecoration.LineThrough, bubbleSpanStyle(BodyStyle.STRIKETHROUGH, wash).textDecoration)
        val mono = bubbleSpanStyle(BodyStyle.MONOSPACE, wash)
        assertEquals(FontFamily.Monospace, mono.fontFamily)
        assertEquals(wash, mono.background)
    }

    @Test
    fun aLinkNoStrikeTouchesIsOnePiece() {
        assertEquals(listOf(LinkPiece(4, 20, struck = false)), linkPieces(4, 20, emptyList()))
        assertEquals(listOf(LinkPiece(4, 20, struck = false)), linkPieces(4, 20, listOf(strike(0, 4), strike(20, 25))))
    }

    @Test
    fun aLinkHalfStruckIsSplitWhereTheStrikeEnds() {
        assertEquals(
            listOf(LinkPiece(4, 10, struck = true), LinkPiece(10, 20, struck = false)),
            linkPieces(4, 20, listOf(strike(0, 10))),
        )
    }

    @Test
    fun aStrikeInsideALinkCutsItInThree() {
        assertEquals(
            listOf(LinkPiece(4, 8, struck = false), LinkPiece(8, 12, struck = true), LinkPiece(12, 20, struck = false)),
            linkPieces(4, 20, listOf(strike(8, 12))),
        )
    }

    @Test
    fun aLinkWhollyStruckIsOneStruckPiece() {
        assertEquals(listOf(LinkPiece(4, 20, struck = true)), linkPieces(4, 20, listOf(strike(0, 30))))
    }

    @Test
    fun theBubbleTextCarriesEachStyleOverItsWords() {
        val body = "Bring the charger now"
        val out =
            annotateMessageBody(
                body = body,
                mentions = emptyList(),
                mentionStyle = mention,
                linkStyle = link,
                styles = listOf(StyleSpan(10, 17, BodyStyle.BOLD), StyleSpan(18, 21, BodyStyle.ITALIC)),
            )
        val bold = out.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("charger", body.substring(bold.start, bold.end))
        val italic = out.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("now", body.substring(italic.start, italic.end))
    }

    @Test
    fun boldOverAMentionComesAfterItsHighlight() {
        val out =
            annotateMessageBody(
                body = "hi @Coral",
                mentions = listOf(Mention("n2", "Coral")),
                mentionStyle = mention,
                linkStyle = link,
                styles = listOf(StyleSpan(3, 9, BodyStyle.BOLD)),
            )
        // A later span wins where two set the same property, so the bold must follow the mention's semibold.
        val order = out.spanStyles.map { it.item.fontWeight }
        assertEquals(listOf(FontWeight.SemiBold, FontWeight.Bold), order)
    }

    @Test
    fun aStruckLinkKeepsItsUnderlineAndTakesTheLineThrough() {
        val body = "old https://a.com/x plan"
        val out =
            annotateMessageBody(
                body = body,
                mentions = emptyList(),
                mentionStyle = mention,
                linkStyle = link,
                styles = listOf(StyleSpan(0, 12, BodyStyle.STRIKETHROUGH)),
            )
        val links = out.getLinkAnnotations(0, out.length).sortedBy { it.start }
        assertEquals(listOf(4 to 12, 12 to 19), links.map { it.start to it.end })
        assertTrue("both pieces open the one link", links.all { (it.item as LinkAnnotation.Url).url == "https://a.com/x" })
        val decorations = links.map { (it.item as LinkAnnotation.Url).styles?.style?.textDecoration }
        assertEquals(
            listOf(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), TextDecoration.Underline),
            decorations,
        )
    }

    private fun strike(
        start: Int,
        end: Int,
    ) = StyleSpan(start, end, BodyStyle.STRIKETHROUGH)
}
