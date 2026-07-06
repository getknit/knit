package app.getknit.knit.mesh.link

import java.io.OutputStream

/**
 * An [OutputStream] filter that splits every write into slices no larger than [maxChunk] before handing them
 * to [delegate]. Pure (no Android), so it is JVM-unit-testable
 * ([app.getknit.knit.SegmentingOutputStreamTest]).
 *
 * It exists for the Bluetooth **L2CAP CoC** socket ([app.getknit.knit.mesh.bluetooth.BluetoothSocketLink]):
 * each write to that socket becomes one L2CAP SDU, whose length is a **16-bit field (max 65535)**. A single
 * whole [LinkFraming] FILE_CHUNK record is 64 KiB + 5 B = 65541 B — and `BufferedOutputStream` passes any
 * array at least as large as its own buffer **straight through as one write** — so without segmenting, one
 * write emits an SDU over the ceiling. A weaker vendor Bluetooth stack (observed on a Moto G `cherokee`,
 * API 30) underflowed reassembling that oversized SDU, corrupting its heap and aborting the whole
 * `com.android.bluetooth` process. [FramedLink]'s length-prefixed framing reassembles the byte stream
 * irrespective of SDU boundaries, so slicing here is transparent to every layer above.
 *
 * The Wi-Fi Aware NDP path is a plain TCP [java.net.Socket] ([NetSocketLink]) with no such ceiling and is
 * deliberately **not** wrapped.
 */
internal class SegmentingOutputStream(
    private val delegate: OutputStream,
    private val maxChunk: Int,
) : OutputStream() {
    init {
        require(maxChunk > 0) { "maxChunk must be positive, was $maxChunk" }
    }

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val n = minOf(maxChunk, end - pos)
            delegate.write(b, pos, n)
            pos += n
        }
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
