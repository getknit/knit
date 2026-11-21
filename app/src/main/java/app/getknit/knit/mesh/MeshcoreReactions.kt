package app.getknit.knit.mesh

import java.security.MessageDigest
import java.text.BreakIterator

/**
 * MeshCore One's channel reaction (ADR 2026-11.gsqu's 2026-11-21 amendment; `Avi0n/MeshCoreOne` `docs/Reactions.md`): a
 * plain channel text `@[targetName]{emoji}\n{tag}` — or, from MeshCore One ≤ 1.4.1, `{emoji}@[targetName]\n{tag}` —
 * where the tag names the reacted-to post by Crockford Base32 of the first 5 bytes of
 * `SHA-256(UTF-8 text ‖ LE uint32 sender stamp)`: the post's text **without** the board's `"name: "` prefix, and the
 * stamp its sender's board aired. Every receiver of the post computes the same tag; nothing about it is secret or proves
 * anything. Pure, so the whole rule is pinned on the JVM ([MeshcoreReactionsTest]).
 */
object MeshcoreReactions {
    /** A tag's length: 40 bits in 5-bit symbols. */
    const val TAG_CHARS = 8

    private const val TAG_BYTES = 5
    private const val BITS_PER_CHAR = 5
    private const val SYMBOL_MASK = 0x1f
    private const val BYTE_MASK = 0xff
    private const val STAMP_MASK = 0xffffffffL

    /** Crockford's alphabet, lowercase as MeshCore One sends it: no I, L, O or U. */
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    private const val TOKEN_OPEN = "@["
    private const val TOKEN_CLOSE = ']'

    /** A reaction read off a channel text: its emoji, the tag of the post it names, and the name its token carried. */
    data class Parsed(
        val targetName: String,
        val emoji: String,
        val tag: String,
    )

    /**
     * The tag of a post whose text was [text] (as aired, after the board's `"name: "` prefix) under its sender's
     * [stampSecs]. The stamp is taken as the unsigned 32 bits a board airs.
     */
    fun tag(
        text: ByteArray,
        stampSecs: Long,
    ): String {
        val stamp = stampSecs and STAMP_MASK
        val le = ByteArray(Int.SIZE_BYTES) { i -> (stamp ushr (Byte.SIZE_BITS * i)).toByte() }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update(text)
                    update(le)
                }.digest()
        var bits = 0L
        for (i in 0 until TAG_BYTES) bits = (bits shl Byte.SIZE_BITS) or (digest[i].toLong() and BYTE_MASK.toLong())
        return buildString(TAG_CHARS) {
            for (i in TAG_CHARS - 1 downTo 0) append(ALPHABET[((bits ushr (BITS_PER_CHAR * i)) and SYMBOL_MASK.toLong()).toInt()])
        }
    }

    /**
     * [body] read as a reaction, or null when it is anything else (a plain post, kept as one). Exactly MeshCore One's
     * grammar: split at the last line break; after it exactly [TAG_CHARS] Crockford symbols (case-insensitive, `O` read
     * as `0`, `I` and `L` as `1`) and nothing else; before it `@[name]` and one emoji, in either order, the name by
     * [MeshcoreMentions]' token rule. The emoji is one extended grapheme cluster that [isReactionEmoji] takes; a shape
     * whose emoji it refuses stays a plain post.
     */
    fun parse(body: String): Parsed? {
        val cut = body.lastIndexOf('\n')
        if (cut < 0) return null
        val tag = canonicalTag(body.substring(cut + 1)) ?: return null
        val head = body.substring(0, cut)
        val (name, emoji) = splitHead(head) ?: return null
        if (MeshcoreMentions.replyPrefix(name) == null || !isReactionEmoji(emoji)) return null
        return Parsed(targetName = name, emoji = emoji, tag = tag)
    }

    /**
     * The text a reaction with [emoji] to [targetName]'s post under [tag] airs as — the current order — or null when the
     * name can't ride a token ([MeshcoreMentions.replyPrefix]'s rule) or [emoji] or [tag] would not parse back.
     */
    fun format(
        targetName: String?,
        emoji: String,
        tag: String,
    ): String? {
        if (targetName == null || MeshcoreMentions.replyPrefix(targetName) == null) return null
        if (!isReactionEmoji(emoji) || canonicalTag(tag) != tag) return null
        return "$TOKEN_OPEN$targetName$TOKEN_CLOSE$emoji\n$tag"
    }

    /** One extended grapheme cluster, no whitespace or control, within the wire's reaction bound. */
    fun isReactionEmoji(emoji: String): Boolean {
        if (!app.getknit.knit.isValidReactionEmoji(emoji)) return false
        if (emoji.any(::breaksToken) || emoji.codePoints().anyMatch(::hidesItself)) return false
        if (emoji.all { it.isLetterOrDigit() }) return false
        val clusters = BreakIterator.getCharacterInstance().apply { setText(emoji) }
        return clusters.next() == emoji.length && clusters.next() == BreakIterator.DONE
    }

    /** A character that can't sit in a reaction's emoji: whitespace, a control, or one of the token's own. */
    private fun breaksToken(c: Char): Boolean = c.isWhitespace() || c.isISOControl() || c in TOKEN_CHARS

    /**
     * An invisible format character (Unicode `Cf`) other than the ones emoji are built from — the zero-width joiner and the
     * tag characters of a subdivision flag: a bidi override would turn the line it lands in around, a zero-width space
     * would draw an empty chip (security review).
     */
    private fun hidesItself(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.FORMAT.toInt() &&
            codePoint != ZERO_WIDTH_JOINER &&
            codePoint !in TAG_CHARACTERS

    private const val ZERO_WIDTH_JOINER = 0x200D
    private val TAG_CHARACTERS = 0xE0020..0xE007F

    private const val TOKEN_CHARS = "[]@"

    private fun splitHead(head: String): Pair<String, String>? {
        if (head.startsWith(TOKEN_OPEN)) {
            val close = head.indexOf(TOKEN_CLOSE)
            if (close > TOKEN_OPEN.length) return head.substring(TOKEN_OPEN.length, close) to head.substring(close + 1)
        }
        if (head.endsWith(TOKEN_CLOSE)) {
            val open = head.lastIndexOf(TOKEN_OPEN)
            if (open > 0) return head.substring(open + TOKEN_OPEN.length, head.length - 1) to head.substring(0, open)
        }
        return null
    }

    private fun canonicalTag(raw: String): String? {
        if (raw.length != TAG_CHARS) return null
        val out = StringBuilder(TAG_CHARS)
        for (c in raw.lowercase()) {
            val symbol =
                when (c) {
                    'o' -> '0'
                    'i', 'l' -> '1'
                    else -> c
                }
            if (symbol !in ALPHABET) return null
            out.append(symbol)
        }
        return out.toString()
    }
}
