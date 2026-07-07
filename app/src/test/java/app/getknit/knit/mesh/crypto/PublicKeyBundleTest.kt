package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.signature.Ed25519PublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Equality is by [PublicKeyBundle.encoded] — the property TOFU key-change detection relies on: a re-parsed
 * bundle compares equal to itself, and a bundle for a *different* key never does (which is how a swapped-in
 * key for a pinned nodeId is refused). Also pins the launch-baseline raw-key wire layout: raw 32-byte keys and the
 * `HPKE_PARAMS` that the wrapped-key reconstruction depends on matching the `_RAW` template exactly.
 */
class PublicKeyBundleTest {
    @Before
    fun tink() {
        TinkInit.ensure()
    }

    private fun newBundle(): PublicKeyBundle {
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        return PublicKeyBundle.fromPrivate(hybrid, sig)
    }

    @Test
    fun bundlesParsedFromTheSameEncodingAreEqual() {
        val bundle = newBundle()
        val reparsed = PublicKeyBundle.decode(bundle.encoded)!!
        assertEquals(bundle, reparsed)
        assertEquals(bundle.hashCode(), reparsed.hashCode())
    }

    @Test
    fun bundlesForDifferentKeysAreNotEqual() {
        assertNotEquals(newBundle(), newBundle())
    }

    /**
     * The single subtle correctness gate for the de-Tink change: [PublicKeyBundle.hybridEncrypt]
     * reconstructs an encryptor from raw key bytes against the fixed [PublicKeyBundle.HPKE_PARAMS]. If those
     * params drift from Tink's `_RAW` template, the reconstructed encryptor is incompatible and every DM
     * silently fails to open. Assert they are identical, and that the extracted keys are bare 32-byte RFC
     * layouts (no Tink keyset proto, no output prefix).
     */
    @Test
    fun rawKeysAndHpkeParamsMatchTheRawTemplates() {
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        val hpkeKey = hybrid.publicKeysetHandle.primary.key as HpkePublicKey
        val sigKey = sig.publicKeysetHandle.primary.key as Ed25519PublicKey

        assertEquals(PublicKeyBundle.HPKE_PARAMS, hpkeKey.parameters)
        assertEquals(32, hpkeKey.publicKeyBytes.toByteArray().size)
        assertEquals(32, sigKey.publicKeyBytes.toByteArray().size)
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        const val SIG_TEMPLATE = "ED25519_RAW"
    }
}
