package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.cryptoCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Golden vectors for the frozen v1 wire (definite-length CBOR, raw-key bundle). Pins the exact bytes of a
 * fixed instance of every wire type so an accidental format change — a re-typed field, a codec-config flip
 * (e.g. losing `useDefiniteLengthEncoding`), a field reorder — fails loudly, and so a future iOS/Swift codec
 * has byte-exact fixtures to validate against. See docs/WIRE_COMPAT.md and docs/IOS_PORT_REVIEW.md §2.3.
 *
 * The map headers are definite-length (`a5` = map(5), not the indefinite `bf…ff`), which is what pins the
 * v1 `useDefiniteLengthEncoding = true` flip. To regenerate after an *intended* wire break, temporarily
 * print `vectors()` + the bundle probe and paste the new hex here.
 *
 * Keyed crypto known-answer vectors (a fixed-key signature / HPKE seal) need RFC 8032 / RFC 9180 test
 * keypairs and land with the iOS client bring-up; the raw-key **bundle decode + nodeId derivation** contract
 * (what an iOS client must reproduce to be recognized) is pinned here with fixed key bytes.
 */
@OptIn(ExperimentalSerializationApi::class)
class GoldenVectorTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    /** Every wire type as a fixed instance → its encoded bytes, in a stable order. */
    @Suppress("LongMethod") // a flat list of one fixture per wire type — clearer as one block than split
    private fun vectors(): Map<String, ByteArray> =
        linkedMapOf(
            "wireEnvelope" to
                WireCodec.encodeWire(
                    WireEnvelope(ttl = 7, hops = 3, relay = false, sig = bytes(64, 1), signed = bytes(8, 2)),
                ),
            "relayEnvelope" to
                WireCodec.encodeEnvelope(
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = "m1",
                        senderId = "alice00000000000000000000aa",
                        sentAt = 100L,
                        recipientId = "bob0000000000000000000000bb",
                        payload = bytes(4, 3),
                    ),
                ),
            "chatContent" to
                WireCodec.encodePayload(
                    ChatContent(
                        body = "hi there",
                        mentions = listOf(Mention("node1", "Ann")),
                        attachmentHash = "abc123",
                        attachmentMime = "image/webp",
                    ),
                ),
            "profileContent" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 15L,
                    ),
                ),
            "groupInfo" to
                WireCodec.encodePayload(
                    GroupInfo(
                        id = "g-1",
                        name = "Team",
                        members = listOf("a", "b"),
                        createdBy = "a",
                        photoHash = "ph1",
                        photoUpdatedAt = 42L,
                    ),
                ),
            // Additive `departed` (roster-integrity change): the departed-less "groupInfo" vector above
            // must stay byte-identical forever; this pins the field's encoding when present.
            "groupInfoDeparted" to
                WireCodec.encodePayload(
                    GroupInfo(
                        id = "g-1",
                        members = listOf("a", "b"),
                        createdBy = "a",
                        departed = listOf("c"),
                    ),
                ),
            "receiptContent" to WireCodec.encodePayload(ReceiptContent(ackId = "m1")),
            "reactionContent" to WireCodec.encodePayload(ReactionContent(messageId = "m1", emoji = "👍")),
            "groupLeaveContent" to WireCodec.encodePayload(GroupLeaveContent(groupId = "g-1")),
            "keyReqContent" to WireCodec.encodePayload(KeyReqContent(nodeIds = listOf("a", "b"))),
            "blobReqContent" to WireCodec.encodePayload(BlobReqContent(hash = "h1")),
            "typingContent" to WireCodec.encodePayload(TypingContent(groupId = "g-1")),
            "mention" to WireCodec.encodePayload(Mention("node1", "Ann")),
            "replyRef" to WireCodec.encodePayload(ReplyRef("m0", "a", "Ann", "see you", hasAttachment = true)),
            "wrappedKey" to WireCodec.encodePayload(WrappedKey(to = "bob", wk = bytes(80, 4))),
            "encEnvelope" to
                WireCodec.encodePayload(
                    EncEnvelope(nonce = bytes(12, 5), ct = bytes(48, 6), keys = listOf(WrappedKey(to = "bob", wk = bytes(80, 4)))),
                ),
            // v2 (epoch ratchet) additions — the v1 fixtures above must stay byte-identical forever.
            "ratchetInit" to WireCodec.encodePayload(RatchetInit(eph = bytes(32, 7), pkid = 3, at = 1234L)),
            "ratchetHeader" to
                WireCodec.encodePayload(
                    RatchetHeader(
                        se = 2,
                        ek = bytes(32, 8),
                        pe = 1,
                        n = 5,
                        init = RatchetInit(eph = bytes(32, 7), pkid = 3, at = 1234L),
                        flags = RatchetHeader.FLAG_RESET,
                    ),
                ),
            "prekeyInfo" to WireCodec.encodePayload(PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11))),
            // A v2 envelope: `v` present (non-default), `keys` the 1-byte empty array (`80`), `r` set.
            "encEnvelopeV2" to
                WireCodec.encodePayload(
                    EncEnvelope(
                        v = EncEnvelope.VERSION_RATCHET,
                        nonce = bytes(12, 5),
                        ct = bytes(48, 6),
                        keys = emptyList(),
                        r =
                            RatchetHeader(
                                se = 1,
                                ek = bytes(32, 8),
                                pe = 0,
                                n = 0,
                                init = RatchetInit(eph = bytes(32, 7), pkid = 3, at = 1234L),
                            ),
                    ),
                ),
            // Group sender-key (v2 group form) additions — earlier fixtures stay byte-identical forever.
            "groupSeed" to WireCodec.encodePayload(GroupSeed(epoch = 3, seed = bytes(32, 7), mintedAt = 1234L)),
            "groupKeyPayload" to
                WireCodec.encodePayload(
                    GroupKeyPayload(groupId = "g-1", keys = listOf(GroupSeed(epoch = 3, seed = bytes(32, 7), mintedAt = 1234L))),
                ),
            // Sealed receipts/reactions (crypto v2 ctl additions) — the reaction ctl's `rp` payload.
            // Deliberately field-compatible with the cleartext ReactionContent (same names, same CBOR),
            // so a port can reuse one codec; the retraction form pins emoji-absent = null = retract.
            "reactionPayload" to WireCodec.encodePayload(ReactionPayload(messageId = "m1", emoji = "👍")),
            "reactionPayloadRetraction" to WireCodec.encodePayload(ReactionPayload(messageId = "m1")),
            // A group-form v2 envelope: `v` present, `keys` the 1-byte empty array, tiny `g` header, no `r`.
            "encEnvelopeGroup" to
                WireCodec.encodePayload(
                    EncEnvelope(
                        v = EncEnvelope.VERSION_RATCHET,
                        nonce = bytes(12, 5),
                        ct = bytes(48, 6),
                        keys = emptyList(),
                        g = GroupRatchetHeader(se = 2, n = 57),
                    ),
                ),
            // The additive ProfileContent.prekey field, appended after the v1 fields.
            "profileContentPrekey" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 31L,
                        prekey = PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11)),
                    ),
                ),
            // The additive ProfileContent.version field (ADR 022), appended after `prekey`. It carries the
            // profile version that used to be implicit in the envelope `sentAt`, freeing `sentAt` to be a
            // publish stamp the sender refreshes so the frame stays inside custody's `sentAt + ttl` window.
            "profileContentVersion" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 31L,
                        prekey = PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11)),
                        version = 1_700_000_000_000L,
                    ),
                ),
            // The additive ProfileContent.openToChat flag: defaulted, so it is elided while off (every fixture
            // above is byte-identical) and rides as one text key + `f5` while on. The same field on the sealed
            // ProfilePayload and its compact ProfileV2 mirror (label 5) follow below, each followed by the
            // bound-board node (`loraNode`, a text key + uint on the two wire layouts, label 6 on the compact one).
            "profileContentOpenToChat" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 31L,
                        prekey = PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11)),
                        version = 1_700_000_000_000L,
                        openToChat = true,
                    ),
                ),
            "profileContentLoraNode" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 31L,
                        prekey = PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11)),
                        version = 1_700_000_000_000L,
                        loraNode = 0xdeadbeefL,
                    ),
                ),
            // The bound board's signing key beside its number (Meshtastic 2.8 XEdDSA): base64 of the 32 raw
            // Curve25519 bytes on the two public layouts, the raw bytes on the compact sealed one.
            "profileContentLoraKey" to
                WireCodec.encodePayload(
                    ProfileContent(
                        "Ann",
                        "hiking",
                        avatarHash = "av1",
                        pubKey = "pk1",
                        deviceTag = "dt1",
                        protoVersion = 1,
                        capabilities = 31L,
                        prekey = PrekeyInfo(id = 7, pub = bytes(32, 9), sig = bytes(64, 11)),
                        version = 1_700_000_000_000L,
                        loraNode = 0xdeadbeefL,
                        loraKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=",
                    ),
                ),
            // The spool plane's shared group root (docs/SPOOL_PROTOCOL.md §3.2), gossiped as the additive
            // `gr` field of the existing group-key ctl payload. The second fixture is the root-only
            // distribution: `keys` defaults to empty and stays off the wire entirely, which is exactly the
            // shape a receiver must still adopt from.
            // The sealed profile update (CTL_PROFILE). `version` is the sender's own profile version —
            // the same number a cleartext ProfileContent frame carries as its envelope `sentAt` — so the
            // sealed and cleartext paths converge on one ordering. The second fixture is the
            // avatar-cleared shape: a null avatarHash stays off the wire entirely.
            "profilePayload" to
                WireCodec.encodePayload(ProfilePayload(name = "Ann", status = "hiking", avatarHash = "av1", version = 1700L)),
            "profilePayloadNoAvatar" to WireCodec.encodePayload(ProfilePayload(name = "Ann", status = "", version = 1700L)),
            "profilePayloadOpenToChat" to
                WireCodec.encodePayload(
                    ProfilePayload(name = "Ann", status = "hiking", avatarHash = "av1", version = 1700L, openToChat = true),
                ),
            "profilePayloadLoraNode" to
                WireCodec.encodePayload(
                    ProfilePayload(name = "Ann", status = "hiking", avatarHash = "av1", version = 1700L, loraNode = 0xdeadbeefL),
                ),
            "profilePayloadLoraKey" to
                WireCodec.encodePayload(
                    ProfilePayload(
                        name = "Ann",
                        status = "hiking",
                        avatarHash = "av1",
                        version = 1700L,
                        loraNode = 0xdeadbeefL,
                        loraKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=",
                    ),
                ),
            "groupRootPayload" to WireCodec.encodePayload(GroupRootPayload(root = bytes(32, 13), version = 2, minter = "aa")),
            "groupKeyPayloadRoot" to
                WireCodec.encodePayload(
                    GroupKeyPayload(groupId = "g-1", gr = GroupRootPayload(root = bytes(32, 13), version = 2, minter = "aa")),
                ),
            // The sealed receipt ctl plaintext, both forms (docs/ENCRYPTED_RECEIPTS_REACTIONS.md §2):
            // the single-ack tick (previously unpinned) and the additive batched form a custody-escalated
            // group tick carries — `acks` present, `ack` absent. Encoded via the production
            // MessageContent.encode() path (cryptoCbor — config-identical to WireCodec's).
            "messageContentReceipt" to
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = "m1").encode(),
            "messageContentReceiptBatch" to
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = listOf("m1", "m2")).encode(),
            // Crypto scheme v3 (ADR 059) — every fixture above stays byte-identical. The envelope is v2's DM
            // form with `v = 3` and an EMPTY nonce (`40`); the unsigned wire envelope carries `sig` as the
            // empty byte string; the compact plaintext is the labeled `MessageContentV2` layout with raw ids.
            "encEnvelopeV3" to
                WireCodec.encodePayload(
                    EncEnvelope(
                        v = EncEnvelope.VERSION_DM_V3,
                        nonce = ByteArray(0),
                        ct = bytes(48, 6),
                        keys = emptyList(),
                        r = RatchetHeader(se = 2, ek = bytes(32, 8), pe = 1, n = 5),
                    ),
                ),
            "wireEnvelopeUnsigned" to WireCodec.encodeWire(WireEnvelope(relay = false, sig = ByteArray(0), signed = bytes(8, 2))),
            "messageContentV2Plain" to compact(MessageContent(body = "hi there")),
            "messageContentV2Receipt" to compact(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = frameId(1))),
            "messageContentV2ReceiptBatch" to
                compact(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = listOf(frameId(1), frameId(2)))),
            "messageContentV2Reaction" to
                compact(
                    MessageContent(
                        body = "",
                        ctl = MessageContent.CTL_REACTION,
                        rp = ReactionPayload(messageId = frameId(1), emoji = "👍"),
                    ),
                ),
            "messageContentV2Full" to
                compact(
                    MessageContent(
                        body = "hi there",
                        mentions = listOf(Mention(nodeId(3), "Ann")),
                        attachmentHash = hex(bytes(32, 4)),
                        attachmentMime = "image/webp",
                        attachmentKey = b64(bytes(32, 5)),
                        replyTo =
                            ReplyRef(
                                messageId = frameId(6),
                                authorId = nodeId(3),
                                author = "Ann",
                                snippet = "see you",
                                hasAttachment = true,
                            ),
                        pr = ProfilePayload(name = "Ann", status = "hiking", avatarHash = hex(bytes(32, 7)), version = 1700L),
                    ),
                ),
            "messageContentV2ProfileOpenToChat" to
                compact(
                    MessageContent(
                        body = "",
                        ctl = MessageContent.CTL_PROFILE,
                        pr =
                            ProfilePayload(
                                name = "Ann",
                                status = "hiking",
                                avatarHash = hex(bytes(32, 7)),
                                version = 1700L,
                                openToChat = true,
                            ),
                    ),
                ),
            "messageContentV2ProfileLoraNode" to
                compact(
                    MessageContent(
                        body = "",
                        ctl = MessageContent.CTL_PROFILE,
                        pr =
                            ProfilePayload(
                                name = "Ann",
                                status = "hiking",
                                avatarHash = hex(bytes(32, 7)),
                                version = 1700L,
                                loraNode = 0xdeadbeefL,
                            ),
                    ),
                ),
            "messageContentV2ProfileLoraKey" to
                compact(
                    MessageContent(
                        body = "",
                        ctl = MessageContent.CTL_PROFILE,
                        pr =
                            ProfilePayload(
                                name = "Ann",
                                status = "hiking",
                                avatarHash = hex(bytes(32, 7)),
                                version = 1700L,
                                loraNode = 0xdeadbeefL,
                                loraKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=",
                            ),
                    ),
                ),
            // Arbitrary-file attachments (ADR 2026-09.qq2r): the sealed name/size, in both plaintext layouts.
            // Every fixture above stays byte-identical — the two fields are additive and absent unless set,
            // which is what makes an image or a voice note's frame the same bytes it was before files existed.
            "messageContentFile" to
                MessageContent(
                    body = "",
                    attachmentHash = hex(bytes(32, 4)),
                    attachmentMime = "application/pdf",
                    attachmentKey = b64(bytes(32, 5)),
                    attachmentName = "report.pdf",
                    attachmentSize = 1_400_000L,
                ).encode(),
            "messageContentV2File" to
                compact(
                    MessageContent(
                        body = "",
                        attachmentHash = hex(bytes(32, 4)),
                        attachmentMime = "application/pdf",
                        attachmentKey = b64(bytes(32, 5)),
                        attachmentName = "report.pdf",
                        attachmentSize = 1_400_000L,
                    ),
                ),
            // Link-preview cards: the container a card attachment's blob holds. Its own type under its own MIME,
            // so no fixture above moves; `v` is required and always emitted (an elided version cannot gate), and
            // the text-only shape pins that an absent picture costs no bytes at all.
            "linkPreviewBlob" to
                LinkPreviewBlob(
                    v = LinkPreviewBlob.VERSION,
                    url = "https://example.com/a?b=1",
                    title = "Title",
                    description = "Desc",
                    image = bytes(8, 12),
                    imageMime = "image/webp",
                ).encode(),
            "linkPreviewBlobTextOnly" to
                LinkPreviewBlob(v = LinkPreviewBlob.VERSION, url = "https://example.com/", title = "Title").encode(),
        )

    private fun compact(content: MessageContent): ByteArray =
        checkNotNull(MessageContentV2.encodeOrNull(content)) {
            "fixture must be compact-encodable"
        }

    private fun frameId(seed: Int): String = FrameId.fromBytes(bytes(16, seed))

    private fun nodeId(seed: Int): String = NodeId.fromBytes(bytes(16, seed))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `every wire type matches its pinned definite-length CBOR`() {
        vectors().forEach { (name, encoded) ->
            assertEquals("golden vector '$name' drifted — an unintended wire change", EXPECTED.getValue(name), encoded.toHex())
        }
    }

    @Test
    fun `the two envelopes decode from their pinned bytes and re-encode identically`() {
        val wire = EXPECTED.getValue("wireEnvelope").fromHex()
        assertArrayEquals(wire, WireCodec.encodeWire(requireNotNull(WireCodec.decodeWire(wire))))
        val relay = EXPECTED.getValue("relayEnvelope").fromHex()
        assertArrayEquals(relay, WireCodec.encodeEnvelope(requireNotNull(WireCodec.decodeEnvelope(relay))))
    }

    @Test
    fun `raw-key bundle matches its pinned encoding, decodes, and derives its pinned nodeId`() {
        // An independent encoder producing the same raw-key CBOR layout (what an iOS client emits) must match
        // byte-for-byte, decode via the production path, and derive the same self-certifying nodeId.
        val bundle = b64(cryptoCbor.encodeToByteArray(BundleProbe(sigPub = bytes(32, 10), hpkePub = bytes(32, 20))))
        assertEquals(BUNDLE_ENCODED, bundle)
        assertNotNull("raw-key bundle must decode", PublicKeyBundle.decode(bundle))
        assertEquals(BUNDLE_NODE_ID, NodeId.fromPublicKeyBundle(bundle))
    }

    /** Mirror of the private `PublicKeyBundle.Proto` (same field names/order/@ByteString) for the vector. */
    @Serializable
    private class BundleProbe(
        @ByteString val sigPub: ByteArray,
        @ByteString val hpkePub: ByteArray,
    )

    private companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val EXPECTED =
            mapOf(
                "wireEnvelope" to
                    "a56374746c0764686f7073036572656c6179f463736967584001080f161d242b323940474e555c636a71787f868d949ba2a9b0b7be" +
                    "c5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3ba667369676e656448020910171e252c33",
                "relayEnvelope" to
                    "a664747970656463686174626964626d316873656e6465724964781b616c696365303030303030303030303030303030303030303061" +
                    "616673656e74417418646b726563697069656e744964781b626f62303030303030303030303030303030303030303030306262677061" +
                    "796c6f616444030a1118",
                "chatContent" to
                    "a464626f6479686869207468657265686d656e74696f6e7381a2666e6f64654964656e6f646531646e616d6563416e6e6e6174746163" +
                    "686d656e7448617368666162633132336e6174746163686d656e744d696d656a696d6167652f77656270",
                "profileContent" to
                    "a7646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c69746965730f",
                "groupInfo" to
                    "a662696463672d31646e616d65645465616d676d656d6265727382616161626963726561746564427961616970686f746f4861736863" +
                    "7068316e70686f746f557064617465644174182a",
                "groupInfoDeparted" to
                    "a462696463672d31676d656d626572738261616162696372656174656442796161686465706172746564816163",
                "receiptContent" to "a16561636b4964626d31",
                "reactionContent" to "a2696d6573736167654964626d3165656d6f6a6964f09f918d",
                "reactionPayload" to "a2696d6573736167654964626d3165656d6f6a6964f09f918d",
                "reactionPayloadRetraction" to "a1696d6573736167654964626d31",
                "groupLeaveContent" to "a16767726f7570496463672d31",
                "keyReqContent" to "a1676e6f64654964738261616162",
                "blobReqContent" to "a16468617368626831",
                "typingContent" to "a16767726f7570496463672d31",
                "mention" to "a2666e6f64654964656e6f646531646e616d6563416e6e",
                "replyRef" to
                    "a5696d6573736167654964626d3068617574686f724964616166617574686f7263416e6e67736e69707065746773656520796f756d68" +
                    "61734174746163686d656e74f5",
                "wrappedKey" to
                    "a262746f63626f6262776b5850040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c" +
                    "232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11181f262d",
                "encEnvelope" to
                    "a3656e6f6e63654c050c131a21282f363d444b526263745830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3ca" +
                    "d1d8dfe6edf4fb020910171e252c333a41484f646b65797381a262746f63626f6262776b5850040b121920272e353c434a51585f666d" +
                    "747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7" +
                    "eef5fc030a11181f262d",
                "ratchetInit" to
                    "a3636570685820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e064706b6964036261741904d2",
                "ratchetHeader" to
                    "a66273650262656b5820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae162706501616e0564696e6974" +
                    "a3636570685820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e064706b6964036261741904d265666c" +
                    "61677301",
                "prekeyInfo" to
                    "a3626964076370756258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b12192027" +
                    "2e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1" +
                    "a8afb6bdc4",
                "encEnvelopeV2" to
                    "a5617602656e6f6e63654c050c131a21282f363d444b526263745830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5" +
                    "bcc3cad1d8dfe6edf4fb020910171e252c333a41484f646b657973806172a56273650162656b5820080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3dae162706500616e0064696e6974a3636570685820070e151c232a31383f464d545b62697077" +
                    "7e858c939aa1a8afb6bdc4cbd2d9e064706b6964036261741904d2",
                "groupSeed" to
                    "a36565706f6368036473656564" +
                    "5820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0686d696e74656441741904d2",
                "groupKeyPayload" to
                    "a26767726f7570496463672d31646b65797381a36565706f63680364736565645820070e151c232a31383f464d545b626970777e85" +
                    "8c939aa1a8afb6bdc4cbd2d9e0686d696e74656441741904d2",
                "encEnvelopeGroup" to
                    "a5617602656e6f6e63654c050c131a21282f363d444b526263745830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5" +
                    "bcc3cad1d8dfe6edf4fb020910171e252c333a41484f646b657973806167a262736502616e1839",
                "profileContentPrekey" to
                    "a8646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c6974696573181f667072656b6579a362696407637075" +
                    "6258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4",
                "profileContentVersion" to
                    "a9646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c6974696573181f667072656b6579a362696407637075" +
                    "6258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4" +
                    "6776657273696f6e1b0000018bcfe56800",
                "profileContentOpenToChat" to
                    "aa646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c6974696573181f667072656b6579a362696407637075" +
                    "6258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4" +
                    "6776657273696f6e1b0000018bcfe56800" +
                    "6a6f70656e546f43686174f5",
                "profileContentLoraNode" to
                    "aa646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c6974696573181f667072656b6579a362696407637075" +
                    "6258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4" +
                    "6776657273696f6e1b0000018bcfe56800" +
                    "686c6f72614e6f64651adeadbeef",
                "profileContentLoraKey" to
                    "ab646e616d6563416e6e667374617475736668696b696e676a6176617461724861736863617631667075624b657963706b3169646576" +
                    "696365546167636474316c70726f746f56657273696f6e016c6361706162696c6974696573181f667072656b6579a362696407637075" +
                    "6258200910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe26373696758400b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4" +
                    "6776657273696f6e1b0000018bcfe56800" +
                    "686c6f72614e6f64651adeadbeef" +
                    "676c6f72614b6579782c6f523632494a6d4655453054676377304763797055355a71554643516c6c56427932736e422f424b5141343d",
                "profilePayload" to
                    "a4646e616d6563416e6e667374617475736668696b696e676a617661746172486173" +
                    "6863617631" +
                    "6776657273696f6e1906a4",
                "profilePayloadNoAvatar" to "a3646e616d6563416e6e66737461747573606776657273696f6e1906a4",
                "profilePayloadOpenToChat" to
                    "a5646e616d6563416e6e667374617475736668696b696e676a617661746172486173" +
                    "6863617631" +
                    "6776657273696f6e1906a4" +
                    "6a6f70656e546f43686174f5",
                "profilePayloadLoraNode" to
                    "a5646e616d6563416e6e667374617475736668696b696e676a617661746172486173" +
                    "6863617631" +
                    "6776657273696f6e1906a4" +
                    "686c6f72614e6f64651adeadbeef",
                "profilePayloadLoraKey" to
                    "a6646e616d6563416e6e667374617475736668696b696e676a617661746172486173" +
                    "6863617631" +
                    "6776657273696f6e1906a4" +
                    "686c6f72614e6f64651adeadbeef" +
                    "676c6f72614b6579782c6f523632494a6d4655453054676377304763797055355a71554643516c6c56427932736e422f424b5141343d",
                "groupRootPayload" to
                    "a364726f6f7458200d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6" +
                    "6776657273696f6e02666d696e746572626161",
                "groupKeyPayloadRoot" to
                    "a26767726f7570496463672d31626772a364726f6f7458200d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3ca" +
                    "d1d8dfe66776657273696f6e02666d696e746572626161",
                "messageContentReceipt" to "a364626f6479606363746c056361636b626d31",
                "messageContentReceiptBatch" to "a364626f6479606363746c056461636b7382626d31626d32",
                "encEnvelopeV3" to
                    "a5617603656e6f6e6365406263745830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb0209" +
                    "10171e252c333a41484f646b657973806172a46273650262656b5820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7" +
                    "bec5ccd3dae162706501616e05",
                "wireEnvelopeUnsigned" to "a36572656c6179f46373696740667369676e656448020910171e252c33",
                "messageContentV2Plain" to "a101686869207468657265",
                "messageContentV2Receipt" to "a20705085001080f161d242b323940474e555c636a",
                "messageContentV2ReceiptBatch" to "a2070509825001080f161d242b323940474e555c636a50020910171e252c333a41484f565d646b",
                "messageContentV2Reaction" to "a207060aa2015001080f161d242b323940474e555c636a0264f09f918d",
                "messageContentV2Full" to
                    "a7016868692074686572650281a20150030a11181f262d343b424950575e656c0263416e6e035820040b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dd046a696d6167652f77656270055820050c131a21282f363d444b525960676e757c838a91" +
                    "989fa6adb4bbc2c9d0d7de06a50150060d141b222930373e454c535a61686f0250030a11181f262d343b424950575e656c0363416e6e" +
                    "046773656520796f7505f50ba40163416e6e026668696b696e67035820070e151c232a31383f464d545b626970777e858c939aa1a8af" +
                    "b6bdc4cbd2d9e0041906a4",
                "messageContentV2ProfileOpenToChat" to
                    "a207080ba50163416e6e026668696b696e67035820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0" +
                    "041906a405f5",
                "messageContentV2ProfileLoraNode" to
                    "a207080ba50163416e6e026668696b696e67035820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0" +
                    "041906a4061adeadbeef",
                "messageContentV2ProfileLoraKey" to
                    "a207080ba60163416e6e026668696b696e67035820070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0" +
                    "041906a4061adeadbeef" +
                    "075820a11eb6209985504d1381cc3419cca953966a505090965541cb6b2707f04a400e",
                "messageContentFile" to
                    "a664626f6479606e6174746163686d656e744861736878403034306231323139323032373265333533633433346135313538" +
                    "35663636366437343762383238393930393739656135616362336261633163386366643664646e6174746163686d656e744d" +
                    "696d656f6170706c69636174696f6e2f7064666d6174746163686d656e744b6579782c425177544769456f4c7a5939524574" +
                    "535757426e626e5638673471526d4a2b6d7262533777736e513139343d6e6174746163686d656e744e616d656a7265706f72" +
                    "742e7064666e6174746163686d656e7453697a651a00155cc0",
                "messageContentV2File" to
                    "a5035820040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd046f6170706c69636174696f6e2f" +
                    "706466055820050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7de0e6a7265706f72742e706466" +
                    "0f1a00155cc0",
                "linkPreviewBlob" to
                    "a66176016375726c781968747470733a2f2f6578616d706c652e636f6d2f613f623d31657469746c65655469746c656b646573" +
                    "6372697074696f6e644465736365696d616765480c131a21282f363d69696d6167654d696d656a696d6167652f77656270",
                "linkPreviewBlobTextOnly" to "a36176016375726c7468747470733a2f2f6578616d706c652e636f6d2f657469746c65655469746c65",
            )

        const val BUNDLE_ENCODED =
            "omZzaWdQdWJYIAoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dzjZ2hwa2VQ" +
                "dWJYIBQbIikwNz5FTFNaYWhvdn2Ei5KZoKeutbzDytHY3+bt"
        const val BUNDLE_NODE_ID = "cswad43wmlont27jr4tyvu63i4"
    }
}
