package app.getknit.knit.data

/**
 * Whether an image's bytes hold more than one frame — the one fact that tells a GIF from a photo once both
 * have been through the ingest pipeline. A picked GIF is re-encoded to an animated WebP ([AttachmentStore]),
 * and a transparent PNG or a keyboard sticker becomes a *still* WebP, so the attachment's MIME cannot say
 * which one a message carries; only the frames can.
 *
 * Read from the bytes on both ends — the sender at ingest, the recipient when the blob lands
 * (`InboundPipeline.onObtained`) — exactly as a voice note's waveform is (`VoiceAudio`), so the flag costs
 * no wire field and both ends agree by construction. It drives a label ("GIF" in place of "Photo") and
 * nothing else: a wrong answer mislabels a preview, it never changes what is drawn or sent.
 *
 * Three containers can animate: GIF (a second image descriptor), WebP (the VP8X header's animation flag) and
 * PNG (an APNG `acTL` chunk ahead of the image data, claiming more than one frame). Anything else is still.
 * Every walk is bounds-checked, so truncated or hostile bytes read as still rather than throwing.
 *
 * Pure Kotlin, no Android — JVM-tested in `AnimatedImageTest`.
 */
object AnimatedImage {
    /** The attachment MIMEs whose container can carry more than one frame — the only ones worth a look. */
    val ANIMATABLE_MIMES: Set<String> = setOf("image/gif", "image/webp", "image/png")

    @Suppress("MagicNumber") // the WebP brand sits at a fixed offset in its RIFF header
    fun isAnimated(bytes: ByteArray): Boolean =
        when {
            bytes.startsWith(GIF87A) || bytes.startsWith(GIF89A) -> gifHasSecondFrame(bytes)
            bytes.startsWith(RIFF) && bytes.matchesAt(WEBP, 8) -> webpIsAnimated(bytes)
            bytes.startsWith(PNG) -> pngIsAnimated(bytes)
            else -> false
        }

    /**
     * Walks the GIF block stream until a second image descriptor (animated) or the trailer / the end of the
     * bytes (still). A single-frame GIF with a looping extension is still — the frames are what count.
     */
    @Suppress("MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements") // GIF89a block layout
    private fun gifHasSecondFrame(bytes: ByteArray): Boolean {
        if (bytes.size < GIF_HEADER) return false
        var at = GIF_HEADER
        val screenFlags = bytes.u8(10)
        if (screenFlags and 0x80 != 0) at += colorTableSize(screenFlags)
        var frames = 0
        while (at < bytes.size) {
            when (bytes.u8(at)) {
                0x2C -> {
                    if (++frames > 1) return true
                    if (at + 10 > bytes.size) return false
                    val flags = bytes.u8(at + 9)
                    at += 10
                    if (flags and 0x80 != 0) at += colorTableSize(flags)
                    at += 1 // LZW minimum code size
                    at = skipSubBlocks(bytes, at) ?: return false
                }

                0x21 -> {
                    at = skipSubBlocks(bytes, at + 2) ?: return false // introducer + label
                }

                else -> {
                    return false // the trailer (0x3B), or bytes that are not a block
                }
            }
        }
        return false
    }

    /** A packed field's colour table: 3 bytes per entry, 2^(n+1) entries. */
    @Suppress("MagicNumber") // GIF packed-field layout
    private fun colorTableSize(flags: Int): Int = 3 * (1 shl ((flags and 0x07) + 1))

    /** The offset past a run of GIF data sub-blocks (size byte, data), ending at its zero terminator. */
    private fun skipSubBlocks(
        bytes: ByteArray,
        start: Int,
    ): Int? {
        var at = start
        while (at < bytes.size) {
            val size = bytes.u8(at)
            at += 1 + size
            if (size == 0) return at
        }
        return null
    }

    /** A WebP animates when its first chunk is the extended header (VP8X) with the animation bit set. */
    @Suppress("MagicNumber") // WebP container layout: chunk tag at 12, VP8X flags at 20
    private fun webpIsAnimated(bytes: ByteArray): Boolean = bytes.matchesAt(VP8X, 12) && bytes.size > 20 && bytes.u8(20) and 0x02 != 0

    /**
     * An APNG declares itself with an `acTL` chunk before the first `IDAT`; a plain PNG never carries one.
     * One declared frame is a still picture in APNG clothing, so it takes a frame count above one.
     */
    @Suppress("MagicNumber", "ReturnCount") // PNG chunk layout: length, type, data, CRC
    private fun pngIsAnimated(bytes: ByteArray): Boolean {
        var at = PNG.size
        while (at + 8 <= bytes.size) {
            val length = bytes.u32(at)
            if (length < 0 || length > bytes.size - at) return false // past the end: truncated, or lying
            when {
                bytes.matchesAt(ACTL, at + 4) -> return at + 12 <= bytes.size && bytes.u32(at + 8) > 1
                bytes.matchesAt(IDAT, at + 4) -> return false
            }
            at += 12 + length
        }
        return false
    }

    @Suppress("MagicNumber")
    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    /** A big-endian unsigned 32-bit field, or -1 when it overflows an Int (no real chunk is that long). */
    @Suppress("MagicNumber")
    private fun ByteArray.u32(at: Int): Int {
        val value = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(prefix, 0)

    private fun ByteArray.matchesAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** Signature (6) + logical screen descriptor (7). */
    private const val GIF_HEADER = 13

    private val GIF87A = ascii("GIF87a")
    private val GIF89A = ascii("GIF89a")
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")
    private val VP8X = ascii("VP8X")
    private val ACTL = ascii("acTL")
    private val IDAT = ascii("IDAT")

    @Suppress("MagicNumber") // raw file-signature bytes
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
