package app.getknit.knit

import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.WrappedKey
import app.getknit.knit.mesh.protocol.isStorable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireSerializationTest {
    private fun envelope(
        type: String = FrameType.CHAT,
        id: String = "m1",
        senderId: String = "alice",
        sentAt: Long = 0L,
        recipientId: String? = null,
        group: GroupInfo? = null,
        payload: ByteArray = ByteArray(0),
    ) = RelayEnvelope(type, id, senderId, sentAt, recipientId, group, payload)

    private fun wrap(
        env: RelayEnvelope,
        ttl: Int = DEFAULT_TTL,
        hops: Int = 0,
        sig: ByteArray = ByteArray(0),
    ) = WireEnvelope(ttl = ttl, hops = hops, sig = sig, signed = WireCodec.encodeEnvelope(env))

    // --- the keystone: relaying mutates only the wrapper; signed + sig pass through verbatim ---

    @Test
    fun relayMutatesOnlyWrapperAndForwardsSignedBytesVerbatim() {
        val env =
            envelope(
                id = "m1",
                senderId = "a",
                sentAt = 5L,
                recipientId = "b",
                payload = WireCodec.encodePayload(ChatContent(body = "hi")),
            )
        val signed = WireCodec.encodeEnvelope(env)
        val sig = byteArrayOf(7, 8, 9)
        val wire = WireEnvelope(ttl = DEFAULT_TTL, hops = 0, sig = sig, signed = signed)

        // Simulate a relay: decode the wrapper off the wire, relay it (bump hops / cap ttl), re-encode.
        var hop = WireCodec.decodeWire(WireCodec.encodeWire(wire))!!
        repeat(3) { hop = WireCodec.decodeWire(WireCodec.encodeWire(hop.relayed()))!! }

        assertEquals(3, hop.hops)
        assertEquals(DEFAULT_TTL, hop.ttl)
        assertArrayEquals("signed blob forwarded byte-for-byte", signed, hop.signed)
        assertArrayEquals("signature forwarded byte-for-byte", sig, hop.sig)
        // ...and the routing envelope still decodes identically.
        val decoded = WireCodec.decodeEnvelope(hop.signed)!!
        assertEquals("m1", decoded.id)
        assertEquals("a", decoded.senderId)
        assertEquals("b", decoded.recipientId)
    }

    @Test
    fun relayedCapsForgedOversizedTtl() {
        val wire = WireEnvelope(ttl = Int.MAX_VALUE, hops = 0, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(envelope()))
        assertEquals(DEFAULT_TTL, wire.relayed().ttl)
        assertEquals(1, wire.relayed().hops)
    }

    // --- an unknown future type decodes (does not throw) and stays routable ---

    @Test
    fun unknownFrameTypeDecodesAndIsRoutable() {
        val env = envelope(type = "future-type", id = "x", senderId = "a")
        val wire = WireCodec.decodeWire(WireCodec.encodeWire(wrap(env)))!!
        val decoded = WireCodec.decodeEnvelope(wire.signed)!!
        assertEquals("future-type", decoded.type) // decoded, not thrown
        assertEquals("x", decoded.id) // id available, so the router can dedup + relay it onward
    }

    // --- wrapper / envelope round-trips ---

    @Test
    fun wireEnvelopeRoundTripsWithNonDefaultRoutingCounters() {
        val wire = wrap(envelope(), ttl = 5, hops = 2, sig = byteArrayOf(1, 2, 3))
        val decoded = WireCodec.decodeWire(WireCodec.encodeWire(wire))!!
        assertEquals(5, decoded.ttl)
        assertEquals(2, decoded.hops)
        assertTrue(decoded.relay)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.sig)
    }

    @Test
    fun nonRelayableWrapperRoundTrips() {
        val wire = WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(envelope(type = FrameType.BLOB_REQ)))
        assertFalse(WireCodec.decodeWire(WireCodec.encodeWire(wire))!!.relay)
    }

    @Test
    fun relayEnvelopeWithGroupRosterRoundTrips() {
        val env =
            envelope(
                id = "g1",
                senderId = "alice",
                sentAt = 55L,
                group = GroupInfo("g-id", "Weekend crew", listOf("alice000", "bob00000", "carol000"), "alice000"),
            )
        val decoded = WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env))!!
        assertEquals("g-id", decoded.group?.id)
        assertEquals("Weekend crew", decoded.group?.name)
        assertEquals(listOf("alice000", "bob00000", "carol000"), decoded.group?.members)
        assertEquals("alice000", decoded.group?.createdBy)
    }

    @Test
    fun unnamedGroupRoundTripsWithNullName() {
        val env = envelope(group = GroupInfo("g", name = null, members = listOf("a", "b", "c"), createdBy = "a"))
        assertNull(WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env))!!.group?.name)
    }

    @Test
    fun groupPhotoFieldsRoundTrip() {
        val env =
            envelope(
                group =
                    GroupInfo(
                        "g",
                        members = listOf("a", "b"),
                        createdBy = "a",
                        photoHash = "a".repeat(64),
                        photoUpdatedAt = 1234L,
                    ),
            )
        val decoded = WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env))!!.group!!
        assertEquals("a".repeat(64), decoded.photoHash)
        assertEquals(1234L, decoded.photoUpdatedAt)
    }

    @Test
    fun groupWithoutPhotoDecodesPhotoFieldsAsNull() {
        // Additive fields: a group that sets no photo omits them on the wire (encodeDefaults = false),
        // and an old or photo-less frame decodes both as null — never a spurious photo.
        val env = envelope(group = GroupInfo("g", members = listOf("a", "b"), createdBy = "a"))
        val decoded = WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env))!!.group!!
        assertNull(decoded.photoHash)
        assertNull(decoded.photoUpdatedAt)
    }

    // --- per-type content payload round-trips ---

    @Test
    fun chatContentRoundTrips() {
        val content =
            ChatContent(
                body = "hey @Bob Jones",
                mentions = listOf(Mention("bob00000", "Bob Jones")),
                attachmentHash = "abc123",
                attachmentMime = "image/gif",
            )
        assertEquals(content, WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(content)))
    }

    @Test
    fun encryptedChatContentRoundTrips() {
        val nonce = byteArrayOf(10, 20, 30)
        val ct = byteArrayOf(40, 50, 60, 70)
        val wk = byteArrayOf(80, 90)
        val content =
            ChatContent(
                enc = EncEnvelope(nonce = nonce, ct = ct, keys = listOf(WrappedKey("bob00000", wk))),
            )
        val decoded = WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(content))
        assertEquals("", decoded?.body)
        val key = decoded?.enc?.keys?.firstOrNull()
        assertEquals("bob00000", key?.to)
        // The @ByteString fields must round-trip as raw bytes (regression guard for the base64 → bytes change).
        assertArrayEquals(nonce, decoded?.enc?.nonce)
        assertArrayEquals(ct, decoded?.enc?.ct)
        assertArrayEquals(wk, key?.wk)
    }

    @Test
    fun profileContentRoundTrips() {
        val content =
            ProfileContent(
                name = "Bob",
                status = "around",
                avatarHash = "abc",
                pubKey = null,
                deviceTag = "abcdef0123456789",
                protoVersion = 1,
                capabilities = 7L,
            )
        assertEquals(content, WireCodec.decodePayload<ProfileContent>(WireCodec.encodePayload(content)))
    }

    @Test
    fun controlContentTypesRoundTrip() {
        assertEquals(ReceiptContent("m1"), WireCodec.decodePayload<ReceiptContent>(WireCodec.encodePayload(ReceiptContent("m1"))))
        assertEquals(GroupLeaveContent("g"), WireCodec.decodePayload<GroupLeaveContent>(WireCodec.encodePayload(GroupLeaveContent("g"))))
        assertEquals(BlobReqContent("h"), WireCodec.decodePayload<BlobReqContent>(WireCodec.encodePayload(BlobReqContent("h"))))
    }

    @Test
    fun reactionRetractContentSurvivesWithNullEmoji() {
        val content = ReactionContent("m1", emoji = null)
        assertNull(WireCodec.decodePayload<ReactionContent>(WireCodec.encodePayload(content))?.emoji)
    }

    // --- isStorable predicate ---

    @Test
    fun isStorableForEveryFloodableFrameButNotControl() {
        assertTrue(envelope(type = FrameType.CHAT, recipientId = "b").isStorable())
        assertTrue(envelope(type = FrameType.CHAT, group = GroupInfo("g", members = listOf("a", "b"), createdBy = "a")).isStorable())
        assertTrue("the broadcast room is carried too", envelope(type = FrameType.CHAT).isStorable())
        assertTrue("reactions are now custodied", envelope(type = FrameType.REACTION).isStorable())
        assertTrue("receipts are now custodied", envelope(type = FrameType.RECEIPT, recipientId = "b").isStorable())
        assertTrue("profiles are now custodied", envelope(type = FrameType.PROFILE).isStorable())
        assertTrue("group updates are now custodied", envelope(type = FrameType.GROUP_UPDATE).isStorable())
        assertTrue("group leaves are now custodied", envelope(type = FrameType.GROUP_LEAVE).isStorable())
        assertFalse("a point-to-point key request is never carried", envelope(type = FrameType.KEY_REQ).isStorable())
        assertFalse("a point-to-point blob request is never carried", envelope(type = FrameType.BLOB_REQ).isStorable())
    }

    // --- signature binding (the bytes the wrapper signature covers) ---

    @Test
    fun signedBytesBindTypeAndId() {
        val chat = WireCodec.encodeEnvelope(envelope(type = FrameType.CHAT, id = "z", senderId = "a"))
        val reaction = WireCodec.encodeEnvelope(envelope(type = FrameType.REACTION, id = "z", senderId = "a"))
        val differentId = WireCodec.encodeEnvelope(envelope(type = FrameType.CHAT, id = "z2", senderId = "a"))
        assertFalse("type is covered, so a sig can't be lifted across types", chat.contentEquals(reaction))
        assertFalse("id is covered, so a captured frame can't be replayed under a fresh id", chat.contentEquals(differentId))
    }

    // --- robustness ---

    @Test
    fun malformedWrapperBytesDecodeToNull() {
        assertNull(WireCodec.decodeWire("not a frame".encodeToByteArray()))
    }
}
