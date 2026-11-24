package app.getknit.knit.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header read behind a sticker's bubble-less look (ADR 2026-11.rk6d): a WebP with transparency no larger than
 * [StickerShape.STICKER_MAX_EDGE]. The headers are built by hand, byte for byte, so each case names the one field it
 * turns; `AttachmentStoreIngestTest` runs the same read on what the platform encoder writes.
 */
class StickerShapeTest {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun le24(value: Int): ByteArray = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF)

    private val riff = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")

    /** The extended header: [flags] (alpha `0x10`, animation `0x02`) and a [width] × [height] canvas. */
    private fun vp8x(
        width: Int,
        height: Int,
        flags: Int,
    ): ByteArray = riff + ascii("VP8X") + bytes(10, 0, 0, 0, flags, 0, 0, 0) + le24(width - 1) + le24(height - 1)

    /** The lossless header: signature `0x2F`, then 14 + 14 bits of size less one and the alpha-is-used bit. */
    private fun vp8l(
        width: Int,
        height: Int,
        alpha: Boolean,
    ): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        return riff + ascii("VP8L") + bytes(5, 0, 0, 0, 0x2F) +
            bytes(bits and 0xFF, (bits shr 8) and 0xFF, (bits shr 16) and 0xFF, (bits shr 24) and 0xFF)
    }

    @Test
    fun aTransparentWebpOnTheStickerCanvasIsASticker() {
        // What the still door writes for a keyboard sticker: lossy VP8 with an ALPH chunk, so the alpha flag is set.
        assertTrue(StickerShape.isSticker(vp8x(512, 512, flags = 0x10)))
        // An animated sticker passed through stripped keeps its extended header.
        assertTrue(StickerShape.isSticker(vp8x(512, 512, flags = 0x12)))
        // Smaller and not square still counts: a wide text sticker, an emoji-sized one.
        assertTrue(StickerShape.isSticker(vp8x(512, 200, flags = 0x10)))
        assertTrue(StickerShape.isSticker(vp8x(1, 1, flags = 0x10)))
    }

    @Test
    fun aWebpWithoutTransparencyIsAPhoto() {
        // An animated GIF made a WebP, opaque: a GIF, not a sticker.
        assertFalse(StickerShape.isSticker(vp8x(480, 480, flags = 0x02)))
        assertFalse(StickerShape.isSticker(vp8x(512, 512, flags = 0x00)))
        // A plain `VP8 ` chunk carries no alpha at all.
        assertFalse(StickerShape.isSticker(riff + ascii("VP8 ") + ByteArray(20)))
    }

    @Test
    fun aCanvasPastTheStickerEdgeIsAPhoto() {
        assertTrue(StickerShape.isSticker(vp8x(StickerShape.STICKER_MAX_EDGE, 64, flags = 0x10)))
        assertFalse(StickerShape.isSticker(vp8x(StickerShape.STICKER_MAX_EDGE + 1, 64, flags = 0x10)))
        assertFalse(StickerShape.isSticker(vp8x(64, StickerShape.STICKER_MAX_EDGE + 1, flags = 0x10)))
        // A transparent picture at the photo door's 1280 px is a cut-out or a logo, drawn as the photo it was sent as.
        assertFalse(StickerShape.isSticker(vp8x(1280, 1280, flags = 0x10)))
    }

    @Test
    fun aLosslessWebpIsReadFromItsOwnHeader() {
        assertTrue(StickerShape.isSticker(vp8l(512, 512, alpha = true)))
        assertFalse(StickerShape.isSticker(vp8l(512, 512, alpha = false)))
        assertFalse(StickerShape.isSticker(vp8l(513, 512, alpha = true)))
    }

    @Test
    fun truncatedOrForeignBytesAreNotAStickerAndNeverThrow() {
        val whole = vp8x(512, 512, flags = 0x10)
        for (cut in whole.indices) assertFalse("cut at $cut", StickerShape.isSticker(whole.copyOf(cut)))
        val lossless = vp8l(512, 512, alpha = true)
        for (cut in lossless.indices) assertFalse("cut at $cut", StickerShape.isSticker(lossless.copyOf(cut)))
        // A lossless chunk without its signature byte is not read as one.
        val unsigned = lossless.copyOf().also { it[20] = 0 }
        assertFalse(StickerShape.isSticker(unsigned))
        assertFalse(StickerShape.isSticker(ByteArray(0)))
        assertFalse(StickerShape.isSticker(ascii("GIF89a") + ByteArray(40)))
        assertFalse(StickerShape.isSticker(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)))
    }
}
