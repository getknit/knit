package app.getknit.knit

import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.chat.REPLY_SNIPPET_MAX
import app.getknit.knit.ui.chat.buildReplySnippet
import app.getknit.knit.ui.chat.quoteAuthorLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure quote-formatting helpers (the viewer-relative author swap + the snippet snapshot rules). */
class ReplyFormattingTest {
    @Test
    fun quoteAuthorLabelShowsSelfLabelForTheViewersOwnMessage() {
        val reply = ReplyRef("m0", "me000000", "My Real Name", "hi")
        assertEquals("You", quoteAuthorLabel(reply, myNodeId = "me000000", selfLabel = "You"))
    }

    @Test
    fun quoteAuthorLabelShowsTheSnapshotNameForSomeoneElse() {
        val reply = ReplyRef("m0", "ada00000", "Ada", "hi")
        assertEquals("Ada", quoteAuthorLabel(reply, myNodeId = "me000000", selfLabel = "You"))
    }

    @Test
    fun buildReplySnippetBlanksAModerationFlaggedBody() {
        // A collapsed (flagged) original must not be re-exposed through a quote that bypasses tap-to-reveal.
        assertEquals("", buildReplySnippet("something flagged", flagged = true))
    }

    @Test
    fun buildReplySnippetIsBlankForAnAttachmentOnlyBody() {
        assertEquals("", buildReplySnippet("", flagged = false))
    }

    @Test
    fun buildReplySnippetFlattensNewlinesAndCapsLength() {
        val body = "line one\nline two   with   spaces " + "x".repeat(200)
        val snippet = buildReplySnippet(body, flagged = false)
        assertEquals(REPLY_SNIPPET_MAX, snippet.length)
        assertFalse("snippet is a single line", snippet.contains('\n'))
    }
}
