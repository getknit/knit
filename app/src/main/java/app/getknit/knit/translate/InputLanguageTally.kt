package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry

/**
 * The language a draft was typed in (ADR 2026-10.t2h6's 2026-10-29 amendment): the keyboard's language at each edit,
 * weighted by the characters that edit added, so a person who switches keyboards mid-word is read by what most of the
 * draft was typed with. The keyboard is a strong hint the words alone cannot give on a short line, and the receiver
 * weighs it against its own read of the words.
 *
 * An edit that adds more than [LARGE_INSERT] characters at once is a paste, a shared text, a restored draft, an applied
 * translation or a whole phrase of voice typing, none of which the current keyboard's language describes, so it counts
 * for no language; [language] answers only when typed characters could make up most of the draft as sent.
 */
class InputLanguageTally {
    private val typed = HashMap<String, Int>()

    /** One edit that added [added] characters while the keyboard named [keyboard] (a BCP-47 tag, null when it said none). */
    fun onEdit(
        added: Int,
        keyboard: String?,
    ) {
        if (added <= 0) return
        if (added > LARGE_INSERT) return
        val code = keyboard?.let(LanguageRegistry::codeOf) ?: return
        typed[code] = (typed[code] ?: 0) + added
    }

    /** Forgets the draft: it was sent or emptied. */
    fun clear() = typed.clear()

    /**
     * The language a draft of [length] characters was typed in, or null: one keyboard language must account for at
     * least two thirds of the typed characters, and the typed characters for more than half of [length], so a mostly
     * pasted or shared message declares none.
     */
    fun language(length: Int): String? {
        val total = typed.values.sum()
        if (total == 0 || total * 2 <= length) return null
        val (code, count) = typed.maxBy { it.value }
        return code.takeIf { count * MAJORITY_OF >= total * MAJORITY_PART }
    }

    companion object {
        /** More than this at once is not typing: a keyboard commits a word, or a suggestion, at a time. */
        const val LARGE_INSERT = 24

        // Two thirds: [MAJORITY_PART] of every [MAJORITY_OF] typed characters.
        private const val MAJORITY_PART = 2
        private const val MAJORITY_OF = 3
    }
}
