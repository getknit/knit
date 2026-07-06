package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The E2E attachment cipher had no test. Round-trip, content confidentiality, and every failure path must
 * return null (open runs before the blob is decoded — a throw would crash rendering). Pure JDK AES-GCM, no
 * Tink init needed.
 */
class AttachmentCryptoTest {
    @Test
    fun sealThenOpenRoundTrips() {
        val plain = "the raw image bytes".encodeToByteArray()
        val sealed = AttachmentCrypto.seal(plain)
        assertArrayEquals(plain, AttachmentCrypto.open(sealed.blob, sealed.key))
    }

    @Test
    fun blobIsIvPlusCiphertextAndNeverExposesThePlaintext() {
        val plain = ByteArray(64) { it.toByte() }
        val sealed = AttachmentCrypto.seal(plain)
        // iv (12) + ciphertext (64 + 16-byte GCM tag).
        assertEquals(AesGcm.IV_BYTES + plain.size + 16, sealed.blob.size)
        val ciphertext = sealed.blob.copyOfRange(AesGcm.IV_BYTES, sealed.blob.size)
        assertFalse("ciphertext must not equal the plaintext", ciphertext.contentEquals(plain))
    }

    @Test
    fun openWithTheWrongKeyReturnsNull() {
        val sealed = AttachmentCrypto.seal("x".encodeToByteArray())
        assertNull(AttachmentCrypto.open(sealed.blob, AesGcm.randomKey()))
    }

    @Test
    fun openATamperedBlobReturnsNull() {
        val sealed = AttachmentCrypto.seal("hello".encodeToByteArray())
        val tampered = sealed.blob.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertNull(AttachmentCrypto.open(tampered, sealed.key))
    }

    @Test
    fun openATooShortBlobReturnsNull() {
        // size == IV_BYTES leaves zero ciphertext, tripping the "blob too short" guard.
        assertNull(AttachmentCrypto.open(ByteArray(AesGcm.IV_BYTES), AesGcm.randomKey()))
    }

    @Test
    fun eachSealUsesAFreshKeyAndIv() {
        val plain = "same input".encodeToByteArray()
        val a = AttachmentCrypto.seal(plain)
        val b = AttachmentCrypto.seal(plain)
        assertFalse("fresh random key per seal", a.key.contentEquals(b.key))
        assertFalse("fresh random IV → different blob for identical input", a.blob.contentEquals(b.blob))
    }
}
