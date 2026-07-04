// ktlint's filename rule wants a single-type file named after the type (CropRect.kt); this file is
// deliberately named for its primary export, computeAvatarCrop (matching AvatarCropTest.kt), so the
// rule is suppressed here rather than misnaming the file after its return type.
@file:Suppress("ktlint:standard:filename")

package app.getknit.knit.ui.util

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Computes the square region of a source bitmap that falls inside a centered circular crop window,
 * given the user's pinch [scale] and drag [offsetX]/[offsetY] as produced by a Compose
 * `transformable` + `graphicsLayer` (center transform origin; translation in viewport pixels,
 * unscaled — the same convention as `FullscreenImageViewer` in chat).
 *
 * Both the image and the crop window are centered, so the viewport size cancels out and is not an
 * input. At [scale] == 1 with no offset this reproduces a plain center-crop. Because [scale] >= 1,
 * the square always fits inside the source, so the result is only ever *shifted* back in-bounds
 * (never shrunk) — keeping it square so the later scale-to-256² introduces no distortion.
 *
 * Pure float/int math with no Android types, so it is directly unit-testable.
 */
fun computeAvatarCrop(
    srcW: Int,
    srcH: Int,
    diameter: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): CropRect {
    val minSide = min(srcW, srcH)
    val screenPerSrc = (diameter / minSide) * scale // total source-px -> screen-px factor
    val side = (diameter / screenPerSrc).roundToInt().coerceIn(1, minSide)
    val cx = srcW / 2f - offsetX / screenPerSrc
    val cy = srcH / 2f - offsetY / screenPerSrc
    val left = (cx - side / 2f).roundToInt().coerceIn(0, srcW - side)
    val top = (cy - side / 2f).roundToInt().coerceIn(0, srcH - side)
    return CropRect(left, top, side, side)
}

// Declared after computeAvatarCrop so the file's only type isn't its first declaration — that keeps
// the meaningful file name AvatarCrop.kt instead of detekt's MatchingDeclarationName forcing CropRect.kt.

/** Square pixel rectangle in source-bitmap coordinates. [width] always equals [height]. */
data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)
