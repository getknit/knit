package app.getknit.knit.redact

import kotlin.math.max
import kotlin.math.min

/**
 * A face the detector found, in the pixels of the picture it was asked about: [left] and [top] are the corner, [score]
 * the detector's confidence in `(0, 1]`. It is the face, not yet the area to cover: redaction pads it.
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val score: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    val area: Float get() = width * height

    /** Intersection over union with [other]; 0 for boxes that do not touch. */
    fun iou(other: FaceBox): Float {
        val intersection = intersection(other)
        val union = area + other.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /** The share of this box lying inside [other]; 0 for an empty box. */
    fun shareInside(other: FaceBox): Float = if (area > 0f) intersection(other) / area else 0f

    private fun intersection(other: FaceBox): Float =
        max(0f, min(right, other.right) - max(left, other.left)) * max(0f, min(bottom, other.bottom) - max(top, other.top))

    /** This box in a picture [sx] times as wide and [sy] times as tall as the one it was found in. */
    fun scaled(
        sx: Float,
        sy: Float,
    ): FaceBox = FaceBox(left * sx, top * sy, width * sx, height * sy, score)
}
