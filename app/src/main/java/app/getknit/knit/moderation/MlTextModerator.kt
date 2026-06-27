package app.getknit.knit.moderation

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device toxicity classifier backed by a bundled TFLite model — runs fully offline (no network).
 *
 * Layered into [HybridTextModerator] as the ML pass behind the deterministic [LexicalTextFilter]; it
 * only sees text the word list let through. Mirrors [NsfwImageModerator]: a bare TFLite [Interpreter]
 * (no MediaPipe/LiteRT), lazy load, inference serialized behind [mutex] off the main thread, and
 * **graceful degradation** — if either asset is missing or fails to load, [classify] returns
 * [TextVerdict.ALLOWED], so the lexical pass still runs and the app never hard-fails on a bad asset.
 *
 * **Model:** Detoxify "original-small" (ALBERT) exported to TFLite — inputs `input_ids` and
 * `attention_mask` (`[1, maxLen]` int), output `[1, 6]` sigmoid probabilities for the Jigsaw labels
 * `toxic, severe_toxic, obscene, threat, insult, identity_hate`. The max label probability is compared
 * to [threshold]. Tokenization uses the HuggingFace tokenizer (`tokenizer.json`) — the same library
 * used in training, so on-device token ids match exactly. See `assets/moderation/README.md`.
 */
class MlTextModerator(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val tokenizerAsset: String = DEFAULT_TOKENIZER_ASSET,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val maxLen: Int = DEFAULT_MAX_LEN,
) : TextModerator {

    private class Engine(val tokenizer: HuggingFaceTokenizer, val interpreter: Interpreter)

    private val mutex = Mutex()
    private var loaded = false
    private var engine: Engine? = null

    override suspend fun classify(text: String): TextVerdict = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!loaded) {
                loaded = true
                engine = loadEngine()
            }
            val e = engine ?: return@withContext TextVerdict.ALLOWED
            runCatching { infer(e, text) }.getOrDefault(TextVerdict.ALLOWED)
        }
    }

    private fun loadEngine(): Engine? =
        try {
            val tokenizer = context.assets.open(tokenizerAsset).use { input ->
                HuggingFaceTokenizer.newInstance(input, mapOf("addSpecialTokens" to "true"))
            }
            val bytes = context.assets.open(modelAsset).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
            Engine(tokenizer, Interpreter(model))
        } catch (_: IOException) {
            null // an asset is missing -> degrade to allow-all
        } catch (_: IllegalStateException) {
            null // malformed/incompatible model -> degrade to allow-all
        }

    private fun infer(engine: Engine, text: String): TextVerdict {
        val encoding = engine.tokenizer.encode(text)
        // The tokenizer returns variable-length ids ([CLS]…[SEP]); pad/truncate to maxLen.
        val rawIds = encoding.ids
        val ids = LongArray(maxLen) // 0 == [PAD]
        val mask = LongArray(maxLen)
        val count = minOf(rawIds.size, maxLen)
        for (i in 0 until count) {
            ids[i] = rawIds[i]
            mask[i] = encoding.attentionMask[i]
        }
        if (rawIds.size > maxLen) ids[maxLen - 1] = SEP_ID // preserve trailing [SEP] on truncation

        val tflite = engine.interpreter
        val inputs = arrayOfNulls<Any>(tflite.inputTensorCount)
        for (i in 0 until tflite.inputTensorCount) {
            val tensor = tflite.getInputTensor(i)
            // Export order is (input_ids, attention_mask); names are generic, so fall back to index.
            val isMask = tensor.name().contains("mask", ignoreCase = true) ||
                (i == ATTENTION_MASK_INPUT && tflite.inputTensorCount == INPUT_COUNT &&
                    !tflite.getInputTensor(INPUT_IDS_INPUT).name().contains("mask", ignoreCase = true))
            inputs[i] = (if (isMask) mask else ids).toBuffer(tensor.dataType())
        }

        val classCount = tflite.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(classCount) }
        tflite.runForMultipleInputsOutputs(inputs, mapOf(0 to output))

        val scores = output[0]
        val score = scores.maxOrNull() ?: 0f
        return if (score >= threshold) {
            TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY, score = score)
        } else {
            TextVerdict.ALLOWED
        }
    }

    /** Pack a fixed-length array into a direct buffer of the tensor's dtype (model emits int64). */
    private fun LongArray.toBuffer(dtype: DataType): ByteBuffer {
        val bytesPerElement = if (dtype == DataType.INT64) LONG_BYTES else INT_BYTES
        val buffer = ByteBuffer.allocateDirect(size * bytesPerElement).order(ByteOrder.nativeOrder())
        for (value in this) {
            if (dtype == DataType.INT64) buffer.putLong(value) else buffer.putInt(value.toInt())
        }
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val DEFAULT_MODEL_ASSET = "moderation/toxicity.tflite"
        const val DEFAULT_TOKENIZER_ASSET = "moderation/tokenizer.json"
        const val DEFAULT_THRESHOLD = 0.7f
        const val DEFAULT_MAX_LEN = 128

        const val INPUT_IDS_INPUT = 0
        const val ATTENTION_MASK_INPUT = 1
        const val INPUT_COUNT = 2
        const val SEP_ID = 3L
        const val LONG_BYTES = 8
        const val INT_BYTES = 4
    }
}
