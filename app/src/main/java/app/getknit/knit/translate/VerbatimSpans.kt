package app.getknit.knit.translate

import app.getknit.knit.location.GeoUri
import app.getknit.knit.ui.chat.findUrls

/** A piece of a message body: words a model translates, or a span it must hand back byte for byte. */
sealed interface BodyPiece {
    val text: String

    data class Words(
        override val text: String,
    ) : BodyPiece

    data class Verbatim(
        override val text: String,
    ) : BodyPiece
}

/**
 * The spans of a body a translation must not touch (ADR 2026-10.t2h6): links ([findUrls]), shared positions
 * ([GeoUri.ranges]) and mentions, whose ranges the caller passes because only the row knows who was mentioned. The
 * translator sends the whole body first, which reads best, and checks that each span came back; only when one did not
 * does it fall back to [pieces], translating the words between the spans one run at a time.
 */
object VerbatimSpans {
    /** Every protected span of [body], sorted and merged where they overlap. */
    fun ranges(
        body: String,
        mentions: List<IntRange> = emptyList(),
    ): List<IntRange> {
        val all =
            buildList {
                findUrls(body).forEach { add(it.start until it.end) }
                addAll(GeoUri.ranges(body))
                mentions.filter { it.first >= 0 && it.last < body.length && !it.isEmpty() }.forEach(::add)
            }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in all) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /** The text of each protected span, which a usable translation carries unchanged. */
    fun spans(
        body: String,
        mentions: List<IntRange> = emptyList(),
    ): List<String> = ranges(body, mentions).map { body.substring(it) }

    /** [body] cut at its protected spans, in order. Words that are only whitespace stay [BodyPiece.Verbatim]. */
    fun pieces(
        body: String,
        mentions: List<IntRange> = emptyList(),
    ): List<BodyPiece> {
        val out = mutableListOf<BodyPiece>()
        var cursor = 0

        fun words(until: Int) {
            if (until <= cursor) return
            val text = body.substring(cursor, until)
            out += if (text.isBlank()) BodyPiece.Verbatim(text) else BodyPiece.Words(text)
        }
        for (range in ranges(body, mentions)) {
            words(range.first)
            out += BodyPiece.Verbatim(body.substring(range))
            cursor = range.last + 1
        }
        words(body.length)
        return out
    }

    /** [body] without its protected spans: what the language identifier should read. */
    fun wordsOnly(
        body: String,
        mentions: List<IntRange> = emptyList(),
    ): String = pieces(body, mentions).filterIsInstance<BodyPiece.Words>().joinToString(" ") { it.text.trim() }
}

/**
 * Whether a model's output can be shown (ADR 2026-10.t2h6). The corpus turned up the shape to refuse: "congrats!! so
 * happy for you" came back as twenty-three "¡". A run of one character, or of one word, longer than [MAX_RUN] and
 * longer than any in the source, or an output past [MAX_GROWTH] times the source, is a model stuck in a loop.
 */
object TranslationCheck {
    const val MAX_RUN = 6
    const val MAX_GROWTH = 3
    private const val GROWTH_SLACK = 24

    fun usable(
        source: String,
        output: String,
    ): Boolean {
        if (output.isBlank()) return source.isBlank()
        if (output.length > source.length * MAX_GROWTH + GROWTH_SLACK) return false
        val charRun = longestRun(output.codePoints().toArray().toList())
        if (charRun > MAX_RUN && charRun > longestRun(source.codePoints().toArray().toList())) return false
        val wordRun = longestRun(words(output))
        return !(wordRun > MAX_RUN / 2 && wordRun > longestRun(words(source)))
    }

    private fun words(text: String): List<String> = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun <T> longestRun(items: List<T>): Int {
        var best = 0
        var run = 0
        var previous: T? = null
        for (item in items) {
            run = if (item == previous) run + 1 else 1
            previous = item
            best = maxOf(best, run)
        }
        return best
    }
}
