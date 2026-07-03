package app.getknit.knit.mesh

import java.security.MessageDigest

/**
 * A blob's content address is the lowercase-hex SHA-256 of its bytes — exactly 64 hex characters.
 *
 * Frames and file-transfer headers carry this address as an *untrusted, peer-supplied* string, and it
 * is interpolated into filesystem paths (the staging file, the transfer temp file). Validating
 * the shape before it touches a path stops a malicious neighbor's `../` from escaping the cache /
 * transfer directory, and recomputing it over the received bytes ([sha256Hex]) stops a holder from
 * serving arbitrary bytes under someone else's address (content-address / cache poisoning).
 */
private val BLOB_HASH_REGEX = Regex("^[0-9a-f]{64}$")

/** True if [s] is a well-formed blob content address (exactly 64 lowercase hex chars). */
fun isValidBlobHash(s: String): Boolean = BLOB_HASH_REGEX.matches(s)

/** Lowercase-hex SHA-256 of [bytes] — the canonical content address used across the mesh blob layer. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
