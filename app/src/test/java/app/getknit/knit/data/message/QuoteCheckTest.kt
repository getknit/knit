package app.getknit.knit.data.message

import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.mesh.MeshPostOrigin
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure quote rules (ADR 2026-10.gpa3): which held message a quote may be judged against, and what it then shows. */
class QuoteCheckTest {
    private val labels = QuoteLabels.ENGLISH

    private val original = msg("ada", 1, "peer-1", body = "Meet at the trailhead", id = "o1")

    private fun reply(
        authorId: String = "ada",
        conversationId: String = "peer-1",
        id: String = "r1",
        quotedId: String = "o1",
        snippet: String = "Meet at the trailhead",
    ): MessageEntity =
        msg("bo", 2, conversationId, body = "On my way", id = id)
            .withReply(ReplyRef(messageId = quotedId, authorId = authorId, author = "Ada", snippet = snippet))

    @Test
    fun `the replyCheck values are distinct and append-only`() {
        // Stored on the row, so a recycled value would re-read an old verdict as another.
        assertEquals(0, MessageEntity.REPLY_UNCHECKED)
        assertEquals(1, MessageEntity.REPLY_VERIFIED)
        assertEquals(2, MessageEntity.REPLY_MISMATCH)
    }

    @Test
    fun `a quote of the named author in the same thread verifies`() {
        assertEquals(MessageEntity.REPLY_VERIFIED, quoteVerdict(reply(), original))
    }

    @Test
    fun `a quote naming another author, or reaching into another thread, is a mismatch`() {
        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(reply(authorId = "mallory"), original))
        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(reply(conversationId = Conversations.NEARBY), original))
    }

    @Test
    fun `a status line, a heard post and the reply itself are never an original`() {
        val notice = original.copy(kind = MessageEntity.KIND_PEER_RENAMED)
        val heard = original.copy(originNode = 0x1234L)
        val self = reply(id = "r1", quotedId = "r1", authorId = "bo")

        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(reply(), notice))
        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(reply(), heard))
        // A MeshCore room post's sentinel origin (ADR 2026-11.gsqu's Stage 6 amendment) is as much somebody else's.
        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(reply(), original.copy(originNode = MeshPostOrigin.MESHCORE_NODE)))
        assertEquals(MessageEntity.REPLY_MISMATCH, quoteVerdict(self, self))
    }

    @Test
    fun `a verified quote is rebuilt from the original, not the sender's words`() {
        val forged = reply(snippet = "I never want to see you again")

        val settled = settleQuote(forged, original, labels)

        assertEquals(MessageEntity.REPLY_VERIFIED, settled.check)
        assertEquals("Meet at the trailhead", settled.snippet)
        assertFalse(settled.hasAttachment)
        assertFalse(settled.flagged)
    }

    @Test
    fun `a verified quote of a flagged original keeps its words and carries the flag`() {
        // The reader's toggle decides whether the words show; the column only says they are flagged.
        val flagged = original.copy(moderation = MessageEntity.MODERATION_TEXT_FLAGGED)

        val settled = settleQuote(reply(), flagged, labels)

        assertEquals("Meet at the trailhead", settled.snippet)
        assertTrue(settled.flagged)
    }

    @Test
    fun `only the original's own words carry the flag, not a flag it inherited from its own quote`() {
        val hiddenForItsQuote = original.copy(replyToId = "older", replyToFlagged = true)

        assertFalse(settleQuote(reply(), hiddenForItsQuote, labels).flagged)
    }

    @Test
    fun `a mismatch keeps nothing of the claim`() {
        val settled = settleQuote(reply(authorId = "mallory", snippet = "lies"), original, labels)

        assertEquals(QuoteSettlement(MessageEntity.REPLY_MISMATCH, snippet = "", hasAttachment = false, flagged = false), settled)
    }

    @Test
    fun `a body-less original is named by its own kind of attachment`() {
        val photo = original.copy(body = "", attachmentHash = "h", attachmentMime = "image/jpeg")
        val gif = photo.copy(attachmentAnimated = true)
        val voice = photo.copy(attachmentMime = VoiceAudio.MIME)
        val file = photo.copy(attachmentMime = "application/pdf", attachmentName = "map.pdf")

        assertEquals("", settleQuote(reply(), photo, labels).snippet)
        assertTrue(settleQuote(reply(), photo, labels).hasAttachment)
        assertEquals("🎞️ GIF", settleQuote(reply(), gif, labels).snippet)
        assertEquals("🎤 Voice message", settleQuote(reply(), voice, labels).snippet)
        assertEquals("📎 map.pdf", settleQuote(reply(), file, labels).snippet)
    }

    @Test
    fun `a position reads as its label and a link card is not an attachment`() {
        val located = original.copy(body = "here geo:37.42,-122.08")
        val card = original.copy(body = "https://example.org", attachmentHash = "h", attachmentMime = LinkPreviewBlob.MIME)

        assertEquals("here 📍 Location", settleQuote(reply(), located, labels).snippet)
        assertFalse(settleQuote(reply(), card, labels).hasAttachment)
    }

    @Test
    fun `the composer blanks a flagged body, flattens and caps the rest`() {
        assertEquals("", buildReplySnippet("something flagged", flagged = true))
        val long = buildReplySnippet("line one\nline two " + "x".repeat(200), flagged = false)
        assertEquals(REPLY_SNIPPET_MAX, long.length)
        assertFalse(long.contains('\n'))
    }

    @Test
    fun `the cap never splits a surrogate pair`() {
        val body = "x".repeat(REPLY_SNIPPET_MAX - 1) + "😀"

        assertEquals("x".repeat(REPLY_SNIPPET_MAX - 1), buildReplySnippet(body, flagged = false))
    }

    @Test
    fun `a reaction's quoted words end in an ellipsis only when cut`() {
        assertEquals("lunch?", reactionSnippet("lunch?"))
        assertEquals("x".repeat(REPLY_SNIPPET_MAX), reactionSnippet("x".repeat(REPLY_SNIPPET_MAX)))
        assertEquals("x".repeat(REPLY_SNIPPET_MAX - 1) + "…", reactionSnippet("x".repeat(REPLY_SNIPPET_MAX + 1)))
        assertEquals(labels.gif, reactionSnippet("", attachmentLabel = labels.gif))
    }

    @Test
    fun `hiddenByFilter hides a flagged body or flagged quoted words, and only while filtering is on`() {
        val flaggedBody = original.copy(moderation = MessageEntity.MODERATION_TEXT_FLAGGED)
        val flaggedQuote = reply().copy(replyToFlagged = true)

        assertTrue(flaggedBody.hiddenByFilter(filterOn = true))
        assertTrue(flaggedQuote.hiddenByFilter(filterOn = true))
        assertFalse(flaggedQuote.hiddenByFilter(filterOn = false))
        assertFalse(reply().hiddenByFilter(filterOn = true))
    }
}
