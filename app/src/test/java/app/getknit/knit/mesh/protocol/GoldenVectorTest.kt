package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
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
 * Golden vectors for the frozen v22 wire (definite-length CBOR, raw-key bundle). Pins the exact bytes of a
 * fixed instance of every wire type so an accidental format change — a re-typed field, a codec-config flip
 * (e.g. losing `useDefiniteLengthEncoding`), a field reorder — fails loudly, and so a future iOS/Swift codec
 * has byte-exact fixtures to validate against. See docs/WIRE_COMPAT.md and docs/IOS_PORT_REVIEW.md §2.3.
 *
 * The map headers are definite-length (`a5` = map(5), not the indefinite `bf…ff`), which is what pins the
 * v22 `useDefiniteLengthEncoding = true` flip. To regenerate after an *intended* wire break, temporarily
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
                        protoVersion = 3,
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
        )

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
                    "696365546167636474316c70726f746f56657273696f6e036c6361706162696c69746965730f",
                "groupInfo" to
                    "a662696463672d31646e616d65645465616d676d656d6265727382616161626963726561746564427961616970686f746f4861736863" +
                    "7068316e70686f746f557064617465644174182a",
                "receiptContent" to "a16561636b4964626d31",
                "reactionContent" to "a2696d6573736167654964626d3165656d6f6a6964f09f918d",
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
            )

        const val BUNDLE_ENCODED =
            "omZzaWdQdWJYIAoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dzjZ2hwa2VQ" +
                "dWJYIBQbIikwNz5FTFNaYWhvdn2Ei5KZoKeutbzDytHY3+bt"
        const val BUNDLE_NODE_ID = "cswad43wmlont27jr4tyvu63i4"
    }
}
