package app.getknit.knit.crash

/**
 * Carries a short text in a crash's stack frames, the one part of a report that always reaches Play. Play keeps every
 * frame but drops the message of an exception the app threw, and a phone that crashes before its first frame cannot
 * open Diagnostics, so for that phone nothing else gets out (ADR 2026-10.47rw's 2026-11-05 amendment).
 *
 * The text becomes frames named `knit.TraceText.t<chunk>(TraceText:<n>)`, a class that does not exist and that no R8
 * map names, so retrace leaves them alone. Every byte of its UTF-8 other than an ASCII letter or digit is `_` and two
 * hex digits, which keeps a chunk inside what any stack trace parser takes for a method name. `scripts/trace-text.py`
 * reads a report back. They go ahead of the real frames, where a reader meets them first and the trace's tail still
 * matches the exception around it, so it prints as `... n more` as it would without them.
 *
 * A frame leaves the phone in every crash report, and [CrashRedactor] passes frames through unread on the strength of
 * their being compiled constants. So [text][carry] must be safe on a Google server as it stands: structure and counts
 * that name nobody ([app.getknit.knit.data.crypto.CborShape]), never a message built from anything a peer or the user
 * wrote.
 */
internal object TraceText {
    /** The frames' class: no such class exists, so nothing can mistake them for code. */
    const val CARRIER = "knit.TraceText"

    /** Longest chunk of escaped text in one frame, well inside the method names Play already shows whole. */
    const val CHUNK_CHARS = 80

    /** Several times what three reads' shapes come to, escaped; a longer text keeps its head. */
    const val MAX_FRAMES = 64

    /** Puts [text] ahead of [throwable]'s own frames and returns it. */
    fun <T : Throwable> carry(
        throwable: T,
        text: String,
    ): T {
        throwable.stackTrace = (frames(text) + throwable.stackTrace).toTypedArray()
        return throwable
    }

    fun frames(text: String): List<StackTraceElement> =
        escape(text).chunked(CHUNK_CHARS).take(MAX_FRAMES).mapIndexed { index, chunk ->
            StackTraceElement(CARRIER, METHOD_PREFIX + chunk, FILE, index + 1)
        }

    private fun escape(text: String): String =
        buildString {
            for (byte in text.encodeToByteArray()) {
                val code = byte.toInt() and BYTE_MASK
                val char = code.toChar()
                if (char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z') {
                    append(char)
                } else {
                    append(ESCAPE).append(code.toString(HEX_RADIX).padStart(ESCAPED_DIGITS, '0'))
                }
            }
        }

    /** Keeps a text that opens with a digit from reading as anything but a method name. */
    private const val METHOD_PREFIX = "t"
    private const val FILE = "TraceText"
    private const val ESCAPE = '_'
    private const val BYTE_MASK = 0xFF
    private const val HEX_RADIX = 16
    private const val ESCAPED_DIGITS = 2
}
