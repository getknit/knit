package app.getknit.knit.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame count behind the chat list's "GIF" label. The load-bearing case is WebP: a picked GIF is stored
 * as an animated WebP and a transparent sticker as a still one, under the same MIME, so only the VP8X flag
 * tells them apart.
 */
class AnimatedImageTest {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** A GIF89a with no global colour table and [frames] 1×1 image descriptors, each with a GCE ahead of it. */
    private fun gif(frames: Int): ByteArray {
        val header = ascii("GIF89a") + bytes(1, 0, 1, 0, 0x00, 0, 0)
        val loop = bytes(0x21, 0xFF, 11) + ascii("NETSCAPE2.0") + bytes(3, 1, 0, 0, 0)
        val gce = bytes(0x21, 0xF9, 4, 0, 10, 0, 0, 0)
        val image = bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x00, 2, 2, 0x4C, 0x01, 0)
        var out = header + loop
        repeat(frames) { out += gce + image }
        return out + bytes(0x3B)
    }

    private fun webp(flags: Int): ByteArray =
        ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP") + ascii("VP8X") + bytes(10, 0, 0, 0, flags, 0, 0, 0)

    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray = bytes(0, 0, 0, data.size) + ascii(type) + data + bytes(0, 0, 0, 0)

    private fun png(acTLFrames: Int?): ByteArray {
        var out = pngSignature + chunk("IHDR", ByteArray(13))
        if (acTLFrames != null) out += chunk("acTL", bytes(0, 0, 0, acTLFrames, 0, 0, 0, 0))
        return out + chunk("IDAT", ByteArray(4)) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun aGifIsAnimatedOnlyFromItsSecondFrame() {
        assertTrue(AnimatedImage.isAnimated(gif(frames = 2)))
        // A looping extension on one frame is still a still picture: frames count, not the loop.
        assertFalse(AnimatedImage.isAnimated(gif(frames = 1)))
    }

    @Test
    fun aGifsGlobalColourTableIsSkippedToReachItsFrames() {
        // Global colour table flag set, size field 0 → 2 entries, 6 bytes.
        val header = ascii("GIF89a") + bytes(1, 0, 1, 0, 0x80, 0, 0) + ByteArray(6)
        val image = bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x00, 2, 2, 0x4C, 0x01, 0)
        assertTrue(AnimatedImage.isAnimated(header + image + image + bytes(0x3B)))
    }

    @Test
    fun aWebpAnimatesOnlyWithTheVp8xAnimationFlag() {
        assertTrue(AnimatedImage.isAnimated(webp(flags = 0x02)))
        assertTrue(AnimatedImage.isAnimated(webp(flags = 0x12))) // animation + alpha
        // A transparent sticker: the extended header with only the alpha flag.
        assertFalse(AnimatedImage.isAnimated(webp(flags = 0x10)))
        // A simple lossy WebP carries no VP8X at all.
        assertFalse(AnimatedImage.isAnimated(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP") + ascii("VP8 ") + ByteArray(16)))
    }

    @Test
    fun aPngAnimatesOnlyWithAnAcTLClaimingMoreThanOneFrame() {
        assertTrue(AnimatedImage.isAnimated(png(acTLFrames = 3)))
        assertFalse(AnimatedImage.isAnimated(png(acTLFrames = 1)))
        assertFalse(AnimatedImage.isAnimated(png(acTLFrames = null)))
    }

    @Test
    fun truncatedOrHostileBytesReadAsStill() {
        val two = gif(frames = 2)
        for (cut in two.indices) AnimatedImage.isAnimated(two.copyOf(cut)) // never throws
        assertFalse(AnimatedImage.isAnimated(ByteArray(0)))
        assertFalse(AnimatedImage.isAnimated(ascii("GIF89a")))
        assertFalse(AnimatedImage.isAnimated(webp(flags = 0x02).copyOf(20)))
        // A chunk length far past the end of the bytes (and past Int.MAX_VALUE) must not walk off the array.
        assertFalse(AnimatedImage.isAnimated(pngSignature + bytes(0x7F, 0xFF, 0xFF, 0xF0) + ascii("tEXt")))
        assertFalse(AnimatedImage.isAnimated(pngSignature + bytes(0xFF, 0xFF, 0xFF, 0xFF) + ascii("tEXt")))
        // A JPEG never animates.
        assertFalse(AnimatedImage.isAnimated(bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(32)))
    }
}
