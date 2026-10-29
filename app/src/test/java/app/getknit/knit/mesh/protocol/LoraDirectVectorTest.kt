package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.LoraDirectSeal
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.lora.LoraDirectOpen
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The compact LoRa DM's known answers (`vectors/lora-direct-v1.json`, ADR 2026-10.x9fa): keyed-v1's two identities,
 * their identity agreement, each direction's three `knit/lora/v1/…` derivations over the canonical 16-byte node ids,
 * and one packet Alice seals to Bob under a fixed salt — its nonce, its AAD and its bytes. Everything is rebuilt on every
 * run and compared; the packet must also open for Bob and name Alice. Another port reads the same file.
 *
 * Write mode (`KNIT_WRITE_VECTORS=1`) rewrites the file from the fixtures; only an intended change of the seal does that.
 */
class LoraDirectVectorTest {
    private val alice = VectorParty(fixtureBytes(32, 1), fixtureBytes(32, 2))
    private val bob = VectorParty(fixtureBytes(32, 3), fixtureBytes(32, 4))
    private val frameId = FrameId.fromBytes(fixtureBytes(16, 31))
    private val salt = fixtureBytes(LoraDirectSeal.SALT_BYTES, 41)
    private val content = MessageContent(body = BODY)

    private fun idBytes(party: VectorParty): ByteArray = checkNotNull(NodeId.toBytesOrNull(party.nodeId))

    private fun pairSecret(): ByteArray = X25519.computeSharedSecret(alice.agreementScalar, X25519.publicFromPrivate(bob.agreementScalar))

    private fun plaintext(): ByteArray = checkNotNull(MessageContentV2.encodeOrNull(content))

    private fun aliceSeal(): LoraDirectSeal =
        LoraDirectSeal(ownDhPrivate = { alice.agreementScalar }, clock = { SENT_AT }).apply {
            rebuild(alice.nodeId, mapOf(bob.nodeId to X25519.publicFromPrivate(bob.agreementScalar)))
        }

    private fun bobSeal(): LoraDirectSeal =
        LoraDirectSeal(ownDhPrivate = { bob.agreementScalar }, clock = { SENT_AT }).apply {
            rebuild(bob.nodeId, mapOf(alice.nodeId to X25519.publicFromPrivate(alice.agreementScalar)))
        }

    private fun packet(): ByteArray = checkNotNull(aliceSeal().sealWithSalt(bob.nodeId, frameId, SENT_AT, plaintext(), salt))

    private fun direction(
        from: VectorParty,
        to: VectorParty,
    ): JsonObject {
        val keys = LoraDirectSeal.keys(pairSecret(), idBytes(from), idBytes(to))
        return buildJsonObject {
            put("info", "label ‖ ${from.nodeId} (16 B) ‖ ${to.nodeId} (16 B)")
            put("key", keys.key.toHex())
            put("nonceKey", keys.nonceKey.toHex())
            put("pairTag", keys.tag.toHex())
        }
    }

    private fun build(): JsonObject {
        val packet = packet()
        val forward = LoraDirectSeal.keys(pairSecret(), idBytes(alice), idBytes(bob))
        val header = packet.copyOfRange(0, LoraDirectSeal.HEADER_BYTES)
        return buildJsonObject {
            put("about", ABOUT)
            putJsonObject("identities") {
                put("alice", alice.toJson())
                put("bob", bob.toJson())
            }
            put("pairSecret", pairSecret().toHex())
            putJsonObject("derivations") {
                put("aliceToBob", direction(alice, bob))
                put("bobToAlice", direction(bob, alice))
            }
            putJsonObject("aliceToBob") {
                put("frameId", frameId)
                put("sentAt", SENT_AT)
                put("salt", salt.toHex())
                put("body", BODY)
                put("plaintext", plaintext().toHex())
                put("nonce", LoraDirectSeal.nonce(forward.nonceKey, fixtureBytes(16, 31), salt).toHex())
                put("aad", (header + idBytes(alice) + idBytes(bob)).toHex())
                put("packet", packet.toHex())
            }
        }
    }

    @Test
    fun `the derivations and the sealed packet rebuild byte for byte`() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)
        val built = build()
        for (key in listOf("identities", "pairSecret", "derivations", "aliceToBob")) {
            assertEquals("lora-direct vector '$key' drifted", file.getValue(key), built.getValue(key))
        }
    }

    @Test
    fun `the pinned packet opens for bob, names alice, and its ciphertext is the stated AES-GCM`() {
        assumeFalse(VectorFiles.writing)
        val pinned = VectorFiles.read(FILE).getValue("aliceToBob").jsonObject
        val packet =
            pinned
                .getValue("packet")
                .jsonPrimitive.content
                .fromHex()
        val opened = bobSeal().open(packet) as LoraDirectOpen.Opened
        assertEquals(alice.nodeId, opened.direct.peerId)
        assertEquals(frameId, opened.direct.frameId)
        assertEquals(pinned.getValue("sentAt").jsonPrimitive.long, opened.direct.sentAtMs)
        assertEquals(BODY, MessageContentV2.decode(opened.direct.plaintext)?.body)
        // The ciphertext is plain AES-256-GCM under the stated key, nonce and AAD: a port needs nothing else.
        val key = LoraDirectSeal.keys(pairSecret(), idBytes(alice), idBytes(bob)).key
        val ct = packet.copyOfRange(LoraDirectSeal.HEADER_BYTES, packet.size)
        val plain =
            AesGcm.decrypt(
                key,
                pinned
                    .getValue("nonce")
                    .jsonPrimitive.content
                    .fromHex(),
                ct,
                pinned
                    .getValue("aad")
                    .jsonPrimitive.content
                    .fromHex(),
            )
        assertArrayEquals(
            pinned
                .getValue("plaintext")
                .jsonPrimitive.content
                .fromHex(),
            plain,
        )
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the lora-direct vectors`() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build())
    }

    private companion object {
        const val FILE = "lora-direct-v1.json"
        const val SENT_AT = 1_790_000_000_000L
        const val BODY = "See you at the north gate"
        const val ABOUT =
            "Compact LoRa DM (ADR 2026-10.x9fa). pairSecret = X25519(alice.agreementScalar, bob's X25519 key). Each direction: " +
                "HKDF-SHA256(ikm = pairSecret, salt = 32 zero bytes, info = label || senderId || recipientId, L) with labels " +
                "knit/lora/v1/key (32), knit/lora/v1/nonce (32), knit/lora/v1/tag (4), node ids as their 16 raw bytes. " +
                "nonce = HKDF-SHA256(ikm = nonceKey, salt = 32 zero bytes, info = frameId (16) || salt (8), 12). " +
                "packet = 0x11 || pairTag (4) || salt (8) || frameId (16) || sentAt (u48 BE, ms) || AES-256-GCM(key, nonce, " +
                "plaintext, aad) with its 16-byte tag; aad = the 35 header bytes || senderId (16) || recipientId (16). The " +
                "plaintext is v3 compact content (MessageContentV2). A receiver finds the sender by the pairTag among its " +
                "pinned peers and refuses a sentAt older than 24 h or more than 5 min ahead of its own clock."
    }
}
