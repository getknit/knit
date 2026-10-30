package app.getknit.knit.mesh.protocol

/**
 * The languages a frame may declare, by index (ADR 2026-10.t2h6's 2026-10-29 amendment): a chat frame's `lang` (the
 * body's language) and `reads` (the language its author reads) each carry one of these as a small CBOR uint, so a
 * declaration costs a byte where a code would cost three. Every language of Mozilla's translation registry is here
 * whether or not a model for it ships yet, so a newer phone's declaration means the same thing to an older one.
 *
 * Wire law, shared with Knit for iOS and pinned in `vectors/wire-v1.json`: append only. An index never moves and never
 * changes its language; a retired one is burned (WIRE_COMPAT rule 3). The likeliest languages come first so they stay
 * under 24, which CBOR writes in the type byte itself. An index a phone does not know decodes to nothing. Index 0,
 * English, is never sent as a body's `lang` (its absence says English) and only ever as `reads`. The value is a CBOR
 * uint forever: a text or an out-of-range value fails the whole content's decode, losing only that sender's message,
 * and a negative integer decodes and names no language.
 */
object LanguageRegistry {
    /** Model registry codes in index order. */
    val CODES: List<String> =
        listOf(
            "en",
            "es",
            "uk",
            "fr",
            "pt",
            "de",
            "ru",
            "ar",
            "zh",
            "zh_hant",
            "hi",
            "bn",
            "ja",
            "id",
            "tr",
            "vi",
            "ko",
            "it",
            "fa",
            "pl",
            "ur",
            "nl",
            "ro",
            "el",
            "cs",
            "hu",
            "sv",
            "he",
            "th",
            "ms",
            "tl",
            "ta",
            "te",
            "mr",
            "gu",
            "kn",
            "ml",
            "da",
            "fi",
            "no",
            "nb",
            "nn",
            "sk",
            "bg",
            "hr",
            "sr",
            "bs",
            "hbs",
            "sl",
            "lt",
            "lv",
            "et",
            "ca",
            "gl",
            "eu",
            "sq",
            "az",
            "be",
            "is",
            "af",
            "ug",
        )

    private val INDEX: Map<String, Int> = CODES.withIndex().associate { (i, code) -> code to i }

    /** [code]'s index, or null for a language the registry does not name (it is then not declared at all). */
    fun indexOf(code: String?): Int? = code?.let(INDEX::get)

    /** The code at [index], or null for one this phone does not know (a newer registry's, or a hostile value). */
    fun codeAt(index: Int?): String? = index?.let(CODES::getOrNull)

    /** What a declaration of [index] costs in a frame beside its key: CBOR's uint, one byte under 24, two under 256. */
    fun valueBytes(index: Int?): Int =
        when {
            index == null -> 0
            index < CBOR_INLINE -> 1
            else -> 2
        }

    private const val CBOR_INLINE = 24
}

/**
 * What a sender declares about one message (ADR 2026-10.t2h6's 2026-10-29 amendment), as registry codes: [lang] the
 * body's language, [reads] the language its author reads. [of] leaves out what the wire implies — English for an
 * absent [lang], [lang] for an absent [reads] — and anything the registry does not name, so the common case costs
 * nothing.
 */
data class DeclaredLangs(
    val lang: String? = null,
    val reads: String? = null,
) {
    /** This declaration minus what the wire implies ([of]): what a frame carries, whoever built it. */
    fun normalized(): DeclaredLangs = of(lang, reads)

    /** [lang] as its registry index for the frame, or null. */
    val langIndex: Int? get() = LanguageRegistry.indexOf(lang)

    /** [reads] as its registry index for the frame, or null. */
    val readsIndex: Int? get() = LanguageRegistry.indexOf(reads)

    /** What the two fields cost a frame: under a one-letter name ([named]: `"l"`, `"r"`) or a v3 CBOR label. */
    fun wireCost(named: Boolean): Int =
        listOf(langIndex, readsIndex).sumOf {
            if (it == null) 0 else (if (named) NAMED_KEY_BYTES else LABEL_KEY_BYTES) + LanguageRegistry.valueBytes(it)
        }

    companion object {
        val NONE = DeclaredLangs()

        private const val ENGLISH = "en"

        // A one-letter text key is its header byte and the letter; a label under 24 is one byte.
        private const val NAMED_KEY_BYTES = 2
        private const val LABEL_KEY_BYTES = 1

        /** The declaration for a body in [lang] by a reader of [reads], either null for unknown, minus what is implied. */
        fun of(
            lang: String?,
            reads: String?,
        ): DeclaredLangs {
            val body = lang?.takeIf { it != ENGLISH && LanguageRegistry.indexOf(it) != null }
            val reader = reads?.takeIf { it != (body ?: ENGLISH) && LanguageRegistry.indexOf(it) != null }
            return DeclaredLangs(body, reader)
        }

        /** What a frame's two indices declare, read back as codes; an index this phone does not know is dropped. */
        fun fromWire(
            lang: Int?,
            reads: Int?,
        ): DeclaredLangs = DeclaredLangs(LanguageRegistry.codeAt(lang), LanguageRegistry.codeAt(reads))
    }
}
