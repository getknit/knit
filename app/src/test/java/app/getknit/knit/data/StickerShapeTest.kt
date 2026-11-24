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

    private fun be32(value: Int): ByteArray = bytes(value ushr 24, (value ushr 16) and 0xFF, (value ushr 8) and 0xFF, value and 0xFF)

    /** A PNG's signature and IHDR ([colour] type, 8-bit), then [chunks] as (type, length) with zeroed data and CRCs. */
    private fun png(
        width: Int,
        height: Int,
        colour: Int,
        vararg chunks: Pair<String, Int>,
    ): ByteArray {
        var out =
            bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ascii("IHDR") + be32(width) + be32(height) +
                bytes(8, colour, 0, 0, 0) + be32(0)
        for ((type, length) in chunks) out += be32(length) + ascii(type) + ByteArray(length) + be32(0)
        return out
    }

    @Test
    fun aTransparentPngOnTheStickerCanvasIsAStickerSource() {
        // What a keyboard hands the field before the still door: RGBA (Emoji Kitchen's 534 px), grey with alpha.
        assertTrue(StickerShape.isStickerSource(png(534, 534, colour = 6, "IDAT" to 4)))
        assertTrue(StickerShape.isStickerSource(png(420, 420, colour = 4, "IDAT" to 4)))
        // A palette, or RGB, made transparent by tRNS, read past an ICC profile and the palette.
        assertTrue(StickerShape.isStickerSource(png(256, 256, colour = 3, "iCCP" to 456, "PLTE" to 48, "tRNS" to 16, "IDAT" to 4)))
        assertTrue(StickerShape.isStickerSource(png(256, 256, colour = 2, "tRNS" to 6, "IDAT" to 4)))
        // The WebP the header read already takes.
        assertTrue(StickerShape.isStickerSource(vp8x(512, 512, flags = 0x10)))
    }

    @Test
    fun anOpaqueOrLargePngIsNotAStickerSource() {
        assertFalse(StickerShape.isStickerSource(png(256, 256, colour = 2, "IDAT" to 4)))
        assertFalse(StickerShape.isStickerSource(png(256, 256, colour = 3, "PLTE" to 48, "IDAT" to 4)))
        // The walk stops at the first IDAT: a tRNS after it is not the picture's.
        assertFalse(StickerShape.isStickerSource(png(256, 256, colour = 3, "PLTE" to 48, "IDAT" to 4, "tRNS" to 16)))
        assertTrue(StickerShape.isStickerSource(png(640, 640, colour = 6)))
        assertFalse(StickerShape.isStickerSource(png(StickerShape.STICKER_MAX_EDGE + 1, 64, colour = 6)))
        assertFalse(StickerShape.isStickerSource(png(64, StickerShape.STICKER_MAX_EDGE + 1, colour = 6)))
        assertFalse(StickerShape.isStickerSource(png(0, 64, colour = 6)))
        // A width with the top bit set is huge, never negative.
        assertFalse(StickerShape.isStickerSource(png(-1, 64, colour = 6)))
        // A photo straight off the camera is a JPEG.
        assertFalse(StickerShape.isStickerSource(bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(40)))
    }

    @Test
    fun aPngHeadCutShortIsNotAStickerSource() {
        val rgba = png(512, 512, colour = 6, "IDAT" to 4)
        for (cut in 0 until 26) assertFalse("cut at $cut", StickerShape.isStickerSource(rgba.copyOf(cut)))
        assertTrue(StickerShape.isStickerSource(rgba.copyOf(26)))
        // A tRNS past the head the store read: the review opens rather than guess.
        val palette = png(256, 256, colour = 3, "iCCP" to 6000, "tRNS" to 16, "IDAT" to 4)
        assertFalse(StickerShape.isStickerSource(palette.copyOf(4096)))
        assertTrue(StickerShape.isStickerSource(palette))
        // A chunk length running past the end ends the walk.
        val lying = png(256, 256, colour = 3, "PLTE" to 48) + be32(Int.MAX_VALUE) + ascii("iCCP")
        assertFalse(StickerShape.isStickerSource(lying))
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
        // Gboard's Emoji Kitchen, as a Pixel 9 sent it: 534 px, past the 512 px canvas others publish, with an ICC
        // profile beside the alpha flag.
        assertTrue(StickerShape.isSticker(vp8x(534, 534, flags = 0x30)))
        // The edge the ADR names, in literals so a smaller constant goes red: Apple's large sticker, then 640 itself.
        assertTrue(StickerShape.isSticker(vp8x(618, 618, flags = 0x10)))
        assertTrue(StickerShape.isSticker(vp8x(640, 640, flags = 0x10)))
        assertFalse(StickerShape.isSticker(vp8x(641, 64, flags = 0x10)))
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
        assertFalse(StickerShape.isSticker(vp8l(StickerShape.STICKER_MAX_EDGE + 1, 512, alpha = true)))
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
