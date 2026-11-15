package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import com.google.crypto.tink.subtle.Ed25519Verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The MeshCore bind (ADR 2026-11.gsqu, Stage 5d): a board's Ed25519 signature over `"knit-meshcore-bind/v1"` ‖ node id ‖
 * key, verified with the key checked first ([MeshcoreKey]) and `S` below the group order, then Tink.
 */
class MeshcoreBindTest {
    private val alice = NodeId.derive("alice")
    private val mallory = NodeId.derive("mallory")
    private val node = NodeId.toBytes(alice)
    private val key = MeshcoreBindFixture.keyOf()
    private val sig = MeshcoreBindFixture.sigBytes(alice)

    @Test
    fun theMessageIsTheDomainTheNodeAndTheKeyAtFixedLengths() {
        val message = checkNotNull(MeshcoreBind.message(node, key))
        assertEquals(69, message.size)
        assertEquals(MeshcoreBind.MESSAGE_BYTES, message.size)
        assertArrayEquals("knit-meshcore-bind/v1".encodeToByteArray(), message.copyOf(21))
        assertArrayEquals(node, message.copyOfRange(21, 37))
        assertArrayEquals(key, message.copyOfRange(37, 69))
        assertNull("a short node id", MeshcoreBind.message(node.copyOf(15), key))
        assertNull("a long key", MeshcoreBind.message(node, key + 0))
        // The consent proof's domain differs at byte 14, so neither signature serves the other.
        val consent = "knit-meshcore-consent/v1".encodeToByteArray()
        assertEquals(14, (0 until 21).first { consent[it] != MeshcoreBind.DOMAIN[it] })
    }

    @Test
    fun aBoardsSignatureOverItsBindVerifies() {
        assertTrue(MeshcoreBind.verifies(node, key, sig))
        assertTrue(MeshcoreBind.verifies(alice, MeshcoreBindFixture.KEY, b64(sig)))
        assertArrayEquals("RFC 8032 is deterministic: one bind, one signature", sig, MeshcoreBindFixture.sigBytes(alice))
    }

    @Test
    fun anotherNodeAnotherKeyOrAFlippedBitIsRefused() {
        assertFalse("bound to Alice, not Mallory", MeshcoreBind.verifies(NodeId.toBytes(mallory), key, sig))
        assertFalse(MeshcoreBind.verifies(mallory, MeshcoreBindFixture.KEY, b64(sig)))
        val otherKey = MeshcoreBindFixture.keyOf(ByteArray(32) { 7 })
        assertFalse("another board's key", MeshcoreBind.verifies(node, otherKey, sig))
        for (bit in listOf(0, 255, 256, 511)) {
            val flipped = sig.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            assertFalse("bit $bit flipped", MeshcoreBind.verifies(node, key, flipped))
        }
    }

    @Test
    fun aSignatureNotMadeByTheKeyIsRefused() {
        // Another board signs Alice's bind naming this key: the message is right, the signer is not.
        val other = ByteArray(32) { 7 }
        val forged =
            com.google.crypto.tink.subtle
                .Ed25519Sign(other)
                .sign(checkNotNull(MeshcoreBind.message(node, key)))
        assertFalse(MeshcoreBind.verifies(node, key, forged))
    }

    @Test
    fun sPlusLIsRefused() {
        // S + L is the same scalar mod L: a verifier that skips the range check accepts it, a second signature for free.
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
        val l = BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        val sPlusL = (s + l).toByteArray().reversedArray().copyOf(32)
        assertEquals("still fits 32 bytes", s + l, BigInteger(1, sPlusL.reversedArray()))
        val malleated = sig.copyOf(32) + sPlusL
        // Tink 1.24 refuses S >= L itself, so the end-to-end check alone would stay green without ours: test ours directly.
        assertTrue("the honest S is below L", MeshcoreBind.sBelowOrder(sig))
        assertFalse("S + L is not", MeshcoreBind.sBelowOrder(malleated))
        assertFalse(MeshcoreBind.verifies(node, key, malleated))
    }

    @Test
    fun aLowOrderKeyWithTheIdentityRAndAZeroSIsRefused() {
        // The identity point as a key, R = the identity, S = 0: [S]B − [k]A = identity = R for every message.
        val identity = ByteArray(32).also { it[0] = 1 }
        val anything = identity + ByteArray(32)
        val message = checkNotNull(MeshcoreBind.message(node, identity))
        val tinkAlone = runCatching { Ed25519Verify(identity).verify(anything, message) }.isSuccess
        assertTrue("Tink alone checks no key order: why the key is checked first", tinkAlone)
        assertFalse(MeshcoreBind.verifies(node, identity, anything))
        assertFalse(MeshcoreBind.verifies(alice, b64(identity), b64(anything)))
    }

    @Test
    fun theTextFormIsStrict() {
        val good = b64(sig)
        assertNotEquals(null, MeshcoreBind.sigBytesOrNull(good))
        assertNull("63 bytes", MeshcoreBind.sigBytesOrNull(b64(sig.copyOf(63))))
        assertNull("65 bytes", MeshcoreBind.sigBytesOrNull(b64(sig + 0)))
        assertNull("unpadded", MeshcoreBind.sigBytesOrNull(good.trimEnd('=')))
        // 64 bytes leave two slack bits in the last character before the padding: set one and the bytes are the same.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val stray = good.substring(0, 85) + alphabet[alphabet.indexOf(good[85]) xor 1] + "=="
        assertNull("stray bits", MeshcoreBind.sigBytesOrNull(stray))
        assertFalse("a node id that is not one", MeshcoreBind.verifies("not-a-node", MeshcoreBindFixture.KEY, good))
        assertFalse("a key that is not canonical", MeshcoreBind.verifies(alice, MeshcoreBindFixture.KEY.trimEnd('='), good))
    }
}
