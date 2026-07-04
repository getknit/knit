package app.getknit.knit.data.webp

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for [AnimatedWebpMuxer]: feed it synthetic single-frame WebP byte arrays (the shape
 * `Bitmap.compress(WEBP_LOSSY)` produces) and parse the muxed output back, asserting a spec-valid
 * `RIFF…WEBP` file with a `VP8X`, an `ANIM`, and one `ANMF` per frame. No Android — pure bytes.
 */
class AnimatedWebpMuxerTest {

    @Test
    fun `muxes opaque frames into a valid animated webp`() {
        val frames = listOf(opaqueFrame(byteArrayOf(1, 2, 3, 4)), opaqueFrame(byteArrayOf(5, 6, 7, 8)))
        val out = AnimatedWebpMuxer.mux(frames, intArrayOf(66, 100), canvasW = 480, canvasH = 270)
        assertNotNull(out)
        val chunks = parse(out!!)

        assertEquals("VP8X", chunks[0].tag)
        assertEquals(10, chunks[0].payload.size)
        assertTrue("animation flag set", chunks[0].payload[0].toInt() and 0x02 == 0x02)
        assertFalse("no alpha flag", chunks[0].payload[0].toInt() and 0x10 == 0x10)
        assertEquals(479, u24(chunks[0].payload, 4)) // canvas width minus one
        assertEquals(269, u24(chunks[0].payload, 7)) // canvas height minus one

        assertEquals("ANIM", chunks[1].tag)
        assertEquals(0, u16(chunks[1].payload, 4)) // loop forever

        val anmf = chunks.drop(2)
        assertEquals(2, anmf.size)
        assertTrue(anmf.all { it.tag == "ANMF" })
        assertEquals(66, u24(anmf[0].payload, 12))  // frame 0 duration
        assertEquals(100, u24(anmf[1].payload, 12)) // frame 1 duration
    }

    @Test
    fun `keeps the VP8 bitstream and drops per-frame VP8X and ICCP metadata`() {
        // What Bitmap.compress(WEBP_LOSSY) emits when it writes an ICC profile: VP8X + ICCP + VP8.
        val frame = webpFile(chunk("VP8X", ByteArray(10)), chunk("ICCP", ByteArray(456)), chunk("VP8 ", byteArrayOf(9, 9, 9, 9)))
        val anmf = parse(AnimatedWebpMuxer.mux(listOf(frame), intArrayOf(50), 8, 8)!!).single { it.tag == "ANMF" }
        val sub = parse(anmf.payload.copyOfRange(ANMF_HEADER, anmf.payload.size), fromRiff = false)
        assertTrue("frame keeps its VP8 bitstream", sub.any { it.tag == "VP8 " })
        assertFalse("per-frame VP8X is dropped", sub.any { it.tag == "VP8X" })
        assertFalse("per-frame ICCP profile is dropped (spec-invalid inside ANMF)", sub.any { it.tag == "ICCP" })
        assertEquals("only the image chunk remains", 1, sub.size)
        assertArrayEqualsMsg(byteArrayOf(9, 9, 9, 9), sub.single { it.tag == "VP8 " }.payload)
    }

    @Test
    fun `sets the top-level alpha flag when a frame carries ALPH`() {
        val frames = listOf(
            opaqueFrame(byteArrayOf(1, 1)),
            alphaFrame(alph = byteArrayOf(7, 7, 7), vp8 = byteArrayOf(2, 2)),
        )
        val chunks = parse(AnimatedWebpMuxer.mux(frames, intArrayOf(40, 40), 16, 16)!!)
        assertTrue("alpha flag set", chunks[0].payload[0].toInt() and 0x10 == 0x10)
        val alphaAnmf = chunks.last()
        val sub = parse(alphaAnmf.payload.copyOfRange(ANMF_HEADER, alphaAnmf.payload.size), fromRiff = false)
        assertTrue("ALPH carried into the frame", sub.any { it.tag == "ALPH" })
    }

    @Test
    fun `pads an odd-length bitstream and keeps the file even`() {
        // Odd (3-byte) VP8 payload forces a pad byte; the whole file must still parse cleanly.
        val out = AnimatedWebpMuxer.mux(listOf(opaqueFrame(byteArrayOf(1, 2, 3))), intArrayOf(30), 4, 4)
        assertNotNull(out)
        assertEquals("file length is even", 0, out!!.size and 1)
        val anmf = parse(out).single { it.tag == "ANMF" }
        val sub = parse(anmf.payload.copyOfRange(ANMF_HEADER, anmf.payload.size), fromRiff = false)
        assertEquals(3, sub.single { it.tag == "VP8 " }.payload.size) // size field excludes the pad
    }

    @Test
    fun `rejects inconsistent input`() {
        assertNull("no frames", AnimatedWebpMuxer.mux(emptyList(), intArrayOf(), 8, 8))
        assertNull(
            "frame/duration count mismatch",
            AnimatedWebpMuxer.mux(listOf(opaqueFrame(byteArrayOf(1, 2))), intArrayOf(10, 20), 8, 8),
        )
        assertNull(
            "non-positive canvas",
            AnimatedWebpMuxer.mux(listOf(opaqueFrame(byteArrayOf(1, 2))), intArrayOf(10), 0, 8),
        )
        assertNull(
            "unparseable frame",
            AnimatedWebpMuxer.mux(listOf(byteArrayOf(0, 1, 2, 3)), intArrayOf(10), 8, 8),
        )
    }

    // --- helpers: build synthetic single-frame WebP files and parse RIFF chunk lists ---

    private fun opaqueFrame(vp8: ByteArray): ByteArray = webpFile(chunk("VP8 ", vp8))

    private fun alphaFrame(alph: ByteArray, vp8: ByteArray): ByteArray =
        webpFile(chunk("VP8X", ByteArray(10)), chunk("ALPH", alph), chunk("VP8 ", vp8))

    private fun webpFile(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(leU32(b.size))
        out.write(b)
        return out.toByteArray()
    }

    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(leU32(payload.size))
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private class Chunk(val tag: String, val payload: ByteArray)

    /** Walks a RIFF chunk list; [fromRiff] true skips the 12-byte RIFF/WEBP header first. */
    private fun parse(bytes: ByteArray, fromRiff: Boolean = true): List<Chunk> {
        var pos = 0
        if (fromRiff) {
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("RIFF size = file - 8", bytes.size - 8, leU32read(bytes, 4))
            assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            pos = 12
        }
        val chunks = mutableListOf<Chunk>()
        while (pos + 8 <= bytes.size) {
            val tag = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leU32read(bytes, pos + 4)
            chunks.add(Chunk(tag, bytes.copyOfRange(pos + 8, pos + 8 + size)))
            pos += 8 + size + (size and 1)
        }
        assertEquals("chunks consumed exactly", bytes.size, pos)
        return chunks
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray) =
        assertTrue("bitstream preserved", expected.contentEquals(actual))

    private fun leU32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun leU32read(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u24(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16)

    private companion object {
        const val ANMF_HEADER = 16 // frame X/Y/W/H/duration (5×u24) + 1 flags byte
    }
}
