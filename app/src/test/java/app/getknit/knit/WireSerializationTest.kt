package app.getknit.knit

import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun chatFrameWithMentionsRoundTrips() {
        val frame = ChatFrame(
            id = "m3", senderId = "alice", sentAt = 7L, body = "hey @Bob Jones and @ab12cd34",
            mentions = listOf(
                Mention(nodeId = "bob00000", name = "Bob Jones"),
                Mention(nodeId = "ab12cd34", name = "ab12cd34"),
            ),
        )
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun chatFrameWithoutMentionsDecodesToEmptyList() {
        // With encodeDefaults off, a no-mentions frame omits the field on the wire; decode refills it.
        val frame = ChatFrame(id = "m4", senderId = "alice", sentAt = 1L, body = "hi")
        val decoded = WireCodec.decode(WireCodec.encode(frame)) as ChatFrame
        assertTrue(decoded.mentions.isEmpty())
    }

    @Test
    fun encodedFramesNeverCollideWithFileHeaderMagic() {
        // NearbyTransport tells a file-header BYTES payload from a frame by this 4-byte prefix; a CBOR
        // frame must never begin with it (mirrors NearbyTransport.FILE_HEADER_MAGIC = "KFH1").
        val magic = byteArrayOf(0x4B, 0x46, 0x48, 0x31)
        val frames = listOf(
            ChatFrame(id = "m", senderId = "a", sentAt = 1L, body = "hi"),
            ProfileFrame(id = "p", senderId = "b", sentAt = 2L, name = "Bob", status = "ok"),
            ReceiptFrame(id = "r", senderId = "c", ackId = "m"),
            ReactionFrame(id = "x", senderId = "c", messageId = "m", emoji = "👍", sentAt = 3L),
            BlobRequestFrame(id = "q", senderId = "b", hash = "abc"),
        )
        frames.forEach { frame ->
            val bytes = WireCodec.encode(frame)
            assertFalse(bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] })
        }
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
    fun reactionFrameRoundTrips() {
        val frame = ReactionFrame(id = "x1", senderId = "carol", messageId = "m1", emoji = "👍", sentAt = 42L)
        assertEquals(frame, WireCodec.decode(WireCodec.encode(frame)))
    }

    @Test
    fun reactionRetractFrameRoundTrips() {
        // A retraction carries a null emoji; it must survive the round trip (not silently default away).
        val frame = ReactionFrame(id = "x2", senderId = "carol", messageId = "m1", emoji = null, sentAt = 99L)
        val decoded = WireCodec.decode(WireCodec.encode(frame)) as ReactionFrame
        assertEquals(frame, decoded)
        assertNull(decoded.emoji)
    }

    @Test
    fun malformedBytesDecodeToNull() {
        assertNull(WireCodec.decode("not a frame".encodeToByteArray()))
    }
}
