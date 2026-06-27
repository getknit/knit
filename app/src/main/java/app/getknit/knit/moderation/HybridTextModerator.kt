package app.getknit.knit.moderation

/**
 * Composes the two text moderation strategies: the cheap deterministic [lexical] filter runs first and
 * short-circuits on a hit; only text it clears is handed to the optional ML classifier [ml] for the
 * contextual/obfuscated cases a word list misses. With [ml] null (no toxicity model bundled yet) this
 * degrades cleanly to lexical-only.
 */
class HybridTextModerator(
    private val lexical: LexicalTextFilter,
    private val ml: TextModerator? = null,
) : TextModerator {
    override suspend fun classify(text: String): TextVerdict {
        val lexicalVerdict = lexical.classify(text)
        if (lexicalVerdict.flagged) return lexicalVerdict
        return ml?.classify(text) ?: TextVerdict.ALLOWED
    }
}
