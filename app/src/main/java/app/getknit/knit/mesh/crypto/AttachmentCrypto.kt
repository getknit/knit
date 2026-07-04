package app.getknit.knit.mesh.crypto

/**
 * Encrypts image-attachment bytes for end-to-end DM/group messages. The plaintext image is sealed
 * under a fresh random key with AES-256-GCM; the stored/transferred blob is `iv || ciphertext`, and is
 * **content-addressed by the SHA-256 of that blob** — so the existing content-addressed pull/dedup
 * (BlobExchange/BlobStore) keeps working while relays only ever see ciphertext. The random [Sealed.key]
 * travels (base64) inside the encrypted [MessageContent], never on the wire in the clear.
 */
object AttachmentCrypto {
    /** A sealed attachment: the `iv || ciphertext` [blob] to store/serve, and its [key]. */
    data class Sealed(
        val blob: ByteArray,
        val key: ByteArray,
    )

    /** Encrypts [plain] under a fresh key. */
    fun seal(plain: ByteArray): Sealed {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, plain, EMPTY_AAD)
        return Sealed(iv + ct, key)
    }

    /** Decrypts a stored `iv || ciphertext` [blob] with [key]; null on any failure (bad key/tag/size). */
    fun open(
        blob: ByteArray,
        key: ByteArray,
    ): ByteArray? =
        runCatching {
            require(blob.size > AesGcm.IV_BYTES) { "attachment blob too short" }
            val iv = blob.copyOfRange(0, AesGcm.IV_BYTES)
            val ct = blob.copyOfRange(AesGcm.IV_BYTES, blob.size)
            AesGcm.decrypt(key, iv, ct, EMPTY_AAD)
        }.getOrNull()

    private val EMPTY_AAD = ByteArray(0)
}
