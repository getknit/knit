package app.getknit.knit.data.crypto

/**
 * The structure of a CBOR document and never a value in it: major types, counts and lengths, and a map key only when
 * it is one of the caller's [describe] names. It is what a Keystore-wrapped secret that opens but does not parse can
 * carry into a crash log: enough to tell a span lost or repeated mid-array from a bad build, with no key byte in it.
 *
 * `i` is an integer, `b32` a 32-byte string, `t7` a 7-byte text, `s` a simple value, `f` a float, `#n(…)` tag n,
 * `{k:v,…}` a map, and `[…]` an array whose equal neighbours collapse to `shape×n`, every run after the first carrying
 * the offset it starts at (`[i×92 t7@203 …]`). A trailing `*` marks an indefinite length, `!@n` the offset where the
 * walk met malformed CBOR and stopped, and `+n B` bytes left over after the document. The document's size leads.
 */
internal object CborShape {
    fun describe(
        blob: ByteArray,
        names: Set<String> = emptySet(),
    ): String {
        val walk = Walk(blob, names)
        val shape =
            try {
                val document = walk.item(0)
                val trailing = blob.size - walk.at
                if (trailing > 0) "$document +$trailing B" else document
            } catch (cut: Cut) {
                cut.shape
            }
        val text = "${blob.size} B $shape"
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "…" else text
    }

    /** Deep enough for any secret this app wraps; a deeper document is cut there rather than walked. */
    private const val MAX_DEPTH = 16

    /** A walk of garbage alternates shapes and grows with it; the head of it is the part that says what happened. */
    private const val MAX_CHARS = 1_000

    // RFC 8949 §3: a head byte is three bits of major type over five of additional information.
    private const val MAJOR_SHIFT = 5
    private const val INFO_MASK = 0x1F
    private const val BYTE_MASK = 0xFF
    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
    private const val MAJOR_TAG = 6

    /** The first additional information whose argument follows the head: 24, 25, 26, 27 take 1, 2, 4, 8 bytes. */
    private const val INFO_FOLLOWS = 24
    private const val INFO_LAST_WIDTH = 27
    private const val INDEFINITE = 31
    private const val BREAK = 0xFF

    /** The walk stopped on malformed CBOR; [shape] is everything up to it, ending in `!@offset`. */
    private class Cut(
        val shape: String,
        inner: Cut? = null,
    ) : RuntimeException(null, inner, false, false)

    private class Run(
        val shape: String,
        val start: Int,
    ) {
        var count = 1
    }

    private class Walk(
        private val blob: ByteArray,
        private val names: Set<String>,
    ) {
        var at = 0

        fun item(depth: Int): String {
            val start = at
            if (depth > MAX_DEPTH || at >= blob.size) throw Cut("!@$start")
            val head = blob[at++].toInt() and BYTE_MASK
            val major = head ushr MAJOR_SHIFT
            val info = head and INFO_MASK
            return if (info == INDEFINITE) indefinite(major, start, depth) else definite(major, info, start, depth)
        }

        private fun definite(
            major: Int,
            info: Int,
            start: Int,
            depth: Int,
        ): String {
            val argument = argument(info, start)
            return when (major) {
                MAJOR_UNSIGNED, MAJOR_NEGATIVE -> "i"
                MAJOR_BYTES -> "b" + skip(argument, start)
                MAJOR_TEXT -> "t" + skip(argument, start)
                MAJOR_ARRAY -> elements(argument, start, depth)
                MAJOR_MAP -> entries(argument, start, depth)
                MAJOR_TAG -> tagged(argument, depth)
                else -> if (info > INFO_FOLLOWS) "f" else "s" // 25–27 are the half, single and double floats
            }
        }

        private fun indefinite(
            major: Int,
            start: Int,
            depth: Int,
        ): String =
            when (major) {
                MAJOR_BYTES, MAJOR_TEXT -> chunks(major)
                MAJOR_ARRAY -> elements(null, start, depth) + "*"
                MAJOR_MAP -> entries(null, start, depth) + "*"
                else -> throw Cut("!@$start") // a break outside an indefinite item, or an integer or tag
            }

        /** The head's argument: [info] itself below [INFO_FOLLOWS], else the 1, 2, 4 or 8 bytes after the head. */
        private fun argument(
            info: Int,
            start: Int,
        ): Long {
            if (info < INFO_FOLLOWS) return info.toLong()
            if (info > INFO_LAST_WIDTH) throw Cut("!@$start") // 28–30 are reserved
            val width = 1 shl (info - INFO_FOLLOWS)
            if (blob.size - at < width) throw Cut("!@$start")
            var value = 0L
            repeat(width) { value = (value shl Byte.SIZE_BITS) or (blob[at++].toLong() and BYTE_MASK.toLong()) }
            return value
        }

        private fun skip(
            length: Long,
            start: Int,
        ): Long {
            if (length < 0 || length > blob.size - at) throw Cut("!@$start")
            at += length.toInt()
            return length
        }

        /** Whether an indefinite item ends here, consuming its break. */
        private fun breakNext(): Boolean {
            if (at >= blob.size) throw Cut("!@$at")
            if (blob[at].toInt() and BYTE_MASK != BREAK) return false
            at++
            return true
        }

        private fun more(
            count: Long?,
            seen: Long,
        ): Boolean = if (count == null) !breakNext() else seen < count

        private fun elements(
            count: Long?,
            start: Int,
            depth: Int,
        ): String {
            if (count != null && count < 0) throw Cut("!@$start")
            val runs = mutableListOf<Run>()
            try {
                var seen = 0L
                while (more(count, seen)) {
                    val itemStart = at
                    val shape = item(depth + 1)
                    val last = runs.lastOrNull()
                    if (last != null && last.shape == shape) last.count++ else runs += Run(shape, itemStart)
                    seen++
                }
            } catch (cut: Cut) {
                throw Cut("[" + runs.render() + cut.shape, cut)
            }
            return "[" + runs.render().trimEnd() + "]"
        }

        private fun List<Run>.render(): String =
            withIndex().joinToString("") { (index, run) ->
                run.shape + (if (run.count > 1) "×${run.count}" else "") + (if (index > 0) "@${run.start}" else "") + " "
            }

        private fun entries(
            count: Long?,
            start: Int,
            depth: Int,
        ): String {
            if (count != null && count < 0) throw Cut("!@$start")
            val parts = mutableListOf<String>()
            var pending = ""
            try {
                var seen = 0L
                while (more(count, seen)) {
                    pending = key(depth + 1) + ":"
                    parts += pending + item(depth + 1)
                    pending = ""
                    seen++
                }
            } catch (cut: Cut) {
                throw Cut("{" + (parts + (pending + cut.shape)).joinToString(","), cut)
            }
            return "{" + parts.joinToString(",") + "}"
        }

        /** A text key with its length in the head that is one of [names] reads as itself; any other reads as its shape. */
        private fun key(depth: Int): String {
            val head = blob.getOrNull(at)?.toInt()?.and(BYTE_MASK) ?: throw Cut("!@$at")
            val length = head and INFO_MASK
            if (head ushr MAJOR_SHIFT == MAJOR_TEXT && length < INFO_FOLLOWS && length < blob.size - at) {
                val text = blob.decodeToString(at + 1, at + 1 + length)
                if (text in names) {
                    at += 1 + length
                    return text
                }
            }
            return item(depth)
        }

        private fun tagged(
            tag: Long,
            depth: Int,
        ): String =
            try {
                "#$tag(" + item(depth + 1) + ")"
            } catch (cut: Cut) {
                throw Cut("#$tag(" + cut.shape, cut)
            }

        /** An indefinite byte or text string: definite chunks of the same major type up to a break. */
        private fun chunks(major: Int): String {
            val prefix = if (major == MAJOR_BYTES) "b" else "t"
            var total = 0L
            while (!breakNext()) {
                val chunkStart = at
                val head = blob[at++].toInt() and BYTE_MASK
                val info = head and INFO_MASK
                if (head ushr MAJOR_SHIFT != major || info == INDEFINITE) throw Cut("$prefix$total*!@$chunkStart")
                total += skip(argument(info, chunkStart), chunkStart)
            }
            return "$prefix$total*"
        }
    }
}
