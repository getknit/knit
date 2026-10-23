package app.getknit.knit.translate

/**
 * The language a reader would want a translation in, by the product rule ADR 2026-10.t2h6 proposes, measured by the
 * spike and not yet used by it: the app's language says nothing reliable (a direct install shows English, Google Play
 * translates the strings into the phone's language by itself, and a person may run the app in English and write in
 * Spanish). So the rule reads the languages the person writes in, from their own sent messages, and falls back to the
 * phone's preferred list — the system's, never the app's resolved locale.
 */
object ReaderLanguages {
    /** Fewer confident picks than this from the reader's own messages say nothing about what they write. */
    const val MIN_WRITTEN = 3

    data class Estimate(
        val written: Map<TranslateLang, Int>,
        val unsure: Int,
        val preferred: List<String>,
        val target: TranslateLang?,
    )

    /**
     * [own] is a pick per recent message this phone sent; [preferred] the system's language tags in order. The target
     * is the language written most, with at least [MIN_WRITTEN] picks, else the first preferred language of the pair.
     */
    fun estimate(
        own: List<LanguagePick>,
        preferred: List<String>,
    ): Estimate {
        val written =
            own
                .filterIsInstance<LanguagePick.Picked>()
                .groupingBy { it.lang }
                .eachCount()
        val unsure = own.count { it is LanguagePick.Unsure }
        val most = written.maxByOrNull { it.value }?.takeIf { it.value >= MIN_WRITTEN }?.key
        val fromSystem = preferred.firstNotNullOfOrNull { TranslateLang.ofCode(it.substringBefore('-').lowercase()) }
        return Estimate(written, unsure, preferred, most ?: fromSystem)
    }
}
