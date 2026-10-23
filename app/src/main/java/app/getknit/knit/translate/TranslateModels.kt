package app.getknit.knit.translate

import app.getknit.knit.call.SttFile

/** A language of the translation spike's one pair (ADR 2026-10.t2h6). Mozilla trains every model with English on one side. */
enum class TranslateLang(
    val code: String,
) {
    EN("en"),
    ES("es"),
    ;

    /** The pair's other language: what the spike translates into, whatever the app is shown in. */
    val other: TranslateLang get() = if (this == EN) ES else EN

    companion object {
        fun ofCode(code: String): TranslateLang? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One direction of the spike (ADR 2026-10.t2h6): a Mozilla Firefox Translations model (MPL-2.0) — int8 Marian weights,
 * the lexical shortlist that bounds each decoding step, and the SentencePiece vocabulary — fetched file by file under
 * [baseUrl] and pinned by size and hash here, so the host is trusted with nothing. `scripts/translate-model.py --check`
 * re-derives the pins from Mozilla's registry; never edit them by hand.
 *
 * Both directions are the registry's "base-memory" release: six encoder layers and [decoderLayers] (four) decoder
 * layers, which slimt is told at load since it cannot read them from the file. Told two, it loads and writes nonsense.
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
        /** Every base-memory model's encoder depth. */
        const val ENCODER_LAYERS = 6

        private const val BASE_MEMORY_DECODER_LAYERS = 4
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        val EN_ES =
            TranslateDirection(
                from = TranslateLang.EN,
                to = TranslateLang.ES,
                id = "mozilla-en-es-3b1c3995",
                baseUrl = "https://getknit.app/models/translate/mozilla-en-es-3b1c3995/",
                files =
                    listOf(
                        SttFile(
                            "model.enes.intgemm.alphas.bin",
                            31_561_787,
                            "3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c",
                        ),
                        SttFile("lex.50.50.enes.s2t.bin", 4_198_436, "7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c"),
                        SttFile("vocab.enes.spm", 816_054, "5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad"),
                        SttFile("LICENSE", 16_725, "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"),
                    ),
                decoderLayers = BASE_MEMORY_DECODER_LAYERS,
            )

        val ES_EN =
            TranslateDirection(
                from = TranslateLang.ES,
                to = TranslateLang.EN,
                id = "mozilla-es-en-4aed7734",
                baseUrl = "https://getknit.app/models/translate/mozilla-es-en-4aed7734/",
                files =
                    listOf(
                        SttFile(
                            "model.esen.intgemm.alphas.bin",
                            31_561_787,
                            "4aed7734152ae0045d1a69ae49c86cfda18f53c61f90e95e1d1de1c7c7c3b033",
                        ),
                        SttFile("lex.50.50.esen.s2t.bin", 4_636_248, "e2610211d3b9577d012638fe7e7e74ed7b4b708ce96b9e792e67c282a6492daa"),
                        SttFile("vocab.esen.spm", 816_054, "5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad"),
                        SttFile("LICENSE", 16_725, "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"),
                    ),
                decoderLayers = BASE_MEMORY_DECODER_LAYERS,
            )

        val ALL: List<TranslateDirection> = listOf(EN_ES, ES_EN)

        /** Every language a model translates from: the only answers a message's language read may give. */
        val sources: Set<TranslateLang> = ALL.map { it.from }.toSet()

        fun of(
            from: TranslateLang,
            to: TranslateLang,
        ): TranslateDirection? = ALL.firstOrNull { it.from == from && it.to == to }
    }
}
