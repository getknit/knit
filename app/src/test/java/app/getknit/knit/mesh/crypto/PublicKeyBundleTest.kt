package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Equality is by [PublicKeyBundle.encoded] — the property TOFU key-change detection relies on: a re-parsed
 * bundle compares equal to itself, and a bundle for a *different* key never does (which is how a swapped-in
 * key for a pinned nodeId is refused).
 */
class PublicKeyBundleTest {
    @Before
    fun tink() {
        TinkInit.ensure()
    }

    private fun newBundle(): PublicKeyBundle {
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
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
}
