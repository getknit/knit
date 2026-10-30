package app.getknit.knit.translate

import app.getknit.knit.call.SttFile
import app.getknit.knit.mesh.protocol.LanguageRegistry

/**
 * A language as its code in Mozilla's model registry (ADR 2026-10.t2h6): `en`, `es`, and in time `zh_hant`. Every model
 * has English on one side, so [EN] is the hub. A value is only as known as the table: [ofCode] answers the languages a
 * model translates from or into, and a stored code the table no longer names still carries its name.
 */
@JvmInline
value class TranslateLang(
    val code: String,
) {
    override fun toString(): String = code

    companion object {
        val EN = TranslateLang("en")

        /**
         * The table's language for [code] — a registry code, a BCP-47 tag (`EN-gb`, `es-419`) or CLD2's answer — or
         * null when no model translates from or into it. Spelled as [LanguageRegistry.codeOf] reads it: case and region
         * dropped, a script kept where it is its own model (`zh-Hant` is `zh_hant`).
         */
        fun ofCode(code: String): TranslateLang? =
            LanguageRegistry.codeOf(code)?.let { c -> TranslateDirection.languages.firstOrNull { it.code == c } }
    }
}

/**
 * One direction (ADR 2026-10.t2h6): a Mozilla Firefox Translations model (MPL-2.0) — int8 Marian weights, the lexical
 * shortlist that bounds each decoding step, and the SentencePiece vocabulary — fetched file by file under [baseUrl] and
 * pinned by size and hash, so the host is trusted with nothing. The pins live in [TranslateDirections], which
 * `scripts/translate-model.py --write` generates from Mozilla's registry; never edit them by hand.
 *
 * Every model has six encoder layers and [decoderLayers] decoder layers, set by its architecture ("base-memory" has
 * four), which slimt is told at load since it cannot read them from the file. Told the wrong count, it loads and writes
 * nonsense.
 */
class TranslateDirection(
    val from: TranslateLang,
    val to: TranslateLang,
    val id: String,
    val baseUrl: String,
    val files: List<SttFile>,
    val decoderLayers: Int,
) {
    private val pair = from.code + to.code

    /** The three files the engine loads, by name. */
    val model: String = "model.$pair.intgemm.alphas.bin"
    val shortlist: String = "lex.50.50.$pair.s2t.bin"
    val vocabulary: String = "vocab.$pair.spm"

    init {
        require(from != to) { "a direction goes from one language to another" }
        require(baseUrl.startsWith("https://") && baseUrl.endsWith("/")) { "a model is fetched over https, from a directory" }
        require(files.all { it.name.matches(SAFE_NAME) }) { "a model's files are plain names" }
        require(files.map { it.name }.containsAll(listOf(model, shortlist, vocabulary))) { "a direction pins its three files" }
    }

    /** Every file's bytes together: what a download costs. */
    val bytes: Long get() = files.sumOf { it.bytes }

    fun urlOf(file: SttFile): String = baseUrl + file.name

    override fun toString(): String = "${from.code}->${to.code}"

    companion object {
        /** Every model's encoder depth. */
        const val ENCODER_LAYERS = 6

        /** Every direction a phone can download, from the generated table. */
        val ALL: List<TranslateDirection> get() = TranslateDirections.ALL

        /** Every language the table names. */
        val languages: Set<TranslateLang> by lazy { ALL.flatMap { listOf(it.from, it.to) }.toSet() }

        /** Every language a model translates from: the only answers a message's language read may give. */
        val sources: Set<TranslateLang> by lazy { ALL.map { it.from }.toSet() }

        /** Every language a model translates into: what a reader's language may be. */
        val targets: Set<TranslateLang> by lazy { ALL.map { it.to }.toSet() }

        fun of(
            from: TranslateLang,
            to: TranslateLang,
        ): TranslateDirection? = ALL.firstOrNull { it.from == from && it.to == to }
    }
}

private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
