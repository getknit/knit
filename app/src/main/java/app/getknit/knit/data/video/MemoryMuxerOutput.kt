package app.getknit.knit.data.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * A [SeekableMuxerOutput] that is a growable byte array, so `Mp4Muxer` writes a re-encoded video into memory and its
 * plaintext never reaches the filesystem: `AttachmentStore`'s invariant (ADR 029), which a file-path muxer would break.
 *
 * The muxer seeks back to patch box sizes and writes the moov box last, then moves it to the front when it can
 * (streamable output), so this keeps a position apart from the size, and a write past the end zero-fills the gap.
 * Bounded by [maxBytes]: a write or seek that would take the output past it fails, which fails the export rather than
 * holding an unbounded buffer, and sets [overflowed], so an encode that ran over is told from one that broke. Not
 * thread-safe; the muxer writes from one thread. JVM-tested in
 * `MemoryMuxerOutputTest`.
 */
@OptIn(UnstableApi::class)
class MemoryMuxerOutput(
    private val maxBytes: Int,
) : SeekableMuxerOutput {
    private var buffer = ByteArray(INITIAL_CAPACITY.coerceAtMost(maxBytes))
    private var size = 0
    private var position = 0
    private var open = true

    /** Whether a write or seek went past [maxBytes]: the encode was too big, not broken. */
    var overflowed = false
        private set

    /** The bytes written so far, [size] of them. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    override fun write(src: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        val count = src.remaining()
        val end = position.toLong() + count
        if (end > maxBytes) {
            overflowed = true
            throw IOException("video output over $maxBytes bytes")
        }
        ensureCapacity(end.toInt())
        if (position > size) buffer.fill(0, size, position)
        src.get(buffer, position, count)
        position = end.toInt()
        if (position > size) size = position
        return count
    }

    override fun getPosition(): Long = position.toLong()

    override fun setPosition(position: Long) {
        if (position > maxBytes) overflowed = true
        if (position !in 0..maxBytes) throw IOException("seek to $position outside the output")
        this.position = position.toInt()
    }

    override fun getSize(): Long = size.toLong()

    override fun truncate(size: Long) {
        if (size < 0) throw IOException("truncate to $size")
        if (size < this.size) this.size = size.toInt()
        if (position > this.size) position = this.size
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        val grown = (buffer.size.toLong() * 2).coerceAtLeast(needed.toLong()).coerceAtMost(maxBytes.toLong())
        buffer = buffer.copyOf(grown.toInt())
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 20
    }
}
