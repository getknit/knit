package app.getknit.knit.moderation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/** Fixed-length tokenization output. */
class Encoding(
    val inputIds: IntArray,
    val attentionMask: IntArray,
)

/**
 * Pure-Kotlin ALBERT/SentencePiece (Unigram) tokenizer driven by a HuggingFace
 * `tokenizer.json`. No native libraries — so it's 16 KB-page-size safe and adds
 * nothing to the APK's `.so` set, unlike the prebuilt HF/DJL tokenizers.
 *
 * Reproduces exactly what the HF tokenizer does for this model (verified against
 * the HF tokenizer over a 4k-string corpus — see detoxify-mobile/src/verify_tokenizer.py):
 *   1. normalize: ``` `` ```/`''` → `"`, NFKD, strip combining marks, lowercase, collapse spaces
 *   2. pre-tokenize: split on whitespace, prepend the metaspace `▁` to each word
 *   3. Unigram Viterbi: max-score segmentation per word; out-of-vocab spans fuse to a single `<unk>`
 *
 * Construction parses the unigram `model.vocab` (≈30k entries) once; build it lazily
 * off the main thread.
 */
class SentencePieceTokenizer private constructor(
    private val pieceId: Map<String, Int>,
    private val pieceScore: Map<String, Float>,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
    private val maxPieceLen: Int,
    private val unkScore: Float,
) {
    fun encode(
        text: String,
        maxLen: Int,
    ): Encoding {
        val body = ArrayList<Int>(maxLen)
        for (word in pretokenize(normalize(text))) {
            viterbi(word, body)
        }
        val cap = maxLen - 2 // room for [CLS] … [SEP]
        val bodyLen = minOf(body.size, cap)

        val ids = IntArray(maxLen) // 0 == [PAD]
        val mask = IntArray(maxLen)
        ids[0] = clsId
        mask[0] = 1
        for (i in 0 until bodyLen) {
            ids[i + 1] = body[i]
            mask[i + 1] = 1
        }
        ids[bodyLen + 1] = sepId
        mask[bodyLen + 1] = 1
        return Encoding(ids, mask)
    }

    /** Mirrors the tokenizer.json normalizer Sequence. */
    private fun normalize(text: String): String {
        val replaced = text.replace("``", "\"").replace("''", "\"")
        val decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFKD)
        val stripped = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) stripped.append(ch)
        }
        // lowercase() is locale-independent (Locale.ROOT) — matches HF's Lowercase.
        return MULTISPACE.replace(stripped.toString().lowercase(), " ")
    }

    /** WhitespaceSplit + Metaspace(prepend ▁). */
    private fun pretokenize(text: String): List<String> =
        WHITESPACE
            .split(text)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { METASPACE + it }
            .toList()

    /** Unigram Viterbi over [word]; appends the resulting token ids to [out]. */
    private fun viterbi(
        word: String,
        out: MutableList<Int>,
    ) {
        val n = word.length
        val best = FloatArray(n + 1) { if (it == 0) 0f else Float.NEGATIVE_INFINITY }
        val backPos = IntArray(n + 1) { -1 }
        val backUnk = BooleanArray(n + 1)
        for (i in 1..n) relax(word, i, best, backPos, backUnk)
        emit(word, n, backPos, backUnk, out)
    }

    /** DP step: best segmentation score reaching position [i] (known pieces + single-char <unk>). */
    private fun relax(
        word: String,
        i: Int,
        best: FloatArray,
        backPos: IntArray,
        backUnk: BooleanArray,
    ) {
        for (j in maxOf(0, i - maxPieceLen) until i) {
            if (best[j] != Float.NEGATIVE_INFINITY) {
                val score = pieceScore[word.substring(j, i)]
                if (score != null && best[j] + score > best[i]) {
                    best[i] = best[j] + score
                    backPos[i] = j
                    backUnk[i] = false
                }
            }
        }
        val j = i - 1 // single-character <unk> fallback
        if (best[j] != Float.NEGATIVE_INFINITY && best[j] + unkScore > best[i]) {
            best[i] = best[j] + unkScore
            backPos[i] = j
            backUnk[i] = true
        }
    }

    /** Backtrack the lattice and append ids in order, fusing consecutive <unk> into one. */
    private fun emit(
        word: String,
        n: Int,
        backPos: IntArray,
        backUnk: BooleanArray,
        out: MutableList<Int>,
    ) {
        val starts = ArrayList<Int>()
        val unks = ArrayList<Boolean>()
        var i = n
        while (i > 0) {
            starts.add(backPos[i])
            unks.add(backUnk[i])
            i = backPos[i]
        }
        var prevUnk = false
        for (k in starts.indices.reversed()) {
            if (unks[k]) {
                if (!prevUnk) out.add(unkId)
                prevUnk = true
            } else {
                val end = if (k == 0) n else starts[k - 1]
                out.add(pieceId.getValue(word.substring(starts[k], end)))
                prevUnk = false
            }
        }
    }

    companion object {
        private const val METASPACE = "▁" // ▁
        private val WHITESPACE = Regex("\\s+")
        private val MULTISPACE = Regex(" {2,}")
        private const val UNK_SCORE_PENALTY = 10f

        /** Build from the contents of a HuggingFace `tokenizer.json`. */
        fun fromJson(tokenizerJson: String): SentencePieceTokenizer {
            val model =
                Json
                    .parseToJsonElement(tokenizerJson)
                    .jsonObject
                    .getValue("model")
                    .jsonObject
            val unkId = model.getValue("unk_id").jsonPrimitive.int
            val vocab = model.getValue("vocab").jsonArray

            val pieceId = HashMap<String, Int>(vocab.size * 2)
            val pieceScore = HashMap<String, Float>(vocab.size * 2)
            var maxPieceLen = 1
            var minScore = Float.POSITIVE_INFINITY
            vocab.forEachIndexed { id, entry ->
                val pair = entry.jsonArray
                val piece = pair[0].jsonPrimitive.content
                val score = pair[1].jsonPrimitive.float
                pieceId[piece] = id
                pieceScore[piece] = score
                if (piece.length > maxPieceLen) maxPieceLen = piece.length
                if (score < minScore) minScore = score
            }
            return SentencePieceTokenizer(
                pieceId = pieceId,
                pieceScore = pieceScore,
                unkId = unkId,
                clsId = pieceId.getValue("[CLS]"),
                sepId = pieceId.getValue("[SEP]"),
                maxPieceLen = maxPieceLen,
                unkScore = minScore - UNK_SCORE_PENALTY,
            )
        }
    }
}
