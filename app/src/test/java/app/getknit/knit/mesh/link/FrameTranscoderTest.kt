package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [FrameTranscoder] — the `0x05` schema-aware re-encoding (ADR 060). The contract under test is exactness:
 * `rebuild(transcode(signed))` is `signed`, byte for byte, for every frame shape the wire has, for anything an
 * older or newer build might have encoded (unknown keys, non-canonical ids), and never by mangling — a frame it
 * cannot reproduce is refused. The golden vectors and label map are the schema freeze.
 */
@OptIn(ExperimentalSerializationApi::class)
class FrameTranscoderTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun frameId(seed: Int): String = FrameId.fromBytes(bytes(16, seed))

    private fun nodeId(seed: Int): String = NodeId.fromBytes(bytes(16, seed))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun tstr(s: String): ByteArray = byteArrayOf((0x60 + s.length).toByte()) + s.encodeToByteArray()

    private val hash = hex(bytes(32, 9))
    private val groupId = "g-" + hex(bytes(12, 4))
    private val bundle = checkNotNull(PublicKeyBundle.fromRaw(bytes(32, 10), bytes(32, 20))).encoded

    private fun envelope(
        type: String,
        payload: ByteArray,
        recipientId: String? = null,
        group: GroupInfo? = null,
        id: String = frameId(1),
        sentAt: Long = SENT_AT,
        senderId: String = nodeId(2),
    ): ByteArray = WireCodec.encodeEnvelope(RelayEnvelope(type, id, senderId, sentAt, recipientId, group, payload))

    private fun chat(
        content: ChatContent,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ): ByteArray = envelope(FrameType.CHAT, WireCodec.encodePayload(content), recipientId, group)

    private fun ratchetHeader(
        init: Boolean,
        flags: Int = 0,
    ) = RatchetHeader(
        se = 1,
        ek = bytes(32, 5),
        pe = 0,
        n = 2,
        init = if (init) RatchetInit(eph = bytes(32, 6), pkid = 1, at = SENT_AT) else null,
        flags = flags,
    )

    private fun groupInfo(full: Boolean) =
        GroupInfo(
            id = groupId,
            name = if (full) "Hikers" else null,
            members = listOf(nodeId(2), nodeId(3)),
            createdBy = nodeId(3),
            photoHash = if (full) hash else null,
            photoUpdatedAt = if (full) SENT_AT else null,
            departed = if (full) listOf(nodeId(4)) else null,
        )

    private fun profile(): ByteArray =
        envelope(
            FrameType.PROFILE,
            WireCodec.encodePayload(
                ProfileContent(
                    name = "Alice",
                    status = "Out exploring",
                    avatarHash = hash,
                    pubKey = bundle,
                    deviceTag = hex(bytes(8, 8)),
                    protoVersion = Protocol.VERSION,
                    // A pinned literal, not Protocol.LOCAL_CAPABILITIES: the vectors below freeze the
                    // transcoder's *layout*, and capability bits are append-only, so reading the live
                    // bitfield made every new bit look like a layout change and rewrite a frozen vector.
                    // The value is the bitfield as of the transcoder's own release; it never needs updating.
                    capabilities = PINNED_CAPABILITIES,
                    prekey = PrekeyInfo(id = 1, pub = bytes(32, 5), sig = bytes(64, 6)),
                    version = SENT_AT,
                ),
            ),
            id = "profile-${nodeId(2)}-$SENT_AT", // the one frame whose id is not a FrameId: stays text
        )

    /** Every frame shape the wire has, with real-format ids — the exactness matrix. */
    @Suppress("LongMethod") // one fixture per wire shape, clearer as a single table
    private fun fixtures(): Map<String, ByteArray> =
        linkedMapOf(
            "chat-plain" to chat(ChatContent(body = "hi there")),
            "chat-rich" to
                chat(
                    ChatContent(
                        body = "look @Ann",
                        mentions = listOf(Mention(nodeId(3), "Ann")),
                        attachmentHash = hash,
                        attachmentMime = "image/webp",
                        replyTo = ReplyRef(frameId(7), nodeId(3), "Ann", "earlier", hasAttachment = true),
                    ),
                ),
            "enc-v1" to
                chat(
                    ChatContent(
                        enc = EncEnvelope(nonce = bytes(12, 1), ct = bytes(40, 2), keys = listOf(WrappedKey(nodeId(3), bytes(80, 3)))),
                    ),
                    recipientId = nodeId(3),
                ),
            "enc-v2-init" to
                chat(
                    ChatContent(
                        enc =
                            EncEnvelope(
                                v = 2,
                                nonce = bytes(12, 1),
                                ct = bytes(40, 2),
                                keys = emptyList(),
                                r = ratchetHeader(init = true, flags = 1),
                            ),
                    ),
                    recipientId = nodeId(3),
                ),
            "enc-v3" to
                chat(
                    ChatContent(
                        enc =
                            EncEnvelope(
                                v = 3,
                                nonce = ByteArray(0),
                                ct = bytes(37, 2),
                                keys = emptyList(),
                                r = ratchetHeader(init = false),
                            ),
                    ),
                    recipientId = nodeId(3),
                ),
            "enc-group" to
                chat(
                    ChatContent(
                        enc =
                            EncEnvelope(
                                v = 2,
                                nonce = bytes(12, 1),
                                ct = bytes(40, 2),
                                keys = emptyList(),
                                g = GroupRatchetHeader(se = 1, n = 3),
                            ),
                    ),
                    group = groupInfo(full = true),
                ),
            "profile" to profile(),
            "groupupdate" to envelope(FrameType.GROUP_UPDATE, ByteArray(0), group = groupInfo(full = false)),
            "groupleave" to envelope(FrameType.GROUP_LEAVE, WireCodec.encodePayload(GroupLeaveContent(groupId))),
            "receipt" to envelope(FrameType.RECEIPT, WireCodec.encodePayload(ReceiptContent(frameId(3)))),
            "reaction" to envelope(FrameType.REACTION, WireCodec.encodePayload(ReactionContent(frameId(3), "👍"))),
            "reaction-retract" to envelope(FrameType.REACTION, WireCodec.encodePayload(ReactionContent(frameId(3)))),
            "blobreq" to envelope(FrameType.BLOB_REQ, WireCodec.encodePayload(BlobReqContent(hash))),
            "keyreq" to envelope(FrameType.KEY_REQ, WireCodec.encodePayload(KeyReqContent(listOf(nodeId(3), nodeId(4))))),
            "typing-dm" to envelope(FrameType.TYPING, WireCodec.encodePayload(TypingContent()), recipientId = nodeId(3)),
            "typing-group" to envelope(FrameType.TYPING, WireCodec.encodePayload(TypingContent(groupId))),
        )

    @Test
    fun everyFrameShapeRoundTripsExactlyAndSmaller() {
        for ((label, signed) in fixtures()) {
            val compact = checkNotNull(FrameTranscoder.transcode(signed)) { "$label must transcode" }
            assertArrayEquals("$label rebuilds byte-exact", signed, FrameTranscoder.rebuild(compact))
            assertTrue("$label is smaller (${signed.size} → ${compact.size})", compact.size < signed.size)
            println("transcoder: $label canonical=${signed.size}B compact=${compact.size}B")
        }
    }

    @Test
    fun theGoldenVectorsPinSchemaOne() {
        val all = fixtures()
        val actual = GOLDEN.keys.associateWith { hex(checkNotNull(FrameTranscoder.transcode(all.getValue(it)))) }
        actual.forEach { (name, vector) -> println("transcoder-vector: $name $vector") }
        for ((name, expected) in GOLDEN) {
            assertEquals("transcoded $name is frozen — a changed layout is a new tag, never an edit", expected, actual.getValue(name))
        }
    }

    @Test
    @Suppress("LongMethod") // the whole label table, one line per field — the freeze is the point
    fun theLabelMapIsFrozen() {
        val expected =
            mapOf(
                "RelayEnvelope.type" to 1,
                "RelayEnvelope.id" to 2,
                "RelayEnvelope.senderId" to 3,
                "RelayEnvelope.sentAt" to 4,
                "RelayEnvelope.recipientId" to 5,
                "RelayEnvelope.group" to 6,
                "RelayEnvelope.payload" to 7,
                "GroupInfo.id" to 1,
                "GroupInfo.name" to 2,
                "GroupInfo.members" to 3,
                "GroupInfo.createdBy" to 4,
                "GroupInfo.photoHash" to 5,
                "GroupInfo.photoUpdatedAt" to 6,
                "GroupInfo.departed" to 7,
                "ChatContent.body" to 1,
                "ChatContent.mentions" to 2,
                "ChatContent.attachmentHash" to 3,
                "ChatContent.attachmentMime" to 4,
                "ChatContent.enc" to 5,
                "ChatContent.replyTo" to 6,
                "Mention.nodeId" to 1,
                "Mention.name" to 2,
                "ReplyRef.messageId" to 1,
                "ReplyRef.authorId" to 2,
                "ReplyRef.author" to 3,
                "ReplyRef.snippet" to 4,
                "ReplyRef.hasAttachment" to 5,
                "EncEnvelope.v" to 1,
                "EncEnvelope.nonce" to 2,
                "EncEnvelope.ct" to 3,
                "EncEnvelope.keys" to 4,
                "EncEnvelope.r" to 5,
                "EncEnvelope.g" to 6,
                "WrappedKey.to" to 1,
                "WrappedKey.wk" to 2,
                "RatchetHeader.se" to 1,
                "RatchetHeader.ek" to 2,
                "RatchetHeader.pe" to 3,
                "RatchetHeader.n" to 4,
                "RatchetHeader.init" to 5,
                "RatchetHeader.flags" to 6,
                "RatchetInit.eph" to 1,
                "RatchetInit.pkid" to 2,
                "RatchetInit.at" to 3,
                "GroupRatchetHeader.se" to 1,
                "GroupRatchetHeader.n" to 2,
                "ProfileContent.name" to 1,
                "ProfileContent.status" to 2,
                "ProfileContent.avatarHash" to 3,
                "ProfileContent.pubKey" to 4,
                "ProfileContent.deviceTag" to 5,
                "ProfileContent.protoVersion" to 6,
                "ProfileContent.capabilities" to 7,
                "ProfileContent.prekey" to 8,
                "ProfileContent.version" to 9,
                "PrekeyInfo.id" to 1,
                "PrekeyInfo.pub" to 2,
                "PrekeyInfo.sig" to 3,
                "ReceiptContent.ackId" to 1,
                "ReactionContent.messageId" to 1,
                "ReactionContent.emoji" to 2,
                "GroupLeaveContent.groupId" to 1,
                "BlobReqContent.hash" to 1,
                "KeyReqContent.nodeIds" to 1,
                "TypingContent.groupId" to 1,
            )
        assertEquals(expected, FrameTranscoder.schema())
    }

    // --- passthrough: what an older or newer build might have encoded ---

    @Serializable
    private class RelayEnvelopeX(
        val type: String,
        val id: String,
        val senderId: String,
        val sentAt: Long = 0L,
        val recipientId: String? = null,
        @ByteString val payload: ByteArray,
        val extra: String,
        val negative: Long,
    )

    @Serializable
    private class EncEnvelopeX(
        val v: Int = 1,
        @ByteString val nonce: ByteArray,
        @ByteString val ct: ByteArray,
        val keys: List<WrappedKey>,
        val r: RatchetHeader? = null,
        val extra: Int,
    )

    @Serializable
    private class ChatContentX(
        val body: String = "",
        val enc: EncEnvelopeX? = null,
    )

    @Serializable
    private class ProfileContentX(
        val name: String,
        val status: String,
        val pubKey: String? = null,
        val extra: Boolean = false,
    )

    /** The open-to-chat flag is outside the frozen label map: it rides as its text key plus a CBOR boolean, exact on rebuild. */
    @Test
    fun anOpenToChatProfileRidesAsTextKeyPlusBooleanAndRebuildsExact() {
        val content = ProfileContent(name = "A", status = "S", pubKey = bundle, version = SENT_AT, openToChat = true)
        val signed = envelope(FrameType.PROFILE, WireCodec.encodePayload(content))
        val compact = checkNotNull(FrameTranscoder.transcode(signed))
        assertArrayEquals(signed, FrameTranscoder.rebuild(compact))
        assertTrue("the flag travels as text key + `f5`", contains(compact, tstr("openToChat") + byteArrayOf(0xF5.toByte())))
        assertTrue("the pubKey still went raw beside it", compact.size < signed.size - 40)
    }

    /** The bound-board node is outside the frozen label map too: it rides as its text key plus a CBOR uint, exact on rebuild. */
    @Test
    fun aBoundBoardProfileRidesAsTextKeyPlusUintAndRebuildsExact() {
        val content = ProfileContent(name = "A", status = "S", pubKey = bundle, version = SENT_AT, loraNode = 0xdeadbeefL)
        val signed = envelope(FrameType.PROFILE, WireCodec.encodePayload(content))
        val compact = checkNotNull(FrameTranscoder.transcode(signed))
        assertArrayEquals(signed, FrameTranscoder.rebuild(compact))
        val uint = byteArrayOf(0x1a, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertTrue("the node travels as text key + uint", contains(compact, tstr("loraNode") + uint))
    }

    /** The board's signing key is outside the frozen label map too: text key plus the base64 as a CBOR text string, exact on rebuild. */
    @Test
    fun aBoardKeyProfileRidesAsTextKeyPlusValueAndRebuildsExact() {
        val key = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4="
        val content = ProfileContent(name = "A", status = "S", pubKey = bundle, version = SENT_AT, loraNode = 0xdeadbeefL, loraKey = key)
        val signed = envelope(FrameType.PROFILE, WireCodec.encodePayload(content))
        val compact = checkNotNull(FrameTranscoder.transcode(signed))
        assertArrayEquals(signed, FrameTranscoder.rebuild(compact))
        // 44 characters is past CBOR's one-byte text header: `78 2c` then the base64.
        val tstr44 = byteArrayOf(0x78, key.length.toByte()) + key.encodeToByteArray()
        assertTrue("the key travels as text key + tstr(44)", contains(compact, tstr("loraKey") + tstr44))
    }

    @Test
    fun unknownKeysRideAsTextAndSwitchElisionOff() {
        val futureChat =
            WireCodec.cbor.encodeToByteArray(
                ChatContentX(
                    enc =
                        EncEnvelopeX(
                            v = 3,
                            nonce = ByteArray(0),
                            ct = bytes(37, 2),
                            keys = emptyList(),
                            r = ratchetHeader(init = false),
                            extra = 7,
                        ),
                ),
            )
        val signed =
            WireCodec.cbor.encodeToByteArray(
                RelayEnvelopeX(
                    FrameType.CHAT,
                    frameId(1),
                    nodeId(2),
                    SENT_AT,
                    nodeId(3),
                    futureChat,
                    extra = "from a newer build",
                    negative = -1_234_567_890_123L,
                ),
            )
        val compact = checkNotNull(FrameTranscoder.transcode(signed))
        assertArrayEquals(signed, FrameTranscoder.rebuild(compact))
        assertTrue("the unknown key travels as its text", contains(compact, tstr("extra")))
        assertTrue(
            "…and its unknown value verbatim (a negative Long)",
            contains(compact, WireCodec.cbor.encodeToByteArray(-1_234_567_890_123L)),
        )
        assertTrue(
            "an EncEnvelope with an unknown key keeps its empty nonce explicit (label 2, h'')",
            contains(compact, byteArrayOf(0x02, 0x40)),
        )
        assertTrue("…and its empty keys (label 4, [])", contains(compact, byteArrayOf(0x04, 0x80.toByte())))

        val futureProfile = WireCodec.cbor.encodeToByteArray(ProfileContentX(name = "A", status = "S", pubKey = bundle, extra = true))
        val profileSigned = envelope(FrameType.PROFILE, futureProfile)
        val profileCompact = checkNotNull(FrameTranscoder.transcode(profileSigned))
        assertArrayEquals(profileSigned, FrameTranscoder.rebuild(profileCompact))
        assertTrue("the pubKey still went raw beside the unknown key", profileCompact.size < profileSigned.size - 40)
    }

    @Test
    fun nonCanonicalLeavesStayTextAndExact() {
        val receipt = WireCodec.encodePayload(ReceiptContent(frameId(3)))
        val shapes =
            listOf(
                "m1-id" to envelope(FrameType.RECEIPT, WireCodec.encodePayload(ReceiptContent("m1")), id = "m1"),
                "dirty-tail-id" to envelope(FrameType.RECEIPT, receipt, id = "AAAAAAAAAAAAAAAAAAAAAB"),
                "uppercase-node" to envelope(FrameType.RECEIPT, receipt, senderId = nodeId(2).uppercase()),
                "uppercase-hash" to envelope(FrameType.BLOB_REQ, WireCodec.encodePayload(BlobReqContent(hash.uppercase()))),
                "small-sentAt" to envelope(FrameType.RECEIPT, receipt, sentAt = 100L),
                "huge-sentAt" to envelope(FrameType.RECEIPT, receipt, sentAt = (1L shl 48) + 1),
                "odd-pubkey-and-tag" to
                    envelope(
                        FrameType.PROFILE,
                        WireCodec.encodePayload(
                            ProfileContent(name = "A", status = "", pubKey = "pk1", deviceTag = "tag-0123456789abcdef"),
                        ),
                    ),
                "long-group-id" to envelope(FrameType.TYPING, WireCodec.encodePayload(TypingContent("g-" + "a".repeat(26)))),
                "future-type" to envelope("future-type", WireCodec.encodePayload(ChatContent(body = "x"))),
            )
        for ((label, signed) in shapes) {
            val compact = checkNotNull(FrameTranscoder.transcode(signed)) { "$label must still transcode" }
            assertArrayEquals("$label rebuilds byte-exact", signed, FrameTranscoder.rebuild(compact))
        }
        assertTrue("a non-FrameId id stays text", contains(checkNotNull(FrameTranscoder.transcode(shapes[0].second)), tstr("m1")))
        val futurePayload = WireCodec.encodePayload(ChatContent(body = "x"))
        assertTrue(
            "an unknown type keeps its opaque payload",
            contains(checkNotNull(FrameTranscoder.transcode(shapes.last().second)), futurePayload),
        )
    }

    // --- refusals ---

    @Test
    fun aScopeTheRebuildWouldReorderIsRefusedNotMangled() {
        // An EncEnvelope hand-encoded with `nonce` BEFORE `v` — nothing kotlinx emits. Eliding the empty nonce
        // would rebuild it into its canonical slot after `v`, a different byte string: the self-check refuses.
        val enc =
            byteArrayOf(0xA4.toByte()) + tstr("nonce") + byteArrayOf(0x40) + tstr("v") + byteArrayOf(0x03) +
                tstr("ct") + byteArrayOf(0x44, 1, 2, 3, 4) + tstr("keys") + byteArrayOf(0x80.toByte())
        val payload = byteArrayOf(0xA1.toByte()) + tstr("enc") + enc
        val signed = envelope(FrameType.CHAT, payload, recipientId = nodeId(3))
        assertNull(FrameTranscoder.transcode(signed))
        val best = checkNotNull(FastFrameCodec.encodeBest(WireEnvelope(sig = ByteArray(64), signed = signed), transcode = true))
        assertTrue("the codec reports the refusal", best.transcodeRefused)
        assertEquals("…and keeps 0x03", FastFrameCodec.TAG_COMPACT, best.frame[0])
    }

    @Test
    fun malformedOrIndefiniteInputIsRefusedNeverThrown() {
        val canonical = fixtures().getValue("receipt")
        assertNull("indefinite map", FrameTranscoder.transcode(byteArrayOf(0xBF.toByte(), 0x61, 0x61, 0x01, 0xFF.toByte())))
        assertNull("truncated", FrameTranscoder.transcode(canonical.copyOf(canonical.size - 1)))
        assertNull("trailing bytes", FrameTranscoder.transcode(canonical + 0x00))
        assertNull("empty", FrameTranscoder.transcode(ByteArray(0)))
        assertNull(
            "a nesting bomb",
            FrameTranscoder.transcode(byteArrayOf(0xA1.toByte()) + tstr("z") + ByteArray(20) { 0x81.toByte() } + 0x01),
        )
        val compact = checkNotNull(FrameTranscoder.transcode(canonical))
        assertNull("an unknown label", FrameTranscoder.rebuild(compact.copyOf().also { it[1] = 0x17 }))
        assertNull("empty compact", FrameTranscoder.rebuild(ByteArray(0)))
        val rng = Random(11)
        repeat(10_000) {
            val mutated = compact.copyOf()
            val i = rng.nextInt(mutated.size)
            mutated[i] = (mutated[i].toInt() xor (1 shl rng.nextInt(Byte.SIZE_BITS))).toByte()
            FrameTranscoder.rebuild(mutated) // null or bytes — never a throw
        }
        repeat(2_000) { FrameTranscoder.transcode(rng.nextBytes(rng.nextInt(1, 300))) }
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { at -> needle.indices.all { haystack[at + it] == needle[it] } }

    private companion object {
        const val SENT_AT = 1_755_700_000_000L

        /** The advertised capability bitfield when the 0x05 transcoder shipped — see the profile fixture. */
        const val PINNED_CAPABILITIES = 0x1FFL

        /** Transcoded bytes of six fixtures — schema 1, frozen. */
        val GOLDEN =
            linkedMapOf(
                "receipt" to
                    "a50105025001080f161d242b323940474e555c636a0350020910171e252c333a41484f565d646b04460198c7dff50007" +
                    "a10150030a11181f262d343b424950575e656c",
                "enc-v3" to
                    "a60101025001080f161d242b323940474e555c636a0350020910171e252c333a41484f565d646b04460198c7dff50005" +
                    "50030a11181f262d343b424950575e656c07a105a30103035825020910171e252c333a41484f565d646b727980878e95" +
                    "9ca3aab1b8bfc6cdd4dbe2e9f0f7fe05a40101025820050c131a21282f363d444b525960676e757c838a91989fa6adb4" +
                    "bbc2c9d0d7de03000402",
                "profile" to
                    "a5010402783070726f66696c652d616965726166793665757764676f73626a6268766d786c656e6d2d31373535373030" +
                    "3030303030300350020910171e252c333a41484f565d646b04460198c7dff50007a90165416c696365026d4f75742065" +
                    "78706c6f72696e670358200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe20458400a11" +
                    "181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3141b222930373e454c535a61686f767d848b" +
                    "9299a0a7aeb5bcc3cad1d8dfe6ed0548080f161d242b32390601071901ff08a30101025820050c131a21282f363d444b" +
                    "525960676e757c838a91989fa6adb4bbc2c9d0d7de035840060d141b222930373e454c535a61686f767d848b9299a0a7" +
                    "aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bf09460198c7dff500",
                "enc-group" to
                    "a60101025001080f161d242b323940474e555c636a0350020910171e252c333a41484f565d646b04460198c7dff50006" +
                    "a7014c040b121920272e353c434a51026648696b657273038250020910171e252c333a41484f565d646b50030a11181f" +
                    "262d343b424950575e656c0450030a11181f262d343b424950575e656c0558200910171e252c333a41484f565d646b72" +
                    "7980878e959ca3aab1b8bfc6cdd4dbe206460198c7dff500078150040b121920272e353c434a51585f666d07a105a401" +
                    "02024c01080f161d242b323940474e035828020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cd" +
                    "d4dbe2e9f0f7fe050c1306a201010203",
                "typing-group" to
                    "a50109025001080f161d242b323940474e555c636a0350020910171e252c333a41484f565d646b04460198c7dff50007" +
                    "a1014c040b121920272e353c434a51",
                "keyreq" to
                    "a50108025001080f161d242b323940474e555c636a0350020910171e252c333a41484f565d646b04460198c7dff50007" +
                    "a1018250030a11181f262d343b424950575e656c50040b121920272e353c434a51585f666d",
            )
    }
}
