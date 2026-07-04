package app.getknit.knit.data.webp

import java.io.ByteArrayOutputStream

/**
 * Muxes a sequence of **single-frame** WebP files (each the output of
 * `android.graphics.Bitmap.compress(WEBP_LOSSY, …)`) into one **animated** WebP, by writing the
 * RIFF container (`VP8X` + `ANIM` + one `ANMF` per frame) by hand. Android can *decode/play* animated
 * WebP (Coil's `AnimatedImageDecoder`) but has no API to *encode* one — this fills that gap in pure
 * JVM, so [WebpTranscode] can lean on the built-in per-frame VP8 encoder (far better than GIF's
 * 256-colour LZW) without an NDK/native `libwebp`.
 *
 * The class is deliberately Android-free (byte manipulation only) so it is JVM-unit-testable — see
 * `AnimatedWebpMuxerTest`. It copies each frame's image sub-chunks (`ALPH`?/`VP8 `) verbatim into an
 * `ANMF`, dropping the per-frame `VP8X` (the animation carries a single top-level one). Frames are
 * full-canvas, overwrite-blended (`ANMF` blending = "do not blend"), so each fully-composited frame
 * from `Movie.draw` simply replaces the canvas — no disposal/background bookkeeping.
 *
 * All multi-byte fields are little-endian, and every RIFF chunk is padded to an even length, per the
 * [WebP container spec](https://developers.google.com/speed/webp/docs/riff_container).
 */
@Suppress("MagicNumber") // RIFF/WebP byte offsets, chunk sizes, and flag bit masks are format constants.
object AnimatedWebpMuxer {

    /** VP8X feature flag: the file is an animation (an `ANIM`/`ANMF` sequence follows). */
    private const val VP8X_ANIMATION = 0x02

    /** VP8X feature flag: at least one frame carries alpha (an `ALPH` chunk). */
    private const val VP8X_ALPHA = 0x10

    /** ANMF flags: blending = "do not blend" (overwrite), disposal = "none" — full-frame overwrite. */
    private const val ANMF_OVERWRITE = 0x02

    /** `ANIM` loop count meaning "loop forever" (matches a typical GIF). */
    const val LOOP_FOREVER = 0

    /**
     * Assembles [frames] (single-frame WebP byte arrays, one per animation frame, all [canvasW]×[canvasH])
     * with per-frame on-screen times [durationsMs] into one animated WebP. Returns null if the inputs are
     * inconsistent or any frame can't be parsed as a WebP.
     */
    fun mux(
        frames: List<ByteArray>,
        durationsMs: IntArray,
        canvasW: Int,
        canvasH: Int,
        loopCount: Int = LOOP_FOREVER,
    ): ByteArray? {
        if (frames.isEmpty() || frames.size != durationsMs.size) return null
        if (canvasW <= 0 || canvasH <= 0) return null

        val anmf = ByteArrayOutputStream()
        var hasAlpha = false
        for (i in frames.indices) {
            val image = extractImageChunks(frames[i]) ?: return null
            hasAlpha = hasAlpha || image.hasAlpha
            val payload = ByteArrayOutputStream()
            writeU24(payload, 0)               // frame X (÷2) — full-canvas, so 0
            writeU24(payload, 0)               // frame Y (÷2)
            writeU24(payload, canvasW - 1)     // frame width minus one
            writeU24(payload, canvasH - 1)     // frame height minus one
            writeU24(payload, durationsMs[i].coerceAtLeast(0))
            payload.write(ANMF_OVERWRITE)
            payload.write(image.chunks)        // ALPH?/VP8 sub-chunks, verbatim (already even-padded)
            writeChunk(anmf, "ANMF", payload.toByteArray())
        }

        val body = ByteArrayOutputStream()
        writeChunk(body, "VP8X", vp8x(canvasW, canvasH, hasAlpha))
        writeChunk(body, "ANIM", anim(loopCount))
        body.write(anmf.toByteArray())

        val bodyBytes = body.toByteArray()
        val file = ByteArrayOutputStream()
        file.write(ASCII_RIFF)
        writeU32(file, (ASCII_WEBP.size + bodyBytes.size).toLong()) // RIFF size = "WEBP" + body
        file.write(ASCII_WEBP)
        file.write(bodyBytes)
        return file.toByteArray()
    }

    /** The 10-byte `VP8X` payload: feature flags + 3 reserved bytes + canvas W-1/H-1. */
    private fun vp8x(canvasW: Int, canvasH: Int, hasAlpha: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VP8X_ANIMATION or if (hasAlpha) VP8X_ALPHA else 0)
        out.write(0); out.write(0); out.write(0) // reserved
        writeU24(out, canvasW - 1)
        writeU24(out, canvasH - 1)
        return out.toByteArray()
    }

    /** The 6-byte `ANIM` payload: background colour (BGRA, transparent) + loop count. */
    private fun anim(loopCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0)) // background colour — transparent
        writeU16(out, loopCount)
        return out.toByteArray()
    }

    private class ImageChunks(val chunks: ByteArray, val hasAlpha: Boolean)

    /**
     * Returns the `ALPH`?/`VP8 `/`VP8L` sub-chunks (verbatim, with their padding) from a single-frame
     * WebP. Only those image chunks are kept — a spec-valid `ANMF` frame carries **nothing else**, so
     * top-level metadata the per-frame encoder adds (`VP8X`, and the `ICCP` colour profile Android's
     * `Bitmap.compress` emits) is dropped; leaving `ICCP` inside an `ANMF` makes decoders reject the
     * frame. Null if [webp] isn't a well-formed `RIFF…WEBP` file with an image chunk.
     */
    private fun extractImageChunks(webp: ByteArray): ImageChunks? {
        if (webp.size < HEADER_LEN) return null
        if (fourCC(webp, 0) != "RIFF" || fourCC(webp, 8) != "WEBP") return null

        val out = ByteArrayOutputStream()
        var hasAlpha = false
        var pos = HEADER_LEN
        while (pos + CHUNK_HEADER_LEN <= webp.size) {
            val tag = fourCC(webp, pos)
            val size = readU32(webp, pos + 4)
            if (size < 0) return null
            val full = CHUNK_HEADER_LEN + size + (size and 1) // include even-padding
            if (full < 0 || pos + full > webp.size) return null
            if (tag == "VP8 " || tag == "VP8L" || tag == "ALPH") {
                out.write(webp, pos, full)
                if (tag == "ALPH") hasAlpha = true
            }
            pos += full
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else ImageChunks(bytes, hasAlpha)
    }

    private fun writeChunk(out: ByteArrayOutputStream, tag: String, payload: ByteArray) {
        out.write(tag.toByteArray(Charsets.US_ASCII))
        writeU32(out, payload.size.toLong())
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0) // pad to an even length
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeU24(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun fourCC(b: ByteArray, off: Int): String = String(b, off, 4, Charsets.US_ASCII)

    private const val HEADER_LEN = 12       // "RIFF" + size + "WEBP"
    private const val CHUNK_HEADER_LEN = 8   // fourCC + size

    private val ASCII_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val ASCII_WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
}
