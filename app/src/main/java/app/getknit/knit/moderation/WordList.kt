package app.getknit.knit.moderation

import android.content.Context
import java.io.IOException

/**
 * Loads the bundled word lists from `assets/`, ignoring blank lines and `#` comments: the room's profanity
 * list and the identity-slur list every scope runs (ADR 2026-10.d62w), and the three lists the name screen adds
 * to them (ADR 2026-11.y4pc). Missing or unreadable assets degrade to an empty list (the filter then allows
 * everything) rather than crashing startup.
 */
object WordList {
    private const val PROFANITY_ASSET = "moderation/profanity_en.txt"
    private const val SLURS_ASSET = "moderation/slurs_en.txt"
    private const val NAME_EMBEDDABLE_ASSET = "moderation/name_embeddable_en.txt"
    private const val NAME_EXCLUSIONS_ASSET = "moderation/name_exclusions_en.txt"
    private const val NAME_ALLOWED_ASSET = "moderation/name_allowed_en.txt"

    fun loadProfanity(context: Context): List<String> = load(context, PROFANITY_ASSET)

    fun loadSlurs(context: Context): List<String> = load(context, SLURS_ASSET)

    /** The [NameScreen] built from every list it reads. */
    fun nameScreen(context: Context): NameScreen =
        NameScreen(
            slurs = loadSlurs(context),
            profanity = loadProfanity(context),
            embeddable = load(context, NAME_EMBEDDABLE_ASSET),
            exclusions = load(context, NAME_EXCLUSIONS_ASSET),
            allowedNames = load(context, NAME_ALLOWED_ASSET),
        )

    private fun load(
        context: Context,
        asset: String,
    ): List<String> =
        try {
            context.assets.open(asset).bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
}
