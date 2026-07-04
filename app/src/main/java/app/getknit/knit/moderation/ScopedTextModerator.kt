package app.getknit.knit.moderation

/**
 * Routes text moderation by conversation scope. The public Nearby broadcast room runs the full hybrid
 * pass — the deterministic profanity word-list ([LexicalTextFilter]) first, then the ML toxicity
 * classifier ([MlTextModerator]) on anything it clears — while private DMs and groups run the toxicity
 * classifier only. Profanity filtering is deliberately limited to the public room; toxicity still
 * applies everywhere. The two scopes share one underlying [MlTextModerator] (wired in the moderation DI
 * module) so the heavy model is loaded at most once.
 */
class ScopedTextModerator(
    private val room: TextModerator,
    private val direct: TextModerator,
) {
    /** Classifies [text]; [isRoom] is true for the Nearby broadcast room, false for DMs and groups. */
    suspend fun classify(
        text: String,
        isRoom: Boolean,
    ): TextVerdict = if (isRoom) room.classify(text) else direct.classify(text)
}
