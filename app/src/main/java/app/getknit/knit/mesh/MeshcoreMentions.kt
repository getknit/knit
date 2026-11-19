package app.getknit.knit.mesh

import app.getknit.knit.identity.NameKey

/**
 * MeshCore's own reply and mention form on a Public channel (ADR 2026-11.gsqu's 2026-11-19 amendment): a reply puts the
 * parent's sender name in brackets in front of the text, `@[Name] body` (the companion apps' keyless form), and a mention
 * is the same `@[Name]` token anywhere in a post. It is plain text and nothing more: the token is a self-chosen display
 * name, proves nothing and never resolves to anyone. Pure, so the whole rule is pinned on the JVM
 * ([MeshcoreMentionsTest]).
 */
object MeshcoreMentions {
    /** The longest name a companion can hold (`char node_name[32]`, `NodePrefs.h:17`): a longer token names no board. */
    const val NAME_MAX_BYTES = 31

    /**
     * A token: `@[`, a name of 1–31 UTF-16 units holding no bracket and no line break, `]`. The byte bound is asked after
     * ([NAME_MAX_BYTES]); a unit is never more than its bytes, so the unit bound loses no name a board can hold.
     */
    private val TOKEN = Regex("""@\[([^\[\]\r\n]{1,$NAME_MAX_BYTES})]""")

    /**
     * The reply prefix a MeshCore client reads as a reply to [name]'s post, `@[name] `, or null when there is no name to
     * address (unnamed, invisible) or the token could not carry it (a bracket or a line break, or more than a board's name
     * holds): the reply then goes as a plain post, as a companion's does when it knows no parent.
     */
    fun replyPrefix(name: String?): String? {
        if (name == null || NameKey.of(name).isEmpty()) return null
        val token = "@[$name]"
        return if (TOKEN.matchEntire(token) != null && fits(name)) "$token " else null
    }

    /** Every `@[name]` token in [body] a board's name could fill, as written, in order. */
    fun names(body: String): List<String> =
        TOKEN
            .findAll(body)
            .map { it.groupValues[1] }
            .filter(::fits)
            .toList()

    /**
     * The first token in [body] that names [handle] (the user's own board's name, its owner's handle), as written, or null.
     * Matched by [NameKey], so case, a lookalike letter or an invisible character still reads as the same name; a handle
     * whose key is empty is never mentioned.
     */
    fun mentionOf(
        body: String,
        handle: String?,
    ): String? {
        val key = handle?.let(NameKey::of)?.takeIf { it.isNotEmpty() } ?: return null
        return names(body).firstOrNull { NameKey.of(it) == key }
    }

    private fun fits(name: String): Boolean = name.encodeToByteArray().size <= NAME_MAX_BYTES
}
