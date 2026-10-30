package app.getknit.knit

/**
 * Character limits for user-editable free-text inputs, shared by the input fields that enforce them,
 * their on-screen counters, and the wire-level guards in [app.getknit.knit.mesh.MeshManager].
 * Centralized so the cap and the counter can never drift apart.
 */
object TextLimits {
    /** Profile display name — single line, shown in chat lists, headers, and notifications. */
    const val DISPLAY_NAME = 32

    /** Profile status one-liner. */
    const val STATUS = 100

    /** Group title — single line, shown in the chat header. */
    const val GROUP_NAME = 32

    /** Chat message body. Generous, but bounded so a frame stays well within the transport's payload budget. */
    const val MESSAGE = 2000

    /**
     * A reaction emoji, in UTF-16 units — the unit every other cap here is enforced in. The longest RGI emoji
     * sequences are 15 units (a two-person kiss with skin tones) and 14 (a tag-sequence subdivision flag), so
     * 32 is ~2× the worst case with room for Unicode to grow a sequence, while bounding the UTF-8 form at
     * 64 B — the whole of a sealed reaction's size variance on the wire. Enforced on both send and receive by
     * [isValidReactionEmoji]; a length cap only, never an emoji-class test (see that function).
     */
    const val REACTION = 32

    /**
     * Received display text — a peer's name or status, a group's name where it is drawn — made safe to show on one line
     * (ADR 2026-10.2kfr): C0/C1 controls outside regex `\s` and the bidi controls removed, [normalizeSingleLine], cut to
     * [limit] UTF-16 units on a code-point boundary, and trimmed again (a cut can land after a space, and a leading
     * U+0085 is `\s` but not `isWhitespace`, so it survives the first trim as a space). Idempotent. ZWJ and ZWNJ stay:
     * emoji sequences and Persian names need them, and [app.getknit.knit.identity.NameKey] drops them anyway. knit-ios
     * mirrors it as `TextLimits.cleanReceived`; `vectors/name-key-v1.json` pins both.
     */
    fun cleanReceived(
        value: String,
        limit: Int,
    ): String {
        val kept = StringBuilder(value.length)
        for (c in value) if (!isRemovedFromReceived(c)) kept.append(c)
        return normalizeSingleLine(kept.toString()).clampToUtf16(limit).trim()
    }
}

/**
 * Whether [emoji] is a reaction the wire accepts: non-blank and at most [TextLimits.REACTION] UTF-16 units.
 * Shared by the sender ([app.getknit.knit.mesh.MeshManager.sendReaction]) and both inbound paths so the
 * two can never drift. Deliberately **length-only**: an emoji-class check (code-point ranges, "one grapheme
 * cluster") would make an old build drop every emoji Unicode adds after it shipped, and grapheme
 * segmentation varies with the device ICU — the picker is the only emitter and is where "one RGI emoji"
 * is guaranteed. Blank is refused rather than read as a retraction: retraction is the explicit `null`.
 */
fun isValidReactionEmoji(emoji: String): Boolean = emoji.isNotBlank() && emoji.length <= TextLimits.REACTION

/**
 * The longest prefix of this string within [limit] UTF-16 units that ends on a code-point boundary: `take(limit)`,
 * except that a cut which would split a surrogate pair stops before the pair. Every cut of user text to a
 * [TextLimits] cap goes through it, on send and receive, so a name that ends mid-emoji keeps whole code points
 * instead of a lone surrogate (which the JVM writes as `?` and iOS cannot hold), and both platforms cut the same
 * text to the same prefix (knit-ios `TextLimits.truncated(_:toUTF16:)`; the interop audit's IOS-8).
 */
fun String.clampToUtf16(limit: Int): String {
    if (length <= limit) return this
    if (limit <= 0) return ""
    val splitsPair = Character.isHighSurrogate(this[limit - 1]) && Character.isLowSurrogate(this[limit])
    return substring(0, if (splitsPair) limit - 1 else limit)
}

/**
 * [clampToUtf16] for display: a cut string ends in "…" (inside [limit]) so it reads as cut, not as said. Never for
 * text that crosses the wire or is compared on the other side — those cut silently, the same on both platforms.
 */
fun String.ellipsizeToUtf16(limit: Int): String = if (length <= limit) this else clampToUtf16(limit - 1).trimEnd() + "…"

/**
 * Normalizes a single-line field: trims the ends and collapses internal whitespace runs (including
 * stray newlines/tabs from a paste) down to single spaces. Apply this at commit time, never
 * per-keystroke — trimming the trailing space on every keystroke would stop the user from typing a
 * space between words (the field would reset before the next character).
 */
fun normalizeSingleLine(value: String): String = value.trim().replace(ART_WHITESPACE_RUN, " ")

/**
 * A run of regex `\s` as ART answers it — Unicode White_Space, the set `ArtTextSemanticsTest` pins on a device and
 * the iOS port mirrors (ADR 2026-10.swst) — spelled as a literal class, because the desktop JDK's own `\s` is the
 * six ASCII characters: written as `\s`, a JVM test (and a vector it writes) would see a different law from a phone.
 */
internal val ART_WHITESPACE_RUN =
    Regex("[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

/** A C0/C1 control that is not regex `\s`, or a bidi control (ALM, LRM, RLM, the embeddings and overrides, the isolates). */
@Suppress("MagicNumber") // the code points are the definition
private fun isRemovedFromReceived(c: Char): Boolean =
    when (c.code) {
        in 0x0000..0x0008, in 0x000E..0x001F, in 0x007F..0x0084, in 0x0086..0x009F -> true
        0x061C, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2066..0x2069 -> true
        else -> false
    }
