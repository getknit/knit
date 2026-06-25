package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.min

/**
 * Shared image-decoding helpers used by [AvatarStore] and [AttachmentStore]. Decodes are
 * EXIF-corrected (so portrait photos aren't stored sideways) and bounded by an `inSampleSize`
 * pre-pass (so a large source image can't OOM the full decode).
 */

/**
 * Decodes [uri] applying its EXIF orientation, sub-sampling during decode so the result stays at or
 * just above [maxDim] on each pre-rotation edge. Pair with [downscale] for an exact bound. Returns
 * null if the stream can't be read.
 */
@Suppress("MagicNumber") // rotation degrees (90/180/270) mirror the named ORIENTATION_ROTATE_* constants
internal fun decodeOrientedBounded(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    // inJustDecodeBounds populates bounds.outWidth/outHeight and returns null by design, so the
    // null check must be on openInputStream, not on decodeStream's (always-null) result.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (context.contentResolver.openInputStream(uri) ?: return null).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
    }
    val bitmap = (context.contentResolver.openInputStream(uri) ?: return null).use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val orientation = context.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Largest power-of-two sample size that keeps both decoded dimensions at or above [maxDim], so the
 * coarse decode never drops below the target before [downscale] applies the exact bound.
 */
private fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxDim && height / (sample * 2) >= maxDim) {
        sample *= 2
    }
    return sample
}

/** Scales [src] down so neither side exceeds [max], preserving aspect ratio; returns [src] if already within bounds. */
internal fun downscale(src: Bitmap, max: Int): Bitmap {
    if (src.width <= max && src.height <= max) return src
    val ratio = min(max.toFloat() / src.width, max.toFloat() / src.height)
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}
