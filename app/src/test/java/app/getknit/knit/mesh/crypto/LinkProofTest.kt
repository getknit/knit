package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [LinkProof] and [LinkProver] on plain JVM, with fixed identities built the way `ContactCardTest` builds its own
 * (Ed25519 from a fixture seed, X25519 from a fixture scalar), so every signature is real Tink.
 *
 * [LinkProof.Failure.KEYS] is not reachable through [LinkProof.verify]: the length check fixes both raw keys at 32
 * bytes, and [PublicKeyBundle.fromRaw] refuses only a wrong length (it validates no point; a bad Ed25519 point fails
 * at the signature, as [MessageCrypto.verify] maps Tink's refusal to false). It stays as a defensive arm, and
 * [aMalformedSigningKeyIsASignatureFailureNotAThrow] pins that a garbage key lands on a failure, never a throw.
 */
class LinkProofTest {
    @Before
    fun tink() = TinkInit.ensure()

    private class Identity(
        seed: Int,
    ) {
        private val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(bytes(32, seed))
        val bundle = PublicKeyBundle.fromRaw(signing.publicKey, X25519.publicFromPrivate(bytes(32, seed + 1)))!!
        val nodeId = NodeId.fromPublicKeyBundle(bundle.encoded)
        val prover = LinkProver(bundle, Ed25519Sign(signing.privateKey)::sign)
    }

    private val ann = Identity(1)
    private val bob = Identity(3)

    private val helloD = bytes(40, 7)
    private val helloR = bytes(52, 9)

    @Test
    fun aProofVerifiesForItsRole() {
        assertEquals(ann.nodeId, ann.prover.nodeId)
        for (role in LinkProof.Role.entries) {
            val proof = ann.prover.proof(role, helloD, helloR)
            assertEquals(LinkProof.PAYLOAD_BYTES, proof.size)
            assertNull(LinkProof.verify(proof, ann.nodeId, role, helloD, helloR))
        }
    }

    @Test
    fun aProofDoesNotHoldUnderTheOtherRole() {
        val dialer = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        val responder = ann.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(dialer, ann.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))
        assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(responder, ann.nodeId, LinkProof.Role.DIALER, helloD, helloR))
    }

    @Test
    fun aFlippedByteInEitherHelloFailsTheSignature() {
        val proof = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        for (i in helloD.indices) {
            val bent = helloD.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(proof, ann.nodeId, LinkProof.Role.DIALER, bent, helloR))
        }
        for (i in helloR.indices) {
            val bent = helloR.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(proof, ann.nodeId, LinkProof.Role.DIALER, helloD, bent))
        }
    }

    @Test
    fun aProofOverAnotherResponderNonceFails() {
        // The same dialer HELLO answered on another link: only the responder's nonce differs, as a replay would see it.
        val proof = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        val otherLink = helloR.copyOf().also { it.fill(0x42, fromIndex = helloR.size - NONCE_BYTES) }
        assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(proof, ann.nodeId, LinkProof.Role.DIALER, helloD, otherLink))
    }

    @Test
    fun anotherIdentitysValidProofIsAnIdMismatch() {
        val proof = bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        assertNull(LinkProof.verify(proof, bob.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))
        assertEquals(LinkProof.Failure.ID_MISMATCH, LinkProof.verify(proof, ann.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))
    }

    @Test
    fun aSwappedSignatureUnderTheRightKeysFails() {
        // Ann's keys carrying Bob's signature over the same transcript: the id matches, the signature does not.
        val annProof = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        val bobProof = bob.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        val spliced = annProof.copyOfRange(0, KEYS_BYTES) + bobProof.copyOfRange(KEYS_BYTES, LinkProof.PAYLOAD_BYTES)
        assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(spliced, ann.nodeId, LinkProof.Role.DIALER, helloD, helloR))
    }

    @Test
    fun anyOtherLengthIsALengthFailure() {
        val proof = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        for (payload in listOf(proof.copyOf(127), proof + 0, ByteArray(0))) {
            assertEquals(LinkProof.Failure.LENGTH, LinkProof.verify(payload, ann.nodeId, LinkProof.Role.DIALER, helloD, helloR))
        }
    }

    @Test
    fun aMalformedSigningKeyIsASignatureFailureNotAThrow() {
        // All-0xFF is no Ed25519 point. The id is computed over the same keys, so only the signature can refuse it.
        val keys = ByteArray(KEYS_BYTES) { 0xFF.toByte() }
        val bundle = PublicKeyBundle.fromRaw(keys.copyOfRange(0, 32), keys.copyOfRange(32, KEYS_BYTES))!!
        val payload = keys + ByteArray(64)
        val id = NodeId.fromPublicKeyBundle(bundle.encoded)
        assertEquals(LinkProof.Failure.SIGNATURE, LinkProof.verify(payload, id, LinkProof.Role.DIALER, helloD, helloR))
    }

    @Test
    fun theProofCarriesTheSignersRawKeys() {
        val proof = ann.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        assertArrayEquals(ann.bundle.sigPublicKey() + ann.bundle.dhPublicKey(), proof.copyOfRange(0, KEYS_BYTES))
    }

    @Test
    fun theTranscriptLayoutIsPinned() {
        val d = byteArrayOf(0x0A, 0x0B, 0x0C)
        val r = ByteArray(0x0102) { 0x55 }
        val expected =
            byteArrayOf(
                0x6B,
                0x6E,
                0x69,
                0x74,
                0x2F,
                0x6C,
                0x69,
                0x6E,
                0x6B, // "knit/link"
                0x2F,
                0x76,
                0x31,
                0x2F,
                0x70,
                0x72,
                0x6F,
                0x6F,
                0x66, // "/v1/proof"
                0x02, // RESPONDER
                0x00,
                0x00,
                0x00,
                0x03,
                0x0A,
                0x0B,
                0x0C, // u32be(3) ‖ hD
                0x00,
                0x00,
                0x01,
                0x02, // u32be(258)
            ) + r
        assertEquals(18, LinkProof.LABEL.length)
        assertArrayEquals(expected, LinkProof.transcript(LinkProof.Role.RESPONDER, d, r))
        // The dialer's role byte is 0x01 and changes nothing else.
        val dialer = LinkProof.transcript(LinkProof.Role.DIALER, d, r)
        assertArrayEquals(expected.copyOf().also { it[18] = 0x01 }, dialer)
        // Empty HELLOs still carry both length prefixes.
        assertArrayEquals(
            LinkProof.LABEL.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0, 0),
            LinkProof.transcript(LinkProof.Role.DIALER, ByteArray(0), ByteArray(0)),
        )
    }

    @Test
    fun twoProofsOverTheSameInputAreByteIdentical() {
        val first = ann.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        val second = ann.prover.proof(LinkProof.Role.RESPONDER, helloD.copyOf(), helloR.copyOf())
        assertArrayEquals(first, second)
    }

    private companion object {
        const val KEYS_BYTES = 64
        const val NONCE_BYTES = 16

        fun bytes(
            n: Int,
            seed: Int,
        ): ByteArray = ByteArray(n) { (it * 31 + seed).toByte() }
    }
}
