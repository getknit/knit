package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check of the self-certifying identity scheme (finding 3.1c) using real Tink keypairs:
 * a nodeId is the hash of the public-key bundle, so an inbound profile/key is trusted only when the
 * advertised bundle derives back to the claimed nodeId — the exact check `MeshManager.handleProfile`
 * and `decrypt` perform. This makes the trust-on-first-use pin race-proof: a peer cannot pin its key
 * for a nodeId it doesn't hold the keypair for.
 */
class SelfCertifyingIdentityTest {
    private fun freshBundle(): PublicKeyBundle {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return PublicKeyBundle.fromPrivate(hybrid, sig)
    }

    /** The nodeId a device advertises is the derivation of its own bundle (what Identity computes). */
    private fun nodeIdOf(bundle: PublicKeyBundle) = NodeId.fromPublicKeyBundle(bundle.encoded)

    @Test
    fun aRealKeyBundleDerivesToItsOwnShapedNodeId() {
        val bundle = freshBundle()
        val nodeId = nodeIdOf(bundle)
        assertTrue(Regex("^[a-z2-7]{26}$").matches(nodeId))
        // The binding check accepts the genuine (nodeId, bundle) pair.
        assertEquals(nodeId, NodeId.fromPublicKeyBundle(bundle.encoded))
    }

    @Test
    fun anAttackerKeyCannotMatchAVictimsNodeId() {
        val victim = freshBundle()
        val attacker = freshBundle()
        val victimNodeId = nodeIdOf(victim)

        // A profile claiming the victim's nodeId but carrying the attacker's key fails the binding
        // check (NodeId.fromPublicKeyBundle(attackerKey) != victimNodeId), so handleProfile drops it
        // and the victim's nodeId can never be pinned to the attacker's key.
        assertNotEquals(victimNodeId, nodeIdOf(attacker))
        assertNotEquals(victimNodeId, NodeId.fromPublicKeyBundle(attacker.encoded))
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    }
}
