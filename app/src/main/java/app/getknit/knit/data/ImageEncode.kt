package app.getknit.knit.data

import android.graphics.Bitmap
import android.os.Build

/**
 * The lossy WebP format for this API level. `WEBP` (deprecated at API 30) is the only lossy WebP form on
 * API 29, and `WEBP_LOSSY` does not exist there; on 30+ the deprecated constant still works but the new
 * one is the documented form. Shared by every site that re-encodes a picture with transparency
 * (`AttachmentStore`, `WebpTranscode`, `PreviewImage`) so the branch is written once.
 */
@Suppress("DEPRECATION")
internal fun lossyWebpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
