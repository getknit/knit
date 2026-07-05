package app.getknit.knit.moderation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device NSFW image classifier backed by a bundled TFLite model — runs fully offline (no network).
 *
 * **Graceful degradation:** if the model asset is absent or fails to load, [classify] returns
 * [ImageVerdict.ALLOWED], so the whole moderation feature ships and is wired before a model is sourced.
 * Drop a compatible model at `assets/[modelAsset]` to activate it (see `assets/moderation/README.md`).
 *
 * **Expected model:** a MobileNet-style classifier with input `[1, H, W, 3]` (float32 normalized to
 * `[0,1]`, or uint8 `0..255`) and output `[1, N]` class scores. The scores at [unsafeClasses] are
 * summed into the NSFW probability; the image is flagged when that exceeds [threshold]. Shapes and the
 * input dtype are read from the model at load, so any input size works. The defaults match the common
 * GantMan / NSFWJS 5-class model (`0=drawings 1=hentai 2=neutral 3=porn 4=sexy` → unsafe `{1,3,4}`).
 *
 * The TFLite [Interpreter] is loaded lazily on first use and is not thread-safe, so inference is
 * serialized behind [mutex] and runs off the main thread.
 */
class NsfwImageModerator(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val unsafeClasses: Set<Int> = DEFAULT_UNSAFE_CLASSES,
    private val threshold: Float = DEFAULT_THRESHOLD,
) : ImageModerator {
    private val mutex = Mutex()
    private var loaded = false
    private var interpreter: Interpreter? = null

    override suspend fun classify(bitmap: Bitmap): ImageVerdict =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!loaded) {
                    loaded = true
                    // runCatching, not just loadInterpreter's own catch: it also absorbs Errors (TFLite's
                    // JNI load can throw UnsatisfiedLinkError; the direct buffer can OOM). classify sits
                    // on the inbound blob path and must never throw.
                    interpreter = runCatching { loadInterpreter() }.getOrNull()
                }
                val tflite = interpreter ?: return@withContext ImageVerdict.ALLOWED
                runCatching { infer(tflite, bitmap) }.getOrDefault(ImageVerdict.ALLOWED)
            }
        }

    private fun loadInterpreter(): Interpreter? =
        try {
            val bytes = context.assets.open(modelAsset).use { it.readBytes() }
            val model =
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            Interpreter(model)
        } catch (_: Exception) {
            // No model bundled (IOException) or malformed/incompatible flatbuffer (Interpreter throws
            // IllegalArgument / IllegalState) -> degrade to allow-all.
            null
        }

    private fun infer(
        tflite: Interpreter,
        bitmap: Bitmap,
    ): ImageVerdict {
        val inputTensor = tflite.getInputTensor(0)
        val shape = inputTensor.shape() // [1, H, W, 3]
        val height = shape[INPUT_H]
        val width = shape[INPUT_W]
        val quantized = inputTensor.dataType() == DataType.UINT8
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)

        val classCount = tflite.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(classCount) }
        tflite.run(toInputBuffer(scaled, width, height, quantized), output)

        val scores = output[0]
        val nsfw = unsafeClasses.filter { it in scores.indices }.sumOf { scores[it].toDouble() }.toFloat()
        return ImageVerdict(allowed = nsfw < threshold, score = nsfw)
    }

    private fun toInputBuffer(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        quantized: Boolean,
    ): ByteBuffer {
        val bytesPerChannel = if (quantized) 1 else FLOAT_BYTES
        val buffer =
            ByteBuffer
                .allocateDirect(width * height * CHANNELS * bytesPerChannel)
                .order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            if (quantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                buffer.putFloat(r / MAX_CHANNEL)
                buffer.putFloat(g / MAX_CHANNEL)
                buffer.putFloat(b / MAX_CHANNEL)
            }
        }
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val DEFAULT_MODEL_ASSET = "moderation/nsfw.tflite"
        val DEFAULT_UNSAFE_CLASSES = setOf(1, 3, 4)
        const val DEFAULT_THRESHOLD = 0.9f

        const val INPUT_H = 1
        const val INPUT_W = 2
        const val CHANNELS = 3
        const val FLOAT_BYTES = 4
        const val MAX_CHANNEL = 255f
    }
}
