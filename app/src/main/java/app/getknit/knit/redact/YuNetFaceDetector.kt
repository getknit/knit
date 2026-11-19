package app.getknit.knit.redact

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.TfLiteModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * On-device face detection with YuNet (opencv_zoo, MIT), bundled as TFLite and run on the same LiteRT interpreter as
 * the moderation models: fully offline, about 240 KB.
 *
 * The model takes any input size, so a picture is looked at as it is — padded right and bottom to the model's grid,
 * never squeezed to a fixed square — and again at half and a quarter of its size ([YuNetDecoder.passSizes]); each
 * pass's faces are mapped back to the picture and merged ([YuNetDecoder.merge]) into a box per face. On WIDER FACE's
 * protest-like photos that found 89 % of faces 16 px and taller at [DEFAULT_THRESHOLD], where shrinking to a 640 px
 * square found 69 % and OpenCV's demo threshold of 0.9 found 16 %. A lower threshold over a higher one, because a
 * box drawn on something that is not a face costs one tap on the review screen and a missed face costs a person.
 *
 * Held on a [ModelLease] like the moderators (ADR 2026-09.cq9z), released after [DEFAULT_IDLE_MS] rather than the
 * moderators' ten minutes: the interpreter's arena grows to the largest picture it has seen, tens of MB for a full-size
 * photo, and the detector runs in a burst while a photo is attached and then not at all. Every load runs under
 * [ModelLoadGuard] as [ModelLoadGuard.FACES] (ADR 037), and includes one probe inference, so a model that loads but
 * cannot run is a failed load: [detect] answers null, never a quiet empty list.
 */
class YuNetFaceDetector(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val guard: ModelLoadGuard? = null,
    scope: CoroutineScope,
    idleMs: Long = DEFAULT_IDLE_MS,
) : FaceDetector {
    /** [model] is the mapping the interpreter reads in place; it lives exactly as long as [interpreter]. */
    private class Loaded(
        val model: MappedByteBuffer,
        val interpreter: Interpreter,
        val cls: IntArray,
        val obj: IntArray,
        val bbox: IntArray,
    )

    private val lease =
        ModelLease(
            scope = scope,
            idleMs = idleMs,
            load = ::buildInterpreter,
            close = { it.interpreter.close() },
        )

    /** Whether the engine is in memory right now. */
    val isResident: Boolean get() = lease.isResident

    override suspend fun detect(bitmap: Bitmap): List<FaceBox>? =
        withContext(Dispatchers.Default) {
            lease.use { loaded -> loaded?.let { runCatching { detectIn(it, bitmap) }.getOrNull() } }
        }

    /** Release the engine now; the next [detect] reloads it. */
    suspend fun unload() = lease.unload()

    private suspend fun buildInterpreter(): Loaded? =
        if (guard == null) loadAndProbe() else guard.guard(ModelLoadGuard.FACES, ::loadAndProbe)

    /**
     * The guarded region includes one inference, because tensors are allocated and kernels chosen on the first run, not
     * in the constructor. A probe that throws closes the interpreter and fails the load, so the lease is spent and
     * [detect] answers null for the rest of the process. runCatching absorbs Errors too (the JNI load's
     * UnsatisfiedLinkError, the arena's OOM).
     */
    private fun loadAndProbe(): Loaded? =
        runCatching {
            loadInterpreter()?.let { loaded ->
                if (runCatching { detectIn(loaded, createBitmap(YuNetDecoder.ALIGN, YuNetDecoder.ALIGN)) }.isSuccess) {
                    loaded
                } else {
                    loaded.interpreter.close()
                    null
                }
            }
        }.getOrNull()

    private fun loadInterpreter(): Loaded? =
        try {
            val model = TfLiteModels.mapAsset(context, modelAsset)
            val interpreter = Interpreter(model, TfLiteModels.interpreterOptions())

            fun indices(kind: String) = YuNetDecoder.STRIDES.map { interpreter.getOutputIndex("${kind}_$it") }.toIntArray()
            Loaded(model, interpreter, indices("cls"), indices("obj"), indices("bbox"))
        } catch (_: Exception) {
            // No model bundled or a compressed one (FileNotFoundException), a malformed flatbuffer, or an output name
            // that is not there (IllegalArgumentException): no detector.
            null
        }

    /** Every pass over [bitmap], each pass's faces in [bitmap]'s pixels, merged. */
    private fun detectIn(
        loaded: Loaded,
        bitmap: Bitmap,
    ): List<FaceBox> {
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val sizes = YuNetDecoder.passSizes(source.width, source.height)
        // One input buffer, sized for the first pass, sliced for the smaller ones.
        val input = ByteBuffer.allocateDirect(inputBytes(sizes.first().first, sizes.first().second))
        val scales = YuNetDecoder.passScales(source.width, source.height)
        val found = ArrayList<List<FaceBox>>()
        var pass = source
        for ((i, size) in sizes.withIndex()) {
            val (w, h) = size
            if (pass.width != w || pass.height != h) pass = pass.scale(w, h).also { if (pass !== source) pass.recycle() }
            val sx = source.width.toFloat() / w
            found += runPass(loaded, pass, input).map { it.scaled(sx, scales[i]) }
        }
        if (pass !== source) pass.recycle()
        if (source !== bitmap) source.recycle()
        return YuNetDecoder.merge(found, scales)
    }

    private fun runPass(
        loaded: Loaded,
        bitmap: Bitmap,
        input: ByteBuffer,
    ): List<FaceBox> {
        val width = YuNetDecoder.padded(bitmap.width)
        val height = YuNetDecoder.padded(bitmap.height)
        val buffer = packInput(input, bitmap)

        val interpreter = loaded.interpreter
        interpreter.resizeInput(0, intArrayOf(1, height, width, CHANNELS))
        interpreter.allocateTensors()
        val outputs = HashMap<Int, Any>()
        val buffers =
            YuNetDecoder.STRIDES.mapIndexed { i, stride ->
                HeadBuffers(stride, YuNetDecoder.cells(stride, width, height)).also {
                    outputs[loaded.cls[i]] = it.cls
                    outputs[loaded.obj[i]] = it.obj
                    outputs[loaded.bbox[i]] = it.bbox
                }
            }
        interpreter.runForMultipleInputsOutputs(arrayOf(buffer), outputs)
        val decoded = buffers.map { YuNetDecoder.Head(it.stride, it.cls.read(), it.obj.read(), it.bbox.read()) }
        return YuNetDecoder.nms(YuNetDecoder.decode(decoded, width, threshold))
    }

    /** One stride's output buffers, which the interpreter copies its answers into. */
    private class HeadBuffers(
        val stride: Int,
        cells: Int,
    ) {
        val cls: ByteBuffer = floats(cells)
        val obj: ByteBuffer = floats(cells)
        val bbox: ByteBuffer = floats(cells * BOX)
    }

    companion object {
        const val DEFAULT_MODEL_ASSET = "redact/face_detection_yunet_2026may.tflite"

        /** The score above which a cell is a face. See the class comment for why it is this low. */
        const val DEFAULT_THRESHOLD = 0.3f

        /** A minute without a picture, and the engine and its arena go. */
        const val DEFAULT_IDLE_MS = 60_000L

        private const val BOX = 4
    }
}

private fun floats(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

private fun ByteBuffer.read(): FloatArray {
    rewind()
    return FloatArray(capacity() / Float.SIZE_BYTES).also { asFloatBuffer().get(it) }
}

/**
 * [bitmap] packed into the front of [input] as the model reads it, and that front returned as its own buffer:
 * `[1, H, W, 3]` floats for [bitmap] padded to the model's grid, BGR, 0 to 255 unscaled, the picture in the top left and
 * zeros beyond it. Every element of the returned buffer is written, padding included, because [input] is reused by
 * every pass and still holds a larger pass's picture. [input] must hold at least [inputBytes] for [bitmap].
 */
internal fun packInput(
    input: ByteBuffer,
    bitmap: Bitmap,
): ByteBuffer {
    val width = YuNetDecoder.padded(bitmap.width)
    val height = YuNetDecoder.padded(bitmap.height)
    val buffer =
        input
            .duplicate()
            .apply {
                clear()
                limit(inputBytes(bitmap.width, bitmap.height))
            }.slice()
            .order(ByteOrder.nativeOrder())
    val floats = buffer.asFloatBuffer()
    val pixels = IntArray(bitmap.width)
    val row = FloatArray(width * CHANNELS)
    val blank = FloatArray(width * CHANNELS)
    for (y in 0 until height) {
        if (y >= bitmap.height) {
            floats.put(blank)
            continue
        }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (x in pixels.indices) {
            val p = pixels[x]
            row[CHANNELS * x] = (p and BYTE).toFloat()
            row[CHANNELS * x + 1] = (p shr GREEN and BYTE).toFloat()
            row[CHANNELS * x + 2] = (p shr RED and BYTE).toFloat()
        }
        floats.put(row)
    }
    return buffer
}

/** The bytes [packInput] writes for a [width] × [height] picture. */
internal fun inputBytes(
    width: Int,
    height: Int,
): Int = YuNetDecoder.padded(width) * YuNetDecoder.padded(height) * CHANNELS * Float.SIZE_BYTES

private const val CHANNELS = 3
private const val BYTE = 0xFF
private const val GREEN = 8
private const val RED = 16
