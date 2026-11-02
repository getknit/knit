package app.getknit.knit.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareInboxTest {
    @Test
    fun consumeReturnsOfferedPayloadThenEmpties() {
        val inbox = ShareInbox()
        val content = SharedContent(text = "hello", imageUri = "content://x/1")

        inbox.offer(content)
        assertEquals(content, inbox.pending.value)

        assertEquals(content, inbox.consume())
        assertNull(inbox.pending.value)
    }

    @Test
    fun consumeIsSingleShot() {
        val inbox = ShareInbox()
        inbox.offer(SharedContent(text = "hi", imageUri = null))

        assertEquals("hi", inbox.consume()?.text)
        assertNull(inbox.consume())
    }

    @Test
    fun fullyEmptyPayloadsAreIgnored() {
        val inbox = ShareInbox()

        inbox.offer(SharedContent(text = null, imageUri = null))
        inbox.offer(SharedContent(text = "   ", imageUri = null))

        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    @Test
    fun textOnlyAndImageOnlyPayloadsAreAccepted() {
        val inbox = ShareInbox()

        inbox.offer(SharedContent(text = "caption", imageUri = null))
        assertEquals("caption", inbox.consume()?.text)

        inbox.offer(SharedContent(text = null, imageUri = "content://x/2"))
        assertEquals("content://x/2", inbox.consume()?.imageUri)
    }

    @Test
    fun clearDropsPendingPayload() {
        val inbox = ShareInbox()
        inbox.offer(SharedContent(text = "draft", imageUri = null))

        inbox.clear()

        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    /** Diagnostics' Send in a chat (#287): a payload that is only Knit's own bytes is a share, not an empty one. */
    @Test
    fun anOwnFileOnlyPayloadIsAcceptedAndConsumedOnce() {
        val inbox = ShareInbox()
        val file = OwnFile("knit-fieldlog.jsonl", "text/plain", byteArrayOf(1, 2, 3))

        inbox.offer(SharedContent(text = null, imageUri = null, ownFile = file))

        assertSame(file, inbox.consume()?.ownFile)
        assertNull(inbox.consume())
    }

    /** A file reaches only a DM or a group, so the picker drops the rooms when the share is a file and nothing else. */
    @Test
    fun fileOnlyIsAFileWithNothingARoomCouldTake() {
        val file = OwnFile("knit-crash.txt", "text/plain", byteArrayOf(1))

        assertTrue(SharedContent(text = null, imageUri = null, ownFile = file).fileOnly)
        assertTrue(SharedContent(text = null, imageUri = null, fileUri = "content://x/3").fileOnly)
        assertTrue(SharedContent(text = "  ", imageUri = null, fileUri = "content://x/3").fileOnly)
        assertFalse(SharedContent(text = "note", imageUri = null, fileUri = "content://x/3").fileOnly)
        assertFalse(SharedContent(text = null, imageUri = "content://x/2", fileUri = "content://x/3").fileOnly)
        assertFalse(SharedContent(text = "hi", imageUri = null).fileOnly)
    }
}
