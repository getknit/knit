package app.getknit.knit.moderation

import java.io.File

/**
 * A shipped `assets/moderation/` word list read straight from disk and parsed with the same rules as
 * [WordList.load] (trim, drop blank lines and `#` comments) — so the contract tests run on the plain JVM,
 * no Robolectric host. Gradle runs unit tests with the module dir as the working dir.
 */
object ShippedWordList {
    const val PROFANITY = "profanity_en.txt"
    const val SLURS = "slurs_en.txt"
    const val NAME_EMBEDDABLE = "name_embeddable_en.txt"
    const val NAME_EXCLUSIONS = "name_exclusions_en.txt"
    const val NAME_ALLOWED = "name_allowed_en.txt"

    /**
     * A handle the shipped [nameScreen] flags for strangers — the one `NameScreenTest`, `PeerLabelsTest` and
     * `PeerRepositoryTest` hide, pinned flagged by `NameScreenVectorTest`. A test that needs a name the screen hides
     * names it through here, so no new term is spelled out in a new place.
     */
    const val FLAGGED_NAME = "Fuck69"

    /** What the app draws other people's names with while Content filtering is on (ADR 2026-11.y4pc), over the shipped lists. */
    fun shownNames(): ShownNames = ShownNames(nameScreen())

    /** The [NameScreen] the app builds, from the shipped lists. */
    fun nameScreen(): NameScreen =
        NameScreen(
            slurs = read(SLURS),
            profanity = read(PROFANITY),
            embeddable = read(NAME_EMBEDDABLE),
            exclusions = read(NAME_EXCLUSIONS),
            allowedNames = read(NAME_ALLOWED),
        )

    fun read(name: String): List<String> {
        val file =
            listOf("src/main/assets/moderation/$name", "app/src/main/assets/moderation/$name")
                .map(::File)
                .firstOrNull { it.exists() }
                ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file
            .readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
