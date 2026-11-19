package app.getknit.knit.ui.redact

import kotlin.math.min

/**
 * Where the photo sits on the review screen: a view pixel is `photo pixel × scale + offset`. Pure, so the zoom and pan
 * arithmetic a pinch drives is tested on the JVM.
 */
data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun toPhotoX(viewX: Float): Float = (viewX - offsetX) / scale

    fun toPhotoY(viewY: Float): Float = (viewY - offsetY) / scale

    fun toViewX(photoX: Float): Float = photoX * scale + offsetX

    fun toViewY(photoY: Float): Float = photoY * scale + offsetY

    /**
     * Zoomed by [factor] about the view point ([centroidX], [centroidY]), which stays over the same photo pixel; the scale
     * is held within [minScale] and [maxScale].
     */
    fun zoomedAround(
        centroidX: Float,
        centroidY: Float,
        factor: Float,
        minScale: Float,
        maxScale: Float,
    ): ViewTransform {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        val applied = next / scale
        return ViewTransform(next, centroidX - (centroidX - offsetX) * applied, centroidY - (centroidY - offsetY) * applied)
    }

    fun panned(
        dx: Float,
        dy: Float,
    ): ViewTransform = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /**
     * Kept on screen: on an axis where the photo is narrower than the view it is centred, and where it is wider no edge
     * comes inside the view's, so a pan cannot lose the photo.
     */
    fun clamped(
        photoWidth: Int,
        photoHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): ViewTransform =
        copy(offsetX = axis(offsetX, photoWidth * scale, viewWidth), offsetY = axis(offsetY, photoHeight * scale, viewHeight))

    companion object {
        /** How far past fitting the screen a pinch zooms: far enough to tap a face a few pixels tall. */
        const val MAX_ZOOM = 8f

        /** The whole photo, as large as fits the view, centred. */
        fun fit(
            photoWidth: Int,
            photoHeight: Int,
            viewWidth: Float,
            viewHeight: Float,
        ): ViewTransform {
            val scale = min(viewWidth / photoWidth, viewHeight / photoHeight)
            return ViewTransform(scale, (viewWidth - photoWidth * scale) / 2, (viewHeight - photoHeight * scale) / 2)
        }

        private fun axis(
            offset: Float,
            content: Float,
            view: Float,
        ): Float = if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0f)
    }
}
