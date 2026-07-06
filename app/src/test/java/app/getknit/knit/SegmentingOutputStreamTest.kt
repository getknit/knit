package app.getknit.knit

import app.getknit.knit.mesh.link.SegmentingOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Pure-JVM guard for [SegmentingOutputStream] — the slicer that keeps a single Bluetooth L2CAP write from
 * exceeding one SDU. The load-bearing invariant is that **no underlying write is larger than the chunk** (a
 * >64 KiB FILE_CHUNK record otherwise crossed the 65535-byte SDU ceiling and crashed a weak vendor BT stack),
 * while the byte stream is passed through **whole and in order** (the framing above depends on that).
 */
class SegmentingOutputStreamTest {
    /** Sink that records each underlying write's length and accumulates all bytes in arrival order. */
    private class RecordingSink : OutputStream() {
        val writeLengths = ArrayList<Int>()
        val bytes = ByteArrayOutputStream()

        override fun write(b: Int) {
            writeLengths.add(1)
            bytes.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            writeLengths.add(len)
            bytes.write(b, off, len)
        }
    }

    @Test
    fun oversizedRecordIsSlicedBelowChunkAndLosesNoBytes() {
        val sink = RecordingSink()
        val chunk = 8 * 1024
        val out = SegmentingOutputStream(sink, chunk)
        // A whole FILE_CHUNK record: 64 KiB payload + 5 B header = 65541 B — the size that overran the 65535
        // L2CAP SDU ceiling and aborted the Moto G's Bluetooth stack.
        val record = ByteArray(65541) { (it % 256).toByte() }
        out.write(record)
        out.flush()

        assertTrue(
            "every underlying write must fit within one L2CAP SDU (<= chunk)",
            sink.writeLengths.all { it <= chunk },
        )
        assertArrayEquals("bytes must arrive whole and in order, unaltered", record, sink.bytes.toByteArray())
    }

    @Test
    fun writeSmallerThanChunkPassesThroughAsOneWrite() {
        val sink = RecordingSink()
        val out = SegmentingOutputStream(sink, 8 * 1024)
        val small = ByteArray(100) { it.toByte() }
        out.write(small)

        assertEquals("a sub-chunk write must not be split", listOf(100), sink.writeLengths)
        assertArrayEquals(small, sink.bytes.toByteArray())
    }

    @Test
    fun offsetAndLengthAreHonoredWhileSlicing() {
        val sink = RecordingSink()
        val out = SegmentingOutputStream(sink, 4)
        val src = ByteArray(20) { it.toByte() }
        out.write(src, 3, 10) // bytes [3, 13)

        assertEquals("10 bytes in chunks of 4 → 4 + 4 + 2", listOf(4, 4, 2), sink.writeLengths)
        assertArrayEquals(src.copyOfRange(3, 13), sink.bytes.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveChunkIsRejected() {
        SegmentingOutputStream(RecordingSink(), 0)
    }
}
