package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Ingests picked/keyboard images into the encrypted, content-addressed `blobs` table (see
 * [app.getknit.knit.data.blob.BlobEntity]). The content hash both keys the blob and is the key carried
 * in the wire frame, so any holder can serve the bytes and identical images dedupe.
 *
 * Photos are decoded, EXIF-rotated, downscaled, and re-encoded to JPEG; GIFs are kept **verbatim** so
 * their animation survives (decoding through [android.graphics.BitmapFactory] would flatten them). The
 * bytes never touch disk — they go straight into the encrypted database.
 *
 * Before staging, the image is screened for explicit content via [BlobRepository]. Sending an explicit
 * image is *allowed but discouraged*: a flagged image is still ingested, and [ingest] reports the flag
 * so the caller can ask the user to confirm before staging/sending it (the receive side blurs it).
 */
class AttachmentStore(private val context: Context, private val blobs: BlobRepository) {

    /** The result of ingesting a picked/keyboard image: its content [hash] and [mime]. */
    data class Ingested(val hash: String, val mime: String)

    /**
     * Outcome of [ingest]: stored ([Success], with [Success.flagged] true when screening judged the
     * image explicit so the caller can prompt for confirmation), or [Failed] to decode / too large.
     */
    sealed interface IngestResult {
        data class Success(val ingested: Ingested, val flagged: Boolean) : IngestResult
        data object Failed : IngestResult
    }

    /**
     * Ingests [uri] into the blob store. GIFs are kept as-is; other images are downscaled to
     * [MAX_DIMENSION] and re-encoded as JPEG. Returns [IngestResult.Failed] on a decode failure or if
     * the processed image exceeds [MAX_BYTES], else [IngestResult.Success] with [Success.flagged] set
     * when on-device screening judged the image explicit.
     */
    suspend fun ingest(uri: Uri): IngestResult = withContext(Dispatchers.IO) {
        val sourceMime = context.contentResolver.getType(uri)
        val (mime, bytes) = if (sourceMime == "image/gif") {
            // GIFs are stored verbatim so their animation survives.
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext IngestResult.Failed
            "image/gif" to raw
        } else {
            val bitmap = decodeOrientedBounded(context, uri, MAX_DIMENSION)
                ?: return@withContext IngestResult.Failed
            val scaled = downscale(bitmap, MAX_DIMENSION)
            // JPEG has no alpha channel, so a transparent PNG would flatten its transparent regions to
            // black. When the source carries transparency, re-encode as lossy WebP instead — it keeps
            // the alpha channel and still compresses well; opaque photos stay JPEG (smallest).
            if (scaled.hasAlpha()) {
                val webp = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
                    out.toByteArray()
                }
                "image/webp" to webp
            } else {
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                "image/jpeg" to jpeg
            }
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext IngestResult.Failed
        // Screen the exact bytes we store and transmit (decoded at the receiver's bound), so the
        // send-side verdict matches what the recipient computes rather than scoring the sharper,
        // pre-JPEG source. Stored regardless — an explicit image is allowed but the caller confirms
        // before sending; fail-open when the bytes can't be decoded.
        val flagged = blobs.isImageExplicit(bytes)
        val hash = sha256(bytes)
        blobs.insert(hash, mime, bytes)
        IngestResult.Success(Ingested(hash, mime), flagged)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 85
        const val WEBP_QUALITY = 85
        const val MAX_BYTES = 8 * 1024 * 1024 // 8 MiB cap (mostly bounds verbatim GIFs)
    }
}
