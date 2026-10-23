package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.VideoInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v3 compact plaintext codec (ADR 059): a lossless round trip for everything the domain carries, a
 * refusal (never a mangling) for anything without a canonical raw form, and a byte layout pinned by hand.
 */
class MessageContentV2Test {
    private val frameId = FrameId.new()
    private val nodeId = NodeId.derive("alice")
    private val hash = "ab".repeat(32)
    private val key = b64(ByteArray(32) { it.toByte() })

    private fun roundTrip(content: MessageContent): MessageContent {
        val bytes = checkNotNull(MessageContentV2.encodeOrNull(content)) { "must have a compact form" }
        return checkNotNull(MessageContentV2.decode(bytes)) { "must decode" }
    }

    @Test
    fun aTickRoundTripsAndIsPinnedByteForByte() {
        val tick = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = frameId)
        val bytes = checkNotNull(MessageContentV2.encodeOrNull(tick))
        // map(2) { 7: 5, 8: h'<16 bytes>' } — no body, no version: 21 bytes against the named form's 39.
        assertEquals(21, bytes.size)
        assertEquals(0xA2, bytes[0].toInt() and 0xFF)
        assertEquals(0x07, bytes[1].toInt())
        assertEquals(0x05, bytes[2].toInt())
        assertEquals(0x08, bytes[3].toInt())
        assertEquals(0x50, bytes[4].toInt() and 0xFF) // bstr(16)
        assertArrayEquals(checkNotNull(FrameId.toBytesOrNull(frameId)), bytes.copyOfRange(5, 21))
        assertEquals(39, tick.encode().size)
        assertEquals(tick, roundTrip(tick))
    }

    @Test
    fun aTwelveAckBatchIsSeventyTwoBytesLighter() {
        val acks = List(12) { FrameId.new() }
        val batch = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks)
        val compact = checkNotNull(MessageContentV2.encodeOrNull(batch))
        assertTrue("${batch.encode().size} - ${compact.size}", batch.encode().size - compact.size >= 72)
        assertEquals(acks, roundTrip(batch).acks)
    }

    @Test
    fun everyFieldRoundTrips() {
        val full =
            MessageContent(
                body = "see you at the gate — 👍",
                mentions = listOf(Mention(nodeId, "Alice"), Mention(NodeId.derive("bob"), "Bob (2)")),
                attachmentHash = hash,
                attachmentMime = "image/jpeg",
                attachmentKey = key,
                replyTo =
                    ReplyRef(
                        messageId = FrameId.new(),
                        authorId = nodeId,
                        author = "Alice",
                        snippet = "earlier",
                        hasAttachment = true,
                    ),
                ctl = null,
                ack = null,
                rp = ReactionPayload(messageId = FrameId.new(), emoji = "🔥"),
                pr =
                    ProfilePayload(
                        name = "Alice",
                        status = "out",
                        avatarHash = "cd".repeat(32),
                        version = 1_756_100_000_000L,
                        openToChat = true,
                    ),
                acks = listOf(FrameId.new(), FrameId.new()),
            )
        assertEquals(full, roundTrip(full))
        val retraction =
            MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload(messageId = frameId, emoji = null))
        assertEquals(retraction, roundTrip(retraction))
        val plain = MessageContent(body = "just text")
        assertEquals(plain, roundTrip(plain))
        // A plain message encodes to exactly { 1: body }.
        assertEquals(0xA1, checkNotNull(MessageContentV2.encodeOrNull(plain))[0].toInt() and 0xFF)
    }

    @Test
    fun anythingWithoutACanonicalRawFormIsRefusedNotMangled() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertNull("a 36-char UUID id", MessageContentV2.encodeOrNull(MessageContent(body = "", ctl = 5, ack = uuid)))
        assertNull(
            "a 25-char node id",
            MessageContentV2.encodeOrNull(MessageContent(body = "", mentions = listOf(Mention(nodeId.dropLast(1), "x")))),
        )
        assertNull(
            "uppercase base32",
            MessageContentV2.encodeOrNull(MessageContent(body = "", mentions = listOf(Mention(nodeId.uppercase(), "x")))),
        )
        // A 22-char base64url string whose last char carries non-zero padding bits: it decodes, but not to
        // bytes that re-encode to it — the JDK decoder silently discards those bits.
        val padded = frameId.dropLast(1) + "B"
        assertNull(FrameId.toBytesOrNull(padded))
        assertNull("padding-bit base64url", MessageContentV2.encodeOrNull(MessageContent(body = "", ctl = 5, ack = padded)))
        assertNull("uppercase hex", MessageContentV2.encodeOrNull(MessageContent(body = "", attachmentHash = hash.uppercase())))
        assertNull("a short hash", MessageContentV2.encodeOrNull(MessageContent(body = "", attachmentHash = "abcd")))
        assertNull("an unpadded key", MessageContentV2.encodeOrNull(MessageContent(body = "", attachmentKey = key.trimEnd('='))))
        assertNull(
            "a group-key ctl",
            MessageContentV2.encodeOrNull(MessageContent(body = "", ctl = 2, gk = GroupKeyPayload(groupId = "g-1"))),
        )
        val transfer = MessageContent(body = "", ctl = MessageContent.CTL_TRANSFER, xf = TransferPayload(id = "t1", phase = 2))
        assertNull("a direct-transfer ctl", MessageContentV2.encodeOrNull(transfer))
        assertEquals(EncEnvelope.VERSION_RATCHET, transfer.sealBytes(v3 = true).scheme)
        val call = MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = CallPayload(id = "c1", ph = CallPayload.PHASE_END))
        assertNull("a call ctl", MessageContentV2.encodeOrNull(call))
        assertEquals(EncEnvelope.VERSION_RATCHET, call.sealBytes(v3 = true).scheme)
        // The compact layout has no label for `sc`: encoded there, a scope config would ship as `ctl = 7` with no payload.
        val config =
            MessageContent(
                body = "",
                ctl = MessageContent.CTL_SCOPE_CONFIG,
                sc = ScopeConfigPayload(spools = listOf("wss://a.example/spool/v1"), maxFrames = 400, ttlMs = 1L, maxBlob = 1, version = 1),
            )
        assertNull("a scope-config ctl", MessageContentV2.encodeOrNull(config))
        assertEquals(EncEnvelope.VERSION_RATCHET, config.sealBytes(v3 = true).scheme)
        assertNull("a foreign content version", MessageContentV2.encodeOrNull(MessageContent(v = 2, body = "")))
        // The board key goes through the same canonical check (#175): an unpadded one would be rewritten padded, and
        // one that does not decode at all used to throw IllegalArgumentException out of the seal.
        for (loraKey in listOf("QQ", "!!!!")) {
            val profile =
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_PROFILE,
                    pr = ProfilePayload(name = "A", status = "", version = 5L, loraNode = 7L, loraKey = loraKey),
                )
            assertNull("board key $loraKey", MessageContentV2.encodeOrNull(profile))
            assertEquals(EncEnvelope.VERSION_RATCHET, profile.sealBytes(v3 = true).scheme)
        }
        // And the seam every seal site uses falls back to the named form instead of losing the frame.
        val (bytes, scheme) = MessageContent(body = "", ctl = 5, ack = uuid).sealBytes(v3 = true)
        assertEquals(EncEnvelope.VERSION_RATCHET, scheme)
        assertNotNull(MessageContent.decode(bytes))
        assertEquals(EncEnvelope.VERSION_DM_V3, MessageContent(body = "", ctl = 5, ack = frameId).sealBytes(v3 = true).scheme)
        assertEquals(EncEnvelope.VERSION_RATCHET, MessageContent(body = "", ctl = 5, ack = frameId).sealBytes(v3 = false).scheme)
    }

    @Test
    fun malformedBytesDecodeToNull() {
        assertNull(MessageContentV2.decode(byteArrayOf(0x7F)))
        // A wrong-width id inside an otherwise valid map: { 8: h'0102' }.
        assertNull(MessageContentV2.decode(byteArrayOf(0xA1.toByte(), 0x08, 0x42, 0x01, 0x02)))
        // A newer compact schema version: { 0: 2 }.
        assertNull(MessageContentV2.decode(byteArrayOf(0xA1.toByte(), 0x00, 0x02)))
        // An unknown label is ignored, not fatal: { 1: "hi", 99: 1 }.
        val unknownLabel = byteArrayOf(0xA2.toByte(), 0x01, 0x62, 'h'.code.toByte(), 'i'.code.toByte(), 0x18, 0x63, 0x01)
        assertEquals("hi", checkNotNull(MessageContentV2.decode(unknownLabel)).body)
    }

    @Test
    fun aFileAttachmentRoundTripsItsNameAndSize() {
        val file =
            MessageContent(
                body = "",
                attachmentHash = hash,
                attachmentMime = "application/pdf",
                attachmentKey = key,
                attachmentName = "report.pdf",
                attachmentSize = 1_400_000L,
            )
        val back = roundTrip(file)
        assertEquals("report.pdf", back.attachmentName)
        assertEquals(1_400_000L, back.attachmentSize)
        assertEquals("application/pdf", back.attachmentMime)
    }

    /** A video's description takes label 16 and its poster rides as raw bytes (ADR 2026-10.hmfe). */
    @Test
    fun aVideoDescriptionRoundTripsWithItsPosterRaw() {
        val poster = ByteArray(5_000) { (it * 7).toByte() }
        val plain = MessageContent(body = "", attachmentHash = hash, attachmentMime = "video/mp4", attachmentKey = key)
        val video = plain.copy(video = VideoInfo(durationMs = 61_000L, width = 480, height = 854, poster = poster))
        assertEquals(video.video, roundTrip(video).video)
        val growth = checkNotNull(MessageContentV2.encodeOrNull(video)).size - checkNotNull(MessageContentV2.encodeOrNull(plain)).size
        assertTrue("the poster is raw, not base64: grew $growth B", growth < poster.size + 32)
    }

    /** The open-to-chat flag is additive: a profile without it carries no label 5, so an older peer's bytes are unchanged. */
    @Test
    fun aProfileWithoutTheOpenToChatFlagCarriesNoLabelFive() {
        val off = MessageContent(body = "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "A", status = "", version = 5L))
        val on = off.copy(pr = checkNotNull(off.pr).copy(openToChat = true))
        val offBytes = checkNotNull(MessageContentV2.encodeOrNull(off))
        val onBytes = checkNotNull(MessageContentV2.encodeOrNull(on))
        assertEquals("label 5 + `f5`", offBytes.size + 2, onBytes.size)
        assertFalse(checkNotNull(roundTrip(off).pr).openToChat)
        assertTrue(checkNotNull(roundTrip(on).pr).openToChat)
    }

    /** The two file labels are additive: an image's compact bytes must not move because they exist. */
    @Test
    fun anImageAttachmentCarriesNeitherLabel() {
        val image = MessageContent(body = "", attachmentHash = hash, attachmentMime = "image/jpeg", attachmentKey = key)
        val bytes = checkNotNull(MessageContentV2.encodeOrNull(image))
        // map(3): hash, mime, key — labels 14/15 absent, so no byte of an image frame changed.
        assertEquals(0xA3, bytes[0].toInt() and 0xFF)
        val back = roundTrip(image)
        assertNull(back.attachmentName)
        assertNull(back.attachmentSize)
    }

    /** A name is normalized on the way out of the codec, not trusted from whoever encoded it. */
    @Test
    fun aHostileNameIsRepairedOnDecode() {
        val hostile =
            MessageContent(
                body = "",
                attachmentHash = hash,
                attachmentKey = key,
                attachmentName = "../../etc/passwd",
            )
        assertEquals("....etcpasswd", roundTrip(hostile).attachmentName)
    }
}
