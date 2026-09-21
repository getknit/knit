package app.getknit.knit.moderation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

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
 * The TFLite [Interpreter] is mapped straight out of the APK and held on a [ModelLease]: loaded on the
 * first image screened, not thread-safe so inference is serialized off the main thread, released after ten
 * idle minutes and reloaded on the next image. A photo after a quiet stretch pays the map + build + first
 * inference again, once per idle cycle where it used to be once per process.
 */
class NsfwImageModerator(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val unsafeClasses: Set<Int> = DEFAULT_UNSAFE_CLASSES,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val guard: ModelLoadGuard? = null,
    scope: CoroutineScope,
    idleMs: Long = ModelLease.DEFAULT_IDLE_MS,
) : ImageModerator {
    /** [model] is the mapping the interpreter reads in place; it has to live exactly as long as [interpreter]. */
    private class Loaded(
        val model: MappedByteBuffer,
        val interpreter: Interpreter,
    )

    private val lease =
        ModelLease(
            scope = scope,
            idleMs = idleMs,
            load = ::buildInterpreter,
            close = { it.interpreter.close() },
        )

    /** Whether the engine is in memory right now (Diagnostics / the debug bridge). */
    val isResident: Boolean get() = lease.isResident

    /** The last time [classify] touched the engine, on the wall clock; `0` before the first. */
    val lastUsedAt: Long get() = lease.lastUsedAt

    override suspend fun classify(bitmap: Bitmap): ImageVerdict =
        withContext(Dispatchers.Default) {
            lease.use { loaded ->
                loaded?.let { runCatching { infer(it.interpreter, bitmap) }.getOrDefault(ImageVerdict.ALLOWED) }
                    ?: ImageVerdict.ALLOWED
            }
        }

    /** Release the engine now; the next [classify] reloads it. The debug bridge's shortcut through the idle cycle. */
    suspend fun unload() = lease.unload()

    /**
     * Every touch of the model — the first, and each reload after an idle release — runs under
     * [ModelLoadGuard] (ADR 037). "Not on the launch path" undersells the risk here: [classify] is reached
     * from the **inbound** blob pipeline with no user action at all, so a native fault would mean "Knit
     * dies whenever someone sends you a photo" — a loop in all but name, and one the user cannot escape by
     * avoiding a button. A latched model yields `null`, and the lease keeps that empty result for the rest
     * of the process rather than asking the journal again.
     *
     * There is deliberately **no** `warmUp()` twin of `MlTextModerator`'s: moving a 17 MB load onto the
     * launch path is precisely what `5da5601` moved off it.
     */
    private suspend fun buildInterpreter(): Loaded? =
        if (guard == null) loadAndProbe() else guard.guard(ModelLoadGuard.NSFW, ::loadAndProbe)

    /**
     * runCatching, not just [loadInterpreter]'s own catch: it also absorbs Errors (TFLite's JNI load can
     * throw UnsatisfiedLinkError; the arena can OOM). [classify] sits on the inbound blob path and must
     * never throw — and the guard counts process deaths, not exceptions.
     *
     * The 1×1 probe is not decoration: tensor allocation and kernel selection happen on the first `run`,
     * not in the [Interpreter] constructor, so the guarded region has to include one inference. [infer]
     * scales whatever it is given to the model's own input size, so one pixel is enough.
     */
    private fun loadAndProbe(): Loaded? =
        runCatching {
            loadInterpreter()?.also {
                runCatching { infer(it.interpreter, createBitmap(1, 1)) }
            }
        }.getOrNull()

    private fun loadInterpreter(): Loaded? =
        try {
            val model = TfLiteModels.mapAsset(context, modelAsset)
            Loaded(model, Interpreter(model, TfLiteModels.interpreterOptions()))
        } catch (_: Exception) {
            // No model bundled or a compressed one (FileNotFoundException), or a malformed/incompatible
            // flatbuffer (Interpreter throws IllegalArgument / IllegalState) -> degrade to allow-all.
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
        val scaled = bitmap.scale(width, height)

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
