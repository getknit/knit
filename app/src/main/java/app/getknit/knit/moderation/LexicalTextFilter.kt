package app.getknit.knit.moderation

import java.text.Normalizer

/**
 * Deterministic, offline profanity filter — the cheap first pass of the hybrid text pipeline (see
 * [HybridTextModerator]). Pure Kotlin with no Android dependencies, so it is unit-tested directly via
 * [check].
 *
 * Matching is resilient to the evasions a naive blacklist misses:
 *  - **case / diacritics / homoglyphs** — input is lowercased and NFKD-folded with combining marks
 *    stripped, so `SHÍT` folds to `shit`;
 *  - **leetspeak** — digits/symbols map to look-alike letters (`sh1t`, `$h!t` → `shit`);
 *  - **stretched letters** — runs of 3+ identical characters collapse (`shiiit` → `shit`);
 *  - **spaced-out letters** — runs of single letters separated by punctuation/spaces are rejoined
 *    (`f u c k`, `f.u.c.k` → `fuck`).
 *
 * It deliberately matches whole tokens (not arbitrary substrings) to avoid the "Scunthorpe problem"
 * (e.g. *class*, *assistant*), trading recall for far fewer false positives; the ML classifier layered
 * on top in [HybridTextModerator] is what catches the contextual cases this misses. [allowedTerms] is
 * an explicit allow-list escape hatch for tokens that would otherwise match. A hit reports [category], so the
 * slur list (every scope) and the profanity list (the room) say which one tripped.
 */
class LexicalTextFilter(
    blockedWords: Collection<String>,
    allowedTerms: Collection<String> = emptySet(),
    category: TextVerdict.Category = TextVerdict.Category.PROFANITY,
) : TextModerator {
    private val blocked: Set<String> =
        blockedWords.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
    private val blockedCollapsed: Set<String> = blocked.map { collapseRuns(it) }.toSet()
    private val allowed: Set<String> = allowedTerms.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
    private val flagged = TextVerdict(allowed = false, category = category, score = 1f)

    override suspend fun classify(text: String): TextVerdict = check(text)

    /** Synchronous core, directly unit-testable without coroutines. */
    fun check(text: String): TextVerdict {
        if (blocked.isEmpty() || text.isBlank()) return TextVerdict.ALLOWED
        val tokens = normalize(text).split(SEPARATORS).filter(String::isNotEmpty)

        // Accumulate runs of single letters so a spaced-out evasion ("f u c k") is tested rejoined.
        val pendingSingles = StringBuilder()
        for (token in tokens) {
            if (isBlocked(token)) return flagged
            if (token.length == 1) {
                pendingSingles.append(token)
            } else if (pendingSingles.isNotEmpty()) {
                if (isBlocked(pendingSingles.toString())) return flagged
                pendingSingles.setLength(0)
            }
        }
        if (pendingSingles.isNotEmpty() && isBlocked(pendingSingles.toString())) return flagged
        return TextVerdict.ALLOWED
    }

    /**
     * A token matches a term as written, or — only when it was itself stretched — the term's collapsed form. The
     * collapsed set answers "is this a stretched term?", and a token with nothing to collapse is not stretched: asking
     * it anyway made a lone `x` match `xxx`, whose collapsed form is `x`, so "2 x 3" was blocked in the room.
     */
    private fun isBlocked(token: String): Boolean {
        if (token.isEmpty() || token in allowed) return false
        if (token in blocked) return true
        val collapsed = collapseRuns(token)
        return collapsed != token && collapsed in blockedCollapsed
    }

    internal companion object {
        private val SEPARATORS = Regex("[^a-z]+")
        private val COMBINING_MARKS = Regex("\\p{Mn}+")
        private const val MIN_RUN = 3

        /** Lowercase, NFKD-fold, strip combining marks, then map leet digits and symbols to letters. Shared with [NameScreen]. */
        internal fun normalize(raw: String): String {
            val folded =
                Normalizer
                    .normalize(raw.lowercase(), Normalizer.Form.NFKD)
                    .replace(COMBINING_MARKS, "")
            val mapped = StringBuilder(folded.length)
            for (ch in folded) mapped.append(LEET[ch] ?: ch)
            return mapped.toString()
        }

        /** The letter a leet digit stands for (`1` → `i`), or null; [NameScreen] reads a digit as one only between letters. */
        internal fun leetLetter(ch: Char): Char? = LEET[ch]

        /**
         * Collapses runs of [MIN_RUN]+ identical chars to one (`shiiit` → `shit`) while leaving doubles
         * intact, so short words such as `ass` are unaffected (otherwise `as` could collide with it).
         */
        internal fun collapseRuns(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                var j = i
                while (j < s.length && s[j] == ch) j++
                val runLen = j - i
                repeat(if (runLen >= MIN_RUN) 1 else runLen) { out.append(ch) }
                i = j
            }
            return out.toString()
        }

        private val LEET =
            mapOf(
                '0' to 'o',
                '1' to 'i',
                '3' to 'e',
                '4' to 'a',
                '5' to 's',
                '7' to 't',
                '8' to 'b',
                '@' to 'a',
                '$' to 's',
                '!' to 'i',
                '|' to 'i',
            )
    }
}
