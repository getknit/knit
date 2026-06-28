package app.getknit.knit.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
