package app.getknit.knit.wearstatus

import java.io.IOException
import java.io.InputStream

/**
 * The snapshot on the RFCOMM stream (ADR 2026-09.wetm, third amendment): one length byte, then the
 * [WearStatusCodec] bytes — a stream has no characteristic boundary, and the watch must know where the value
 * ends without waiting for the phone to close (a close right behind a write can drop the tail). The watch
 * closes once it holds the frame; the phone lingers for that close. Same pure-Kotlin rule as the codec.
 */
object WearStatusFrame {
    /** A frame's payload never exceeds one length byte; the codec is 13 or 20 bytes today. */
    const val MAX_PAYLOAD = 0xFF

    fun frame(snapshot: ByteArray): ByteArray {
        require(snapshot.isNotEmpty() && snapshot.size <= MAX_PAYLOAD) { "snapshot of ${snapshot.size} B" }
        return byteArrayOf(snapshot.size.toByte()) + snapshot
    }

    /**
     * Reads one frame's payload, blocking. Null when the stream ends before a whole frame (the phone had no
     * snapshot to give, or the link dropped) or declares an empty one.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): ByteArray? {
        val size = input.read()
        if (size <= 0) return null
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = input.read(out, at, size - at)
            if (n < 0) return null
            at += n
        }
        return out
    }
}
