package app.getknit.knit.translate

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

/** Which language of the pair a message is in, or that it cannot be told. */
sealed interface LanguagePick {
    data class Picked(
        val lang: TranslateLang,
    ) : LanguagePick

    /** CLD2 could not tell, or named a language outside the pair: the reader is asked. */
    data object Unsure : LanguagePick
}

/**
 * Names a message's language for the spike's one pair from CLD2's answer. Only a confident one counts: reliable, the
 * top language English or Spanish, and holding [MIN_PERCENT] of the text. Everything else is [LanguagePick.Unsure] and
 * the reader picks: on the corpus that is mostly lines of one to three words, which CLD2 calls `un`, and Spanish it
 * reads as a neighbour (Galician, Catalan, Portuguese). A neighbour is never mapped to Spanish: that would be a guess.
 *
 * One contradiction is caught: CLD2 read "qué fotos tan bonitas" as English, reliably. An English answer for text
 * carrying a mark English does not write (¿ ¡ ñ or an accented vowel) is unsure.
 */
object LanguagePolicy {
    const val MIN_PERCENT = 80

    private val SPANISH_MARKS = Regex("[¿¡ñÑáéíóúÁÉÍÓÚ]")

    fun pick(
        detection: Detection?,
        text: String,
    ): LanguagePick {
        if (detection == null || !detection.reliable) return LanguagePick.Unsure
        val (code, percent) = detection.top.firstOrNull() ?: return LanguagePick.Unsure
        val lang = TranslateLang.ofCode(code) ?: return LanguagePick.Unsure
        if (percent < MIN_PERCENT) return LanguagePick.Unsure
        if (lang == TranslateLang.EN && SPANISH_MARKS.containsMatchIn(text)) return LanguagePick.Unsure
        return LanguagePick.Picked(lang)
    }
}
