package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.link.LinkFraming
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * `ios-emitted-v1.json`'s `link.seal` (#286, the contract's "Vectors", second half), checked for [IosEmittedVectorTest]:
 * Carol's sealed links as the iPhone writes them. Carol dials Alice, and Alice dials Carol, with Carol's scalar `80..9f`
 * and nonce `20..2f` and Alice's `a0..bf` and `30..3f`, both HELLOs at caps `140009`.
 *
 * Every key share, the shared secret, both keys, both HELLOs, Carol's sealed HELLO frame and the v2 transcript are
 * rebuilt here byte for byte. CryptoKit randomizes Ed25519, so Carol's PROOF frame is opened at counter 1 and its
 * signature verified, never compared; it must fail under the other role and under the v1 label.
 */
internal class IosSealVectors(
    private val alice: VectorParty,
    private val carol: VectorParty,
) {
    private val scalars =
        mapOf(
            "carol" to ByteArray(32) { (0x80 + it).toByte() },
            "alice" to ByteArray(32) { (0xA0 + it).toByte() },
        )
    private val nonces =
        mapOf(
            "carol" to ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x20 + it).toByte() },
            "alice" to ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x30 + it).toByte() },
        )
    private val parties by lazy { mapOf("alice" to alice, "carol" to carol) }

    private fun hello(name: String): String =
        "${parties.getValue(name).nodeId}|${Protocol.VERSION}|${CAPS.toString(16)}|n${nonces.getValue(name).toHex()}"

    fun assertAll(seal: JsonObject) {
        assertEquals(setOf("about", "carolDialsAlice", "aliceDialsCarol"), seal.keys)
        assertCase("carolDialsAlice", seal.getValue("carolDialsAlice").jsonObject, "carol", "alice", LinkProof.Role.DIALER)
        assertCase("aliceDialsCarol", seal.getValue("aliceDialsCarol").jsonObject, "alice", "carol", LinkProof.Role.RESPONDER)
    }

    private fun assertCase(
        case: String,
        entry: JsonObject,
        dialer: String,
        responder: String,
        role: LinkProof.Role,
    ) {
        assertEquals(case, dialer, entry.text("dialer"))
        assertEquals(case, responder, entry.text("responder"))
        assertEquals(case, "carol", entry.text("signer"))
        assertEquals(case, role.byte.toInt(), entry.getValue("role").jsonPrimitive.int)
        assertEquals(case, CAPS.toString(16), entry.text("capabilities"))

        // The key shares, the shared secret and both keys.
        val eD = X25519.publicFromPrivate(scalars.getValue(dialer))
        val eR = X25519.publicFromPrivate(scalars.getValue(responder))
        for ((key, name, pub) in listOf(Triple("keyShareDialer", dialer, eD), Triple("keyShareResponder", responder, eR))) {
            val share = entry.obj(key)
            assertEquals("$case $key scalar", scalars.getValue(name).toHex(), share.text("scalar"))
            assertEquals("$case $key record", LinkFraming.encodeKeyShare(pub).toHex(), share.text("record"))
        }
        assertEquals("$case shared", X25519.computeSharedSecret(scalars.getValue(dialer), eR).toHex(), entry.text("shared"))
        val keys = LinkSeal.derive(LinkProof.Role.DIALER, LinkSeal.Ephemeral.fixed(scalars.getValue(dialer)), eR)
        val keyD2R = keys.send
        val keyR2D = keys.recv
        assertEquals("$case keyD2R", keyD2R.toHex(), entry.text("keyD2R"))
        assertEquals("$case keyR2D", keyR2D.toHex(), entry.text("keyR2D"))
        val responderKeys = LinkSeal.derive(LinkProof.Role.RESPONDER, LinkSeal.Ephemeral.fixed(scalars.getValue(responder)), eD)
        assertArrayEquals("$case both ends derive the same keys", keyD2R, responderKeys.recv)

        // The HELLOs, and Carol's sealed in her direction at counter 0.
        val helloD = hello(dialer)
        val helloR = hello(responder)
        assertEquals("$case helloDialer", helloD, entry.text("helloDialer"))
        assertEquals("$case helloResponder", helloR, entry.text("helloResponder"))
        val carolsHello = if (role == LinkProof.Role.DIALER) helloD else helloR
        val carolsKeyName = if (role == LinkProof.Role.DIALER) "keyD2R" else "keyR2D"
        val carolsKey = if (role == LinkProof.Role.DIALER) keyD2R else keyR2D
        val sealed = entry.obj("helloSealed")
        assertEquals("$case helloSealed payload", carolsHello, sealed.text("payload"))
        assertEquals("$case helloSealed key", carolsKeyName, sealed.text("key"))
        assertEquals("$case helloSealed counter", 0, sealed.getValue("counter").jsonPrimitive.int)
        val helloRecord = LinkFraming.encode(LinkFraming.Type.HELLO, carolsHello.encodeToByteArray())
        assertEquals("$case helloSealed frame", LinkSeal.seal(carolsKey, 0, helloRecord).toHex(), sealed.text("frame"))

        // The v2 transcript Carol signs.
        val binding = LinkSeal.Binding(eD, eR)
        val hD = helloD.encodeToByteArray()
        val hR = helloR.encodeToByteArray()
        assertEquals("$case transcript", LinkProof.sealedTranscript(role, binding, hD, hR).toHex(), entry.text("transcript"))

        // Carol's PROOF: open it at counter 1 under the named key, then verify, never compare.
        val proof = entry.obj("proof")
        assertEquals("$case proof signer", "carol", proof.text("signer"))
        assertEquals("$case proof key", carolsKeyName, proof.text("key"))
        assertEquals("$case proof counter", 1, proof.getValue("counter").jsonPrimitive.int)
        val opened = LinkSeal.open(carolsKey, 1, proof.text("frame").fromHex())
        assertEquals("$case proof frame opens to its record", proof.text("record"), opened.toHex())
        val record = checkNotNull(LinkFraming.read(opened.inputStream())) { case }
        assertEquals(case, LinkFraming.Type.PROOF, record.type)
        assertArrayEquals(case, carol.bundle.sigPublicKey() + carol.bundle.dhPublicKey(), record.payload.copyOf(64))
        assertNull("$case proof does not verify", LinkProof.verifySealed(record.payload, carol.nodeId, role, binding, hD, hR))
        val other = if (role == LinkProof.Role.DIALER) LinkProof.Role.RESPONDER else LinkProof.Role.DIALER
        assertEquals(
            "$case proof must fail under the other role",
            LinkProof.Failure.SIGNATURE,
            LinkProof.verifySealed(record.payload, carol.nodeId, other, binding, hD, hR),
        )
        assertEquals(
            "$case proof must fail under the v1 label",
            LinkProof.Failure.SIGNATURE,
            LinkProof.verify(record.payload, carol.nodeId, role, hD, hR),
        )
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private companion object {
        /** E2E, store-and-forward, the link proof and the seal, as keyed-v1's `link.seal`. */
        const val CAPS = 0x9L or Protocol.CAP_LINK_PROOF or Protocol.CAP_LINK_SEAL
    }
}
