package app.getknit.knit.data

/**
 * Whether an image's bytes have a sticker's shape: a WebP with transparency whose canvas is no larger than
 * [STICKER_MAX_EDGE] on its long side (ADR 2026-11.rk6d). The chat draws such a picture without a bubble and offers to
 * save it, and the chat list calls it a sticker.
 *
 * Every sticker reaches the wire as a WebP. A keyboard's still sticker (a transparent PNG, or a lossless WebP) goes
 * through the still door and comes out a lossy WebP that keeps its alpha ([AttachmentStore]), and a sticker-sized
 * animated WebP passes through stripped (ADR 2026-10.8hmx). A photo never has transparency: the still door writes a
 * JPEG for an opaque picture, and libwebp writes a plain `VP8 ` chunk, with no alpha, for a WebP with none. So the
 * container header says all of it, and nothing is decoded.
 *
 * Read from the bytes on both ends, as [AnimatedImage] is — the sender at ingest, the recipient when the blob lands
 * (`InboundPipeline.onObtained`) — so the flag costs no wire field and both ends agree by construction. It changes
 * how a picture is drawn and labelled and nothing else: a wrong answer draws a photo as a sticker or the other way
 * round, it never changes what is stored or sent.
 *
 * Every read is bounds-checked, so truncated or hostile bytes read as not a sticker rather than throwing.
 *
 * Pure Kotlin, no Android — JVM-tested in `StickerShapeTest`.
 */
object StickerShape {
    /**
     * The longest canvas edge a sticker has (ADR 2026-11.rk6d's 2026-11-23 edge amendment). WhatsApp and Telegram
     * publish a 512 px canvas, but Gboard's Emoji Kitchen writes 534 px (measured on a Pixel 9), and Apple's large
     * Messages sticker is 618 px; 640 takes all three. A transparent picture past it is a cut-out or a logo, drawn as
     * the photo it was sent as.
     */
    const val STICKER_MAX_EDGE = 640

    @Suppress("MagicNumber") // the WebP brand sits at a fixed offset in its RIFF header
    fun isSticker(bytes: ByteArray): Boolean {
        if (!bytes.matchesAt(RIFF, 0) || !bytes.matchesAt(WEBP, 8)) return false
        val shape =
            when {
                bytes.matchesAt(VP8X, 12) -> extended(bytes)
                bytes.matchesAt(VP8L, 12) -> lossless(bytes)
                else -> null // a plain `VP8 ` chunk carries no alpha
            } ?: return false
        return shape.alpha && shape.width in 1..STICKER_MAX_EDGE && shape.height in 1..STICKER_MAX_EDGE
    }

    /**
     * Whether a picture still on its way in has a sticker's shape, read off the source's first bytes before the ingest:
     * [isSticker]'s WebP, or a PNG with transparency (an alpha channel, or a `tRNS` chunk before the first `IDAT`) no
     * larger than [STICKER_MAX_EDGE]. Either comes out of the still door as the WebP [isSticker] accepts. It decides
     * whether a keyboard's picture skips the Cover faces review (ADR 2026-11.rk6d's 2026-11-23 review amendment), so a
     * header cut short, or a `tRNS` past the bytes given, reads as not a sticker: the review opens.
     */
    fun isStickerSource(head: ByteArray): Boolean = isSticker(head) || isTransparentPng(head)

    @Suppress("MagicNumber", "ReturnCount") // PNG layout: IHDR's size and colour type, then the chunk walk
    private fun isTransparentPng(bytes: ByteArray): Boolean {
        if (!bytes.matchesAt(PNG, 0) || !bytes.matchesAt(IHDR, 12) || bytes.size < 26) return false
        val width = bytes.u32(16)
        val height = bytes.u32(20)
        if (width !in 1L..STICKER_MAX_EDGE.toLong() || height !in 1L..STICKER_MAX_EDGE.toLong()) return false
        // Grey with alpha (4) and RGBA (6) carry an alpha channel; grey, RGB and palette (0, 2, 3) only through tRNS.
        val colour = bytes.u8(25)
        if (colour == 4 || colour == 6) return true
        var at = 8L
        while (at + 8 <= bytes.size) {
            val chunk = at.toInt()
            when {
                bytes.matchesAt(TRNS, chunk + 4) -> return true
                bytes.matchesAt(IDAT, chunk + 4) -> return false
            }
            at += 12 + bytes.u32(chunk)
        }
        return false
    }

    private class Shape(
        val width: Int,
        val height: Int,
        val alpha: Boolean,
    )

    /** The extended header: flags at 20 (alpha is `0x10`), then the canvas as two 24-bit little-endian sizes less one. */
    @Suppress("MagicNumber") // VP8X layout
    private fun extended(bytes: ByteArray): Shape? {
        if (bytes.size < 30) return null
        return Shape(
            width = bytes.u24(24) + 1,
            height = bytes.u24(27) + 1,
            alpha = bytes.u8(20) and 0x10 != 0,
        )
    }

    /**
     * The lossless header: the signature byte `0x2F` at 20, then 14 bits of width less one, 14 of height less one and
     * the alpha-is-used bit, little-endian.
     */
    @Suppress("MagicNumber") // VP8L layout
    private fun lossless(bytes: ByteArray): Shape? {
        if (bytes.size < 25 || bytes.u8(20) != 0x2F) return null
        val bits = bytes.u8(21) or (bytes.u8(22) shl 8) or (bytes.u8(23) shl 16) or (bytes.u8(24) shl 24)
        return Shape(
            width = (bits and 0x3FFF) + 1,
            height = ((bits ushr 14) and 0x3FFF) + 1,
            alpha = (bits ushr 28) and 1 != 0,
        )
    }

    @Suppress("MagicNumber")
    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    @Suppress("MagicNumber")
    private fun ByteArray.u24(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)

    /** A big-endian unsigned 32-bit value, as PNG writes its sizes; a Long so the top bit is never a sign. */
    @Suppress("MagicNumber")
    private fun ByteArray.u32(at: Int): Long =
        (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

    private fun ByteArray.matchesAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")
    private val VP8X = ascii("VP8X")
    private val VP8L = ascii("VP8L")

    @Suppress("MagicNumber")
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val IHDR = ascii("IHDR")
    private val TRNS = ascii("tRNS")
    private val IDAT = ascii("IDAT")
}
