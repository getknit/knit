package app.getknit.knit.data.emoji

import java.util.Locale

/**
 * Which `emoji/emoji_<tag>.tsv` overlay the app's language takes, matched the way Android matches the
 * `res/values-<locale>/` strings: walk the language list in order; English stops the walk at the base
 * catalog (it *is* the default), the first language with an overlay — by `lang-REGION`, then es-419 for a
 * Latin American Spanish, then `lang` — wins, and a list with none gets the base. Pure JVM.
 */
object EmojiLocales {
    /** CLDR's parentLocales: these Spanish regions inherit es-419, not es. */
    private val LATIN_AMERICA =
        setOf(
            "AR",
            "BO",
            "BR",
            "BZ",
            "CL",
            "CO",
            "CR",
            "CU",
            "DO",
            "EC",
            "GT",
            "HN",
            "MX",
            "NI",
            "PA",
            "PE",
            "PR",
            "PY",
            "SV",
            "US",
            "UY",
            "VE",
        )
    /**
     * The ISO 639 codes java.util.Locale may still hand back (Android's `Locale.getLanguage()` says `in` for
     * Indonesian, `iw` for Hebrew, `ji` for Yiddish), mapped to the current codes the overlays are tagged with.
     */
    private val LEGACY_LANGUAGE = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
    private const val PREFIX = "emoji_"
    private const val SUFFIX = ".tsv"
    private const val BASE = "en"

    fun overlayFor(
        languageTags: List<String>,
        available: Set<String>,
    ): String? {
        for (tag in languageTags) {
            val locale = Locale.forLanguageTag(tag)
            val lang = currentLanguage(locale.language)
            if (lang.isEmpty()) continue
            if (lang == BASE) return null
            val region = locale.country
            val candidates =
                buildList {
                    if (region.isNotEmpty()) add("$lang-$region")
                    if (lang == "es" && (region in LATIN_AMERICA || region == "419")) add("es-419")
                    add(lang)
                }
            candidates.firstOrNull { it in available }?.let { return it }
        }
        return null
    }

    /**
     * [language] under its current ISO 639 code: Android's `Locale.getLanguage()` says `in` for Indonesian even for
     * the tag `id` (as a JVM started with `-Djava.locale.useOldISOCodes=true` does), while the overlays are tagged
     * `id`. A separate step because the test JVM (JDK 17+) never hands back the legacy code.
     */
    internal fun currentLanguage(language: String): String = LEGACY_LANGUAGE[language] ?: language

    /** The overlay tags among the `emoji/` asset file names (`emoji_de.tsv` → `de`), the base excluded. */
    fun tagsIn(fileNames: List<String>): Set<String> =
        fileNames
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .map { it.removePrefix(PREFIX).removeSuffix(SUFFIX) }
            .filter { it.isNotEmpty() && it != BASE }
            .toSet()

    fun asset(tag: String): String = "emoji/$PREFIX$tag$SUFFIX"
}
