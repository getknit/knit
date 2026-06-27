package app.getknit.knit.moderation

/**
 * Classifies message text as allowed or abusive. Implementations run entirely on-device (the app has
 * no network); see [LexicalTextFilter] (deterministic) and [HybridTextModerator] (lexical + ML).
 */
interface TextModerator {
    suspend fun classify(text: String): TextVerdict
}
