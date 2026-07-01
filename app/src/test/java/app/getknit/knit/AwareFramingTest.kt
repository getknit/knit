package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.AwareFraming
import app.getknit.knit.mesh.wifiaware.FileHeaderWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Unit tests for [AwareFraming] — the pure length-prefixed record codec that multiplexes mesh frames
 * and file transfers over a Wi-Fi Aware data-path socket. The socket I/O itself needs real NAN hardware,
 * but the wire framing is pure and belongs under test.
 */
class AwareFramingTest {

    private fun roundTrip(vararg records: Pair<AwareFraming.Type, ByteArray>): List<AwareFraming.Message> {
        val out = ByteArrayOutputStream()
        records.forEach { (type, payload) -> out.write(AwareFraming.encode(type, payload)) }
        val input = ByteArrayInputStream(out.toByteArray())
        return generateSequence { AwareFraming.read(input) }.toList()
    }

    @Test
    fun encodesAndReadsBackASingleFrameRecord() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val records = roundTrip(AwareFraming.Type.FRAME to payload)
        assertEquals(1, records.size)
        assertEquals(AwareFraming.Type.FRAME, records[0].type)
        assertArrayEquals(payload, records[0].payload)
    }

    @Test
    fun readsBackToBackRecordsInOrderIncludingAnEmptyFileEnd() {
        val chunk = ByteArray(1000) { it.toByte() }
        val records = roundTrip(
            AwareFraming.Type.FILE_HEADER to "hdr".encodeToByteArray(),
            AwareFraming.Type.FRAME to byteArrayOf(9), // a frame interleaved between file chunks
            AwareFraming.Type.FILE_CHUNK to chunk,
            AwareFraming.Type.FILE_END to ByteArray(0),
        )
        assertEquals(
            listOf(
                AwareFraming.Type.FILE_HEADER,
                AwareFraming.Type.FRAME,
                AwareFraming.Type.FILE_CHUNK,
                AwareFraming.Type.FILE_END,
            ),
            records.map { it.type },
        )
        assertArrayEquals(chunk, records[2].payload)
        assertEquals(0, records[3].payload.size) // FILE_END carries no payload
    }

    @Test
    fun keepAliveRecordRoundTripsWithAnEmptyPayload() {
        val records = roundTrip(AwareFraming.Type.KEEPALIVE to ByteArray(0))
        assertEquals(1, records.size)
        assertEquals(AwareFraming.Type.KEEPALIVE, records[0].type)
        assertEquals(0, records[0].payload.size)
    }

    @Test
    fun cleanEofAtARecordBoundaryReturnsNull() {
        assertNull(AwareFraming.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aTruncatedRecordPayloadThrows() {
        val full = AwareFraming.encode(AwareFraming.Type.FRAME, byteArrayOf(1, 2, 3, 4))
        val truncated = full.copyOf(full.size - 2) // header intact, payload short by 2 bytes
        assertThrows(IOException::class.java) { AwareFraming.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun anOutOfRangeLengthPrefixThrows() {
        val tooBig = AwareFraming.MAX_PAYLOAD_BYTES + 1
        val header = byteArrayOf(
            AwareFraming.Type.FRAME.tag,
            (tooBig ushr 24).toByte(), (tooBig ushr 16).toByte(), (tooBig ushr 8).toByte(), tooBig.toByte(),
        )
        assertThrows(IOException::class.java) { AwareFraming.read(ByteArrayInputStream(header)) }
    }

    @Test
    fun encodeRejectsAnOversizePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            AwareFraming.encode(AwareFraming.Type.FILE_CHUNK, ByteArray(AwareFraming.MAX_PAYLOAD_BYTES + 1))
        }
    }

    @Test
    fun fileHeaderRoundTripsAndGarbageDecodesToNull() {
        val header = FileHeaderWire(kind = "AVATAR", key = "abc123", mime = "image/jpeg")
        assertEquals(header, AwareFraming.decodeFileHeader(AwareFraming.encodeFileHeader(header)))
        assertNull(AwareFraming.decodeFileHeader("not json".encodeToByteArray()))
    }
}
