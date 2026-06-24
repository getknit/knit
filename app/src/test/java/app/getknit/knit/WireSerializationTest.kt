package app.getknit.knit

import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertEquals
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
    fun chatFrameWithoutMentionsKeyDecodesToEmptyList() {
        // An older peer's frame has no "mentions" key; the defaulted field must fill in.
        val legacy = """{"t":"chat","id":"m4","senderId":"alice","sentAt":1,"body":"hi"}"""
        val decoded = WireCodec.decode(legacy.encodeToByteArray()) as ChatFrame
        assertTrue(decoded.mentions.isEmpty())
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
