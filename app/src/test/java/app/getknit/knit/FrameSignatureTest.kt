package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.cappedTtl
import app.getknit.knit.mesh.protocol.incrementHop
import app.getknit.knit.mesh.protocol.signedBytes
import app.getknit.knit.mesh.protocol.withSig
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end frame-level signature authentication for flooded (unencrypted) frames, with real Tink
 * keypairs. A frame is signed over its canonical [signedBytes] with the sender's Ed25519 identity key
 * and verified against a bundle that must derive to the claimed senderId — exactly what
 * `MeshManager.sign` (outbound) and `MeshManager.verifyInbound` (inbound) do. This closes the forgery
 * gap where any relay could fabricate a broadcast chat/reaction/receipt/profile under another node's id.
 */
class FrameSignatureTest {

    /** A device identity: its cipher (private keys), its public bundle, and the nodeId it derives to. */
    private class Party(val crypto: MessageCrypto, val bundle: PublicKeyBundle) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    /** Stamps [frame] with this party's signature (mirrors MeshManager.sign). */
    private fun Party.sign(frame: Frame): Frame = frame.withSig(crypto.sign(frame.signedBytes()))

    /** Verifies [frame]'s signature against [bundle] (the core of MeshManager.verifyInbound). */
    private fun verify(bundle: PublicKeyBundle, frame: Frame): Boolean =
        MessageCrypto.verify(bundle, frame.sig, frame.signedBytes())

    @Test
    fun signedFrameVerifiesAgainstSenderBundle() {
        val alice = party()
        val frame = alice.sign(
            ReactionFrame(id = "x1", senderId = alice.nodeId, messageId = "m1", emoji = "👍", sentAt = 1L),
        )
        assertTrue(verify(alice.bundle, frame))
    }

    @Test
    fun signatureSurvivesRelayHopAndTtlMutation() {
        val alice = party()
        val origin = alice.sign(
            ProfileFrame(
                id = "p1", senderId = alice.nodeId, sentAt = 2L, name = "Alice", status = "hi",
                pubKey = alice.bundle.encoded,
            ),
        )
        // Simulate several relays: hops climb and ttl is capped — neither is authenticated, so the
        // signature on the relayed copy must still verify.
        var relayed: Frame = origin
        repeat(3) { relayed = relayed.incrementHop() }
        relayed = relayed.cappedTtl(DEFAULT_TTL)
        assertNotEquals(origin.hops, relayed.hops)
        assertTrue(verify(alice.bundle, relayed))
    }

    @Test
    fun tamperedContentFailsVerification() {
        val alice = party()
        val frame = alice.sign(
            ReactionFrame(id = "x2", senderId = alice.nodeId, messageId = "m1", emoji = "👍", sentAt = 3L),
        ) as ReactionFrame
        // A relay flips the emoji but keeps the original signature.
        assertFalse(verify(alice.bundle, frame.copy(emoji = "👎")))
    }

    @Test
    fun unsignedFrameFailsVerification() {
        val alice = party()
        val frame = ReactionFrame(id = "x3", senderId = alice.nodeId, messageId = "m1", emoji = "👍", sentAt = 4L)
        assertFalse(verify(alice.bundle, frame)) // sig == null
    }

    @Test
    fun attackerSignatureUnderVictimNodeIdIsRejected() {
        val victim = party()
        val attacker = party()
        // Attacker forges a reaction claiming the victim's nodeId, signed with the attacker's own key.
        val forged = attacker.sign(
            ReactionFrame(id = "x4", senderId = victim.nodeId, messageId = "m1", emoji = "💀", sentAt = 5L),
        )
        // The inbound gate first requires the verifying bundle to derive to senderId; the attacker's
        // bundle does not, so it could never be selected for verification...
        assertNotEquals(victim.nodeId, NodeId.fromPublicKeyBundle(attacker.bundle.encoded))
        // ...and verifying the forged signature against the victim's real bundle fails anyway.
        assertFalse(verify(victim.bundle, forged))
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    }
}
