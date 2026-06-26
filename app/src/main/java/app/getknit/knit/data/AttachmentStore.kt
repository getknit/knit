package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Stores message image attachments as content-addressed files in `filesDir/attachments/<hash>.<ext>`
 * (persistent, unlike avatars which live in cacheDir). The content hash both names the file and is
 * the key carried in the wire frame, so any holder can serve the bytes and identical images dedupe.
 *
 * Photos are decoded, EXIF-rotated, downscaled, and re-encoded to JPEG; GIFs are copied **verbatim**
 * so their animation survives (decoding through [BitmapFactory] would flatten them). Mirrors
 * [AvatarStore]'s Uri→file approach.
 */
class AttachmentStore(private val context: Context) {

    /** The result of ingesting a picked/keyboard image: its content [hash], [mime], and local [path]. */
    data class Ingested(val hash: String, val mime: String, val path: String)

    private fun dir(): File = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    private fun existing(hash: String): File? =
        dir().listFiles { f -> f.name.startsWith("$hash.") }?.firstOrNull()

    fun has(hash: String): Boolean = existing(hash) != null

    fun fileFor(hash: String): File? = existing(hash)

    fun path(hash: String): String? = existing(hash)?.absolutePath

    fun mimeFor(hash: String): String? = existing(hash)?.let { mimeForExt(it.extension) }

    /** Deletes the content-addressed file for [hash]. Callers must ensure no message still references it. */
    fun delete(hash: String): Boolean = existing(hash)?.delete() ?: false

    /**
     * Ingests [uri] into the store and returns its [Ingested] descriptor (or null on failure / if the
     * processed image exceeds [MAX_BYTES]). GIFs are kept as-is; other images are downscaled to
     * [MAX_DIMENSION] and re-encoded as JPEG.
     */
    suspend fun ingest(uri: Uri): Ingested? = withContext(Dispatchers.IO) {
        val sourceMime = context.contentResolver.getType(uri)
        val (mime, bytes) = if (sourceMime == "image/gif") {
            "image/gif" to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null)
        } else {
            val bitmap = decodeOrientedBounded(context, uri, MAX_DIMENSION) ?: return@withContext null
            val scaled = downscale(bitmap, MAX_DIMENSION)
            val jpeg = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            "image/jpeg" to jpeg
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
        val hash = sha256(bytes)
        val dest = File(dir(), "$hash.${extForMime(mime)}")
        if (!dest.exists()) {
            runCatching { dest.outputStream().use { it.write(bytes) } }
                .onFailure { Log.w(TAG, "Failed writing attachment $hash", it); return@withContext null }
        }
        Ingested(hash, mime, dest.absolutePath)
    }

    /** Persists a blob received over the mesh (copied from [srcPath]) as `<hash>.<ext>`. */
    suspend fun saveIncoming(hash: String, mime: String, srcPath: String): File? =
        withContext(Dispatchers.IO) {
            val src = File(srcPath)
            if (!src.exists()) return@withContext null
            val dest = File(dir(), "$hash.${extForMime(mime)}")
            if (dest.exists()) return@withContext dest
            runCatching { src.copyTo(dest, overwrite = true) }
                .onFailure { Log.w(TAG, "Failed saving incoming attachment $hash", it) }
                .getOrNull()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "gif" -> "image/gif"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private companion object {
        const val TAG = "AttachmentStore"
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 85
        const val MAX_BYTES = 8 * 1024 * 1024 // 8 MiB cap (mostly bounds verbatim GIFs)
    }
}
