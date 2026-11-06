package app.getknit.knit.data.crypto

/**
 * The structure of a document `cryptoCbor` wrote, safe to leave the phone in a crash report: what an identity that opens
 * but does not parse can say about itself, enough to tell a span lost or repeated mid-array from a bad build.
 *
 * It walks only what such a document holds, integers, arrays and maps keyed by the caller's [describe] names, and stops
 * at the first head that is anything else, printing nothing of it. That stop is the point. `ByteArray`s are arrays of
 * integers here, so past a lost span the walk can read a key byte as a head, and a head's type and length together are
 * the whole byte; a tag's argument is eight of them (ADR 2026-10.47rw's 2026-11-05 amendment). A head out of step in an
 * array can show only that it read as an integer one or two bytes wide, and an offset how many value bytes before it took
 * one byte to encode, which a short run can narrow to one head's width and no further. The slot after a name can be
 * wider, as `createdAt` has to be.
 *
 * `i` is an integer, `{k:v,…}` a map, and `[…]` an array whose equal neighbours collapse to `shape×n`, every run after the
 * first carrying the offset it starts at. A name inside an array is the next field arriving where the array promised
 * more (`[i×92 sigPriv:[i×154]@203 …`). `!@n` is the offset the walk stopped at, `+n B` bytes left over after the
 * document. The document's size leads.
 */
internal object CborShape {
    fun describe(
        blob: ByteArray,
        names: Set<String>,
    ): String {
        val walk = Walk(blob, names)
        val shape =
            try {
                val document = walk.value(0)
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

    /** A guard: the walk stops too early to reach it on anything but a document that really is this large. */
    private const val MAX_CHARS = 1_000

    // RFC 8949 §3: a head byte is three bits of major type over five of additional information.
    private const val MAJOR_SHIFT = 5
    private const val INFO_MASK = 0x1F
    private const val BYTE_MASK = 0xFF
    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5

    /** The first additional information whose argument follows the head: 24, 25, 26, 27 take 1, 2, 4, 8 bytes. */
    private const val INFO_FOLLOWS = 24
    private const val INFO_LAST_WIDTH = 27

    /** The walk stopped; [shape] is everything up to it, ending in `!@offset`. */
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

        /** The document, or a field's value: an integer, an array or a map. */
        fun value(depth: Int): String {
            val start = at
            return when (major(depth)) {
                MAJOR_UNSIGNED, MAJOR_NEGATIVE -> integer(start)
                MAJOR_ARRAY -> elements(start, depth)
                MAJOR_MAP -> entries(start, depth)
                else -> throw Cut("!@$start")
            }
        }

        /** An array's element: an integer, a map, or a field out of place, read with its value. */
        private fun element(depth: Int): String {
            val start = at
            name()?.let { return field(it, depth) }
            return when (major(depth)) {
                MAJOR_UNSIGNED, MAJOR_NEGATIVE -> byteElement(start)
                MAJOR_MAP -> entries(start, depth)
                else -> throw Cut("!@$start")
            }
        }

        /** An integer in an array is a `ByteArray`'s, two bytes at most; a wider head is out of step, and its width narrows it. */
        private fun byteElement(start: Int): String {
            if (blob[at].toInt() and INFO_MASK > INFO_FOLLOWS) throw Cut("!@$start")
            return integer(start)
        }

        private fun major(depth: Int): Int {
            if (depth > MAX_DEPTH || at >= blob.size) throw Cut("!@$at")
            return (blob[at].toInt() and BYTE_MASK) ushr MAJOR_SHIFT
        }

        private fun integer(start: Int): String {
            argument(start)
            return "i"
        }

        /** Consumes a head, answering its argument; reserved and indefinite lengths stop the walk, as no build writes them. */
        private fun argument(start: Int): Long {
            val info = blob[at++].toInt() and INFO_MASK
            if (info < INFO_FOLLOWS) return info.toLong()
            if (info > INFO_LAST_WIDTH) throw Cut("!@$start")
            val width = 1 shl (info - INFO_FOLLOWS)
            if (blob.size - at < width) throw Cut("!@$start")
            var value = 0L
            repeat(width) { value = (value shl Byte.SIZE_BITS) or (blob[at++].toLong() and BYTE_MASK.toLong()) }
            return value
        }

        private fun elements(
            start: Int,
            depth: Int,
        ): String {
            val count = argument(start)
            if (count < 0) throw Cut("!@$start")
            val runs = mutableListOf<Run>()
            try {
                var seen = 0L
                while (seen < count) {
                    val itemStart = at
                    val shape = element(depth + 1)
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

        /** A map that opens on a name; any other is a head out of step, and even its `{` would say what that byte is. */
        private fun entries(
            start: Int,
            depth: Int,
        ): String {
            val count = argument(start)
            if (count <= 0 || !opensOnName()) throw Cut("!@$start")
            val parts = mutableListOf<String>()
            try {
                var seen = 0L
                while (seen < count) {
                    parts += namedField(depth + 1)
                    seen++
                }
            } catch (cut: Cut) {
                throw Cut("{" + (parts + cut.shape).joinToString(","), cut)
            }
            return "{" + parts.joinToString(",") + "}"
        }

        private fun opensOnName(): Boolean {
            val first = at
            return (name() != null).also { at = first }
        }

        private fun namedField(depth: Int): String {
            val start = at
            return field(name() ?: throw Cut("!@$start"), depth)
        }

        private fun field(
            name: String,
            depth: Int,
        ): String =
            try {
                "$name:" + value(depth)
            } catch (cut: Cut) {
                throw Cut("$name:" + cut.shape, cut)
            }

        /** The name at [at], consumed, or null with nothing consumed: only a text with its length in the head can be one. */
        private fun name(): String? {
            val head = blob.getOrNull(at)?.toInt()?.and(BYTE_MASK) ?: return null
            val length = head and INFO_MASK
            if (head ushr MAJOR_SHIFT != MAJOR_TEXT || length >= INFO_FOLLOWS || length >= blob.size - at) return null
            val text = blob.decodeToString(at + 1, at + 1 + length)
            if (text !in names) return null
            at += 1 + length
            return text
        }
    }
}
