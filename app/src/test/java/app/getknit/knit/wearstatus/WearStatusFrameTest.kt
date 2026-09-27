package app.getknit.knit.wearstatus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The RFCOMM framing both modules compile: one length byte, then the codec's bytes. */
class WearStatusFrameTest {
    private val snapshot = "010305001bd2040000803bb16a0c000213420c00".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `a frame is the length byte then the snapshot, and reads back`() {
        val frame = WearStatusFrame.frame(snapshot)
        assertEquals(20, frame[0].toInt())
        assertArrayEquals(snapshot, frame.copyOfRange(1, frame.size))
        assertArrayEquals(snapshot, WearStatusFrame.read(ByteArrayInputStream(frame)))
    }

    @Test
    fun `a frame split across socket reads is reassembled`() {
        assertArrayEquals(snapshot, WearStatusFrame.read(Trickle(WearStatusFrame.frame(snapshot))))
    }

    @Test
    fun `only the declared length is read, so trailing bytes stay on the stream`() {
        val input = ByteArrayInputStream(WearStatusFrame.frame(snapshot) + byteArrayOf(7))
        assertArrayEquals(snapshot, WearStatusFrame.read(input))
        assertEquals(7, input.read())
    }

    @Test
    fun `an empty stream, an empty frame or a cut frame reads as nothing`() {
        assertNull(WearStatusFrame.read(ByteArrayInputStream(ByteArray(0))))
        assertNull(WearStatusFrame.read(ByteArrayInputStream(byteArrayOf(0))))
        assertNull(WearStatusFrame.read(ByteArrayInputStream(WearStatusFrame.frame(snapshot).copyOf(10))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload past one length byte is refused`() {
        WearStatusFrame.frame(ByteArray(WearStatusFrame.MAX_PAYLOAD + 1))
    }

    /** Hands out one byte per read, as a socket may. */
    private class Trickle(
        bytes: ByteArray,
    ) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)

        override fun read(): Int = inner.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = inner.read(b, off, minOf(len, 1))
    }
}
