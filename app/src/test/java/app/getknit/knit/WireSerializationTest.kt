package app.getknit.knit

import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.WrappedKey
import app.getknit.knit.mesh.protocol.isStorable
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun groupDepartedTombstonesRoundTrip() {
        // Additive `departed` (docs/WIRE_COMPAT.md rule 1): carried so the founding roster
        // (members ∪ departed) stays derivable after a departure; absent decodes as null.
        val env = envelope(group = GroupInfo("g", members = listOf("a", "b"), createdBy = "a", departed = listOf("c")))
        assertEquals(listOf("c"), WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env))!!.group?.departed)

        val without = envelope(group = GroupInfo("g", members = listOf("a", "b"), createdBy = "a"))
        assertNull(WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(without))!!.group?.departed)
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
    fun chatContentWithReplyRoundTrips() {
        val content =
            ChatContent(
                body = "on my way",
                replyTo = ReplyRef("m0", "ada00000", "Ada", "see you at 8", hasAttachment = true),
            )
        assertEquals(content, WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(content)))
    }

    @Test
    fun chatContentWithoutReplyDecodesReplyAsNull() {
        // Additive field: a non-reply message omits replyTo on the wire (encodeDefaults = false), and an
        // old or non-reply frame decodes it as null — never a spurious quote.
        val decoded = WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(ChatContent(body = "hi")))
        assertNull(decoded?.replyTo)
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
    fun ratchetV2EnvelopeRoundTripsWithRawBytes() {
        val init = RatchetInit(eph = ByteArray(32) { 7 }, pkid = 4, at = 999L)
        val content =
            ChatContent(
                enc =
                    EncEnvelope(
                        v = EncEnvelope.VERSION_RATCHET,
                        nonce = byteArrayOf(1, 2, 3),
                        ct = byteArrayOf(4, 5, 6, 7),
                        keys = emptyList(),
                        r = RatchetHeader(se = 3, ek = ByteArray(32) { 9 }, pe = 2, n = 17, init = init, flags = 0),
                    ),
            )
        val decoded = WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(content))
        val enc = requireNotNull(decoded?.enc)
        assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
        assertTrue(enc.keys.isEmpty())
        val header = requireNotNull(enc.r)
        assertEquals(3, header.se)
        assertEquals(2, header.pe)
        assertEquals(17, header.n)
        assertArrayEquals(ByteArray(32) { 9 }, header.ek)
        assertEquals(4, requireNotNull(header.init).pkid)
        assertEquals(999L, requireNotNull(header.init).at)
        assertArrayEquals(init.eph, requireNotNull(header.init).eph)
    }

    /** The [ProfileContent] shape as every build before the open-to-chat flag compiled it (the fields that matter here). */
    @Serializable
    private class ProfileContentPreFlagShape(
        val name: String,
        val status: String,
        val version: Long? = null,
    )

    @Test
    fun aPreFlagDecoderIgnoresOpenToChatAndAbsenceReadsFalse() {
        val flagged = WireCodec.encodePayload(ProfileContent(name = "A", status = "", openToChat = true))
        assertEquals("A", requireNotNull(WireCodec.decodePayload<ProfileContentPreFlagShape>(flagged)).name)
        assertTrue(requireNotNull(WireCodec.decodePayload<ProfileContent>(flagged)).openToChat)
        // Off is elided, so a profile that never set it is byte-identical to one that set it false — and an
        // older peer's profile (no key at all) reads false.
        val unflagged = WireCodec.encodePayload(ProfileContent(name = "A", status = ""))
        assertArrayEquals(unflagged, WireCodec.encodePayload(ProfileContent(name = "A", status = "", openToChat = false)))
        assertFalse(requireNotNull(WireCodec.decodePayload<ProfileContent>(unflagged)).openToChat)
        // The sealed payload follows the same rule.
        val sealedOn = WireCodec.encodePayload(ProfilePayload(name = "A", status = "", version = 5L, openToChat = true))
        val sealedOff = WireCodec.encodePayload(ProfilePayload(name = "A", status = "", version = 5L))
        assertTrue(requireNotNull(WireCodec.decodePayload<ProfilePayload>(sealedOn)).openToChat)
        assertFalse(requireNotNull(WireCodec.decodePayload<ProfilePayload>(sealedOff)).openToChat)
        assertEquals(sealedOff.size + "openToChat".length + 2, sealedOn.size)
    }

    /** The pre-ratchet [EncEnvelope] shape, exactly as a v1-era build compiled it. */
    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private class EncEnvelopeV1Shape(
        val v: Int = 1,
        @ByteString val nonce: ByteArray,
        @ByteString val ct: ByteArray,
        val keys: List<WrappedKey>,
    )

    @Test
    fun aV1ShapedDecoderIgnoresTheRatchetHeader() {
        // What an old build does with a v2 envelope: `ignoreUnknownKeys` drops `r`, the envelope still
        // decodes, and the version gate (not a parse error) is what rejects it — so it keeps relaying.
        val v2 =
            WireCodec.encodePayload(
                EncEnvelope(
                    v = EncEnvelope.VERSION_RATCHET,
                    nonce = byteArrayOf(1),
                    ct = byteArrayOf(2),
                    keys = emptyList(),
                    r = RatchetHeader(se = 1, ek = ByteArray(32), pe = 0, n = 0, init = RatchetInit(ByteArray(32), 1, 1L)),
                ),
            )
        val seenByOldBuild = WireCodec.decodePayload<EncEnvelopeV1Shape>(v2)
        assertEquals(EncEnvelope.VERSION_RATCHET, seenByOldBuild?.v)
        assertTrue(requireNotNull(seenByOldBuild).keys.isEmpty())
    }

    @Test
    fun everyOlderDecoderShapeDecodesAV3Envelope() {
        // ADR 059's executable reason for keeping `nonce` a required field and sending it EMPTY on v3: a
        // build that cannot decode the envelope cannot carry it either (`canCarry` decodes the chat payload),
        // so every fielded shape — the v1 one and the pre-`g` v2 one — must still decode the real v3 output
        // and leave the version gate, not a parse error, to refuse it.
        val v3 =
            WireCodec.encodePayload(
                EncEnvelope(
                    v = EncEnvelope.VERSION_DM_V3,
                    nonce = ByteArray(0),
                    ct = byteArrayOf(2),
                    keys = emptyList(),
                    r = RatchetHeader(se = 1, ek = ByteArray(32), pe = 0, n = 0, init = RatchetInit(ByteArray(32), 1, 1L)),
                ),
            )
        val v1Shape = requireNotNull(WireCodec.decodePayload<EncEnvelopeV1Shape>(v3))
        assertEquals(EncEnvelope.VERSION_DM_V3, v1Shape.v)
        assertEquals(0, v1Shape.nonce.size)
        val v2Shape = requireNotNull(WireCodec.decodePayload<EncEnvelopeV2Shape>(v3))
        assertEquals(EncEnvelope.VERSION_DM_V3, v2Shape.v)
        assertNotNull(v2Shape.r)
        // And the whole chat payload, as canCarry reads it.
        assertNotNull(
            WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(ChatContent(enc = WireCodec.decodePayload<EncEnvelope>(v3)!!))),
        )
        assertTrue("v3 sits inside this build's gate", EncEnvelope.VERSION_DM_V3 <= EncEnvelope.MAX_SUPPORTED_VERSION)
    }

    @Test
    fun aFutureEnvelopeVersionStillDecodesForRelay() {
        // The WIRE_COMPAT bump checklist's decode half: a higher version must never be a parse error
        // (delivery drops it by the version gate; InboundPipelineTest covers the counting).
        val future = EncEnvelope(v = EncEnvelope.MAX_SUPPORTED_VERSION + 1, nonce = byteArrayOf(1), ct = byteArrayOf(2), keys = emptyList())
        val decoded = WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(ChatContent(enc = future)))
        assertEquals(EncEnvelope.MAX_SUPPORTED_VERSION + 1, decoded?.enc?.v)
    }

    @Test
    fun groupRatchetEnvelopeRoundTripsWithItsHeader() {
        val content =
            ChatContent(
                enc =
                    EncEnvelope(
                        v = EncEnvelope.VERSION_RATCHET,
                        nonce = byteArrayOf(1, 2, 3),
                        ct = byteArrayOf(4, 5, 6, 7),
                        keys = emptyList(),
                        g = GroupRatchetHeader(se = 4, n = 129),
                    ),
            )
        val enc = requireNotNull(WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(content))?.enc)
        assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
        assertTrue(enc.keys.isEmpty())
        assertNull(enc.r)
        assertEquals(4, requireNotNull(enc.g).se)
        assertEquals(129, requireNotNull(enc.g).n)
    }

    /** An [EncEnvelope] decoder shape that predates `g` (an unreleased mid-branch build; also what pins
     *  `ignoreUnknownKeys` for the field). */
    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private class EncEnvelopeV2Shape(
        val v: Int = 1,
        @ByteString val nonce: ByteArray,
        @ByteString val ct: ByteArray,
        val keys: List<WrappedKey>,
        val r: RatchetHeader? = null,
    )

    @Test
    fun aGShapelessDecoderIgnoresTheGroupHeader() {
        // A decoder without the `g` field: `ignoreUnknownKeys` drops it and the envelope still
        // decodes — the additive-field property every older shape relies on to keep relaying.
        val groupForm =
            WireCodec.encodePayload(
                EncEnvelope(
                    v = EncEnvelope.VERSION_RATCHET,
                    nonce = byteArrayOf(1),
                    ct = byteArrayOf(2),
                    keys = emptyList(),
                    g = GroupRatchetHeader(se = 1, n = 0),
                ),
            )
        val seenWithoutG = WireCodec.decodePayload<EncEnvelopeV2Shape>(groupForm)
        assertEquals(EncEnvelope.VERSION_RATCHET, seenWithoutG?.v)
        assertTrue(requireNotNull(seenWithoutG).keys.isEmpty())
        assertNull(seenWithoutG.r)
    }

    @Test
    fun profileContentPrekeySurvivesAndDecodesNullForOldFrames() {
        val prekey = PrekeyInfo(id = 2, pub = ByteArray(32) { 5 }, sig = ByteArray(64) { 6 })
        val decoded =
            WireCodec.decodePayload<ProfileContent>(
                WireCodec.encodePayload(ProfileContent(name = "Ann", status = "", prekey = prekey)),
            )
        assertEquals(2, decoded?.prekey?.id)
        assertArrayEquals(prekey.pub, decoded?.prekey?.pub)
        assertArrayEquals(prekey.sig, decoded?.prekey?.sig)

        val old = WireCodec.decodePayload<ProfileContent>(WireCodec.encodePayload(ProfileContent(name = "Ann", status = "")))
        assertNull(old?.prekey)
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

    @Test
    fun typingContentRoundTrips() {
        assertEquals("g-1", WireCodec.decodePayload<TypingContent>(WireCodec.encodePayload(TypingContent("g-1")))?.groupId)
        // A DM/broadcast typing cue carries no group id: the defaulted field encodes empty and decodes back null.
        assertNull(WireCodec.decodePayload<TypingContent>(WireCodec.encodePayload(TypingContent()))?.groupId)
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
        assertFalse("a point-to-point key request is never carried", envelope(type = FrameType.KEY_REQ).isStorable())
        assertFalse("a point-to-point blob request is never carried", envelope(type = FrameType.BLOB_REQ).isStorable())
        assertFalse("a best-effort typing cue is never carried", envelope(type = FrameType.TYPING).isStorable())
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
