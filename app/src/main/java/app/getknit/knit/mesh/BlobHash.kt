package app.getknit.knit.mesh

import java.security.MessageDigest
import java.util.Locale

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
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * The extension a blob's short-lived transfer file gets, from its [mime].
 *
 * Cosmetic — the file is named `<hash>.<ext>` on the way out and `attach-<hash>-<n>.<ext>` on the way in, and
 * both ends derive it independently from the mime rather than reading a name off the wire. It lives here
 * because it used to live in *two* places (`MeshBlobStore` for the send side, `FramedLink` for the receive
 * side) as identical `when` blocks that had to be extended together; arbitrary files gave that duplication
 * a third arm to get wrong, so it became one function instead.
 *
 * `jpg` stays the fallback for an unknown image-ish type — it is the universal default across the blob layer
 * (`ScopeSync.FALLBACK_MIME`, `MeshManager.AVATAR_MIME`) — but anything that is plainly not media now lands
 * on `bin` rather than being called a photo.
 */
fun transferExtForMime(mime: String): String =
    when (val lower = mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "audio/aac" -> "aac"
        else -> if (lower.startsWith("image/")) "jpg" else "bin"
    }
