package app.getknit.knit.mesh.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * One of the formats a chat body's range can carry (ADR 2026-10.p95v). [code] is its wire value and is
 * append-only: a code this build does not know is dropped on decode and its words are drawn plainly.
 */
@Suppress("MagicNumber") // the codes are the wire values, pinned by StyleSpansTest and the vectors
enum class BodyStyle(
    val code: Int,
) {
    BOLD(0),
    ITALIC(1),
    STRIKETHROUGH(2),
    MONOSPACE(3),
    ;

    companion object {
        /**
         * A spoiler's code, reserved and not built. A receiver without it drops the span and shows the words in the
         * clear, so the style that uses it needs a `CAP_*` send gate, unlike the four above.
         */
        const val SPOILER_RESERVED = 4

        /** The escape: the code is this plus the uvarint that follows, so the codes never run out. */
        const val ESCAPE = 7

        private val byCode = entries.associateBy { it.code }

        /** The style [code] names, or null when this build does not know it. */
        fun ofCode(code: Int): BodyStyle? = byCode[code]
    }
}

/**
 * [style] over the body's UTF-16 units `start until end` (end exclusive), indexed against the body as sent, after the
 * send path's trim and clamp.
 */
data class StyleSpan(
    val start: Int,
    val end: Int,
    val style: BodyStyle,
)

/** The key a layout spends on the styles field, for [StyleSpans.wireCost]. */
enum class StyleKey(
    val bytes: Int,
) {
    /** `MessageContentV2`'s label 17 (`0x11`). */
    LABEL(1),

    /** `"s"` in the named `MessageContent` and in `ChatContent` (`0x61 0x73`). */
    NAMED(2),
}

/**
 * A message's formatting as ranges beside a plain body (ADR 2026-10.p95v): the codec, the normalisation both ports
 * apply, and the range algebra the composer calls. The body never carries markup, so every surface built from it
 * (previews, notifications, quotes, search, moderation, translation) reads the same text with or without styles.
 *
 * **The bytes**, the same in all three layouts: one entry per span, `uvarint(startDelta) uvarint((length << 3) |
 * code)`, where `startDelta` is the start minus the previous entry's start (0 for the first). A uvarint is unsigned
 * LEB128 of at most 3 bytes. Code 7 is the escape: the code is 7 plus the uvarint that follows. A typical span costs
 * 2 bytes.
 *
 * **Decoding never throws** ([unpack]): a truncated or over-long uvarint ends the read and keeps what came before it.
 * The value's CBOR type is frozen as a byte string; only its contents grow.
 *
 * Pure Kotlin, no Android — JVM-tested in `StyleSpansTest`.
 */
object StyleSpans {
    /** Most entries read from one message, and most an encoder writes. */
    const val MAX_SPANS = 64

    private const val UVARINT_MAX_BYTES = 3
    private const val SEVEN = 7
    private const val SEVEN_BITS = 0x7F
    private const val CONTINUE = 0x80
    private const val CODE_BITS = 3
    private const val CODE_MASK = 0x7

    /** The largest value a 3-byte uvarint holds (2²¹ − 1). */
    private const val UVARINT_MAX = (1 shl 21) - 1

    /** The longest span one entry can say: its length shares a uvarint with the 3-bit code. */
    private const val MAX_LENGTH = UVARINT_MAX shr CODE_BITS

    // CBOR's byte-string header: one byte below 24, then a one- or two-byte length after the major-type byte.
    private const val CBOR_TINY = 24
    private const val CBOR_ONE_BYTE = 0x100
    private const val CBOR_TWO_BYTES = 0x10000

    /**
     * The entries of normalised [spans], or null when there are none (the field is then left off the wire, which is
     * what keeps an unformatted message byte-identical to one sent before styles existed). Minimal uvarints. A span
     * whose length or delta no entry can say is left out rather than wrapped, since no body this app sends is long
     * enough to need one.
     */
    fun pack(spans: List<StyleSpan>): ByteArray? {
        if (spans.isEmpty()) return null
        val out = ArrayList<Byte>(spans.size * 2)
        var previous = 0
        for (span in spans.sortedWith(ORDER).take(MAX_SPANS)) {
            val delta = span.start - previous
            val length = span.end - span.start
            if (delta !in 0..UVARINT_MAX || length !in 1..MAX_LENGTH) continue
            writeUvarint(out, delta)
            writeUvarint(out, (length shl CODE_BITS) or span.style.code)
            previous = span.start
        }
        return if (out.isEmpty()) null else out.toByteArray()
    }

    /**
     * The spans [bytes] holds, with the ADR's steps 1 and 2 for codes and lengths: at most [MAX_SPANS] entries read; a
     * truncated uvarint, or a third byte with its high bit set, ends the read and keeps the spans before it; an
     * unknown code or a zero length drops its span but still moves the running start. Bounds against the body wait for
     * [normalize]. Never throws.
     */
    fun unpack(bytes: ByteArray?): List<StyleSpan> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val reader = Reader(bytes)
        val spans = ArrayList<StyleSpan>()
        var start = 0L
        repeat(MAX_SPANS) {
            val delta = reader.uvarint() ?: return spans
            val lengthCode = reader.uvarint() ?: return spans
            var code = lengthCode and CODE_MASK
            if (code == BodyStyle.ESCAPE) code = BodyStyle.ESCAPE + (reader.uvarint() ?: return spans)
            start += delta
            val length = lengthCode shr CODE_BITS
            val style = BodyStyle.ofCode(code)
            // The running start is at most 64 × (2²¹ − 1), so it and its end fit an Int.
            if (style != null && length > 0) spans += StyleSpan(start.toInt(), (start + length).toInt(), style)
        }
        return spans
    }

    /**
     * [spans] made canonical against [body] (the ADR's steps 2 to 6): a span starting at or past the body's end, or
     * empty, is dropped; an end past the body is clipped; a start inside a surrogate pair moves back one unit and an
     * end inside one moves on one; spans of one style that overlap or touch merge; the result is sorted by (start,
     * code) and holds at most [MAX_SPANS]. Never throws, for any body: a lone surrogate at either end is no pair.
     */
    fun normalize(
        body: CharSequence,
        spans: List<StyleSpan>,
    ): List<StyleSpan> {
        if (spans.isEmpty()) return emptyList()
        val length = body.length
        val bounded =
            spans.mapNotNull { span ->
                if (span.start !in 0..<length || span.end <= span.start) return@mapNotNull null
                val end = minOf(span.end, length)
                StyleSpan(snapStart(body, span.start), snapEnd(body, end), span.style)
            }
        return canonical(bounded)
    }

    /**
     * [spans] after the send path dropped [dropLeading] units off the front of the body and cut it to [length]: each
     * shifted left, clipped to the new body, and made canonical again. What the composer's spans become across
     * `trim()` and the clamp, applied by its caller before `sendChat`, which takes spans indexed against the text it
     * is handed.
     */
    fun rebase(
        spans: List<StyleSpan>,
        dropLeading: Int,
        length: Int,
    ): List<StyleSpan> =
        canonical(
            spans.mapNotNull { span ->
                val start = (span.start - dropLeading).coerceAtLeast(0)
                val end = (span.end - dropLeading).coerceAtMost(length)
                if (end > start) StyleSpan(start, end, span.style) else null
            },
        )

    /**
     * [style] toggled over `start until end`: removed there when that style already covers the whole range, else
     * added there. An empty range changes nothing.
     */
    fun toggle(
        spans: List<StyleSpan>,
        style: BodyStyle,
        start: Int,
        end: Int,
    ): List<StyleSpan> {
        if (end <= start) return canonical(spans)
        if (covers(spans, style, start, end)) return remove(spans, start, end) { it == style }
        return canonical(spans + StyleSpan(start, end, style))
    }

    /** [spans] with every style removed over `start until end`; a span reaching past it keeps its outside parts. */
    fun clear(
        spans: List<StyleSpan>,
        start: Int,
        end: Int,
    ): List<StyleSpan> = if (end <= start) canonical(spans) else remove(spans, start, end) { true }

    /** Whether [style] covers every unit of `start until end`. False for an empty range. */
    fun covers(
        spans: List<StyleSpan>,
        style: BodyStyle,
        start: Int,
        end: Int,
    ): Boolean {
        if (end <= start) return false
        var reached = start
        for (span in canonical(spans.filter { it.style == style })) {
            if (span.start > reached) return false
            if (span.end > reached) reached = span.end
            if (reached >= end) return true
        }
        return false
    }

    /**
     * Exactly the bytes [spans] (normalised) add to a message in the layout keyed by [key]: the key, the byte
     * string's header and its entries. Zero when there are none, because the field is then absent. What a LoRa DM's
     * inline-ack room (`inlineAcksFor`, ADR 054) and the composer's LoRa hint subtract.
     */
    fun wireCost(
        spans: List<StyleSpan>,
        key: StyleKey,
    ): Int {
        val size = pack(spans)?.size ?: return 0
        val header =
            when {
                size < CBOR_TINY -> 1
                size < CBOR_ONE_BYTE -> 2
                size < CBOR_TWO_BYTES -> 3
                else -> 5
            }
        return key.bytes + header + size
    }

    private val ORDER = compareBy<StyleSpan>({ it.start }, { it.style.code })

    /** Merges each style's overlapping or touching spans, drops empties, sorts by (start, code), caps the count. */
    private fun canonical(spans: List<StyleSpan>): List<StyleSpan> {
        if (spans.isEmpty()) return emptyList()
        val merged = ArrayList<StyleSpan>(spans.size)
        spans
            .filter { it.end > it.start }
            .groupBy { it.style }
            .forEach { (_, ofStyle) ->
                val sorted = ofStyle.sortedBy { it.start }
                var open = sorted.first()
                for (span in sorted.drop(1)) {
                    if (span.start <= open.end) {
                        open = open.copy(end = maxOf(open.end, span.end))
                    } else {
                        merged += open
                        open = span
                    }
                }
                merged += open
            }
        return merged.sortedWith(ORDER).take(MAX_SPANS)
    }

    /** [spans] with `start until end` cut out of every span whose style [matches]. */
    private inline fun remove(
        spans: List<StyleSpan>,
        start: Int,
        end: Int,
        matches: (BodyStyle) -> Boolean,
    ): List<StyleSpan> =
        canonical(
            spans.flatMap { span ->
                if (!matches(span.style) || span.end <= start || span.start >= end) {
                    listOf(span)
                } else {
                    listOfNotNull(
                        span.copy(end = start).takeIf { it.end > it.start },
                        span.copy(start = end).takeIf { it.end > it.start },
                    )
                }
            },
        )

    /** A pair is a high surrogate at `i - 1` and a low one at `i`, both inside the body. */
    private fun splitsPair(
        body: CharSequence,
        i: Int,
    ): Boolean = i > 0 && i < body.length && body[i - 1].isHighSurrogate() && body[i].isLowSurrogate()

    private fun snapStart(
        body: CharSequence,
        start: Int,
    ): Int = if (splitsPair(body, start)) start - 1 else start

    private fun snapEnd(
        body: CharSequence,
        end: Int,
    ): Int = if (splitsPair(body, end)) end + 1 else end

    private fun writeUvarint(
        out: MutableList<Byte>,
        value: Int,
    ) {
        var rest = value
        while (rest > SEVEN_BITS) {
            out += ((rest and SEVEN_BITS) or CONTINUE).toByte()
            rest = rest ushr SEVEN
        }
        out += rest.toByte()
    }

    /** Reads uvarints off [bytes]; null at a truncated one or a third byte that asks for a fourth. */
    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var at = 0

        fun uvarint(): Int? {
            var value = 0
            for (i in 0 until UVARINT_MAX_BYTES) {
                if (at >= bytes.size) return null
                val b = bytes[at++].toInt() and 0xFF
                value = value or ((b and SEVEN_BITS) shl (SEVEN * i))
                if (b and CONTINUE == 0) return value
            }
            return null
        }
    }
}

/**
 * The styles field as its one CBOR byte string ([StyleSpans.pack] / [StyleSpans.unpack]). Used with `@ByteString` on
 * `ChatContent.styles` and `MessageContent.styles`: the inner value goes through [ByteArraySerializer], which the
 * property's annotation makes a byte string (major type 2), never an array of integers. Its own descriptor on purpose:
 * kotlinx's CBOR writer casts a value to `ByteArray` when the serializer's descriptor equals [ByteArraySerializer]'s,
 * which a list would fail. An empty list is the property's default and so never reaches the writer
 * (`encodeDefaults = false`).
 *
 * A value of another CBOR type at the key throws on decode, as any typed field does (the ADR's frozen-type rule):
 * kotlinx-serialization-cbor 1.12 has no public way to skip an arbitrary item from inside a serializer.
 */
object StyleRunsSerializer : KSerializer<List<StyleSpan>> {
    private val bytes = ByteArraySerializer()

    override val descriptor: SerialDescriptor = SerialDescriptor("app.getknit.knit.StyleRuns", bytes.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: List<StyleSpan>,
    ) = encoder.encodeSerializableValue(bytes, StyleSpans.pack(value) ?: ByteArray(0))

    override fun deserialize(decoder: Decoder): List<StyleSpan> = StyleSpans.unpack(decoder.decodeSerializableValue(bytes))
}
