package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry

/**
 * What CLD2 said about a text (ADR 2026-10.t2h6): its top three languages with the percent of the text each took,
 * its reliable bit and the bytes it scored. CLD2 answers `un` (unknown) for a line too short to call.
 */
data class Detection(
    val top: List<Pair<String, Int>>,
    val reliable: Boolean,
    val bytes: Int,
) {
    companion object {
        /** The native core's "es:97 un:0 un:0|1|38", or null for anything else. */
        fun parse(raw: String): Detection? {
            val parts = raw.split('|')
            if (parts.size != PARTS) return null
            val top =
                parts[0].split(' ').map { entry ->
                    val code = entry.substringBefore(':', "")
                    val percent = entry.substringAfter(':', "").toIntOrNull() ?: return null
                    if (code.isEmpty()) return null
                    code to percent
                }
            val bytes = parts[2].toIntOrNull() ?: return null
            return Detection(top, parts[1] == "1", bytes)
        }

        private const val PARTS = 3
    }
}

/** Which language a message is in, or that it cannot be told. */
sealed interface LanguagePick {
    data class Picked(
        val lang: TranslateLang,
    ) : LanguagePick

    /** CLD2 could not tell, or named a language no model translates: the reader is asked. */
    data object Unsure : LanguagePick

    /**
     * Known to be [code], a registry language no model on this phone translates from (ADR 2026-10.t2h6's 2026-10-29
     * amendment): there is nothing to offer, so Translate is not.
     */
    data class Other(
        val code: String,
    ) : LanguagePick
}

/**
 * Names a message's language from CLD2's answer. Only a confident one counts: reliable, the top language one the table
 * knows ([TranslateLang.ofCode]), and holding [MIN_PERCENT] of the text. Everything else is [LanguagePick.Unsure] and
 * the reader picks: on the corpus that is mostly lines of one to three words, which CLD2 calls `un`, and Spanish it
 * reads as a neighbour (Galician, Catalan, Portuguese). A neighbour is never mapped to a language it is not: that would
 * be a guess.
 *
 * One contradiction is caught: CLD2 read "qué fotos tan bonitas" as English, reliably. An English answer for text
 * carrying a letter English does not write (any letter outside ASCII, ¿ or ¡) is unsure.
 */
object LanguagePolicy {
    const val MIN_PERCENT = 80

    private val NOT_ENGLISH_MARKS = Regex("[¿¡]|[\\p{L}&&[^\\p{ASCII}]]")

    fun pick(
        detection: Detection?,
        text: String,
    ): LanguagePick {
        if (detection == null || !detection.reliable) return LanguagePick.Unsure
        val (code, percent) = detection.top.firstOrNull() ?: return LanguagePick.Unsure
        val lang = TranslateLang.ofCode(code) ?: return LanguagePick.Unsure
        if (percent < MIN_PERCENT) return LanguagePick.Unsure
        if (lang == TranslateLang.EN && NOT_ENGLISH_MARKS.containsMatchIn(text)) return LanguagePick.Unsure
        return LanguagePick.Picked(lang)
    }

    /**
     * The language [text]'s words surely name, as a registry code: [pick]'s confident read, but of any language the
     * registry names rather than only those a model translates, so the resolver can tell a French message on a phone
     * with no French model from one it cannot read at all (ADR 2026-10.t2h6's 2026-10-29 amendment). Null when unsure.
     */
    fun named(
        detection: Detection?,
        text: String,
    ): String? {
        if (detection == null || !detection.reliable) return null
        val (code, percent) = detection.top.firstOrNull() ?: return null
        val lang = LanguageRegistry.codeOf(code) ?: return null
        if (percent < MIN_PERCENT) return null
        if (lang == TranslateLang.EN.code && NOT_ENGLISH_MARKS.containsMatchIn(text)) return null
        return lang
    }

    /** CLD2's named languages the table knows, in its order, for a picker to put first; `un` and the rest dropped. */
    fun ranked(detection: Detection?): List<TranslateLang> =
        detection
            ?.top
            .orEmpty()
            .filter { it.second > 0 }
            .mapNotNull { TranslateLang.ofCode(it.first) }
            .distinct()

    /** The language most of [picks] are in, or null when fewer than [minPicks] are confident or none holds a majority. */
    fun majority(
        picks: List<LanguagePick>,
        minPicks: Int,
    ): TranslateLang? {
        val counts = picks.filterIsInstance<LanguagePick.Picked>().groupingBy { it.lang }.eachCount()
        val (lang, n) = counts.maxByOrNull { it.value } ?: return null
        return lang.takeIf { n >= minPicks && n * 2 > counts.values.sum() }
    }
}
