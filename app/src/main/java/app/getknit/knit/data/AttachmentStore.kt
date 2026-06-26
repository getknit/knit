package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Ingests picked/keyboard images into the encrypted, content-addressed `blobs` table (see
 * [BlobEntity]). The content hash both keys the blob and is the key carried in the wire frame, so any
 * holder can serve the bytes and identical images dedupe.
 *
 * Photos are decoded, EXIF-rotated, downscaled, and re-encoded to JPEG; GIFs are kept **verbatim** so
 * their animation survives (decoding through [BitmapFactory] would flatten them). The bytes never
 * touch disk — they go straight into the encrypted database.
 */
class AttachmentStore(private val context: Context, private val blobs: BlobDao) {

    /** The result of ingesting a picked/keyboard image: its content [hash] and [mime]. */
    data class Ingested(val hash: String, val mime: String)

    /**
     * Ingests [uri] into the blob store and returns its [Ingested] descriptor (or null on failure / if
     * the processed image exceeds [MAX_BYTES]). GIFs are kept as-is; other images are downscaled to
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
        blobs.insert(BlobEntity(hash, mime, bytes))
        Ingested(hash, mime)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 85
        const val MAX_BYTES = 8 * 1024 * 1024 // 8 MiB cap (mostly bounds verbatim GIFs)
    }
}
