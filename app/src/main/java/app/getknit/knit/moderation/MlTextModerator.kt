package app.getknit.knit.moderation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * On-device toxicity classifier backed by a bundled TFLite model — runs fully offline (no network).
 *
 * Layered into [HybridTextModerator] as the ML pass behind the deterministic [LexicalTextFilter]; it
 * only sees text the word list let through. Mirrors [NsfwImageModerator]: a bare TFLite [Interpreter]
 * (no MediaPipe/LiteRT) mapped straight out of the APK, held on a [ModelLease] — loaded on first use,
 * inference serialized off the main thread, released after ten idle minutes and reloaded on the next use —
 * and **graceful degradation**: if any asset is missing or fails to load, [classify] returns
 * [TextVerdict.ALLOWED], so the lexical pass still runs and the app never hard-fails on a bad asset.
 *
 * The cost of the lease is the reload: a message after a quiet stretch pays the map + build + first
 * inference again, once, and on the inbound path that is inline in the router's collector
 * (`MeshManager.isTextFlagged`). It used to be paid once per process; now it is once per idle cycle. The
 * warm-ups (`KnitApp` on resume, `MeshService` when a peer appears) hide it where they can.
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
    private val guard: ModelLoadGuard? = null,
    scope: CoroutineScope,
    idleMs: Long = ModelLease.DEFAULT_IDLE_MS,
) : TextModerator {
    private class BlockRule(
        val index: Int,
        val label: String,
        val threshold: Float,
    )

    /** [model] is the mapping the interpreter reads in place; it has to live exactly as long as [interpreter]. */
    private class Engine(
        val tokenizer: SentencePieceTokenizer,
        val model: MappedByteBuffer,
        val interpreter: Interpreter,
        val rules: List<BlockRule>,
    )

    private val lease =
        ModelLease(
            scope = scope,
            idleMs = idleMs,
            load = ::buildEngine,
            close = { it.interpreter.close() },
        )

    /** Whether the engine is in memory right now (Diagnostics / the debug bridge). */
    val isResident: Boolean get() = lease.isResident

    /** The last time [classify] or [warmUp] touched the engine, on the wall clock; `0` before the first. */
    val lastUsedAt: Long get() = lease.lastUsedAt

    override suspend fun classify(text: String): TextVerdict =
        withContext(Dispatchers.Default) {
            lease.use { e ->
                e?.let { runCatching { infer(it, text) }.getOrDefault(TextVerdict.ALLOWED) } ?: TextVerdict.ALLOWED
            }
        }

    /** Release the engine now; the next [classify] reloads it. The debug bridge's shortcut through the idle cycle. */
    suspend fun unload() = lease.unload()

    /**
     * Every touch of the model — the first, and each reload after an idle release — runs under
     * [ModelLoadGuard] so a **native** crash in there cannot become an unrecoverable launch loop (ADR 037).
     * A latched model yields `null` and [classify] degrades to [TextVerdict.ALLOWED], exactly as it already
     * does when the assets are missing; nothing else in this class knows the difference, and the lease
     * keeps that empty result for the rest of the process rather than asking the journal again.
     *
     * Serialised by the lease: two racing callers must not both mark an attempt, and the load must still
     * happen at most once per residency.
     *
     * The probe inference belongs **inside** the guarded region. `Interpreter(model)` is mostly a
     * flatbuffer parse; tensor allocation and kernel selection land on the first `run`, so closing the
     * journal entry after the load alone would call success before the risky half had run.
     */
    private suspend fun buildEngine(): Engine? = if (guard == null) loadAndProbe() else guard.guard(ModelLoadGuard.TOXICITY, ::loadAndProbe)

    /**
     * runCatching, not just [loadEngine]'s own catch: it also absorbs Errors (TFLite's JNI load can throw
     * UnsatisfiedLinkError; the arena can OOM). [classify] sits on the no-throw inbound path
     * (`MeshManager.onDeliver`) and must never throw — and the guard counts process deaths, not
     * exceptions, so a Java-level failure has to be swallowed here rather than reach it.
     */
    private fun loadAndProbe(): Engine? =
        runCatching {
            loadEngine()?.also { runCatching { infer(it, WARMUP_PROBE) } }
        }.getOrNull()

    /**
     * Load the model *off* the send path — from `KnitApp` on resume and `MeshService` when a peer appears —
     * so the first real [classify] (the first outgoing send, or an inbound flagged-check) hits a warm engine
     * instead of paying the map + [Interpreter] build + first-inference tensor/graph allocation on the send
     * coroutine. The lease dedupes it against a real send that races it (no double-load), the probe
     * inference is the one [loadAndProbe] already runs, and after an idle release it warms again. Never
     * throws: the load already degrades to allow-all on any failure.
     */
    suspend fun warmUp() {
        // Cheap while the engine is in, or once a load has failed: every foreground resume and every peer
        // arrival calls this, and none should queue behind the lease a real classify may be holding. The
        // flag flips under the lease's mutex, so the racy read can only send a caller through the lock path.
        if (lease.isLoaded) return
        withContext(Dispatchers.Default) { lease.use { } }
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
            val model = TfLiteModels.mapAsset(context, modelAsset)
            Engine(tokenizer, model, Interpreter(model, TfLiteModels.interpreterOptions()), rules)
        } catch (_: Exception) {
            // Missing or compressed asset (FileNotFoundException), corrupt flatbuffer (Interpreter throws
            // IllegalArgument / IllegalState), bad tokenizer JSON (SerializationException) -> allow-all.
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

        // A short non-blank probe for loadAndProbe(): non-blank so it forces a real infer() (which is where
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
