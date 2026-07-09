package app.getknit.knit.moderation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
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
 * threshold). The default set blocks `severe_toxicity`, `identity_attack`, `sexual_explicit`, and
 * `threat` — i.e. serious abuse — and deliberately ignores `toxicity`/`insult`/`obscene` (general
 * rudeness) and the identity-mention columns (which detect topic, not toxicity). Tune thresholds on-device.
 */
class MlTextModerator(
    private val context: Context,
    private val modelAsset: String = DEFAULT_MODEL_ASSET,
    private val tokenizerAsset: String = DEFAULT_TOKENIZER_ASSET,
    private val labelsAsset: String = DEFAULT_LABELS_ASSET,
    private val blockThresholds: Map<String, Float> = DEFAULT_BLOCK_THRESHOLDS,
    private val maxLen: Int = DEFAULT_MAX_LEN,
) : TextModerator {
    private class BlockRule(
        val index: Int,
        val label: String,
        val threshold: Float,
    )

    private class Engine(
        val tokenizer: SentencePieceTokenizer,
        val interpreter: Interpreter,
        val rules: List<BlockRule>,
    )

    private val mutex = Mutex()
    private var loaded = false
    private var engine: Engine? = null

    override suspend fun classify(text: String): TextVerdict =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!loaded) {
                    loaded = true
                    // runCatching, not just loadEngine's own catch: it also absorbs Errors (TFLite's JNI
                    // load can throw UnsatisfiedLinkError; the ~30 MB direct buffer can OOM). classify
                    // sits on the no-throw inbound path (MeshManager.onDeliver) and must never throw.
                    engine = runCatching { loadEngine() }.getOrNull()
                }
                val e = engine ?: return@withContext TextVerdict.ALLOWED
                runCatching { infer(e, text) }.getOrDefault(TextVerdict.ALLOWED)
            }
        }

    /**
     * Load the model and run one throwaway inference *off* the send path — call this at startup so the
     * first real [classify] (the first outgoing send, or an inbound flagged-check) hits a warm engine
     * instead of paying the ~16 MB asset read + [Interpreter] build + first-inference tensor/graph
     * allocation on the send coroutine. Reuses [classify], so [mutex]/[loaded] dedupe it against a
     * first real send that races it (no double-load), and the verdict is discarded. Never throws:
     * [classify] already degrades to [TextVerdict.ALLOWED] on any load/inference failure.
     */
    suspend fun warmUp() {
        classify(WARMUP_PROBE)
    }

    private fun loadEngine(): Engine? =
        try {
            val tokenizer =
                context.assets.open(tokenizerAsset).use {
                    SentencePieceTokenizer.fromJson(it.readBytes().decodeToString())
                }
            val labels =
                context.assets.open(labelsAsset).use {
                    it
                        .readBytes()
                        .decodeToString()
                        .lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toList()
                }
            val rules =
                blockThresholds.mapNotNull { (label, threshold) ->
                    labels.indexOf(label).takeIf { it >= 0 }?.let { BlockRule(it, label, threshold) }
                }
            val bytes = context.assets.open(modelAsset).use { it.readBytes() }
            val model =
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            Engine(tokenizer, Interpreter(model), rules)
        } catch (_: Exception) {
            // Missing asset (IOException), corrupt flatbuffer (Interpreter throws IllegalArgument /
            // IllegalState), bad tokenizer JSON (SerializationException) -> degrade to allow-all.
            null
        }

    private fun infer(
        engine: Engine,
        text: String,
    ): TextVerdict {
        val encoding = engine.tokenizer.encode(text, maxLen)
        val tflite = engine.interpreter

        val inputs = arrayOfNulls<Any>(tflite.inputTensorCount)
        for (i in 0 until tflite.inputTensorCount) {
            val tensor = tflite.getInputTensor(i)
            // Export order is (input_ids, attention_mask); names are generic, so fall back to index.
            val isMask =
                tensor.name().contains("mask", ignoreCase = true) ||
                    (
                        i == ATTENTION_MASK_INPUT && tflite.inputTensorCount == INPUT_COUNT &&
                            !tflite.getInputTensor(INPUT_IDS_INPUT).name().contains("mask", ignoreCase = true)
                    )
            val source = if (isMask) encoding.attentionMask else encoding.inputIds
            inputs[i] = source.toBuffer(tensor.dataType())
        }

        val classCount = tflite.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(classCount) }
        tflite.runForMultipleInputsOutputs(inputs, mapOf(0 to output))

        val scores = output[0]
        var blockedRule: BlockRule? = null
        var blockedScore = 0f
        var topLabel: String? = null
        var topScore = 0f
        for (rule in engine.rules) {
            val score = scores.getOrElse(rule.index) { 0f }
            if (score > topScore) {
                topScore = score
                topLabel = rule.label
            }
            if (score >= rule.threshold && score > blockedScore) {
                blockedScore = score
                blockedRule = rule
            }
        }
        return if (blockedRule != null) {
            TextVerdict(
                allowed = false,
                category = TextVerdict.Category.TOXICITY,
                score = blockedScore,
                label = blockedRule.label,
            )
        } else {
            // Allowed: report the highest enforced-category score (and its label) even though it stayed
            // below threshold, so debug logs show how close the text came rather than a flat 0.
            TextVerdict(allowed = true, score = topScore, label = topLabel)
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

        // A short non-blank probe for warmUp(): non-blank so it forces a real infer() (which is where
        // first-inference graph/tensor allocation is paid), not just the model load.
        const val WARMUP_PROBE = "knit"

        // Block serious abuse only; ignore general rudeness (toxicity/insult/obscene) and the
        // identity-mention columns. Starting thresholds — tune on-device.
        val DEFAULT_BLOCK_THRESHOLDS =
            mapOf(
                "severe_toxicity" to 0.85f,
                "identity_attack" to 0.85f,
                "sexual_explicit" to 0.8f,
                "threat" to 0.9f,
            )

        const val INPUT_IDS_INPUT = 0
        const val ATTENTION_MASK_INPUT = 1
        const val INPUT_COUNT = 2
        const val LONG_BYTES = 8
        const val INT_BYTES = 4
    }
}
