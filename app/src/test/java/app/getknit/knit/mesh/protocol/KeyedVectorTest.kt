package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.LinkFraming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Keyed vectors (`vectors/keyed-v1.json`): two fixed identities, the frames Alice signs, a v1 DM she seals to
 * Bob, their safety number, and the link records and advert a peer reads before any frame. Another port reads
 * the same file and must verify every signature, open the DM as Bob, and reproduce everything else exactly.
 *
 * Tink's Ed25519 is deterministic, so the signed frames are rebuilt on every run and compared byte for byte. A
 * v1 DM draws a fresh content key, nonce and HPKE ephemeral, so the pinned DM is one Alice sealed once; the
 * test proves Bob opens it. Write mode (`KNIT_WRITE_VECTORS=1`) keeps that DM while it still opens, so a
 * regeneration only moves what changed.
 */
class KeyedVectorTest {
    private val alice = VectorParty(fixtureBytes(32, 1), fixtureBytes(32, 2))
    private val bob = VectorParty(fixtureBytes(32, 3), fixtureBytes(32, 4))

    private val profileId = "profile-${alice.nodeId}-$PUBLISHED_AT"
    private val roomPostId = FrameId.fromBytes(fixtureBytes(16, 21))
    private val dmId = FrameId.fromBytes(fixtureBytes(16, 22))
    private val digestIds = listOf(profileId, roomPostId, dmId)

    /** Alice's profile, shaped like `MeshManager.currentProfileEnvelope` minus the v2 prekey. */
    private fun profileFrame(): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.PROFILE,
            id = profileId,
            senderId = alice.nodeId,
            sentAt = PUBLISHED_AT,
            payload =
                WireCodec.encodePayload(
                    ProfileContent(
                        name = "Alice",
                        status = "Testing the wire",
                        pubKey = alice.bundle.encoded,
                        deviceTag = DeviceTag.derive("vector-device-alice"),
                        protoVersion = Protocol.VERSION,
                        capabilities = CAPABILITIES,
                        version = 1L,
                    ),
                ),
        )

    private fun roomPostFrame(): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = roomPostId,
            senderId = alice.nodeId,
            sentAt = PUBLISHED_AT + 1_000L,
            payload = WireCodec.encodePayload(ChatContent(body = ROOM_BODY)),
        )

    private fun dmHeader(relay: RelayEnvelope): ByteArray =
        MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, checkNotNull(relay.recipientId))

    private fun sealDm(): ByteArray {
        val sentAt = PUBLISHED_AT + 2_000L
        val header = MessageCrypto.header(dmId, alice.nodeId, sentAt, bob.nodeId)
        val sealed =
            checkNotNull(alice.crypto.seal(MessageContent(body = DM_BODY).encode(), header, mapOf(bob.nodeId to bob.bundle)))
        return alice.signedWire(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = dmId,
                senderId = alice.nodeId,
                sentAt = sentAt,
                recipientId = bob.nodeId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
    }

    /** What Bob reads from the pinned DM, or null anywhere the chain breaks. */
    private fun openDm(
        wire: ByteArray,
        reader: VectorParty,
    ): MessageContent? {
        val envelope = WireCodec.decodeWire(wire) ?: return null
        if (!MessageCrypto.verify(alice.bundle, envelope.sig, envelope.signed)) return null
        val relay = WireCodec.decodeEnvelope(envelope.signed) ?: return null
        val enc = WireCodec.decodePayload<ChatContent>(relay.payload)?.enc ?: return null
        return reader.crypto.open(enc, dmHeader(relay), reader.nodeId)
    }

    private fun helloPayload(): ByteArray = "${alice.nodeId}|${Protocol.VERSION}|${CAPABILITIES.toString(16)}".encodeToByteArray()

    private fun helloRecord(): ByteArray = LinkFraming.encode(LinkFraming.Type.HELLO, helloPayload())

    private fun digestRecord(): ByteArray = LinkFraming.encode(LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(digestIds)))

    private fun fold(): Long = StoreDigest.fingerprint(digestIds)

    private fun advert(): ByteArray = BleAdvertPayload.encode(alice.nodeId, CAPABILITIES, fold(), PSM)

    private fun safetyNumber(): String = SafetyNumber.compute(alice.nodeId, alice.bundle.encoded, bob.nodeId, bob.bundle.encoded)

    private val file: JsonObject by lazy { VectorFiles.read(FILE) }

    private fun frameHex(name: String): String =
        file
            .getValue("frames")
            .jsonObject
            .getValue(name)
            .jsonObject
            .getValue("wire")
            .jsonPrimitive.content

    private fun link(name: String): JsonObject =
        file
            .getValue("link")
            .jsonObject
            .getValue(name)
            .jsonObject

    @Test
    fun `the identities derive their pinned bundles and node ids`() {
        assumeFalse(VectorFiles.writing)
        val identities = file.getValue("identities").jsonObject
        assertEquals(alice.toJson(), identities.getValue("alice"))
        assertEquals(bob.toJson(), identities.getValue("bob"))
        assertEquals("4cgq2pnhh6p3j3afwsue3chrpi", alice.nodeId) // ContactCardTest's identity, the same keys
    }

    @Test
    fun `alice and bob have the pinned safety number`() {
        assumeFalse(VectorFiles.writing)
        assertEquals(
            file
                .getValue("safetyNumber")
                .jsonObject
                .getValue("number")
                .jsonPrimitive.content,
            safetyNumber(),
        )
    }

    @Test
    fun `alice's signed frames rebuild byte for byte and verify`() {
        assumeFalse(VectorFiles.writing)
        for ((name, frame) in listOf("aliceProfile" to profileFrame(), "aliceRoomPost" to roomPostFrame())) {
            val pinned = frameHex(name).fromHex()
            assertEquals("keyed vector '$name' drifted", pinned.toHex(), alice.signedWire(frame).toHex())
            val envelope = checkNotNull(WireCodec.decodeWire(pinned))
            assertTrue(MessageCrypto.verify(alice.bundle, envelope.sig, envelope.signed))
            assertArrayEquals(WireCodec.encodeEnvelope(frame), envelope.signed)
        }
    }

    @Test
    fun `the pinned DM opens for bob and for nobody else`() {
        assumeFalse(VectorFiles.writing)
        val wire = frameHex("aliceDmToBob").fromHex()
        assertEquals(DM_BODY, openDm(wire, bob)?.body)
        assertNull("the sender holds no wrapped key", openDm(wire, alice))
        val relay = checkNotNull(WireCodec.decodeEnvelope(checkNotNull(WireCodec.decodeWire(wire)).signed))
        assertEquals(bob.nodeId, relay.recipientId)
        assertEquals("", WireCodec.decodePayload<ChatContent>(relay.payload)?.body)
    }

    @Test
    fun `the link records and the advert are pinned`() {
        assumeFalse(VectorFiles.writing)
        assertEquals(link("hello").getValue("record").jsonPrimitive.content, helloRecord().toHex())
        assertEquals(link("digest").getValue("record").jsonPrimitive.content, digestRecord().toHex())
        assertEquals(link("digestFold").getValue("fold").jsonPrimitive.content, "%016x".format(fold()))
        assertEquals(link("advert").getValue("serviceData").jsonPrimitive.content, advert().toHex())

        val parsedHello = Protocol.parse(helloPayload().decodeToString())
        assertEquals(alice.nodeId, parsedHello.nodeId)
        assertEquals(CAPABILITIES, parsedHello.capabilities)
        assertEquals(digestIds, LinkFraming.decodeDigest(LinkFraming.encodeDigest(DigestWire(digestIds)))?.ids)
        val parsedAdvert = checkNotNull(BleAdvertPayload.parse(advert()))
        assertEquals(alice.nodeId, parsedAdvert.nodeId)
        assertEquals(link("advert").getValue("digestCue").jsonPrimitive.long, parsedAdvert.digestCue.toLong() and 0xFFFF_FFFFL)
        assertEquals(PSM, parsedAdvert.psm)
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the keyed vectors`() {
        assumeTrue(VectorFiles.writing)
        val previousDm = runCatching { frameHex("aliceDmToBob") }.getOrNull()
        val dm = previousDm?.takeIf { openDm(it.fromHex(), bob)?.body == DM_BODY } ?: sealDm().toHex()
        assertNotNull(openDm(dm.fromHex(), bob))
        VectorFiles.write(
            FILE,
            buildJsonObject {
                put("about", ABOUT)
                putJsonObject("identities") {
                    put("alice", alice.toJson())
                    put("bob", bob.toJson())
                }
                putJsonObject("safetyNumber") {
                    putJsonArray("between") {
                        add("alice")
                        add("bob")
                    }
                    put("number", safetyNumber())
                }
                put("frames", framesJson(dm))
                put("link", linkJson())
            },
        )
    }

    private fun framesJson(dm: String): JsonObject =
        buildJsonObject {
            putJsonObject("aliceProfile") {
                put("sender", "alice")
                put("wire", alice.signedWire(profileFrame()).toHex())
            }
            putJsonObject("aliceRoomPost") {
                put("sender", "alice")
                put("body", ROOM_BODY)
                put("wire", alice.signedWire(roomPostFrame()).toHex())
            }
            putJsonObject("aliceDmToBob") {
                put("sender", "alice")
                put("recipient", "bob")
                put("body", DM_BODY)
                put("wire", dm)
            }
        }

    private fun linkJson(): JsonObject =
        buildJsonObject {
            putJsonObject("hello") {
                put("payload", helloPayload().decodeToString())
                put("record", helloRecord().toHex())
            }
            putJsonObject("digest") {
                putJsonArray("ids") { digestIds.forEach { add(it) } }
                put("record", digestRecord().toHex())
            }
            putJsonObject("digestFold") {
                putJsonArray("ids") { digestIds.forEach { add(it) } }
                put("fold", "%016x".format(fold()))
            }
            putJsonObject("advert") {
                put("nodeId", alice.nodeId)
                put("capabilities", CAPABILITIES)
                put("digestCue", fold() and 0xFFFF_FFFFL)
                put("psm", PSM)
                put("flags", 0)
                put("serviceData", advert().toHex())
            }
        }

    private companion object {
        const val FILE = "keyed-v1.json"
        const val ABOUT =
            "Keyed vectors: two fixed identities, the frames Alice signs, a v1 DM she seals to Bob, their safety number, " +
                "and the link records and advert a peer reads first. KeyedVectorTest checks them. See vectors/README.md."

        /** The publish stamp of Alice's profile, and the clock the other frames count from. */
        const val PUBLISHED_AT = 1_756_100_000_000L

        /** E2E and store-and-forward: what a peer that implements only v1 advertises. */
        const val CAPABILITIES = 0x9L

        const val PSM = 0x81
        const val ROOM_BODY = "Hello, room"
        const val DM_BODY = "Hi Bob"
    }
}
