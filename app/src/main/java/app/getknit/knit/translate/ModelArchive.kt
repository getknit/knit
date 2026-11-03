package app.getknit.knit.translate

import app.getknit.knit.call.SttFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration

/**
 * The byte layout of a bundle of translation models over a direct transfer (ADR 2026-11.ejn8): a plain POSIX ustar
 * archive, `<direction id>/<file name>` for every pinned file. An archive because the offer is also an ordinary file
 * offer, and a phone that takes it as a file (an older build, or one without translation) saves something any
 * archive tool opens, as if the files had been picked by hand.
 *
 * Deterministic: every header field is fixed or comes from the pins (mode 0644, no owner, time zero), so the archive
 * of a plan is one byte string. The receiver does not parse what arrives. It writes the archive it expects from its
 * own pins and compares ([segments]), so a header, a padding or a trailer the peer wrote differently is a corrupt
 * bundle, never an instruction. Pure JVM (`ModelArchiveTest`).
 */
object ModelArchive {
    /** The name an offer carries, which a phone that saves it as a file shows. */
    const val FILE_NAME = "knit-translation-models.tar"
    const val MIME = "application/x-tar"

    const val BLOCK = 512
    private const val TRAILER_BLOCKS = 2

    // The ustar header's fields, by offset.
    private const val NAME_BYTES = 100
    private const val MODE_AT = 100
    private const val UID_AT = 108
    private const val GID_AT = 116
    private const val SIZE_AT = 124
    private const val MTIME_AT = 136
    private const val CHECKSUM_DIGITS = 6
    private const val MODE = "0000644"
    private const val ZERO_OCTAL_7 = "0000000"
    private const val ZERO_OCTAL_11 = "00000000000"
    private const val SIZE_DIGITS = 11
    private const val OCTAL = 8
    private const val CHECKSUM_AT = 148
    private const val CHECKSUM_BYTES = 8
    private const val TYPE_AT = 156
    private const val MAGIC_AT = 257
    private const val DEV_MAJOR_AT = 329
    private const val DEV_MINOR_AT = 337

    /** One stretch of the archive: bytes it must hold exactly, or the next piece's data. */
    sealed interface Segment {
        val length: Long

        class Fixed(
            val bytes: ByteArray,
        ) : Segment {
            override val length: Long get() = bytes.size.toLong()
        }

        class Data(
            val piece: Int,
            override val length: Long,
        ) : Segment
    }

    /** A plan's archive in order: header, data and padding per file (pieces numbered across the plan), then the trailer. */
    fun segments(plan: List<Pair<String, List<SttFile>>>): List<Segment> =
        buildList {
            var piece = 0
            for ((dir, files) in plan) {
                for (file in files) {
                    add(Segment.Fixed(header("$dir/${file.name}", file.bytes)))
                    add(Segment.Data(piece++, file.bytes))
                    padding(file.bytes).takeIf { it > 0 }?.let { add(Segment.Fixed(ByteArray(it))) }
                }
            }
            add(Segment.Fixed(ByteArray(TRAILER_BLOCKS * BLOCK)))
        }

    /** What the archive of [plan] costs on the link, from the pins alone. */
    fun size(plan: List<Pair<String, List<SttFile>>>): Long = segments(plan).sumOf { it.length }

    /** The archive of [plan], each file's bytes read through [open] when the stream reaches it. */
    fun stream(
        plan: List<Pair<String, List<SttFile>>>,
        open: (dir: String, file: SttFile) -> InputStream,
    ): InputStream {
        val pieces = plan.flatMap { (dir, files) -> files.map { dir to it } }
        val parts =
            segments(plan).asSequence().map { segment ->
                when (segment) {
                    is Segment.Fixed -> ByteArrayInputStream(segment.bytes)
                    is Segment.Data -> pieces[segment.piece].let { (dir, file) -> open(dir, file) }
                }
            }
        // Lazily, so no file is opened before the stream reaches it.
        val next = parts.iterator()
        return SequenceInputStream(
            object : Enumeration<InputStream> {
                override fun hasMoreElements() = next.hasNext()

                override fun nextElement() = next.next()
            },
        )
    }

    /** A ustar header for a regular file at [path] of [size] bytes, every other field fixed. */
    fun header(
        path: String,
        size: Long,
    ): ByteArray {
        val name = path.toByteArray(Charsets.US_ASCII)
        require(name.size <= NAME_BYTES && path.all { it.code in 0x21..0x7E }) { "an archive path is short plain ASCII" }
        val h = ByteArray(BLOCK)
        name.copyInto(h, 0)
        put(h, MODE_AT, "$MODE\u0000")
        put(h, UID_AT, "$ZERO_OCTAL_7\u0000")
        put(h, GID_AT, "$ZERO_OCTAL_7\u0000")
        put(h, SIZE_AT, size.toString(OCTAL).padStart(SIZE_DIGITS, '0') + "\u0000")
        put(h, MTIME_AT, "$ZERO_OCTAL_11\u0000")
        put(h, CHECKSUM_AT, " ".repeat(CHECKSUM_BYTES))
        h[TYPE_AT] = '0'.code.toByte()
        put(h, MAGIC_AT, "ustar\u000000")
        put(h, DEV_MAJOR_AT, "$ZERO_OCTAL_7\u0000")
        put(h, DEV_MINOR_AT, "$ZERO_OCTAL_7\u0000")
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(h, CHECKSUM_AT, sum.toString(OCTAL).padStart(CHECKSUM_DIGITS, '0') + "\u0000 ")
        return h
    }

    private fun padding(size: Long): Int = ((BLOCK - size % BLOCK) % BLOCK).toInt()

    private fun put(
        h: ByteArray,
        at: Int,
        text: String,
    ) {
        text.toByteArray(Charsets.US_ASCII).copyInto(h, at)
    }
}
