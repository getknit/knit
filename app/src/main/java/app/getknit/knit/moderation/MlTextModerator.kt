package app.getknit.knit.moderation

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
 * **graceful degradation** — if any asset is missing or fails to load, [classify] returns
 * [TextVerdict.ALLOWED], so the lexical pass still runs and the app never hard-fails on a bad asset.
 *
 * **Model:** Detoxify "unbiased-small" (ALBERT) exported to TFLite — inputs `input_ids` and
 * `attention_mask` (`[1, maxLen]` int), output `[1, N]` sigmoid probabilities over the labels in
 * `labels.txt` (7 Jigsaw toxicity labels + 9 identity-mention columns). Tokenization is the pure-Kotlin
 * [SentencePieceTokenizer] over the bundled `tokenizer.json` (no native libs → 16 KB-page safe).
 *
 * **Selective blocking:** only the categories in [blockThresholds] are enforced (each against its own
 * threshold). The default set blocks `severe_toxicity`, `identity_attack`, and `sexual_explicit` — i.e.
 * serious abuse — and deliberately ignores `toxicity`/`insult`/`obscene` (general rudeness) and the
 * identity-mention columns (which detect topic, not toxicity). Tune thresholds on-device.
 */
class MlTextModerator(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val tokenizerAsset: String = DEFAULT_TOKENIZER_ASSET,
    private val labelsAsset: String = DEFAULT_LABELS_ASSET,
    private val blockThresholds: Map<String, Float> = DEFAULT_BLOCK_THRESHOLDS,
    private val maxLen: Int = DEFAULT_MAX_LEN,
) : TextModerator {

    private class BlockRule(val index: Int, val threshold: Float)
    private class Engine(
        val tokenizer: SentencePieceTokenizer,
        val interpreter: Interpreter,
        val rules: List<BlockRule>,
    )

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
            val tokenizer = context.assets.open(tokenizerAsset).use {
                SentencePieceTokenizer.fromJson(it.readBytes().decodeToString())
            }
            val labels = context.assets.open(labelsAsset).use {
                it.readBytes().decodeToString().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            }
            val rules = blockThresholds.mapNotNull { (label, threshold) ->
                labels.indexOf(label).takeIf { it >= 0 }?.let { BlockRule(it, threshold) }
            }
            val bytes = context.assets.open(modelAsset).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
            Engine(tokenizer, Interpreter(model), rules)
        } catch (_: IOException) {
            null // an asset is missing -> degrade to allow-all
        } catch (_: IllegalStateException) {
            null // malformed/incompatible model -> degrade to allow-all
        }

    private fun infer(engine: Engine, text: String): TextVerdict {
        val encoding = engine.tokenizer.encode(text, maxLen)
        val tflite = engine.interpreter

        val inputs = arrayOfNulls<Any>(tflite.inputTensorCount)
        for (i in 0 until tflite.inputTensorCount) {
            val tensor = tflite.getInputTensor(i)
            // Export order is (input_ids, attention_mask); names are generic, so fall back to index.
            val isMask = tensor.name().contains("mask", ignoreCase = true) ||
                (i == ATTENTION_MASK_INPUT && tflite.inputTensorCount == INPUT_COUNT &&
                    !tflite.getInputTensor(INPUT_IDS_INPUT).name().contains("mask", ignoreCase = true))
            val source = if (isMask) encoding.attentionMask else encoding.inputIds
            inputs[i] = source.toBuffer(tensor.dataType())
        }

        val classCount = tflite.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(classCount) }
        tflite.runForMultipleInputsOutputs(inputs, mapOf(0 to output))

        val scores = output[0]
        var worst = 0f
        for (rule in engine.rules) {
            val score = scores.getOrElse(rule.index) { 0f }
            if (score >= rule.threshold && score > worst) worst = score
        }
        return if (worst > 0f) {
            TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY, score = worst)
        } else {
            TextVerdict.ALLOWED
        }
    }

    /** Pack a fixed-length array into a direct buffer of the tensor's dtype (model emits int64). */
    private fun IntArray.toBuffer(dtype: DataType): ByteBuffer {
        val bytesPerElement = if (dtype == DataType.INT64) LONG_BYTES else INT_BYTES
        val buffer = ByteBuffer.allocateDirect(size * bytesPerElement).order(ByteOrder.nativeOrder())
        for (value in this) {
            if (dtype == DataType.INT64) buffer.putLong(value.toLong()) else buffer.putInt(value)
        }
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val DEFAULT_MODEL_ASSET = "moderation/toxicity.tflite"
        const val DEFAULT_TOKENIZER_ASSET = "moderation/tokenizer.json"
        const val DEFAULT_LABELS_ASSET = "moderation/labels.txt"
        const val DEFAULT_MAX_LEN = 128

        // Block serious abuse only; ignore general rudeness (toxicity/insult/obscene) and the
        // identity-mention columns. Starting thresholds — tune on-device.
        val DEFAULT_BLOCK_THRESHOLDS = mapOf(
            "severe_toxicity" to 0.7f,
            "identity_attack" to 0.7f,
            "sexual_explicit" to 0.6f,
        )

        const val INPUT_IDS_INPUT = 0
        const val ATTENTION_MASK_INPUT = 1
        const val INPUT_COUNT = 2
        const val LONG_BYTES = 8
        const val INT_BYTES = 4
    }
}
