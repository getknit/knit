package app.getknit.knit.redact

import app.getknit.knit.legal.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * [YuNetDecoder] against OpenCV's `FaceDetectorYN` post-processing. The single-cell cases are worked by hand from
 * OpenCV's formula (`face_detect.cpp`); the reference case replays a real inference and expects the faces OpenCV's own
 * detector found in the same picture (`scripts/face-model.py vectors`), so the decoder is held to the reference
 * implementation, not to itself.
 */
class YuNetDecoderTest {
    @Test
    fun aCellDecodesToTheBoxOpenCvDrawsForIt() {
        // Stride 8 over a 64 x 64 input: an 8-column grid, so cell 19 is row 2, column 3. Centre (3 + 0.5) * 8 = 28,
        // (2 + 0.25) * 8 = 18; size e^ln2 * 8 = 16 by e^0 * 8 = 8; score sqrt(0.81 * 1) = 0.9.
        val heads = heads(64, 64, cell = 19, stride = 8, cls = 0.81f, obj = 1f, bbox = floatArrayOf(0.5f, 0.25f, ln(2f), 0f))

        val faces = YuNetDecoder.decode(heads, paddedWidth = 64, threshold = 0.3f)

        assertEquals(1, faces.size)
        assertBox(FaceBox(20f, 14f, 16f, 8f, 0.9f), faces.single())
    }

    @Test
    fun aCoarseStrideCountsItsColumnsAcrossThePaddedWidth() {
        // Stride 32 over 96 wide by 64 tall: 3 columns, so cell 4 is row 1, column 1 (a grid read by height would put
        // it at row 2, column 0, off the input). A zero box is one cell square, centred on the cell's corner.
        val heads = heads(96, 64, cell = 4, stride = 32, cls = 1f, obj = 1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))

        val face = YuNetDecoder.decode(heads, paddedWidth = 96, threshold = 0.3f).single()

        assertBox(FaceBox(16f, 16f, 32f, 32f, 1f), face)
    }

    @Test
    fun eachScoreIsClampedToTheUnitIntervalBeforeTheMean() {
        // Unclamped, sqrt(1.5 * 0.25) = 0.61; OpenCV clamps the class score to 1 first: sqrt(1 * 0.25) = 0.5.
        val heads = heads(64, 64, cell = 0, stride = 8, cls = 1.5f, obj = 0.25f, bbox = floatArrayOf(0f, 0f, 0f, 0f))

        assertEquals(0.5f, YuNetDecoder.decode(heads, 64, threshold = 0.3f).single().score, 0f)
        // A negative class score clamps to 0, whatever the objectness.
        val negative = heads(64, 64, cell = 0, stride = 8, cls = -0.2f, obj = 1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))
        assertTrue(YuNetDecoder.decode(negative, 64, threshold = 0f).isEmpty())
    }

    @Test
    fun aScoreExactlyAtTheThresholdIsDroppedAndOneJustAboveIsKept() {
        val at = heads(64, 64, cell = 0, stride = 8, cls = 0.25f, obj = 1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))
        val above = heads(64, 64, cell = 0, stride = 8, cls = 0.2601f, obj = 1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))

        assertTrue("sqrt(0.25) = 0.5 is not above 0.5", YuNetDecoder.decode(at, 64, threshold = 0.5f).isEmpty())
        assertEquals(1, YuNetDecoder.decode(above, 64, threshold = 0.5f).size)
    }

    @Test
    fun floatNmsKeepsABoxOverlappingByExactlyTheLimitAndDropsOneJustOver() {
        // Knit's NMS, on float boxes: OpenCV truncates each box to whole pixels first, so it would read the 3.1 px box
        // as 3 px and keep it. The expected values here are the port's own rule (YuNetDecoder.nms says why).
        val top = FaceBox(0f, 0f, 10f, 10f, 0.9f)
        // Wholly inside the top box: IoU is its area over the top box's, 30 / 100 = 0.3, then 31 / 100.
        val atLimit = FaceBox(0f, 0f, 3f, 10f, 0.8f)
        val overLimit = FaceBox(0f, 0f, 3.1f, 10f, 0.85f)
        val apart = FaceBox(50f, 50f, 10f, 10f, 0.4f)

        val kept = YuNetDecoder.nms(listOf(apart, atLimit, top, overLimit))

        assertEquals(listOf(top, atLimit, apart), kept)
    }

    @Test
    fun aPhotoGetsFullHalfAndQuarterPasses() {
        assertEquals(listOf(1280 to 960, 640 to 480, 320 to 240), YuNetDecoder.passSizes(1280, 960))
        // Integer halves, as the bitmap is scaled.
        assertEquals(listOf(1280 to 961, 640 to 480, 320 to 240), YuNetDecoder.passSizes(1280, 961))
    }

    @Test
    fun eachPassesScaleIsThePhotosHeightOverItsOwnOneForEveryPassItRuns() {
        // 961 rows halve to 480 and then 240: the scales are not exactly 2 and 4, and a width ratio would be.
        assertEquals(listOf(1f, 961f / 480f, 961f / 240f), YuNetDecoder.passScales(1280, 961))
        assertEquals(listOf(1f), YuNetDecoder.passScales(300, 300))
    }

    @Test
    fun aReducedPassStopsWhereItsLongEdgeWouldFallBelowTheFloor() {
        val floor = YuNetDecoder.MIN_PASS_EDGE
        assertEquals(listOf(floor * 2 to 100, floor to 50), YuNetDecoder.passSizes(floor * 2, 100))
        assertEquals(listOf(floor * 2 - 1 to 100), YuNetDecoder.passSizes(floor * 2 - 1, 100))
    }

    @Test
    fun aLargeBoxFromTheFullOrHalfPassIsKeptOnlyAboveTheLargeFaceScore() {
        val large = YuNetDecoder.LARGE_FACE
        val at = box(0f, 0f, large, YuNetDecoder.LARGE_FACE_SCORE)
        val above = box(200f, 0f, large, 0.51f)
        val smallAndFaint = box(400f, 0f, large - 0.1f, 0.31f)

        for (pass in 0..1) {
            val passes = List(3) { if (it == pass) listOf(at, above, smallAndFaint) else emptyList() }
            assertEquals("pass $pass", setOf(above, smallAndFaint), YuNetDecoder.merge(passes, SCALES).toSet())
        }
    }

    @Test
    fun theQuarterPassReportsNoFaceUnderItsFloorInItsOwnPixelsAndNoScoreFloorForLargeOnes() {
        // At four photo pixels to one of its own, the floor is 160 photo pixels.
        val floor = YuNetDecoder.COARSE_MIN_FACE * 4
        val under = box(0f, 0f, floor - 0.5f, 0.9f)
        val at = box(300f, 0f, floor, 0.9f)
        val largeAndFaint = box(600f, 0f, floor * 2, 0.31f)

        val merged = YuNetDecoder.merge(listOf(emptyList(), emptyList(), listOf(under, at, largeAndFaint)), SCALES)

        assertEquals(setOf(at, largeAndFaint), merged.toSet())
    }

    @Test
    fun aBoxMostlyInsideALargerOneIsDroppedAndTheLargerKeptWhicheverScoresHigher() {
        // A 70 px box and 10 px ones on its right edge: 6 of 10 columns inside is the limit, kept; 6.1 is past it.
        val larger = box(0f, 0f, 70f, 0.4f)
        val inside = box(20f, 20f, 10f, 0.95f)
        val atLimit = FaceBox(64f, 40f, 10f, 10f, 0.9f)
        val pastLimit = FaceBox(63.9f, 55f, 10f, 10f, 0.9f)

        val merged = YuNetDecoder.merge(listOf(listOf(larger, inside, atLimit, pastLimit), emptyList(), emptyList()), SCALES)

        assertEquals(setOf(larger, atLimit), merged.toSet())
    }

    @Test
    fun facesSideBySideAreAllKept() {
        val row = (0 until 5).map { box(it * 50f, 0f, 40f, 0.9f) }

        assertEquals(row.toSet(), YuNetDecoder.merge(listOf(row, emptyList(), emptyList()), SCALES).toSet())
    }

    @Test
    fun theReferenceHeadsDecodeToTheFacesOpenCvFindsInThatPicture() {
        val vector = ReferenceHeads.read()
        val expected =
            Regex("-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?")
                .findAll(repoFile(FACES).readText())
                .map { it.value.toFloat() }
                .chunked(5)
                .map { FaceBox(it[0], it[1], it[2], it[3], it[4]) }
                .toList()
        check(expected.size == 4) { "the vector should hold four faces, found ${expected.size}" }

        val faces = YuNetDecoder.nms(YuNetDecoder.decode(vector.heads, vector.width, threshold = 0.3f))

        assertEquals("faces: $faces", expected.size, faces.size)
        expected.zip(faces).forEach { (want, got) -> assertBox(want, got, pixels = 0.05f, score = 1e-3f) }
    }

    /** One cell set in one stride's head; every other cell of every stride scores 0. */
    private fun heads(
        width: Int,
        height: Int,
        cell: Int,
        stride: Int,
        cls: Float,
        obj: Float,
        bbox: FloatArray,
    ): List<YuNetDecoder.Head> =
        YuNetDecoder.STRIDES.map { s ->
            val cells = YuNetDecoder.cells(s, width, height)
            val head = YuNetDecoder.Head(s, FloatArray(cells), FloatArray(cells), FloatArray(cells * 4))
            if (s == stride) {
                head.cls[cell] = cls
                head.obj[cell] = obj
                bbox.copyInto(head.bbox, destinationOffset = cell * 4)
            }
            head
        }

    private fun assertBox(
        want: FaceBox,
        got: FaceBox,
        pixels: Float = 1e-4f,
        score: Float = 1e-6f,
    ) {
        val close =
            abs(want.left - got.left) <= pixels && abs(want.top - got.top) <= pixels &&
                abs(want.width - got.width) <= pixels && abs(want.height - got.height) <= pixels &&
                abs(want.score - got.score) <= score
        assertTrue("expected $want, got $got", close)
    }

    /** `yunet_heads.bin`: "YNH1", width, height, the BGR input, then cls, obj and bbox at strides 8, 16, 32. */
    private class ReferenceHeads(
        val width: Int,
        val heads: List<YuNetDecoder.Head>,
    ) {
        companion object {
            fun read(): ReferenceHeads {
                val buffer = ByteBuffer.wrap(repoFile(HEADS).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { buffer.get(it) }
                check(String(magic, Charsets.US_ASCII) == "YNH1") { "not a YuNet heads vector" }
                val width = buffer.int
                val height = buffer.int
                buffer.position(buffer.position() + width * height * 3)

                fun floats(count: Int) =
                    FloatArray(count).also { buffer.asFloatBuffer().get(it) }.also {
                        buffer.position(buffer.position() + count * 4)
                    }
                val cells = YuNetDecoder.STRIDES.map { YuNetDecoder.cells(it, width, height) }
                val cls = cells.map(::floats)
                val obj = cells.map(::floats)
                val bbox = cells.map { floats(it * 4) }
                check(!buffer.hasRemaining()) { "${buffer.remaining()} bytes left over" }
                return ReferenceHeads(
                    width,
                    YuNetDecoder.STRIDES.indices.map { YuNetDecoder.Head(YuNetDecoder.STRIDES[it], cls[it], obj[it], bbox[it]) },
                )
            }
        }
    }

    /** A square box of side [side] at ([left], [top]). */
    private fun box(
        left: Float,
        top: Float,
        side: Float,
        score: Float,
    ) = FaceBox(left, top, side, side, score)

    private companion object {
        /** Photo pixels to one of the pass's own, full size, half and quarter. */
        val SCALES = listOf(1f, 2f, 4f)

        const val HEADS = "app/src/androidTest/assets/redact/yunet_heads.bin"
        const val FACES = "app/src/androidTest/assets/redact/yunet_heads_faces.json"
    }
}
