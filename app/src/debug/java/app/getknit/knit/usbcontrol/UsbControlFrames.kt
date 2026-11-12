package app.getknit.knit.usbcontrol

import java.io.IOException
import java.nio.ByteBuffer

/**
 * The USB control spike's framing: a big-endian u32 length, then that many bytes of UTF-8 JSON, 1 to [MAX_FRAME]
 * bytes. The `[len:4][payload]` shape is `mesh/link/LinkFraming`'s, copied rather than shared because that one is
 * the mesh's own door; knit-cli's `frames.py` speaks the same bytes.
 *
 * One rule USB adds: a frame whose whole size is a multiple of [PACKET_ALIGN] gets one trailing space in its JSON.
 * A bulk transfer ends at a short packet, and neither side sends a zero-length one after an exact multiple of the
 * max packet size (64 at full speed, 512 at high, 1024 at super speed; 64 divides the other two), so such a frame
 * would sit in the reader's buffer until the next one arrived. JSON ignores trailing whitespace, so the padded frame
 * decodes to the same value.
 */
internal object UsbControlFrames {
    const val HEADER = 4
    const val MAX_FRAME = 64 * 1024
    const val PACKET_ALIGN = 64

    fun encode(json: String): ByteArray {
        var body = json.encodeToByteArray()
        if ((HEADER + body.size) % PACKET_ALIGN == 0) body += ' '.code.toByte()
        if (body.size !in 1..MAX_FRAME) throw IOException("frame of ${body.size} bytes is outside 1..$MAX_FRAME")
        val out = ByteArray(HEADER + body.size)
        ByteBuffer.wrap(out).putInt(body.size)
        body.copyInto(out, HEADER)
        return out
    }
}

/**
 * Reassembles frames from reads of any size: a read may hold half a header, several frames, or a frame and the
 * start of the next. A length outside 1..[max] throws [IOException]; the stream can't be trusted past that point,
 * so the caller reports it and calls [reset].
 */
internal class UsbControlDeframer(
    private val max: Int = UsbControlFrames.MAX_FRAME,
) {
    private var buf = ByteArray(INITIAL)
    private var len = 0

    /** Bytes held toward a frame not yet complete. */
    val pending: Int get() = len

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size - offset,
    ): List<String> {
        ensure(len + count)
        bytes.copyInto(buf, len, offset, offset + count)
        len += count
        val frames = mutableListOf<String>()
        var start = 0
        while (len - start >= UsbControlFrames.HEADER) {
            val n = ByteBuffer.wrap(buf, start, UsbControlFrames.HEADER).int
            if (n !in 1..max) throw IOException("frame length $n is outside 1..$max")
            if (len - start - UsbControlFrames.HEADER < n) break
            frames += buf.decodeToString(start + UsbControlFrames.HEADER, start + UsbControlFrames.HEADER + n)
            start += UsbControlFrames.HEADER + n
        }
        if (start > 0) {
            buf.copyInto(buf, 0, start, len)
            len -= start
        }
        return frames
    }

    fun reset() {
        len = 0
        if (buf.size > INITIAL) buf = ByteArray(INITIAL)
    }

    private fun ensure(size: Int) {
        if (size <= buf.size) return
        var next = buf.size
        while (next < size) next *= 2
        buf = buf.copyOf(next)
    }

    private companion object {
        const val INITIAL = 16 * 1024
    }
}
