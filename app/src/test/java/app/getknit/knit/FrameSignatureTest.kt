package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end frame-level signature authentication for flooded frames, with real Tink keypairs. The
 * single [WireEnvelope.sig] is raw Ed25519 over the [WireEnvelope.signed] routing-envelope blob, and is
 * verified byte-exact against a bundle that must derive to the claimed senderId — exactly what
 * `MeshManager.sign` (outbound) and `MeshManager.verifyInbound` (inbound) do. Because a relay forwards
 * `signed`/`sig` verbatim (it only mutates the outer ttl/hops), the signature holds at every hop.
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

    private fun reaction(senderId: String, id: String = "x1") =
        RelayEnvelope(type = FrameType.REACTION, id = id, senderId = senderId, sentAt = 1L, payload = ByteArray(0))

    /** Wraps + signs [env] with this party's key (mirrors MeshManager.sign). */
    private fun Party.sign(env: RelayEnvelope): WireEnvelope {
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(sig = crypto.signRaw(signed), signed = signed)
    }

    /** Verifies [wire]'s signature against [bundle] (the core of MeshManager.verifyInbound). */
    private fun verify(bundle: PublicKeyBundle, wire: WireEnvelope): Boolean =
        MessageCrypto.verify(bundle, wire.sig, wire.signed)

    @Test
    fun signedFrameVerifiesAgainstSenderBundle() {
        val alice = party()
        assertTrue(verify(alice.bundle, alice.sign(reaction(alice.nodeId))))
    }

    @Test
    fun signatureSurvivesRelayHopAndTtlMutation() {
        val alice = party()
        val origin = alice.sign(reaction(alice.nodeId))
        // Simulate several relays: only the outer wrapper's ttl/hops change; signed + sig are untouched.
        var relayed = origin
        repeat(3) { relayed = relayed.relayed() }
        assertNotEquals(origin.hops, relayed.hops)
        assertTrue(verify(alice.bundle, relayed))
    }

    @Test
    fun tamperedContentFailsVerification() {
        val alice = party()
        val origin = alice.sign(reaction(alice.nodeId))
        // A relay rewrites the routing envelope (different id) but keeps the original signature.
        val tampered = WireEnvelope(sig = origin.sig, signed = WireCodec.encodeEnvelope(reaction(alice.nodeId, id = "x2")))
        assertFalse(verify(alice.bundle, tampered))
    }

    @Test
    fun unsignedFrameFailsVerification() {
        val alice = party()
        val unsigned = WireEnvelope(sig = ByteArray(0), signed = WireCodec.encodeEnvelope(reaction(alice.nodeId)))
        assertFalse(verify(alice.bundle, unsigned))
    }

    @Test
    fun attackerSignatureUnderVictimNodeIdIsRejected() {
        val victim = party()
        val attacker = party()
        // Attacker forges a reaction claiming the victim's nodeId, signed with the attacker's own key.
        val forged = attacker.sign(reaction(victim.nodeId, id = "x4"))
        // The inbound gate first requires the verifying bundle to derive to senderId; the attacker's
        // bundle does not...
        assertNotEquals(victim.nodeId, NodeId.fromPublicKeyBundle(attacker.bundle.encoded))
        // ...and verifying the forged signature against the victim's real bundle fails anyway.
        assertFalse(verify(victim.bundle, forged))
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    }
}
