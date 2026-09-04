package app.getknit.knit.linkpreview

import android.graphics.Bitmap
import app.getknit.knit.data.decodeOrientedBounded
import app.getknit.knit.data.downscale
import app.getknit.knit.data.lossyWebpFormat
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import java.io.ByteArrayOutputStream

/**
 * Turns a page's picture into the small one a card carries: a bounded, EXIF-upright decode
 * (`data/ImageDecode.kt`, ADR 051 — the bytes are whatever the site served), scaled to [MAX_DIM], and
 * re-encoded as JPEG or, when the source has transparency, lossy WebP (JPEG would flatten a logo's
 * transparent regions to black). A result over [LinkPreviewBlob.IMAGE_MAX_BYTES] is tried once more, smaller
 * and rougher, and then given up on: a card without a picture is still a card, and the bytes ride the mesh.
 */
object PreviewImage {
    const val MAX_DIM = 512
    const val RETRY_DIM = 384
    const val QUALITY = 80
    const val RETRY_QUALITY = 70

    /** Below this on either side the picture is a favicon or a tracking pixel, not a preview. */
    const val MIN_DIM = 64

    /** The card's picture from [bytes], or null when there is nothing worth carrying. */
    fun shrink(bytes: ByteArray): LinkPreviewService.Shrunk? {
        val decoded = decodeOrientedBounded(bytes, MAX_DIM) ?: return null
        if (decoded.width < MIN_DIM || decoded.height < MIN_DIM) return null
        return encode(downscale(decoded, MAX_DIM), QUALITY) ?: encode(downscale(decoded, RETRY_DIM), RETRY_QUALITY)
    }

    private fun encode(
        bitmap: Bitmap,
        quality: Int,
    ): LinkPreviewService.Shrunk? {
        val (format, mime) =
            if (bitmap.hasAlpha()) lossyWebpFormat() to "image/webp" else Bitmap.CompressFormat.JPEG to "image/jpeg"
        val encoded =
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(format, quality, out)) return null
                out.toByteArray()
            }
        return encoded.takeIf { it.size <= LinkPreviewBlob.IMAGE_MAX_BYTES }?.let { LinkPreviewService.Shrunk(it, mime) }
    }
}
