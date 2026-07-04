package app.getknit.knit

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.chat.MentionCandidate
import app.getknit.knit.ui.chat.activeMentionQuery
import app.getknit.knit.ui.chat.annotateMessageBody
import app.getknit.knit.ui.chat.filterCandidates
import app.getknit.knit.ui.chat.findUrls
import app.getknit.knit.ui.chat.highlightMentions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionTextTest {
    // --- activeMentionQuery ---

    @Test
    fun findsTokenWhenCursorInsideAtWord() {
        val text = "hey @Joy"
        val q = activeMentionQuery(text, text.length)
        assertEquals(4, q?.start)
        assertEquals("Joy", q?.query)
    }

    @Test
    fun triggersWhenAtIsAtStringStart() {
        val q = activeMentionQuery("@Jo", 3)
        assertEquals(0, q?.start)
        assertEquals("Jo", q?.query)
    }

    @Test
    fun bareAtYieldsEmptyQuery() {
        val q = activeMentionQuery("hi @", 4)
        assertEquals("", q?.query)
    }

    @Test
    fun closedTokenAfterSpaceIsNull() {
        // Cursor after "@Joy " — the trailing space closed the token.
        assertNull(activeMentionQuery("hi @Joy ", 8))
    }

    @Test
    fun atMidWordDoesNotTrigger() {
        assertNull(activeMentionQuery("email foo@bar", 13))
    }

    @Test
    fun nearestAtBeforeCursorWins() {
        val q = activeMentionQuery("@a @b", 5)
        assertEquals(3, q?.start)
        assertEquals("b", q?.query)
    }

    // --- filterCandidates ---

    private val candidates =
        listOf(
            MentionCandidate("n1", "Joyful Ferret", null),
            MentionCandidate("n2", "Coral", null),
        )

    @Test
    fun filterMatchesAcrossSpacesCaseInsensitively() {
        assertEquals(listOf("n1"), filterCandidates(candidates, "joy").map { it.nodeId })
        // "ferret" lives after a space in the display name; whitespace is stripped before matching.
        assertEquals(listOf("n1"), filterCandidates(candidates, "ferret").map { it.nodeId })
    }

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(2, filterCandidates(candidates, "").size)
    }

    // --- highlightMentions ---

    private val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test
    fun highlightsEveryOccurrenceOfMentionedName() {
        val body = "@Coral hi @Coral"
        val out = highlightMentions(body, listOf(Mention("n2", "Coral")), style)
        assertEquals(body, out.text)
        assertEquals(2, out.spanStyles.size)
        assertEquals(0, out.spanStyles[0].start)
        assertEquals(6, out.spanStyles[0].end)
        assertEquals(10, out.spanStyles[1].start)
        assertEquals(16, out.spanStyles[1].end)
    }

    @Test
    fun longestNameWinsOverPrefix() {
        // "@Jay" must not steal the prefix of "@Jaylene".
        val body = "hi @Jaylene"
        val out =
            highlightMentions(
                body,
                listOf(Mention("a", "Jay"), Mention("b", "Jaylene")),
                style,
            )
        assertEquals(1, out.spanStyles.size)
        assertEquals(3, out.spanStyles[0].start)
        assertEquals(11, out.spanStyles[0].end) // "@Jaylene"
    }

    @Test
    fun noMentionsLeavesTextUnstyled() {
        val out = highlightMentions("plain @text", emptyList(), style)
        assertEquals("plain @text", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun nameWithoutAtIsNotStyled() {
        // The body mentions "Coral" but without the literal '@', so nothing is highlighted.
        val out = highlightMentions("Coral is here", listOf(Mention("n2", "Coral")), style)
        assertTrue(out.spanStyles.isEmpty())
    }

    // --- findUrls ---

    @Test
    fun detectsHttpAndHttpsUrls() {
        assertEquals(
            listOf("http://example.com", "https://example.org/path?q=1"),
            findUrls("go http://example.com or https://example.org/path?q=1").map { it.url },
        )
    }

    @Test
    fun reportsDisplayRangeOfMatch() {
        val text = "see https://a.com now"
        val span = findUrls(text).single()
        assertEquals(4, span.start)
        assertEquals(17, span.end)
        assertEquals("https://a.com", text.substring(span.start, span.end))
    }

    @Test
    fun bareWwwLinkResolvesToHttps() {
        val span = findUrls("visit www.example.com").single()
        assertEquals("https://www.example.com", span.url)
        // The displayed range stays as written, without the synthesized scheme.
        assertEquals("www.example.com", "visit www.example.com".substring(span.start, span.end))
    }

    @Test
    fun trimsTrailingSentencePunctuation() {
        val text = "open https://a.com."
        val span = findUrls(text).single()
        assertEquals("https://a.com", span.url)
        assertEquals("https://a.com", text.substring(span.start, span.end))
    }

    @Test
    fun trimsUnbalancedClosingParenButKeepsBalancedOnes() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Mesh_(networking)",
            findUrls("(see https://en.wikipedia.org/wiki/Mesh_(networking))").single().url,
        )
    }

    @Test
    fun ignoresEmailsAndPlainText() {
        assertTrue(findUrls("ping me at foo@bar.com about it").isEmpty())
    }

    // --- annotateMessageBody ---

    @Test
    fun annotatesBothMentionsAndLinks() {
        val out =
            annotateMessageBody(
                body = "hey @Coral see https://a.com",
                mentions = listOf(Mention("n2", "Coral")),
                mentionStyle = style,
                linkStyle = style,
            )
        // Mention is still highlighted...
        assertEquals(1, out.spanStyles.size)
        assertEquals("@Coral", out.text.substring(out.spanStyles[0].start, out.spanStyles[0].end))
        // ...and the URL is a clickable link with the right target.
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals("https://a.com", (links[0].item as LinkAnnotation.Url).url)
        assertEquals("https://a.com", out.text.substring(links[0].start, links[0].end))
    }
}
