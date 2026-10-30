package app.getknit.knit.translate

/**
 * The language a reader wants a translation in (ADR 2026-10.t2h6 and its 2026-10-29 amendment). The app's language says
 * nothing reliable: a direct install shows English, Google Play translates the strings into the phone's language by
 * itself, and a person may run the app in English and write in Spanish. So, in order: the reader's own choice in
 * Settings, the language they write in, read from their own sent messages, and the phone's preferred list — the
 * system's, never the app's resolved locale. Only a language a model translates into counts.
 */
object ReaderLanguages {
    /** Fewer confident picks than this from the reader's own messages say nothing about what they write. */
    const val MIN_WRITTEN = 3

    /** The reader's own messages read for the language they write in. */
    const val OWN_SAMPLE = 50

    data class Estimate(
        val written: Map<TranslateLang, Int>,
        val unsure: Int,
        val preferred: List<String>,
        val override: TranslateLang?,
        // What Automatic picks: what the reader writes, else the system list. [target] is it, unless overridden.
        val automatic: TranslateLang?,
    ) {
        val target: TranslateLang? get() = override ?: automatic
    }

    /**
     * [own] is a pick per recent message this phone sent; [preferred] the system's language tags in order; [override]
     * the code chosen in Settings, or null for Automatic. Automatic is the language written most, with at least
     * [MIN_WRITTEN] picks, else the first preferred language a model translates into.
     */
    fun estimate(
        own: List<LanguagePick>,
        preferred: List<String>,
        override: String? = null,
    ): Estimate {
        val targets = TranslateDirection.targets
        val written =
            own
                .filterIsInstance<LanguagePick.Picked>()
                .filter { it.lang in targets }
                .groupingBy { it.lang }
                .eachCount()
        val unsure = own.count { it !is LanguagePick.Picked }
        val most = written.maxByOrNull { it.value }?.takeIf { it.value >= MIN_WRITTEN }?.key
        val fromSystem = preferred.firstNotNullOfOrNull { tag -> TranslateLang.ofCode(tag)?.takeIf { it in targets } }
        val chosen = override?.let(TranslateLang::ofCode)?.takeIf { it in targets }
        return Estimate(written, unsure, preferred, chosen, most ?: fromSystem)
    }
}
