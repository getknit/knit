package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.crypto.SealException
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.SealFramingException
import app.getknit.knit.mesh.link.SealedLinkSocket
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * `keyed-v1.json`'s `link.seal` entries (#286, the contract's "Vectors"), built and checked for [KeyedVectorTest]: Alice
 * dials Bob in the sealed form, with the link proof's nonces (`00..0f`, `10..1f`), both HELLOs at caps `140009` written
 * by hand (so a change to `Protocol.LOCAL_CAPABILITIES` never moves them), and fixed ephemeral scalars (Alice `40..5f`,
 * Bob `60..7f`, unclamped: X25519 clamps on both platforms).
 *
 * The shared secret and both keys are checked against an independent build from Tink's X25519 and HKDF, so a drift in
 * [LinkSeal] fails here rather than being re-pinned. Tink's Ed25519 and AES-GCM under a fixed key and nonce are
 * deterministic, so Android rebuilds every byte; iOS rebuilds all but the PROOFs, which it opens and verifies.
 */
internal class SealVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
) {
    private val aliceProver = LinkProver(alice.bundle, alice::sign)
    private val bobProver = LinkProver(bob.bundle, bob::sign)

    private val aliceScalar = ByteArray(32) { (0x40 + it).toByte() }
    private val bobScalar = ByteArray(32) { (0x60 + it).toByte() }
    private val eD = X25519.publicFromPrivate(aliceScalar)
    private val eR = X25519.publicFromPrivate(bobScalar)
    private val binding = LinkSeal.Binding(eD, eR)

    private val dialerKeys = LinkSeal.derive(LinkProof.Role.DIALER, LinkSeal.Ephemeral.fixed(aliceScalar), eR)
    private val responderKeys = LinkSeal.derive(LinkProof.Role.RESPONDER, LinkSeal.Ephemeral.fixed(bobScalar), eD)
    private val kD2R = dialerKeys.send
    private val kR2D = dialerKeys.recv

    private fun hello(
        nodeId: String,
        nonce: ByteArray,
    ): ByteArray = "$nodeId|${Protocol.VERSION}|${CAPS.toString(16)}|n${nonce.toHex()}".encodeToByteArray()

    private val helloD = hello(alice.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { it.toByte() })
    private val helloR = hello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x10 + it).toByte() })

    private fun record(
        type: LinkFraming.Type,
        payload: ByteArray,
    ) = LinkFraming.encode(type, payload)

    private val helloDRecord = record(LinkFraming.Type.HELLO, helloD)
    private val helloRRecord = record(LinkFraming.Type.HELLO, helloR)
    private val proofD = record(LinkFraming.Type.PROOF, aliceProver.sealedProof(LinkProof.Role.DIALER, binding, helloD, helloR))
    private val proofR = record(LinkFraming.Type.PROOF, bobProver.sealedProof(LinkProof.Role.RESPONDER, binding, helloD, helloR))

    /** The shared secret and both keys, from Tink's primitives directly. */
    private fun specKeys(): Triple<ByteArray, ByteArray, ByteArray> {
        val ss = X25519.computeSharedSecret(aliceScalar, eR)
        val salt = eD + eR
        val d2r = Hkdf.computeHkdf("HMACSHA256", ss, salt, "knit/link/v1/seal/d2r".toByteArray(Charsets.US_ASCII), 32)
        val r2d = Hkdf.computeHkdf("HMACSHA256", ss, salt, "knit/link/v1/seal/r2d".toByteArray(Charsets.US_ASCII), 32)
        return Triple(ss, d2r, r2d)
    }

    private val splitAt = 20

    /** One reject: its name, why it fails, and the JSON fields a port replays it from. */
    private class Reject(
        val name: String,
        val reason: String,
        val fields: Map<String, Any>,
    )

    private fun rejects(): List<Reject> {
        val reply = LinkSeal.seal(kR2D, 0, helloRRecord)
        val flipped = reply.copyOf().also { it[HEADER + 3] = (it[HEADER + 3].toInt() xor 0x01).toByte() }
        val v1 = bobProver.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        val swapped = bobProver.sealedProof(LinkProof.Role.RESPONDER, LinkSeal.Binding(eD, ByteArray(32) { -1 }), helloD, helloR)
        return listOf(
            Reject(
                "flippedBit",
                "one ciphertext bit of Bob's sealed reply flipped: the tag fails",
                mapOf("frame" to flipped.toHex(), "key" to "keyR2D", "counter" to 0, "readBy" to "alice"),
            ),
            Reject(
                "skippedCounter",
                "Bob's PROOF sealed at counter 2 where 1 is due: it opens only under nonce 2",
                mapOf("frame" to LinkSeal.seal(kR2D, 2, proofR).toHex(), "key" to "keyR2D", "counter" to 1, "readBy" to "alice"),
            ),
            Reject(
                "wrongDirection",
                "Alice's HELLO sealed under keyR2D: Bob opens her frames with keyD2R",
                mapOf("frame" to LinkSeal.seal(kR2D, 0, helloDRecord).toHex(), "key" to "keyD2R", "counter" to 0, "readBy" to "bob"),
            ),
            Reject(
                "lowOrderKeyShare",
                "a KEY_SHARE of the all-zero point: X25519 refuses it (or yields an all-zero secret)",
                mapOf("record" to LinkFraming.encodeKeyShare(ByteArray(32)).toHex(), "readBy" to "bob"),
            ),
            Reject(
                "shortKeyShare",
                "a KEY_SHARE of 31 bytes: refused from the header",
                mapOf("record" to (byteArrayOf(10, 0, 0, 0, 31) + eD.copyOf(31)).toHex(), "readBy" to "bob"),
            ),
            Reject(
                "oversizeFrame",
                "a SEALED header stating 65,553 bytes: refused from the header, the body never read",
                mapOf("record" to byteArrayOf(11, 0, 1, 0, 0x11).toHex(), "readBy" to "alice"),
            ),
            Reject(
                "emptyFrame",
                "a SEALED header stating 16 bytes, a tag and no plaintext: refused from the header",
                mapOf("record" to (byteArrayOf(11, 0, 0, 0, 16) + ByteArray(16)).toHex(), "readBy" to "alice"),
            ),
            Reject(
                "v1ProofOnSealedLink",
                "Bob's v1 signature over the same HELLOs: it does not cover transcriptResponder",
                mapOf("record" to record(LinkFraming.Type.PROOF, v1).toHex(), "signer" to "bob", "verifyAs" to "transcriptResponder"),
            ),
            Reject(
                "swappedKeyShare",
                "Bob's v2 signature over a transcript whose eR is ff..ff: it does not cover transcriptResponder",
                mapOf("record" to record(LinkFraming.Type.PROOF, swapped).toHex(), "signer" to "bob", "verifyAs" to "transcriptResponder"),
            ),
        )
    }

    fun json(): JsonObject =
        buildJsonObject {
            val (ss, d2r, r2d) = specKeys()
            check(ss.isNotEmpty() && d2r.contentEquals(kD2R) && r2d.contentEquals(kR2D)) { "LinkSeal drifted from the spec build" }
            put("about", ABOUT)
            put("dialer", "alice")
            put("responder", "bob")
            put("capabilities", CAPS.toString(16))
            putJsonObject("keyShareDialer") {
                put("scalar", aliceScalar.toHex())
                put("record", LinkFraming.encodeKeyShare(eD).toHex())
            }
            putJsonObject("keyShareResponder") {
                put("scalar", bobScalar.toHex())
                put("record", LinkFraming.encodeKeyShare(eR).toHex())
            }
            put("shared", ss.toHex())
            put("keyD2R", d2r.toHex())
            put("keyR2D", r2d.toHex())
            putExchange()
            putSplits()
            putRejects()
        }

    // The sealed HELLOs, the v2 transcripts and the PROOFs, one record per frame.
    private fun JsonObjectBuilder.putExchange() {
        putJsonObject("helloSealedDialer") {
            put("payload", helloD.decodeToString())
            put("key", "keyD2R")
            put("counter", 0)
            put("frame", LinkSeal.seal(kD2R, 0, helloDRecord).toHex())
        }
        putJsonObject("helloSealedReply") {
            put("payload", helloR.decodeToString())
            put("key", "keyR2D")
            put("counter", 0)
            put("frame", LinkSeal.seal(kR2D, 0, helloRRecord).toHex())
        }
        putJsonObject("transcriptDialer") {
            put("signer", "alice")
            put(
                "role",
                LinkProof.Role.DIALER.byte
                    .toInt(),
            )
            put("hex", LinkProof.sealedTranscript(LinkProof.Role.DIALER, binding, helloD, helloR).toHex())
        }
        putJsonObject("transcriptResponder") {
            put("signer", "bob")
            put(
                "role",
                LinkProof.Role.RESPONDER.byte
                    .toInt(),
            )
            put("hex", LinkProof.sealedTranscript(LinkProof.Role.RESPONDER, binding, helloD, helloR).toHex())
        }
        putJsonObject("proofResponder") {
            put("signer", "bob")
            put("record", proofR.toHex())
            put("key", "keyR2D")
            put("counter", 1)
            put("frame", LinkSeal.seal(kR2D, 1, proofR).toHex())
        }
        putJsonObject("proofDialer") {
            put("signer", "alice")
            put("record", proofD.toHex())
            put("key", "keyD2R")
            put("counter", 1)
            put("frame", LinkSeal.seal(kD2R, 1, proofD).toHex())
        }
    }

    private fun JsonObjectBuilder.putSplits() {
        putJsonObject("splits") {
            putJsonObject("replyAndProofInOneFrame") {
                put("key", "keyR2D")
                putJsonArray("frames") { add(LinkSeal.seal(kR2D, 0, helloRRecord + proofR).toHex()) }
                putJsonArray("records") {
                    add(helloRRecord.toHex())
                    add(proofR.toHex())
                }
            }
            putJsonObject("dialerHelloInTwoFrames") {
                put("key", "keyD2R")
                putJsonArray("frames") {
                    add(LinkSeal.seal(kD2R, 0, helloDRecord.copyOf(splitAt)).toHex())
                    add(LinkSeal.seal(kD2R, 1, helloDRecord.copyOfRange(splitAt, helloDRecord.size)).toHex())
                }
                putJsonArray("records") { add(helloDRecord.toHex()) }
            }
        }
    }

    private fun JsonObjectBuilder.putRejects() {
        putJsonObject("sealRejects") {
            for (reject in rejects()) {
                putJsonObject(reject.name) {
                    put("reason", reject.reason)
                    for ((k, v) in reject.fields) {
                        when (v) {
                            is Int -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }
            }
        }
    }

    private fun JsonObject.hex(name: String): ByteArray = getValue(name).jsonPrimitive.content.fromHex()

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun keyNamed(name: String): ByteArray =
        when (name) {
            "keyD2R" -> kD2R
            "keyR2D" -> kR2D
            else -> error("no key $name")
        }

    /** Every byte rebuilds; every frame opens to what the file states; both PROOFs verify. */
    fun assertRebuilds(seal: JsonObject) {
        assertEquals("the pinned seal vectors drifted", json(), seal)
        val (ss, d2r, r2d) = specKeys()
        assertArrayEquals(ss, seal.hex("shared"))
        assertArrayEquals(d2r, seal.hex("keyD2R"))
        assertArrayEquals(r2d, seal.hex("keyR2D"))
        assertArrayEquals(kD2R, responderKeys.recv)
        assertArrayEquals(kR2D, responderKeys.send)
        // The key shares: 37-byte records of the public keys the scalars make.
        for ((name, pub) in listOf("keyShareDialer" to eD, "keyShareResponder" to eR)) {
            val read = LinkFraming.read(seal.obj(name).hex("record").inputStream())
            assertEquals(LinkFraming.Message(LinkFraming.Type.KEY_SHARE, pub), read)
        }
        // The sealed HELLOs and PROOFs open, at their counters, to the records the file states.
        assertArrayEquals(helloDRecord, LinkSeal.open(kD2R, 0, seal.obj("helloSealedDialer").hex("frame")))
        assertArrayEquals(helloRRecord, LinkSeal.open(kR2D, 0, seal.obj("helloSealedReply").hex("frame")))
        assertEquals(
            helloD.decodeToString(),
            seal
                .obj("helloSealedDialer")
                .getValue("payload")
                .jsonPrimitive.content,
        )
        val proofs =
            listOf(
                ProofCase("proofResponder", kR2D, bob, LinkProof.Role.RESPONDER),
                ProofCase("proofDialer", kD2R, alice, LinkProof.Role.DIALER),
            )
        for (case in proofs) {
            val name = case.name
            val key = case.key
            val signer = case.signer
            val role = case.role
            val entry = seal.obj(name)
            assertArrayEquals(name, entry.hex("record"), LinkSeal.open(key, 1, entry.hex("frame")))
            val proof = checkNotNull(LinkFraming.read(entry.hex("record").inputStream())).payload
            assertNull(name, LinkProof.verifySealed(proof, signer.nodeId, role, binding, helloD, helloR))
            // Under the other role, and under v1, it fails.
            val other = if (role == LinkProof.Role.DIALER) LinkProof.Role.RESPONDER else LinkProof.Role.DIALER
            assertNotNull(LinkProof.verifySealed(proof, signer.nodeId, other, binding, helloD, helloR))
            assertNotNull(LinkProof.verify(proof, signer.nodeId, role, helloD, helloR))
        }
        val parsed = checkNotNull(Protocol.parse(helloD.decodeToString()))
        assertEquals(CAPS, parsed.capabilities)
        assertTrue(parsed.offersProof && parsed.capabilities and Protocol.CAP_LINK_SEAL != 0L)
    }

    private class ProofCase(
        val name: String,
        val key: ByteArray,
        val signer: VectorParty,
        val role: LinkProof.Role,
    )

    /** Each split's frames, read in order through a sealed socket, are the records it states. */
    fun assertSplits(seal: JsonObject) {
        val splits = seal.obj("splits")
        assertEquals(setOf("replyAndProofInOneFrame", "dialerHelloInTwoFrames"), splits.keys)
        for ((name, entry) in splits) {
            val split = entry.jsonObject
            val frames = split.getValue("frames").jsonArray.map { it.jsonPrimitive.content.fromHex() }
            val reader = readerOf(split.getValue("key").jsonPrimitive.content, frames.reduce(ByteArray::plus))
            val records = generateSequence { LinkFraming.read(reader.input) }.map { LinkFraming.encode(it.type, it.payload).toHex() }
            assertEquals(name, split.getValue("records").jsonArray.map { it.jsonPrimitive.content }, records.toList())
        }
    }

    /** A sealed socket reading [wire], opening with [keyName] from counter 0. */
    private fun readerOf(
        keyName: String,
        wire: ByteArray,
    ): SealedLinkSocket {
        val (keys, role) =
            if (keyName == "keyR2D") dialerKeysAgain() to LinkProof.Role.DIALER else responderKeysAgain() to LinkProof.Role.RESPONDER
        return SealedLinkSocket(Raw(ByteArrayInputStream(wire)), keys, role)
    }

    // A socket takes keys once; each reader derives its own from fresh copies of the fixed scalars.
    private fun dialerKeysAgain() = LinkSeal.derive(LinkProof.Role.DIALER, LinkSeal.Ephemeral.fixed(aliceScalar), eR)

    private fun responderKeysAgain() = LinkSeal.derive(LinkProof.Role.RESPONDER, LinkSeal.Ephemeral.fixed(bobScalar), eD)

    private class Raw(
        override val input: InputStream,
    ) : LinkSocket {
        override val output: OutputStream = ByteArrayOutputStream()

        override fun close() = Unit
    }

    /** Every pinned reject is the one built here, and fails the way its kind must. */
    fun assertRejects(seal: JsonObject) {
        val pinned = seal.obj("sealRejects")
        val rejects = rejects()
        assertEquals(rejects.map { it.name }.toSet(), pinned.keys)
        for (reject in rejects) {
            val entry = pinned.obj(reject.name)
            assertEquals(reject.name, reject.reason, entry.getValue("reason").jsonPrimitive.content)
            when {
                "frame" in entry -> {
                    val key = keyNamed(entry.getValue("key").jsonPrimitive.content)
                    val counter =
                        entry
                            .getValue("counter")
                            .jsonPrimitive.int
                            .toLong()
                    assertThrows(reject.name, SealException::class.java) { LinkSeal.open(key, counter, entry.hex("frame")) }
                }

                "verifyAs" in entry -> {
                    val proof = checkNotNull(LinkFraming.read(entry.hex("record").inputStream())).payload
                    assertEquals(
                        reject.name,
                        LinkProof.Failure.SIGNATURE,
                        LinkProof.verifySealed(proof, bob.nodeId, LinkProof.Role.RESPONDER, binding, helloD, helloR),
                    )
                }

                entry.hex("record")[0] == LinkFraming.Type.KEY_SHARE.tag -> {
                    // Read as the responder reads a first record: from the header, then the key schedule.
                    val failed =
                        runCatching {
                            val share = checkNotNull(LinkFraming.read(entry.hex("record").inputStream()))
                            LinkSeal.derive(LinkProof.Role.RESPONDER, LinkSeal.Ephemeral.fixed(bobScalar), share.payload)
                        }.exceptionOrNull()
                    assertTrue(reject.name, failed is SealException || failed is SealFramingException)
                }

                else -> {
                    // A sealed frame header out of range: the reader refuses before the body.
                    val reader = readerOf("keyR2D", entry.hex("record"))
                    assertThrows(reject.name, SealException::class.java) { reader.input.read() }
                }
            }
            for ((k, v) in reject.fields) {
                val pinnedValue = entry.getValue(k).jsonPrimitive.content
                assertEquals("${reject.name}.$k", v.toString(), pinnedValue)
            }
        }
    }

    private companion object {
        /** E2E, store-and-forward, the link proof and the seal: both HELLOs carry it. */
        const val CAPS = 0x9L or Protocol.CAP_LINK_PROOF or Protocol.CAP_LINK_SEAL

        const val HEADER = LinkSeal.HEADER_BYTES

        const val ABOUT =
            "A sealed link (knit-next #286): Alice dials Bob with ephemeral scalars 40..5f and 60..7f (unclamped), the link " +
                "proof's nonces and both HELLOs at caps 140009. Each frame is [0x0b][u32be len][AES-256-GCM ‖ tag], the 5 header " +
                "bytes its AAD, nonce 00000000 ‖ u64be(counter), one counter per direction from 0. The PROOFs sign the v2 " +
                "transcript; Android rebuilds them, a port with a randomized Ed25519 opens and verifies them. Every sealRejects " +
                "entry must fail as its reason says."
    }
}
