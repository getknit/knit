package app.getknit.knit.data.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/** The in-memory MP4 sink: seeks back to patch, grows, zero-fills a gap, truncates, and stays bounded. */
class MemoryMuxerOutputTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun writesAppendAndASeekBackOverwritesInPlace() {
        val out = MemoryMuxerOutput(maxBytes = 64)
        out.write(ByteBuffer.wrap(bytes(1, 2, 3, 4)))
        out.position = 1
        out.write(ByteBuffer.wrap(bytes(9, 9)))
        assertEquals(3L, out.position)
        assertEquals(4L, out.size)
        assertArrayEquals(bytes(1, 9, 9, 4), out.toByteArray())
    }

    @Test
    fun aWritePastTheEndZeroFillsTheGap() {
        val out = MemoryMuxerOutput(maxBytes = 64)
        out.write(ByteBuffer.wrap(bytes(1)))
        out.position = 3
        out.write(ByteBuffer.wrap(bytes(7)))
        assertArrayEquals(bytes(1, 0, 0, 7), out.toByteArray())
    }

    @Test
    fun itGrowsPastItsFirstBuffer() {
        val out = MemoryMuxerOutput(maxBytes = 3 shl 20)
        val chunk = ByteArray(700_000) { 5 }
        repeat(3) { out.write(ByteBuffer.wrap(chunk)) }
        assertEquals(2_100_000L, out.size)
        assertEquals(5.toByte(), out.toByteArray().last())
    }

    @Test
    fun truncateShrinksAndPullsThePositionIn() {
        val out = MemoryMuxerOutput(maxBytes = 64)
        out.write(ByteBuffer.wrap(bytes(1, 2, 3, 4, 5)))
        out.truncate(2)
        assertEquals(2L, out.size)
        assertEquals(2L, out.position)
        assertArrayEquals(bytes(1, 2), out.toByteArray())
    }

    @Test
    fun aWriteOverTheBoundFailsAndAClosedOutputRefuses() {
        val out = MemoryMuxerOutput(maxBytes = 4)
        assertThrows(IOException::class.java) { out.write(ByteBuffer.wrap(ByteArray(5))) }
        out.close()
        assertFalse(out.isOpen)
        assertThrows(ClosedChannelException::class.java) { out.write(ByteBuffer.wrap(ByteArray(1))) }
    }

    @Test
    fun runningPastTheBoundSaysItOverflowedAndABadSeekBelowZeroDoesNot() {
        // An encode that ran over the cap is too big, not broken: the transcoder retries it smaller (#353).
        val fits = MemoryMuxerOutput(maxBytes = 4)
        fits.write(ByteBuffer.wrap(ByteArray(4)))
        assertFalse(fits.overflowed)
        assertThrows(IOException::class.java) { fits.position = -1 }
        assertFalse("a seek below zero is a bug, not an overflow", fits.overflowed)

        val written = MemoryMuxerOutput(maxBytes = 4)
        assertThrows(IOException::class.java) { written.write(ByteBuffer.wrap(ByteArray(5))) }
        assertTrue(written.overflowed)

        val sought = MemoryMuxerOutput(maxBytes = 4)
        assertThrows(IOException::class.java) { sought.position = 5 }
        assertTrue(sought.overflowed)
    }
}
