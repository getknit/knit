package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/*
 * Shared image-decoding helpers used by [AvatarStore] and [AttachmentStore]. Decodes are
 * EXIF-corrected (so portrait photos aren't stored sideways) and bounded by an `inSampleSize`
 * pre-pass (so a large source image can't OOM the full decode).
 */

/**
 * Decodes [uri] applying its EXIF orientation, sub-sampling during decode so the result stays at or
 * just above [maxDim] on each pre-rotation edge. Pair with [downscale] for an exact bound. Returns
 * null if the stream can't be read, also when the resolver refuses it by throwing ([openOrNull]).
 */
internal fun decodeOrientedBounded(
    context: Context,
    uri: Uri,
    maxDim: Int,
): Bitmap? = decodeOrientedBounded(maxDim) { context.contentResolver.openInputStream(uri) }

/**
 * The in-memory twin of the [Uri] overload above, for an image that never had a Uri — the in-app
 * camera's captured JPEG. Note this is *not* [decodeBoundedFromBytes], which skips EXIF orientation
 * because it only feeds the content classifier; a photo decoded without it is stored sideways.
 */
internal fun decodeOrientedBounded(
    bytes: ByteArray,
    maxDim: Int,
): Bitmap? = decodeOrientedBounded(maxDim) { ByteArrayInputStream(bytes) }

/**
 * Shared body of the two overloads above. [source] is opened three times — the bounds pre-pass, the
 * sub-sampled decode, and the EXIF read — because each consumes the stream. A source that will not open
 * at any of the three is unreadable: a picture whose orientation cannot be read is not one to send
 * as if it were upright.
 */
private fun decodeOrientedBounded(
    maxDim: Int,
    source: () -> InputStream?,
): Bitmap? {
    // inJustDecodeBounds populates bounds.outWidth/outHeight and returns null by design, so the
    // null check must be on the source, not on decodeStream's (always-null) result.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (openOrNull(source) ?: return null).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
    val bitmap =
        (openOrNull(source) ?: return null).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

    val orientation =
        (openOrNull(source) ?: return null).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    return rotatedForExif(bitmap, orientation)
}

/**
 * [source] opened, or null when it will not open. A content resolver refuses by throwing, not by answering
 * null: `FileNotFoundException` for a file Knit may not open, `SecurityException` for an item it holds no
 * grant for (one revoked or expired, or deleted since it was shared). Nothing above the decode caught
 * either, so a share Knit could not read crashed the app and the mesh service with it (#268).
 */
private fun openOrNull(source: () -> InputStream?): InputStream? =
    try {
        source()
    } catch (e: IOException) {
        Log.w(TAG, "image source would not open: ${e.javaClass.simpleName}")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "image source refused: ${e.javaClass.simpleName}")
        null
    }

private const val TAG = "ImageDecode"

/**
 * [bytes]' EXIF orientation tag ([ExifInterface.ORIENTATION_UNDEFINED] when they carry none), or null when their
 * metadata will not parse — an orientation nobody can read is not one a caller may assume is upright.
 */
internal fun exifOrientation(bytes: ByteArray): Int? =
    runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
    }.getOrNull()

/** Rotates [bitmap] upright per its EXIF [orientation]; returns it unchanged when no rotation applies. */
@Suppress("MagicNumber") // rotation degrees (90/180/270) mirror the named ORIENTATION_ROTATE_* constants
private fun rotatedForExif(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Largest power-of-two sample size that keeps both decoded dimensions at or above [maxDim], so the
 * coarse decode never drops below the target before [downscale] applies the exact bound.
 */
private fun sampleSizeFor(
    width: Int,
    height: Int,
    maxDim: Int,
): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxDim && height / (sample * 2) >= maxDim) {
        sample *= 2
    }
    return sample
}

/**
 * Decodes a bitmap from in-memory [bytes], sub-sampled and downscaled so neither side exceeds [maxDim]
 * (so screening a peer-supplied image can't OOM). For an animated GIF this yields its first frame, which
 * is enough for content classification. Returns null if the bytes can't be decoded — also when the decoder
 * throws on them instead of answering null, as Robolectric's ImageIO does on a sealed photo whose ciphertext
 * happens to start "BM" (#139): the receive-side screen stores a blob whatever it decides, and a throw from here
 * dropped it.
 */
internal fun decodeBoundedFromBytes(
    bytes: ByteArray,
    maxDim: Int,
): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { downscale(it, maxDim) }
    }.getOrNull()

/** [bytes]' width and height as their header declares them, or null when it will not read. Nothing is decoded. */
internal fun declaredSize(bytes: ByteArray): Pair<Int, Int>? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        (bounds.outWidth to bounds.outHeight).takeIf { (w, h) -> w > 0 && h > 0 }
    }.getOrNull()

/**
 * Whether [bytes] decode, at exactly the [width] × [height] their header declares — the check a picture passes
 * before it leaves untouched (`AttachmentStore.passThrough`). Read at an eighth of the pixels: the decoder still
 * walks the whole stream, and nothing is held.
 */
internal fun decodesAt(
    bytes: ByteArray,
    width: Int,
    height: Int,
): Boolean =
    declaredSize(bytes) == (width to height) &&
        runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = PASS_THROUGH_CHECK_SAMPLE }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { it.recycle() } != null
        }.getOrDefault(false)

private const val PASS_THROUGH_CHECK_SAMPLE = 8

/** Scales [src] down so neither side exceeds [max], preserving aspect ratio; returns [src] if already within bounds. */
internal fun downscale(
    src: Bitmap,
    max: Int,
): Bitmap {
    if (src.width <= max && src.height <= max) return src
    val ratio = min(max.toFloat() / src.width, max.toFloat() / src.height)
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    return src.scale(w, h)
}
