package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.crypto.LinkKeys
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FileHeaderWire
import app.getknit.knit.mesh.link.LinkFraming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    // The link proof (ADR 2026-11.264w): Alice dials Bob, nonces 00..0f and 10..1f, both HELLOs at caps 0x40009.
    private val aliceProver = LinkProver(alice.bundle, alice::sign)
    private val bobProver = LinkProver(bob.bundle, bob::sign)

    private fun proofHello(
        nodeId: String,
        nonce: ByteArray,
    ): ByteArray = "$nodeId|${Protocol.VERSION}|${PROOF_CAPABILITIES.toString(16)}|n${nonce.toHex()}".encodeToByteArray()

    private val nonceD = ByteArray(Protocol.HELLO_NONCE_BYTES) { it.toByte() }
    private val helloD = proofHello(alice.nodeId, nonceD)
    private val helloR = proofHello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x10 + it).toByte() })

    private fun proofRecord(payload: ByteArray): ByteArray = LinkFraming.encode(LinkFraming.Type.PROOF, payload)

    private fun proofDialer(): ByteArray = proofRecord(aliceProver.proof(LinkProof.Role.DIALER, helloD, helloR))

    private fun proofResponder(): ByteArray = proofRecord(bobProver.proof(LinkProof.Role.RESPONDER, helloD, helloR))

    /** Records that must fail against the dialer's transcript, each with why: name to (record, expected failure). */
    private fun proofRejects(): List<Triple<String, LinkProof.Failure, ByteArray>> {
        val ffReply = proofHello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { -1 })
        return listOf(
            Triple("wrongRole", LinkProof.Failure.SIGNATURE, aliceProver.proof(LinkProof.Role.RESPONDER, helloD, helloR)),
            Triple("otherResponderNonce", LinkProof.Failure.SIGNATURE, aliceProver.proof(LinkProof.Role.DIALER, helloD, ffReply)),
            Triple("idMismatch", LinkProof.Failure.ID_MISMATCH, bobProver.proof(LinkProof.Role.DIALER, helloD, helloR)),
            Triple(
                "short",
                LinkProof.Failure.LENGTH,
                aliceProver.proof(LinkProof.Role.DIALER, helloD, helloR).copyOf(LinkProof.PAYLOAD_BYTES - 1),
            ),
        ).map { (name, why, payload) -> Triple(name, why, proofRecord(payload)) }
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

    // A direct link's lane keys (ADR 2026-10.a5yj, experimental): Alice offered, Bob answered.
    private val linkKOffer = fixtureBytes(32, 31)
    private val linkKAnswer = fixtureBytes(32, 32)
    private val linkId = fixtureBytes(16, 33)

    private fun linkLane(role: LinkKeys.Role) = LinkKeys.lane(role, linkKOffer, linkKAnswer, linkId, alice.nodeId, bob.nodeId)

    private fun safetyNumber(): String = SafetyNumber.compute(alice.nodeId, alice.bundle.encoded, bob.nodeId, bob.bundle.encoded)

    // Blinded adverts (ADR 2026-11.xp6d): the tag construction, a link dialed by tag, the HELLO's t / a contract.
    private val blind = BlindVectors(alice, bob, PUBLISHED_AT)

    private fun blindJson(): JsonObject = file.getValue("blind").jsonObject

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
    fun `the link proof rebuilds byte for byte and verifies`() {
        assumeFalse(VectorFiles.writing)
        assertEquals(helloD.decodeToString(), link("helloProofDialer").getValue("payload").jsonPrimitive.content)
        assertEquals(
            LinkFraming.encode(LinkFraming.Type.HELLO, helloD).toHex(),
            link("helloProofDialer").getValue("record").jsonPrimitive.content,
        )
        assertEquals(helloR.decodeToString(), link("helloProofReply").getValue("payload").jsonPrimitive.content)
        assertEquals(
            LinkFraming.encode(LinkFraming.Type.HELLO, helloR).toHex(),
            link("helloProofReply").getValue("record").jsonPrimitive.content,
        )
        assertEquals(
            LinkProof.transcript(LinkProof.Role.DIALER, helloD, helloR).toHex(),
            link("proofTranscriptDialer").getValue("hex").jsonPrimitive.content,
        )
        assertEquals(
            LinkProof.transcript(LinkProof.Role.RESPONDER, helloD, helloR).toHex(),
            link("proofTranscriptResponder").getValue("hex").jsonPrimitive.content,
        )
        assertEquals(proofDialer().toHex(), link("proofDialer").getValue("record").jsonPrimitive.content)
        assertEquals(proofResponder().toHex(), link("proofResponder").getValue("record").jsonPrimitive.content)

        val dialer =
            checkNotNull(
                LinkFraming.read(
                    link("proofDialer")
                        .getValue("record")
                        .jsonPrimitive.content
                        .fromHex()
                        .inputStream(),
                ),
            )
        assertNull(LinkProof.verify(dialer.payload, alice.nodeId, LinkProof.Role.DIALER, helloD, helloR))
        val responder =
            checkNotNull(
                LinkFraming.read(
                    link("proofResponder")
                        .getValue("record")
                        .jsonPrimitive.content
                        .fromHex()
                        .inputStream(),
                ),
            )
        assertNull(LinkProof.verify(responder.payload, bob.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))

        val parsed = checkNotNull(Protocol.parse(helloD.decodeToString()))
        assertEquals(PROOF_CAPABILITIES, parsed.capabilities)
        assertEquals("000102030405060708090a0b0c0d0e0f", parsed.nonceHex)
        assertTrue(parsed.offersProof)
    }

    @Test
    fun `every pinned proof reject fails against the dialer's transcript`() {
        assumeFalse(VectorFiles.writing)
        val pinned =
            file
                .getValue("link")
                .jsonObject
                .getValue("proofRejects")
                .jsonObject
        val rejects = proofRejects()
        assertEquals(rejects.map { it.first }.toSet(), pinned.keys)
        for ((name, why, record) in rejects) {
            assertEquals(
                name,
                record.toHex(),
                pinned
                    .getValue(name)
                    .jsonObject
                    .getValue("record")
                    .jsonPrimitive.content,
            )
            val payload = checkNotNull(LinkFraming.read(record.inputStream())).payload
            assertEquals(name, why, LinkProof.verify(payload, alice.nodeId, LinkProof.Role.DIALER, helloD, helloR))
        }
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
    fun `the link lane keys are pinned and both ends derive them`() {
        assumeFalse(VectorFiles.writing)
        val pinned = file.getValue("linkKeys").jsonObject
        assertEquals(linkKOffer.toHex(), pinned.getValue("kOffer").jsonPrimitive.content)
        assertEquals(linkKAnswer.toHex(), pinned.getValue("kAnswer").jsonPrimitive.content)
        assertEquals(linkId.toHex(), pinned.getValue("linkId").jsonPrimitive.content)
        val (o2a, a2o) = linkLane(LinkKeys.Role.OFFERER)
        assertEquals(pinned.getValue("o2a").jsonPrimitive.content, o2a.toHex())
        assertEquals(pinned.getValue("a2o").jsonPrimitive.content, a2o.toHex())
        val (answerSend, answerRecv) = linkLane(LinkKeys.Role.ANSWERER)
        assertArrayEquals(a2o, answerSend)
        assertArrayEquals(o2a, answerRecv)
    }

    @Test
    fun `the blind tags rebuild from their layout`() {
        assumeFalse(VectorFiles.writing)
        blind.assertTags(blindJson())
    }

    @Test
    fun `the tag-dialed link proof rebuilds byte for byte and verifies`() {
        assumeFalse(VectorFiles.writing)
        blind.assertLink(blindJson())
    }

    @Test
    fun `every pinned HELLO reads the t and a fields the file states`() {
        assumeFalse(VectorFiles.writing)
        blind.assertHelloFields(blindJson())
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
                put("linkKeys", linkKeysJson())
                put("blind", blind.json())
            },
        )
    }

    private fun linkKeysJson(): JsonObject =
        buildJsonObject {
            val (o2a, a2o) = linkLane(LinkKeys.Role.OFFERER)
            put("offerer", "alice")
            put("answerer", "bob")
            put("kOffer", linkKOffer.toHex())
            put("kAnswer", linkKAnswer.toHex())
            put("linkId", linkId.toHex())
            put("o2a", o2a.toHex())
            put("a2o", a2o.toHex())
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
            putLinkProof()
            putJsonObject("advert") {
                put("nodeId", alice.nodeId)
                put("capabilities", CAPABILITIES)
                put("digestCue", fold() and 0xFFFF_FFFFL)
                put("psm", PSM)
                put("flags", 0)
                put("serviceData", advert().toHex())
            }
        }

    // The link proof's entries (ADR 2026-11.264w), beside the other link records.
    private fun JsonObjectBuilder.putLinkProof() {
        putJsonObject("helloProofDialer") {
            put("payload", helloD.decodeToString())
            put("record", LinkFraming.encode(LinkFraming.Type.HELLO, helloD).toHex())
        }
        putJsonObject("helloProofReply") {
            put("payload", helloR.decodeToString())
            put("record", LinkFraming.encode(LinkFraming.Type.HELLO, helloR).toHex())
        }
        putJsonObject("proofTranscriptDialer") {
            put("signer", "alice")
            put(
                "role",
                LinkProof.Role.DIALER.byte
                    .toInt(),
            )
            put("hex", LinkProof.transcript(LinkProof.Role.DIALER, helloD, helloR).toHex())
        }
        putJsonObject("proofTranscriptResponder") {
            put("signer", "bob")
            put(
                "role",
                LinkProof.Role.RESPONDER.byte
                    .toInt(),
            )
            put("hex", LinkProof.transcript(LinkProof.Role.RESPONDER, helloD, helloR).toHex())
        }
        putJsonObject("proofDialer") {
            put("signer", "alice")
            put("record", proofDialer().toHex())
        }
        putJsonObject("proofResponder") {
            put("signer", "bob")
            put("record", proofResponder().toHex())
        }
        putJsonObject("proofRejects") {
            for ((name, why, record) in proofRejects()) {
                putJsonObject(name) {
                    put("checkedAs", "alice, dialer, proofTranscriptDialer")
                    put("reason", REJECT_REASONS.getValue(why))
                    put("record", record.toHex())
                }
            }
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

        /** [CAPABILITIES] with the link-proof bit: both proof HELLOs carry it. */
        const val PROOF_CAPABILITIES = CAPABILITIES or Protocol.CAP_LINK_PROOF

        /** Why each `proofRejects` entry fails, in words both platforms read. */
        val REJECT_REASONS =
            mapOf(
                LinkProof.Failure.SIGNATURE to "the signature does not cover this transcript",
                LinkProof.Failure.ID_MISMATCH to "the keys hash to another node id than the dialer's HELLO claims",
                LinkProof.Failure.LENGTH to "the payload is not 128 bytes",
            )

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
