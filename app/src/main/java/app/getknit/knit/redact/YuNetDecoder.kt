package app.getknit.knit.redact

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * YuNet's post-processing as OpenCV's `FaceDetectorYN` does it (`modules/objdetect/src/face_detect.cpp`), kept pure so
 * a JVM test holds it to OpenCV's own answers (`scripts/face-model.py vectors`).
 *
 * The model sees a picture padded on the right and the bottom to a multiple of [ALIGN], and answers for every cell of
 * a grid at strides 8, 16 and 32: a class score, an objectness score, and a box as its centre's offset from the cell's
 * corner, in cells, beside the log of its size, in cells. It answers five landmarks per cell too; nothing reads them.
 */
internal object YuNetDecoder {
    val STRIDES = intArrayOf(8, 16, 32)

    /** What the model's input sides are padded to: its coarsest stride. */
    const val ALIGN = 32

    /** OpenCV's default: of two boxes overlapping by more than this, the lower-scored is dropped. */
    const val NMS_IOU = 0.3f

    /**
     * The smallest long edge a reduced pass is still run at. Below it the pass sees a thumbnail and reports noise: on a
     * 300 px photo the quarter pass ran at 75 px and covered a man's stomach (2026-10-30, the Pixel 3 trial).
     */
    const val MIN_PASS_EDGE = 320

    /**
     * A box this tall or taller, in the photo's pixels, from the full-size or half-size pass must score above
     * [LARGE_FACE_SCORE]. A large face shows clearly and scores high: on WIDER FACE 98 % of those passes' large boxes on
     * a face did, while two thirds of their large boxes on no face scored at or below it, among them the chest-sized
     * boxes on a shirtless selfie.
     */
    const val LARGE_FACE = 80f

    /** See [LARGE_FACE]. */
    const val LARGE_FACE_SCORE = 0.5f

    /**
     * The smallest face, in its own pixels, the quarter pass reports. Smaller ones are the full-size pass's, at four
     * times the detail; what the quarter pass finds at that size was mostly noise (WIDER FACE: the median of its boxes
     * on no face was 25 px, of its boxes on a face 54 px). It keeps the low threshold above that: it is the pass that
     * finds close-ups, and a close-up can be soft.
     */
    const val COARSE_MIN_FACE = 40f

    /** The share of a box lying inside a larger one past which it is dropped: the larger one covers it already. */
    const val CONTAINED = 0.6f

    /** Full size, half and quarter. */
    const val MAX_PASSES = 3

    /** One stride's outputs, row-major over its grid: [cls] and [obj] one value a cell, [bbox] four. */
    class Head(
        val stride: Int,
        val cls: FloatArray,
        val obj: FloatArray,
        val bbox: FloatArray,
    )

    /** [size] rounded up to the model's grid. */
    fun padded(size: Int): Int = (size + ALIGN - 1) / ALIGN * ALIGN

    /** Cells in [stride]'s grid over a padded input of [paddedWidth] × [paddedHeight]. */
    fun cells(
        stride: Int,
        paddedWidth: Int,
        paddedHeight: Int,
    ): Int = (paddedWidth / stride) * (paddedHeight / stride)

    /**
     * Every cell scoring above [threshold], as a box in the padded input's pixels, before NMS. A cell's score is the
     * geometric mean of its class and objectness scores, each clamped to [0, 1] first; OpenCV's NMS keeps a score
     * strictly above its threshold, and so does this.
     */
    fun decode(
        heads: List<Head>,
        paddedWidth: Int,
        threshold: Float,
    ): List<FaceBox> {
        val out = ArrayList<FaceBox>()
        for (head in heads) {
            val stride = head.stride
            val cols = paddedWidth / stride
            for (i in head.cls.indices) {
                val score = sqrt(head.cls[i].coerceIn(0f, 1f) * head.obj[i].coerceIn(0f, 1f))
                if (score <= threshold) continue
                val row = i / cols
                val col = i % cols
                val cx = (col + head.bbox[BOX * i]) * stride
                val cy = (row + head.bbox[BOX * i + 1]) * stride
                val w = exp(head.bbox[BOX * i + 2]) * stride
                val h = exp(head.bbox[BOX * i + 3]) * stride
                out += FaceBox(cx - w / 2, cy - h / 2, w, h, score)
            }
        }
        return out
    }

    /**
     * Greedy NMS, highest score first: a box overlapping one already kept by more than [iou] is dropped. On float boxes,
     * where OpenCV truncates each box to whole pixels before its NMS: the merge across passes works on boxes scaled back
     * to the photo, and truncating there would round a quarter pass's box by up to four pixels. The two agree on
     * boxes on whole pixels and may differ by a pixel's worth of overlap otherwise.
     */
    fun nms(
        boxes: List<FaceBox>,
        iou: Float = NMS_IOU,
    ): List<FaceBox> {
        val kept = ArrayList<FaceBox>()
        for (box in boxes.sortedByDescending { it.score }) {
            if (kept.none { it.iou(box) > iou }) kept += box
        }
        return kept
    }

    /**
     * The sizes the passes see a [width] × [height] picture at: as it is, then halved (integer halves, the size the
     * bitmap is scaled to) and halved again, while the reduced pass's long edge stays at least [MIN_PASS_EDGE]. YuNet finds faces of
     * about 10 to 300 px; the reduced passes bring a close-up's face into that range, and on synthetic close-ups of
     * 300 to 2000 px in a 1280 px frame they took coverage from 92–96 % at full size alone to 98 %, for about a third
     * more work than the full-size pass.
     */
    fun passSizes(
        width: Int,
        height: Int,
    ): List<Pair<Int, Int>> {
        val sizes = mutableListOf(width to height)
        while (sizes.size < MAX_PASSES) {
            val (w, h) = sizes.last()
            if (maxOf(w, h) / 2 < MIN_PASS_EDGE) break
            sizes += w / 2 to h / 2
        }
        return sizes
    }

    /**
     * Photo pixels to one of each pass's own, height-wise, in [passSizes]' order: what [merge] divides a quarter-pass box
     * by to read its size in that pass's pixels, and what a pass's boxes are scaled up by.
     */
    fun passScales(
        width: Int,
        height: Int,
    ): List<Float> = passSizes(width, height).map { (_, h) -> height.toFloat() / h }

    /**
     * Each pass's faces, already in the photo's pixels (pass `i` at `scales[i]` photo pixels to one of its own), as the
     * covers a photo gets: each pass's own rules ([LARGE_FACE], [COARSE_MIN_FACE]), one [nms] over them all, and no box
     * left lying mostly inside a larger one ([CONTAINED]). Measured on WIDER FACE against the passes merged by NMS alone:
     * 73 % fewer boxes of 80 px and more on no face, 90.8 % of faces of 16 px and more covered against 91.5 %.
     */
    fun merge(
        passes: List<List<FaceBox>>,
        scales: List<Float>,
    ): List<FaceBox> {
        val kept =
            passes.flatMapIndexed { pass, boxes ->
                boxes.filter { box ->
                    if (pass == MAX_PASSES - 1) {
                        box.height / scales[pass] >= COARSE_MIN_FACE
                    } else {
                        box.height < LARGE_FACE || box.score > LARGE_FACE_SCORE
                    }
                }
            }
        val merged = nms(kept)
        return merged.filter { box -> merged.none { it !== box && it.area > box.area && box.shareInside(it) > CONTAINED } }
    }

    private const val BOX = 4
}
