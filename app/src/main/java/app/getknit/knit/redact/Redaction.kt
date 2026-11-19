package app.getknit.knit.redact

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One area of a photo to cover, in the photo's pixels. [found] says the detector put it there, so a tap uncovers it
 * and a second tap covers it again; one the person added goes away on a tap. Only a [covered] one is filled, with the
 * average colour of what it covers ([fillColor]).
 */
data class Cover(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val found: Boolean,
    val covered: Boolean = true,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The rounding of its corners: the review screen draws the same shape the sent photo is filled with. */
    val cornerRadius: Float get() = min(width, height) * CORNER

    /**
     * The colour this cover is filled with: the average of the photo under it, opaque. [pixel] reads the photo's ARGB
     * at a pixel; it is read at up to [FILL_SAMPLES] points a side spread evenly over the cover, so the review screen and
     * the sent photo, reading the same pixels, fill it the same. Each pixel counts by its alpha, so a clear area does not
     * darken the average; a cover over nothing visible is black.
     */
    fun fillColor(pixel: (x: Int, y: Int) -> Int): Int {
        val columns = min(FILL_SAMPLES, max(1, ceil(width).toInt()))
        val rows = min(FILL_SAMPLES, max(1, ceil(height).toInt()))
        var red = 0L
        var green = 0L
        var blue = 0L
        var weight = 0L
        for (j in 0 until rows) {
            val y = (top + (j + HALF) * height / rows).toInt()
            for (i in 0 until columns) {
                val argb = pixel((left + (i + HALF) * width / columns).toInt(), y)
                val alpha = (argb ushr ALPHA_SHIFT) and BYTE
                red += ((argb shr RED_SHIFT) and BYTE) * alpha.toLong()
                green += ((argb shr GREEN_SHIFT) and BYTE) * alpha.toLong()
                blue += (argb and BYTE) * alpha.toLong()
                weight += alpha
            }
        }
        if (weight == 0L) return OPAQUE

        fun mean(sum: Long) = ((sum + weight / 2) / weight).toInt()
        return OPAQUE or (mean(red) shl RED_SHIFT) or (mean(green) shl GREEN_SHIFT) or mean(blue)
    }

    /** Whether ([x], [y]) is on it, or within [slop] of it. */
    fun contains(
        x: Float,
        y: Float,
        slop: Float = 0f,
    ): Boolean = x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop

    companion object {
        /** The most points a side [fillColor] reads: 4,096 at most, however large the cover. */
        const val FILL_SAMPLES = 64

        private const val CORNER = 0.2f
        private const val HALF = 0.5f
        private const val BYTE = 0xFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val OPAQUE = -0x1000000 // 0xFF000000: opaque black
    }
}

/**
 * What will be covered in a [width] × [height] photo before it is sent: the faces the detector found, each grown by
 * [PAD] so the cover takes the hair and ears a tight face box leaves out, and whatever the person adds or uncovers on
 * the review screen. Immutable and pure, so the review screen's state is one value a JVM test can drive.
 */
data class Redaction(
    val width: Int,
    val height: Int,
    val covers: List<Cover> = emptyList(),
) {
    /** How many covers will be filled. */
    val coveredCount: Int get() = covers.count { it.covered }

    /**
     * A tap at ([x], [y]): on a cover (the smallest one within [slop], when they overlap), [toggle] it; anywhere else,
     * add a cover the size of a face here, centred on the tap.
     */
    fun tap(
        x: Float,
        y: Float,
        slop: Float = 0f,
    ): Redaction {
        val hit = covers.filter { it.contains(x, y, slop) }.minByOrNull { it.width * it.height }
        return if (hit != null) toggle(hit.id) else added(x, y)
    }

    /** A found cover is uncovered or covered again; an added one is removed. An unknown [id] changes nothing. */
    fun toggle(id: Int): Redaction {
        val cover = covers.firstOrNull { it.id == id } ?: return this
        return if (cover.found) {
            copy(covers = covers.map { if (it.id == id) it.copy(covered = !it.covered) else it })
        } else {
            copy(covers = covers.filterNot { it.id == id })
        }
    }

    /**
     * A cover dragged from ([x0], [y0]) to ([x1], [y1]), either corner first, clipped to the photo. A drag shorter than
     * [MIN_DRAWN] of the photo's short side on either axis is taken for a slip and adds nothing.
     */
    fun draw(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Redaction {
        val left = min(x0, x1).coerceIn(0f, width.toFloat())
        val right = max(x0, x1).coerceIn(0f, width.toFloat())
        val top = min(y0, y1).coerceIn(0f, height.toFloat())
        val bottom = max(y0, y1).coerceIn(0f, height.toFloat())
        val least = min(width, height) * MIN_DRAWN
        if (right - left < least || bottom - top < least) return this
        return copy(covers = covers + Cover(nextId(), left, top, right, bottom, found = false))
    }

    /**
     * The size a tap adds: the median of the found covers' sizes, the faces in this photo being the best guess at the
     * size of the one the detector missed; with none found, [TAP_SIDE] of the photo's short side, a face's proportions.
     */
    fun tapSize(): Pair<Float, Float> {
        val found = covers.filter { it.found }
        if (found.isEmpty()) {
            val side = min(width, height) * TAP_SIDE
            return side to side * FACE_ASPECT
        }
        return median(found.map { it.width }) to median(found.map { it.height })
    }

    private fun added(
        x: Float,
        y: Float,
    ): Redaction {
        val (w, h) = tapSize()
        // Shifted, not cut, to stay inside the photo: a face at the edge still gets a whole cover.
        val left = (x - w / 2).coerceIn(0f, max(0f, width - w))
        val top = (y - h / 2).coerceIn(0f, max(0f, height - h))
        val cover = Cover(nextId(), left, top, min(width.toFloat(), left + w), min(height.toFloat(), top + h), found = false)
        return copy(covers = covers + cover)
    }

    private fun nextId(): Int = (covers.maxOfOrNull { it.id } ?: -1) + 1

    companion object {
        /** How much a found face's box grows, about its centre: what the WIDER FACE coverage was measured at. */
        const val PAD = 1.3f

        /** The smallest drag that adds a cover, as a share of the photo's short side. */
        const val MIN_DRAWN = 0.01f

        /** A tapped cover's width with no faces found, as a share of the photo's short side. */
        const val TAP_SIDE = 0.08f

        /** A face's height over its width. */
        const val FACE_ASPECT = 1.25f

        /** The covers for [faces] in a [width] × [height] photo: each face grown by [PAD] and clipped to the photo. */
        fun of(
            width: Int,
            height: Int,
            faces: List<FaceBox>,
        ): Redaction =
            Redaction(
                width,
                height,
                faces
                    .mapIndexed { i, face ->
                        val cx = face.left + face.width / 2
                        val cy = face.top + face.height / 2
                        val hw = face.width * PAD / 2
                        val hh = face.height * PAD / 2
                        Cover(
                            id = i,
                            left = (cx - hw).coerceIn(0f, width.toFloat()),
                            top = (cy - hh).coerceIn(0f, height.toFloat()),
                            right = (cx + hw).coerceIn(0f, width.toFloat()),
                            bottom = (cy + hh).coerceIn(0f, height.toFloat()),
                            found = true,
                        )
                    }.filter { it.width > 0f && it.height > 0f },
            )

        private fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
