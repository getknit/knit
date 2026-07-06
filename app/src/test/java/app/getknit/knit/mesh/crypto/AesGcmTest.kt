package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Directly exercises the AEAD primitive's authentication: the AAD, key, and ciphertext are all bound. */
class AesGcmTest {
    private val aad = "message-header".encodeToByteArray()

    @Test
    fun roundTripsWithTheMatchingKeyAndAad() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        assertEquals("secret", AesGcm.decrypt(key, iv, ct, aad).decodeToString())
    }

    @Test(expected = Exception::class)
    fun decryptingWithADifferentAadFails() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        // The AAD is authenticated (not encrypted): a mismatch must fail the tag, so the E2E header binding holds.
        AesGcm.decrypt(key, iv, ct, "tampered-header".encodeToByteArray())
    }

    @Test(expected = Exception::class)
    fun decryptingWithTheWrongKeyFails() {
        val (iv, ct) = AesGcm.encrypt(AesGcm.randomKey(), "secret".encodeToByteArray(), aad)
        AesGcm.decrypt(AesGcm.randomKey(), iv, ct, aad)
    }

    @Test(expected = Exception::class)
    fun decryptingTamperedCiphertextFails() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        ct[ct.lastIndex] = (ct[ct.lastIndex] + 1).toByte()
        AesGcm.decrypt(key, iv, ct, aad)
    }

    @Test
    fun aFreshIvPerEncryptionYieldsDifferentCiphertextForTheSameInput() {
        val key = AesGcm.randomKey()
        val a = AesGcm.encrypt(key, "same".encodeToByteArray(), aad)
        val b = AesGcm.encrypt(key, "same".encodeToByteArray(), aad)
        assertFalse("random IV per call", a.first.contentEquals(b.first))
        assertFalse("→ different ciphertext", a.second.contentEquals(b.second))
    }
}
