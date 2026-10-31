package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FileHeaderWire
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
 * test proves Bob opens it and that it re-encodes to its own bytes. Write mode (`KNIT_WRITE_VECTORS=1`) keeps
 * that DM while it is still current by both tests, and reseals it otherwise, so a regeneration only moves what
 * changed.
 */
class KeyedVectorTest {
    private val alice = VectorParty(fixtureBytes(32, 1), fixtureBytes(32, 2))
    private val bob = VectorParty(fixtureBytes(32, 3), fixtureBytes(32, 4))

    private val profileId = "profile-${alice.nodeId}-$PUBLISHED_AT"
    private val roomPostId = FrameId.fromBytes(fixtureBytes(16, 21))
    private val dmId = FrameId.fromBytes(fixtureBytes(16, 22))
    private val digestIds = listOf(profileId, roomPostId, dmId)
    private val fileKey = fixtureBytes(32, 23).toHex()

    /** Alice's profile, shaped like `MeshManager.currentProfileEnvelope` minus the v2 prekey. */
    private fun profileFrame(
        sentAt: Long = PUBLISHED_AT,
        status: String = "Testing the wire",
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.PROFILE,
            id = "profile-${alice.nodeId}-$sentAt",
            senderId = alice.nodeId,
            sentAt = sentAt,
            payload =
                WireCodec.encodePayload(
                    ProfileContent(
                        name = "Alice",
                        status = status,
                        pubKey = alice.bundle.encoded,
                        deviceTag = DeviceTag.derive("vector-device-alice"),
                        protoVersion = Protocol.VERSION,
                        capabilities = CAPABILITIES,
                        version = 1L,
                    ),
                ),
        )

    /**
     * Alice's profile, published at [sentAt], with its `status` padded so the frame's `signed` is exactly [signedBytes]
     * long: the profile cap's edge (#234). A text string's length prefix grows at 24, 256 and 65,536, so a step can
     * overshoot by its width: walk back to the size, as `LabSybil.dm` does.
     */
    private fun cappedProfileFrame(
        sentAt: Long,
        signedBytes: Int,
    ): RelayEnvelope {
        fun frame(n: Int) = profileFrame(sentAt, PAD.repeat(n))
        var n = maxOf(0, signedBytes - WireCodec.encodeEnvelope(frame(0)).size)
        while (n > 0 && WireCodec.encodeEnvelope(frame(n)).size > signedBytes) n--
        val env = frame(n)
        check(WireCodec.encodeEnvelope(env).size == signedBytes) { "no status length makes a $signedBytes-byte profile" }
        return env
    }

    private fun atCapFrame(): RelayEnvelope = cappedProfileFrame(AT_CAP_AT, PROFILE_CAP)

    private fun overCapFrame(): RelayEnvelope = cappedProfileFrame(OVER_CAP_AT, PROFILE_CAP + 1)

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
        val header = MessageCrypto.header(dmId, alice.nodeId, DM_SENT_AT, bob.nodeId)
        val sealed =
            checkNotNull(alice.crypto.seal(MessageContent(body = DM_BODY).encode(), header, mapOf(bob.nodeId to bob.bundle)))
        return alice.signedWire(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = dmId,
                senderId = alice.nodeId,
                sentAt = DM_SENT_AT,
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

    /**
     * Whether a pinned DM is still what Alice would send today, short of the fresh key material: both layers
     * and the payload re-encode to their own bytes, and the id, clock and parties are the ones [sealDm] uses.
     * Opening alone is not enough — a decoder that still reads an older layout would keep a stale DM.
     */
    private fun isCurrentDm(wire: ByteArray): Boolean {
        val envelope = WireCodec.decodeWire(wire) ?: return false
        val relay = WireCodec.decodeEnvelope(envelope.signed) ?: return false
        val chat = WireCodec.decodePayload<ChatContent>(relay.payload) ?: return false
        return wire.contentEquals(WireCodec.encodeWire(envelope)) &&
            envelope.signed.contentEquals(WireCodec.encodeEnvelope(relay)) &&
            relay.payload.contentEquals(WireCodec.encodePayload(chat)) &&
            relay.type == FrameType.CHAT &&
            relay.id == dmId &&
            relay.senderId == alice.nodeId &&
            relay.sentAt == DM_SENT_AT &&
            relay.recipientId == bob.nodeId &&
            openDm(wire, bob)?.body == DM_BODY
    }

    private fun helloPayload(): ByteArray = "${alice.nodeId}|${Protocol.VERSION}|${CAPABILITIES.toString(16)}".encodeToByteArray()

    private fun helloRecord(): ByteArray = LinkFraming.encode(LinkFraming.Type.HELLO, helloPayload())

    private fun digestRecord(): ByteArray = LinkFraming.encode(LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(digestIds)))

    private fun fold(): Long = StoreDigest.fingerprint(digestIds)

    private fun advert(): ByteArray = BleAdvertPayload.encode(alice.nodeId, CAPABILITIES, fold(), PSM)

    // A photo's header as a sender writes it, the stream's length included (#115).
    private fun fileHeaderRecord(): ByteArray =
        LinkFraming.encode(
            LinkFraming.Type.FILE_HEADER,
            LinkFraming.encodeFileHeader(FileHeaderWire(FileKind.ATTACHMENT.wire, fileKey, FILE_MIME, size = FILE_SIZE)),
        )

    // The same photo's header when the stream is the rest of a transfer cut off at FILE_OFFSET (#116): the size is
    // the tail's, and the offset rides last.
    private fun fileHeaderResumedRecord(): ByteArray =
        LinkFraming.encode(
            LinkFraming.Type.FILE_HEADER,
            LinkFraming.encodeFileHeader(
                FileHeaderWire(FileKind.ATTACHMENT.wire, fileKey, FILE_MIME, size = FILE_SIZE - FILE_OFFSET, offset = FILE_OFFSET),
            ),
        )

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
        val frames =
            listOf(
                "aliceProfile" to profileFrame(),
                "aliceRoomPost" to roomPostFrame(),
                "aliceProfileAtCap" to atCapFrame(),
                "aliceProfileOverCap" to overCapFrame(),
            )
        for ((name, frame) in frames) {
            val pinned = frameHex(name).fromHex()
            assertEquals("keyed vector '$name' drifted", pinned.toHex(), alice.signedWire(frame).toHex())
            val envelope = checkNotNull(WireCodec.decodeWire(pinned))
            assertTrue(MessageCrypto.verify(alice.bundle, envelope.sig, envelope.signed))
            assertArrayEquals(WireCodec.encodeEnvelope(frame), envelope.signed)
        }
    }

    /** The profile cap's two edge frames are exactly the cap and one byte over it, and say so (#234). */
    @Test
    fun `the profile cap frames sit on its edge`() {
        assumeFalse(VectorFiles.writing)
        for ((name, size) in listOf("aliceProfileAtCap" to PROFILE_CAP, "aliceProfileOverCap" to PROFILE_CAP + 1)) {
            val entry =
                file
                    .getValue("frames")
                    .jsonObject
                    .getValue(name)
                    .jsonObject
            assertEquals(size.toLong(), entry.getValue("signedBytes").jsonPrimitive.long)
            assertEquals(size, checkNotNull(WireCodec.decodeWire(frameHex(name).fromHex())).signed.size)
        }
        assertEquals(8_192, PROFILE_CAP)
    }

    @Test
    fun `the pinned DM opens for bob and for nobody else`() {
        assumeFalse(VectorFiles.writing)
        val wire = frameHex("aliceDmToBob").fromHex()
        assertEquals(DM_BODY, openDm(wire, bob)?.body)
        assertTrue("the pinned DM is not what Alice sends today; regenerate with KNIT_WRITE_VECTORS=1", isCurrentDm(wire))
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
        assertEquals(link("fileHeader").getValue("record").jsonPrimitive.content, fileHeaderRecord().toHex())
        assertEquals(link("fileHeaderResumed").getValue("record").jsonPrimitive.content, fileHeaderResumedRecord().toHex())

        val parsedHello = checkNotNull(Protocol.parse(helloPayload().decodeToString()))
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
        val dm = previousDm?.takeIf { isCurrentDm(it.fromHex()) } ?: sealDm().toHex()
        assertTrue(isCurrentDm(dm.fromHex()))
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
            putJsonObject("aliceProfileAtCap") {
                put("sender", "alice")
                put("signedBytes", PROFILE_CAP)
                put("wire", alice.signedWire(atCapFrame()).toHex())
            }
            putJsonObject("aliceProfileOverCap") {
                put("sender", "alice")
                put("signedBytes", PROFILE_CAP + 1)
                put("wire", alice.signedWire(overCapFrame()).toHex())
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
            putJsonObject("fileHeader") {
                put("kind", FileKind.ATTACHMENT.wire)
                put("key", fileKey)
                put("mime", FILE_MIME)
                put("size", FILE_SIZE)
                put("record", fileHeaderRecord().toHex())
            }
            putJsonObject("fileHeaderResumed") {
                put("kind", FileKind.ATTACHMENT.wire)
                put("key", fileKey)
                put("mime", FILE_MIME)
                put("size", FILE_SIZE - FILE_OFFSET)
                put("offset", FILE_OFFSET)
                put("record", fileHeaderResumedRecord().toHex())
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
                "the link records a peer reads, and the advert it reads first. aliceProfileAtCap and aliceProfileOverCap are " +
                "her profile with its status padded so `signed` is exactly the profile cap (8,192 bytes) and one byte over: a " +
                "receiver pins the first and refuses the second before decoding it. KeyedVectorTest checks them. See vectors/README.md."

        /** The publish stamp of Alice's profile, and the clock the other frames count from. */
        const val PUBLISHED_AT = 1_756_100_000_000L

        /** E2E and store-and-forward: what a peer that implements only v1 advertises. */
        const val CAPABILITIES = 0x9L

        const val PSM = 0x81
        const val DM_SENT_AT = PUBLISHED_AT + 2_000L
        const val ROOM_BODY = "Hello, room"
        const val FILE_MIME = "image/jpeg"
        const val FILE_SIZE = 203_807L
        const val FILE_OFFSET = 81_920L
        const val DM_BODY = "Hi Bob"

        /** The profile cap the two edge frames straddle (#234). */
        const val PROFILE_CAP = ForwardRepository.DEFAULT_MAX_PROFILE_SIGNED_BYTES
        const val AT_CAP_AT = PUBLISHED_AT + 3_000L
        const val OVER_CAP_AT = PUBLISHED_AT + 4_000L

        /** The status padding: one byte of UTF-8 a unit, so its count is its byte length. */
        const val PAD = "x"
    }
}
