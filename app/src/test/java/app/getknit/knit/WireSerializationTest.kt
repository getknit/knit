package app.getknit.knit

import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireSerializationTest {

    @Test
    fun chatFrameRoundTrips() {
        val frame = ChatFrame(id = "m1", senderId = "alice", sentAt = 123L, body = "hello", ttl = 5, hops = 2)
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun chatFrameWithAttachmentRoundTrips() {
        val frame = ChatFrame(
            id = "m2", senderId = "alice", sentAt = 123L, body = "look",
            attachmentHash = "abc123", attachmentMime = "image/gif",
        )
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun blobRequestFrameRoundTrips() {
        val frame = BlobRequestFrame(id = "req1", senderId = "bob", hash = "abc123")
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun profileFrameRoundTrips() {
        val frame = ProfileFrame(
            id = "p1", senderId = "bob", sentAt = 9L, name = "Bob", status = "around",
            avatarHash = "abc", pubKey = null,
        )
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun receiptFrameRoundTrips() {
        val frame = ReceiptFrame(id = "r1", senderId = "carol", ackId = "m1")
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun malformedBytesDecodeToNull() {
        assertNull(WireCodec.decode("not a frame".encodeToByteArray()))
    }
}
