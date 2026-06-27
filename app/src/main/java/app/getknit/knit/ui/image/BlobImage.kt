package app.getknit.knit.ui.image

/**
 * Coil model for an image stored in the encrypted `blobs` table, addressed by content [hash]. Resolved
 * by [BlobFetcher] (bytes read from the DB) and keyed by [BlobKeyer] (memory cache keyed on the hash,
 * so a changed avatar/attachment — i.e. a new hash — misses the cache and re-renders). [mime] is an
 * optional decoder hint passed through to Coil.
 *
 * [key] is the base64 attachment key for an end-to-end-encrypted attachment: when set, the blob bytes
 * are ciphertext and [BlobFetcher] decrypts them before decoding. Null for plaintext blobs (avatars,
 * broadcast-room attachments). The [hash] is unique per ciphertext, so it remains a sufficient cache key.
 */
data class BlobImage(val hash: String, val mime: String? = null, val key: String? = null)
