package app.getknit.knit.redact

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.moderation.TfLiteModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs

/**
 * The bundled face detector on ART and the shipped LiteRT: the model maps out of the APK and answers what ONNX Runtime
 * answered for the same input on a desktop, the whole detector finds the faces the reference pipeline found in a photo,
 * and the lease releases and reloads it. The photo is NASA's Expedition 75 crew portrait, public domain; the vectors
 * are `scripts/face-model.py vectors`.
 *
 * Not in CI's instrumented packages; run it on an emulator, one class per call:
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.redact.YuNetFaceDetectorInstrumentedTest
 *
 * It logs its timings under the `FaceDetectTiming` tag.
 */
class YuNetFaceDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The app: its bundled model. */
    private val app = instrumentation.targetContext

    /** The test APK: the photo and the vectors. */
    private val testAssets = instrumentation.context.assets

    /**
     * `redact/yunet_heads.b64`, decoded: the reference vector is kept as base64 text because F-Droid's source scanner
     * refuses a raw binary anywhere in the tree, test assets included.
     */
    private fun referenceHeads(): ByteArray =
        Base64.getMimeDecoder().decode(testAssets.open("redact/yunet_heads.b64").use { it.readBytes() })

    @Test
    fun theBundledModelAnswersWhatOnnxRuntimeAnsweredForTheSameInput() {
        val vector = referenceHeads()
        val buffer = ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("YNH1", String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII))
        val width = buffer.int
        val height = buffer.int
        val input = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
        repeat(width * height * 3) { input.putFloat((buffer.get().toInt() and 0xFF).toFloat()) }
        input.rewind()

        val model = TfLiteModels.mapAsset(app, YuNetFaceDetector.DEFAULT_MODEL_ASSET)
        Interpreter(model, TfLiteModels.interpreterOptions()).use { interpreter ->
            interpreter.resizeInput(0, intArrayOf(1, height, width, 3))
            interpreter.allocateTensors()
            val names = listOf("cls", "obj", "bbox").flatMap { kind -> YuNetDecoder.STRIDES.map { "${kind}_$it" } }
            val outputs =
                names.associateWith { name ->
                    val cells = YuNetDecoder.cells(name.substringAfter('_').toInt(), width, height)
                    ByteBuffer.allocateDirect(cells * (if (name.startsWith("bbox")) 4 else 1) * 4).order(ByteOrder.nativeOrder())
                }
            interpreter.runForMultipleInputsOutputs(
                arrayOf(input),
                outputs.mapKeys { (name, _) -> interpreter.getOutputIndex(name) },
            )

            var worst = 0f
            for (name in names) {
                val got = outputs.getValue(name).apply { rewind() }.asFloatBuffer()
                repeat(got.remaining()) { worst = maxOf(worst, abs(got.get() - buffer.float)) }
            }
            assertFalse("${buffer.remaining()} reference bytes unread", buffer.hasRemaining())
            assertTrue("largest difference from ONNX Runtime: $worst", worst <= MAX_HEAD_DIFFERENCE)
        }
    }

    @Test
    fun aPictureIsPackedAsItsOwnBgrValues() {
        // The parity test above feeds the vector's bytes to the model directly; this holds packInput to the same floats.
        val buffer = ByteBuffer.wrap(referenceHeads()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val width = buffer.int
        val height = buffer.int
        val bgr = ByteArray(width * height * 3).also { buffer.get(it) }
        val bitmap = createBitmap(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                bitmap[x, y] = Color.rgb(bgr[i + 2].toInt() and 0xFF, bgr[i + 1].toInt() and 0xFF, bgr[i].toInt() and 0xFF)
            }
        }

        val packed = packInput(ByteBuffer.allocateDirect(inputBytes(width, height)), bitmap).asFloatBuffer()

        assertEquals(bgr.size, packed.remaining())
        bgr.forEachIndexed { i, b -> assertEquals("element $i", (b.toInt() and 0xFF).toFloat(), packed.get(), 0f) }
    }

    @Test
    fun aSmallerPassLeavesZerosWhereALargerPassesPictureWas() {
        // The detector packs every pass into one buffer sized for the first; a quarter pass must not see the full one.
        val large = createBitmap(100, 70).apply { eraseColor(Color.rgb(10, 20, 30)) }
        val small = createBitmap(40, 30).apply { eraseColor(Color.rgb(1, 2, 3)) }
        val input = ByteBuffer.allocateDirect(inputBytes(large.width, large.height))
        packInput(input, large)

        val packed = packInput(input, small)

        val (width, height) = YuNetDecoder.padded(small.width) to YuNetDecoder.padded(small.height)
        assertEquals(inputBytes(small.width, small.height), packed.capacity())
        val floats = packed.asFloatBuffer()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val want = if (x < small.width && y < small.height) floatArrayOf(3f, 2f, 1f) else floatArrayOf(0f, 0f, 0f)
                want.forEach { assertEquals("pixel ($x, $y)", it, floats.get(), 0f) }
            }
        }
    }

    @Test
    fun findsTheFacesInTheCrewPortraitThatTheReferenceFinds() =
        withDetector { detector ->
            val photo = checkNotNull(decodeBoundedFromBytes(testAssets.open("redact/crew.jpg").use { it.readBytes() }, 4096))
            val reference = referenceFaces()

            var started = SystemClock.elapsedRealtime()
            val faces = checkNotNull(detector.detect(photo)) { "the detector did not run" }
            Log.i(TAG, "first detect (load, probe, ${photo.width} x ${photo.height}): ${SystemClock.elapsedRealtime() - started} ms")

            for (want in reference.filter { it.score >= CONFIDENT }) {
                assertTrue("no face where the reference has $want; found $faces", faces.any { it.iou(want) >= SAME_FACE })
            }
            for (got in faces.filter { it.score >= CONFIDENT }) {
                assertTrue("$got is not among the reference's $reference", reference.any { it.iou(got) >= SAME_FACE })
            }

            started = SystemClock.elapsedRealtime()
            detector.detect(photo)
            Log.i(TAG, "warm detect, ${photo.width} x ${photo.height}: ${SystemClock.elapsedRealtime() - started} ms")
            // Enlarged to the 1280 px long edge Knit sends: the cost of a photo that size, not a photo taken at it.
            val upscaled = photo.scale(1280, photo.height * 1280 / photo.width)
            started = SystemClock.elapsedRealtime()
            val atUpscaled = detector.detect(upscaled)
            Log.i(TAG, "warm detect, upscaled to ${upscaled.width} x ${upscaled.height}: ${SystemClock.elapsedRealtime() - started} ms")
            assertEquals(faces.count { it.score >= CONFIDENT }, atUpscaled?.count { it.score >= CONFIDENT })
        }

    @Test
    fun aPhotoCoveredWhereItFoundFacesHasNoneOfThemLeftToFind() =
        withDetector { detector ->
            val photo = checkNotNull(decodeBoundedFromBytes(testAssets.open("redact/crew.jpg").use { it.readBytes() }, 4096))
            val faces = checkNotNull(detector.detect(photo)).filter { it.score >= CONFIDENT }
            check(faces.size == 7) { "the crew portrait should show seven faces, found $faces" }

            val (mime, bytes) = FaceRedaction.encode(FaceRedaction.covered(photo, Redaction.of(photo.width, photo.height, faces)))
            val sent = checkNotNull(decodeBoundedFromBytes(bytes, 4096))
            val left = checkNotNull(detector.detect(sent)).filter { found -> faces.any { it.iou(found) > 0f } }

            assertEquals("image/jpeg", mime)
            assertTrue("faces still found under the covers: $left", left.none { it.score >= CONFIDENT })
        }

    @Test
    fun aPhotoHeldAsAHardwareBitmapIsCoveredLikeItsSoftwareTwin() {
        // A HARDWARE bitmap's pixels cannot be read in place; the covering reads them from its own software copy.
        val photo = checkNotNull(decodeBoundedFromBytes(testAssets.open("redact/crew.jpg").use { it.readBytes() }, 4096))
        val hardware = checkNotNull(photo.copy(Bitmap.Config.HARDWARE, false)) { "no HARDWARE bitmap on this device" }
        val redaction = Redaction(photo.width, photo.height).draw(100f, 100f, 220f, 240f)

        val fromHardware = FaceRedaction.covered(hardware, redaction)
        val fromSoftware = FaceRedaction.covered(photo, redaction)

        assertEquals(Bitmap.Config.ARGB_8888, fromHardware.config)
        assertEquals(fromSoftware.getPixel(160, 170), fromHardware.getPixel(160, 170))
        assertEquals(photo.getPixel(20, 20), fromHardware.getPixel(20, 20))
    }

    @Test
    fun releasesTheEngineAndReloadsIt() =
        withDetector { detector ->
            val photo = checkNotNull(decodeBoundedFromBytes(testAssets.open("redact/crew.jpg").use { it.readBytes() }, 4096))

            val before = checkNotNull(detector.detect(photo)) { "the detector did not run" }
            assertTrue("the model did not load from the APK", detector.isResident)

            detector.unload()
            assertFalse(detector.isResident)

            assertEquals(before, detector.detect(photo))
            assertTrue(detector.isResident)
        }

    private fun withDetector(block: suspend (YuNetFaceDetector) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            runBlocking { block(YuNetFaceDetector(app, scope = scope)) }
        } finally {
            scope.cancel()
        }
    }

    /** The reference pipeline's faces in the photo: every pass through OpenCV's detector, at a 0.2 floor. */
    private fun referenceFaces(): List<FaceBox> {
        val rows = JSONArray(testAssets.open("redact/crew_faces.json").use { it.readBytes().decodeToString() })
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONArray(i)
            FaceBox(
                r.getDouble(0).toFloat(),
                r.getDouble(1).toFloat(),
                r.getDouble(2).toFloat(),
                r.getDouble(3).toFloat(),
                r.getDouble(4).toFloat(),
            )
        }
    }

    private companion object {
        const val TAG = "FaceDetectTiming"

        /** fp32 on XNNPACK against fp32 on ONNX Runtime; a desktop LiteRT 1.4.0 differed by 2.3e-5. */
        const val MAX_HEAD_DIFFERENCE = 1e-3f

        /** Well clear of the 0.3 threshold, where a JPEG decoder's rounding cannot move a face across it. */
        const val CONFIDENT = 0.5f

        /** The same face found by two pipelines that scale the passes with different filters. */
        const val SAME_FACE = 0.7f
    }
}
