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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Golden vectors for the frozen v1 wire (definite-length CBOR, raw-key bundle). Pins the exact bytes of a
 * fixed instance of every wire type so an accidental format change — a re-typed field, a codec-config flip
 * (e.g. losing `useDefiniteLengthEncoding`), a field reorder — fails loudly. The expected bytes live in
 * `vectors/wire-v1.json`, which the iOS port copies and tests its own codec against byte for byte; see
 * `vectors/README.md` and docs/WIRE_COMPAT.md.
 *
 * The map headers are definite-length (`a5` = map(5), not the indefinite `bf…ff`), which is what pins the
 * v1 `useDefiniteLengthEncoding = true` flip. To regenerate after an *intended* wire change, run this class
 * with `KNIT_WRITE_VECTORS=1` and review the diff of the JSON: every byte that moved is in it.
 *
 * Keyed vectors — fixed identities, signed frames, a sealed DM, the safety number — are in
 * [KeyedVectorTest]; frames the iOS port emits are checked by [IosEmittedVectorTest].
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
            // The sealed direct-transfer ctl plaintext (an OFFER): the additive `xf` beside the ctl marker.
            "messageContentTransferOffer" to
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_TRANSFER,
                    xf =
                        TransferPayload(
                            id = frameId(3),
                            phase = TransferPayload.PHASE_OFFER,
                            name = "clip.mp4",
                            size = 123_456_789L,
                            mime = "video/mp4",
                        ),
                ).encode(),
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
            // The commons post (docs/SPOOL_PROTOCOL.md §7.4): a new `commons` type wrapping an ordinary
            // ChatContent behind the room's 32-byte scope id. Additive — nothing above changes.
            "commonsPost" to WireCodec.encodePayload(CommonsPost(scope = bytes(32, 14), chat = ChatContent(body = "hello"))),
            "commonsPostFull" to
                WireCodec.encodePayload(
                    CommonsPost(
                        scope = bytes(32, 14),
                        chat =
                            ChatContent(
                                body = "hi @Ann",
                                mentions = listOf(Mention(nodeId(3), "Ann")),
                                replyTo = ReplyRef(messageId = frameId(6), authorId = nodeId(3), author = "Ann", snippet = "see you"),
                            ),
                    ),
                ),
            // The seed's founding roster (work item #47): the additive `group` beside `keys` and `gr`. The
            // roster half of GroupInfo only — no photo fields — which is what GroupEntity.toFoundingInfo emits.
            "groupKeyPayloadRoster" to
                WireCodec.encodePayload(
                    GroupKeyPayload(
                        groupId = "g-1",
                        keys = listOf(GroupSeed(epoch = 3, seed = bytes(32, 7), mintedAt = 1234L)),
                        gr = GroupRootPayload(root = bytes(32, 13), version = 2, minter = "aa"),
                        group =
                            GroupInfo(
                                id = "g-1",
                                name = "Team",
                                members = listOf("aa", "bb"),
                                createdBy = "aa",
                                departed = listOf("cc"),
                            ),
                    ),
                ),
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
        assumeFalse("KNIT_WRITE_VECTORS=1 rewrites the file instead", VectorFiles.writing)
        assertEquals("vectors/$FILE must name exactly the fixtures built here", vectors().keys, expected.keys)
        vectors().forEach { (name, encoded) ->
            assertEquals("golden vector '$name' drifted — an unintended wire change", expected.getValue(name), encoded.toHex())
        }
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the vector file from the fixtures`() {
        assumeTrue(VectorFiles.writing)
        val bundle = probeBundle()
        VectorFiles.write(
            FILE,
            buildJsonObject {
                put("about", ABOUT)
                putJsonObject("vectors") { vectors().forEach { (name, encoded) -> put(name, encoded.toHex()) } }
                putJsonObject("bundle") {
                    put("encoded", bundle)
                    put("nodeId", NodeId.fromPublicKeyBundle(bundle))
                }
            },
        )
    }

    @Test
    fun `the two envelopes decode from their pinned bytes and re-encode identically`() {
        assumeFalse("KNIT_WRITE_VECTORS=1 rewrites the file instead", VectorFiles.writing)
        val wire = expected.getValue("wireEnvelope").fromHex()
        assertArrayEquals(wire, WireCodec.encodeWire(requireNotNull(WireCodec.decodeWire(wire))))
        val relay = expected.getValue("relayEnvelope").fromHex()
        assertArrayEquals(relay, WireCodec.encodeEnvelope(requireNotNull(WireCodec.decodeEnvelope(relay))))
    }

    @Test
    fun `raw-key bundle matches its pinned encoding, decodes, and derives its pinned nodeId`() {
        assumeFalse("KNIT_WRITE_VECTORS=1 rewrites the file instead", VectorFiles.writing)
        // An independent encoder producing the same raw-key CBOR layout (what an iOS client emits) must match
        // byte-for-byte, decode via the production path, and derive the same self-certifying nodeId.
        val bundle = probeBundle()
        assertEquals(bundleEncoded, bundle)
        assertNotNull("raw-key bundle must decode", PublicKeyBundle.decode(bundle))
        assertEquals(bundleNodeId, NodeId.fromPublicKeyBundle(bundle))
    }

    private fun probeBundle(): String = b64(cryptoCbor.encodeToByteArray(BundleProbe(sigPub = bytes(32, 10), hpkePub = bytes(32, 20))))

    /** Mirror of the private `PublicKeyBundle.Proto` (same field names/order/@ByteString) for the vector. */
    @Serializable
    private class BundleProbe(
        @ByteString val sigPub: ByteArray,
        @ByteString val hpkePub: ByteArray,
    )

    private companion object {
        const val FILE = "wire-v1.json"
        const val ABOUT =
            "Knit's frozen v1 wire: the definite-length CBOR of one fixed instance of every wire type, and the raw-key " +
                "bundle probe. GoldenVectorTest builds each fixture and compares it with these bytes. See vectors/README.md."

        private val file by lazy { VectorFiles.read(FILE) }
        val expected: Map<String, String> by lazy {
            file.getValue("vectors").jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
        val bundleEncoded: String by lazy {
            file
                .getValue("bundle")
                .jsonObject
                .getValue("encoded")
                .jsonPrimitive.content
        }
        val bundleNodeId: String by lazy {
            file
                .getValue("bundle")
                .jsonObject
                .getValue("nodeId")
                .jsonPrimitive.content
        }
    }
}
