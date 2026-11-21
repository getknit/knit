package app.getknit.knit.data.webp

import android.graphics.Bitmap
import android.graphics.Color
import app.getknit.knit.data.decodeBoundedFromBytes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Animations made in the test, so the WebP door needs no asset: a GIF written by hand (the platform has no GIF encoder)
 * and an APNG assembled from the platform's own PNG encodes, each frame marked with a colour the output is checked for.
 */
object AnimationFixtures {
    /** The colour at the centre of GIF frame `i`. */
    val GIF_MARKS = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)

    /**
     * A looping [width] × [height] GIF of one frame per [GIF_MARKS] entry, each [delayMs] long: a grey gradient with a
     * [mark]-sized square of the frame's colour at its centre. The image data is never compressed ([lzw]), so the GIF
     * is as large as a camera clip's or a keyboard's for its size; the gradient is what a real picture's smooth areas
     * are to VP8. (Per-pixel noise would be incompressible to any codec, and the 15 fps resample triples the frames.)
     */
    fun gif(
        width: Int = 320,
        height: Int = 240,
        delayMs: Int = 200,
        mark: Int = 100,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.u16(width)
        out.u16(height)
        out.write(GLOBAL_TABLE_256)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        out.write(palette())
        // NETSCAPE2.0: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        for (frame in GIF_MARKS.indices) {
            // Graphic control: dispose to none, the delay in hundredths, no transparency.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x04))
            out.u16(delayMs / 10)
            out.write(byteArrayOf(0x00, 0x00))
            // Image descriptor: the whole canvas, no local table.
            out.write(0x2C)
            out.u16(0)
            out.u16(0)
            out.u16(width)
            out.u16(height)
            out.write(0)
            val indices = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inMark = x in (width - mark) / 2 until (width + mark) / 2 && y in (height - mark) / 2 until (height + mark) / 2
                    val grey = GREYS_FROM + (x + y) * (GREYS - 1) / (width + height - 2)
                    indices[y * width + x] = (if (inMark) frame else grey).toByte()
                }
            }
            out.write(lzw(indices))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** One APNG frame: its rectangle on the canvas, its colour, and how it goes onto what is there. */
    class ApngFrame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val color: Int,
        val blendOver: Boolean = false,
    )

    /**
     * An APNG on a [width] × [height] canvas of [frames], each a solid-colour PNG the platform encoded, each [delayMs]
     * long, played [plays] times (0 for ever). Every frame is disposed to none, so a smaller one lands over the last.
     */
    fun apng(
        width: Int,
        height: Int,
        frames: List<ApngFrame>,
        delayMs: Int,
        plays: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        var sequence = 0
        frames.forEachIndexed { i, frame ->
            val png = pngChunks(solid(frame.width, frame.height, frame.color))
            if (i == 0) {
                val ihdr = png.first { it.first == "IHDR" }.second.copyOf()
                ByteBuffer.wrap(ihdr).putInt(0, width).putInt(4, height)
                out.chunk("IHDR", ihdr)
                out.chunk(
                    "acTL",
                    ByteBuffer
                        .allocate(8)
                        .putInt(frames.size)
                        .putInt(plays)
                        .array(),
                )
            }
            val fctl =
                ByteBuffer
                    .allocate(FCTL_BYTES)
                    .putInt(sequence++)
                    .putInt(frame.width)
                    .putInt(frame.height)
                    .putInt(frame.x)
                    .putInt(frame.y)
                    .putShort(delayMs.toShort())
                    .putShort(MS_PER_S.toShort())
                    .put(0) // dispose: none
                    .put((if (frame.blendOver) 1 else 0).toByte())
                    .array()
            out.chunk("fcTL", fctl)
            for ((type, data) in png) {
                if (type != "IDAT") continue
                if (i == 0) {
                    out.chunk("IDAT", data)
                } else {
                    out.chunk(
                        "fdAT",
                        ByteBuffer
                            .allocate(4 + data.size)
                            .putInt(sequence++)
                            .put(data)
                            .array(),
                    )
                }
            }
        }
        out.chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Each frame of an animated WebP decoded by the platform, with the time it starts at. */
    fun frames(webp: ByteArray): List<Pair<Int, Bitmap>> {
        val animation = checkNotNull(AnimatedFrames.of(webp)) { "not an animated WebP" }
        var at = 0
        return animation.frames.map { frame ->
            // Bounded far above the canvas, so the frame decodes at its own size.
            val bitmap = checkNotNull(decodeBoundedFromBytes(frame.still, AnimatedFrames.MAX_CANVAS)) { "frame at $at ms" }
            (at to bitmap).also { at += frame.durationMs }
        }
    }

    /** Whether [actual] is [expected] to within [tolerance] on each channel: a lossy encode moves a flat colour a little. */
    fun near(
        actual: Int,
        expected: Int,
        tolerance: Int = 48,
    ): Boolean =
        Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance

    fun describe(color: Int): String = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF)

    private fun solid(
        width: Int,
        height: Int,
        color: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun pngChunks(bitmap: Bitmap): List<Pair<String, ByteArray>> {
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var at = PNG_SIGNATURE.size
        val buffer = ByteBuffer.wrap(bytes)
        while (at + CHUNK_OVERHEAD <= bytes.size) {
            val length = buffer.getInt(at)
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            chunks += type to bytes.copyOfRange(at + 8, at + 8 + length)
            at += length + CHUNK_OVERHEAD
        }
        return chunks
    }

    /** The 256-entry table: [GIF_MARKS] first, then a ramp of greys for the noise. */
    private fun palette(): ByteArray {
        val table = ByteArray(PALETTE * 3)
        for (i in 0 until PALETTE) {
            val color =
                if (i < GIF_MARKS.size) {
                    GIF_MARKS[i]
                } else {
                    val grey = (i - GREYS_FROM).coerceAtLeast(0) * 255 / (GREYS - 1)
                    Color.rgb(grey, grey, grey)
                }
            table[i * 3] = Color.red(color).toByte()
            table[i * 3 + 1] = Color.green(color).toByte()
            table[i * 3 + 2] = Color.blue(color).toByte()
        }
        return table
    }

    /**
     * [indices] as GIF image data that never compresses: every pixel its own 9-bit code, with a clear code often
     * enough that the decoder's table never grows to 10 bits. Valid LZW, and simple enough to be obviously right.
     */
    private fun lzw(indices: ByteArray): ByteArray {
        val bits = BitWriter()
        bits.write(CLEAR, CODE_BITS)
        var sinceClear = 0
        for (index in indices) {
            if (sinceClear == CODES_PER_CLEAR) {
                bits.write(CLEAR, CODE_BITS)
                sinceClear = 0
            }
            bits.write(index.toInt() and 0xFF, CODE_BITS)
            sinceClear++
        }
        bits.write(END, CODE_BITS)
        val data = bits.bytes()
        val out = ByteArrayOutputStream()
        out.write(MIN_CODE_SIZE)
        var at = 0
        while (at < data.size) {
            val n = minOf(SUB_BLOCK, data.size - at)
            out.write(n)
            out.write(data, at, n)
            at += n
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Packs codes least-significant bit first, as GIF's LZW stream is. */
    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var acc = 0
        private var count = 0

        fun write(
            code: Int,
            width: Int,
        ) {
            acc = acc or (code shl count)
            count += width
            while (count >= 8) {
                out.write(acc and 0xFF)
                acc = acc ushr 8
                count -= 8
            }
        }

        fun bytes(): ByteArray {
            if (count > 0) out.write(acc and 0xFF)
            acc = 0
            count = 0
            return out.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.chunk(
        type: String,
        data: ByteArray,
    ) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        write(ByteBuffer.allocate(4).putInt(data.size).array())
        write(tag)
        write(data)
        val crc = CRC32().apply { update(tag) }.apply { update(data) }
        write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private const val PALETTE = 256
    private const val GREYS_FROM = 16
    private const val GREYS = PALETTE - GREYS_FROM

    // Global colour table present, 8 bits of colour resolution, 256 entries.
    private const val GLOBAL_TABLE_256 = 0xF7
    private const val MIN_CODE_SIZE = 8
    private const val CLEAR = 256
    private const val END = 257
    private const val CODE_BITS = 9

    // After a clear the decoder adds an entry per code from the second on; 250 codes leave it at 507, under 512.
    private const val CODES_PER_CLEAR = 250
    private const val SUB_BLOCK = 255
    private const val FCTL_BYTES = 26
    private const val CHUNK_OVERHEAD = 12
    private const val MS_PER_S = 1_000
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
