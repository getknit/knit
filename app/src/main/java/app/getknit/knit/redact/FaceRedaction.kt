package app.getknit.knit.redact

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import app.getknit.knit.data.PHOTO_JPEG_QUALITY
import app.getknit.knit.data.PHOTO_WEBP_QUALITY
import app.getknit.knit.data.lossyWebpFormat
import java.io.ByteArrayOutputStream

/**
 * Covers a photo and encodes it to send (ADR 2026-10.whkk). A cover is an opaque fill of one colour, never a blur:
 * blurred and pixelated faces are re-identified by neural networks, and a single colour leaves nothing of the face in
 * the photo to recover. The colour is the average of what the cover hides ([Cover.fillColor]), softer to look at than
 * black and no easier to undo. The photo is drawn on a copy, and only the copy is encoded, so the bytes that leave hold
 * no pixel from under a cover.
 */
object FaceRedaction {
    /** [photo] with every covered [Redaction.covers] filled, on a new bitmap; [photo] itself is not touched. */
    fun covered(
        photo: Bitmap,
        redaction: Redaction,
    ): Bitmap {
        require(photo.width == redaction.width && photo.height == redaction.height) {
            "a ${redaction.width} x ${redaction.height} redaction on a ${photo.width} x ${photo.height} photo"
        }
        // A software copy, which also takes a HARDWARE photo; an opaque photo stays opaque, so [encode] sends it as the
        // JPEG it came as, not as WebP.
        val out = photo.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(photo.hasAlpha()) }
        // Every fill is read before any is drawn, so an overlapping cover never averages another's fill.
        val covers = redaction.covers.filter { it.covered }
        val fills = covers.map { it.fillColor(out::getPixel) }
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        covers.forEachIndexed { i, cover ->
            paint.color = fills[i]
            canvas.drawRoundRect(cover.left, cover.top, cover.right, cover.bottom, cover.cornerRadius, cover.cornerRadius, paint)
        }
        return out
    }

    /**
     * [bitmap] as the bytes and MIME type to send: JPEG at the quality the attachment pipeline sends a photo at, or
     * lossy WebP when it has transparency, which JPEG would turn black. Encoded from pixels, so it carries no metadata.
     */
    fun encode(bitmap: Bitmap): Pair<String, ByteArray> {
        val alpha = bitmap.hasAlpha()
        val bytes =
            ByteArrayOutputStream().use { out ->
                if (alpha) {
                    bitmap.compress(lossyWebpFormat(), PHOTO_WEBP_QUALITY, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, out)
                }
                out.toByteArray()
            }
        return (if (alpha) "image/webp" else "image/jpeg") to bytes
    }
}
