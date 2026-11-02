package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry

/**
 * The language a reader wants a translation in (ADR 2026-10.t2h6 and its 2026-10-29 and 2026-11-02 amendments). The
 * app's language says nothing reliable: a direct install shows English, Google Play translates the strings into the
 * phone's language by itself, and a person may run the app in English and write in Spanish. So, in order: the reader's
 * own choice in Settings, then the phone's preferred list — the system's, never the app's resolved locale — when its
 * first language a model reaches is not English, then the language they write in, read from their own sent messages,
 * then English off that list. Only a language a model translates into counts.
 *
 * What they write ranks below a phone set to another language because it names the chat as often as the reader: a
 * Spanish speaker writing to English contacts, by hand or through the composer's translation, sends English.
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
        // What Automatic picks: the system list's language unless it is English, else what the reader writes, else
        // English off the list. [target] is it, unless overridden.
        val automatic: TranslateLang?,
    ) {
        val target: TranslateLang? get() = override ?: automatic
    }

    /**
     * [own] is a pick per recent message this phone sent; [preferred] the system's language tags in order; [override]
     * the code chosen in Settings, or null for Automatic. Automatic is the first preferred language a model
     * translates into, unless that is English; then the language written most, with at least [MIN_WRITTEN] picks;
     * then that English. A phone whose list no model reaches reads what they write alone.
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
        // English is every model's hub, so a phone in English says least: there, what they write decides.
        val automatic = fromSystem?.takeIf { it != TranslateLang.EN } ?: most ?: fromSystem
        return Estimate(written, unsure, preferred, chosen, automatic)
    }

    /**
     * What one of the reader's own messages says they write (ADR 2026-10.t2h6's 2026-11-02 amendment): the language its
     * draft was in when the composer translated the body ([draftTranslatedFrom], a registry code), else the identifier's
     * [read] of the body. A translated body is in the chat's language, not the reader's, so it is never read; a draft
     * language no model reaches is unsure.
     */
    suspend fun ownPick(
        draftTranslatedFrom: String?,
        read: suspend () -> LanguagePick,
    ): LanguagePick =
        if (draftTranslatedFrom == null) {
            read()
        } else {
            TranslateLang.ofCode(draftTranslatedFrom)?.let(LanguagePick::Picked) ?: LanguagePick.Unsure
        }

    /**
     * The language this reader says they read, on what they send (ADR 2026-10.t2h6's 2026-10-29 amendment), a registry
     * code: [override], their choice in Settings, else the first of [perApp], the language they chose for this app,
     * else the first of [system], the phone's own. A choice is the evidence: never the language the app fell back to
     * showing, and never only one a model on this phone translates into.
     */
    fun declaredReads(
        override: String?,
        perApp: List<String>,
        system: List<String>,
    ): String? =
        override?.let(LanguageRegistry::codeOf)
            ?: perApp.firstOrNull()?.let(LanguageRegistry::codeOf)
            ?: system.firstOrNull()?.let(LanguageRegistry::codeOf)
}
