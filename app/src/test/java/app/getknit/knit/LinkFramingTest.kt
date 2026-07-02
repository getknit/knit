package app.getknit.knit

import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FileHeaderWire
import app.getknit.knit.mesh.link.LinkFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Unit tests for [LinkFraming] — the pure length-prefixed record codec that multiplexes mesh frames
 * and file transfers over a connected byte stream. The socket I/O itself needs real radios, but the wire
 * framing is transport-neutral and pure, so it belongs under test.
 */
class LinkFramingTest {

    private fun roundTrip(vararg records: Pair<LinkFraming.Type, ByteArray>): List<LinkFraming.Message> {
        val out = ByteArrayOutputStream()
        records.forEach { (type, payload) -> out.write(LinkFraming.encode(type, payload)) }
        val input = ByteArrayInputStream(out.toByteArray())
        return generateSequence { LinkFraming.read(input) }.toList()
    }

    @Test
    fun encodesAndReadsBackASingleFrameRecord() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val records = roundTrip(LinkFraming.Type.FRAME to payload)
        assertEquals(1, records.size)
        assertEquals(LinkFraming.Type.FRAME, records[0].type)
        assertArrayEquals(payload, records[0].payload)
    }

    @Test
    fun readsBackToBackRecordsInOrderIncludingAnEmptyFileEnd() {
        val chunk = ByteArray(1000) { it.toByte() }
        val records = roundTrip(
            LinkFraming.Type.FILE_HEADER to "hdr".encodeToByteArray(),
            LinkFraming.Type.FRAME to byteArrayOf(9), // a frame interleaved between file chunks
            LinkFraming.Type.FILE_CHUNK to chunk,
            LinkFraming.Type.FILE_END to ByteArray(0),
        )
        assertEquals(
            listOf(
                LinkFraming.Type.FILE_HEADER,
                LinkFraming.Type.FRAME,
                LinkFraming.Type.FILE_CHUNK,
                LinkFraming.Type.FILE_END,
            ),
            records.map { it.type },
        )
        assertArrayEquals(chunk, records[2].payload)
        assertEquals(0, records[3].payload.size) // FILE_END carries no payload
    }

    @Test
    fun keepAliveRecordRoundTripsWithAnEmptyPayload() {
        val records = roundTrip(LinkFraming.Type.KEEPALIVE to ByteArray(0))
        assertEquals(1, records.size)
        assertEquals(LinkFraming.Type.KEEPALIVE, records[0].type)
        assertEquals(0, records[0].payload.size)
    }

    @Test
    fun cleanEofAtARecordBoundaryReturnsNull() {
        assertNull(LinkFraming.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aTruncatedRecordPayloadThrows() {
        val full = LinkFraming.encode(LinkFraming.Type.FRAME, byteArrayOf(1, 2, 3, 4))
        val truncated = full.copyOf(full.size - 2) // header intact, payload short by 2 bytes
        assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun anOutOfRangeLengthPrefixThrows() {
        val tooBig = LinkFraming.MAX_PAYLOAD_BYTES + 1
        val header = byteArrayOf(
            LinkFraming.Type.FRAME.tag,
            (tooBig ushr 24).toByte(), (tooBig ushr 16).toByte(), (tooBig ushr 8).toByte(), tooBig.toByte(),
        )
        assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(header)) }
    }

    @Test
    fun encodeRejectsAnOversizePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkFraming.encode(LinkFraming.Type.FILE_CHUNK, ByteArray(LinkFraming.MAX_PAYLOAD_BYTES + 1))
        }
    }

    @Test
    fun fileHeaderRoundTripsAndGarbageDecodesToNull() {
        val header = FileHeaderWire(kind = "AVATAR", key = "abc123", mime = "image/jpeg")
        assertEquals(header, LinkFraming.decodeFileHeader(LinkFraming.encodeFileHeader(header)))
        assertNull(LinkFraming.decodeFileHeader("not json".encodeToByteArray()))
    }

    @Test
    fun digestRoundTripsAndGarbageDecodesToNull() {
        val digest = DigestWire(ids = listOf("a1b2c3", "d4e5f6", "g7h8i9"))
        assertEquals(digest, LinkFraming.decodeDigest(LinkFraming.encodeDigest(digest)))
        assertNull(LinkFraming.decodeDigest("not json".encodeToByteArray()))
    }

    @Test
    fun digestRecordRoundTripsThroughTheCodec() {
        val payload = LinkFraming.encodeDigest(DigestWire(ids = listOf("x", "y")))
        val records = roundTrip(LinkFraming.Type.DIGEST to payload)
        assertEquals(1, records.size)
        assertEquals(LinkFraming.Type.DIGEST, records[0].type)
        assertEquals(DigestWire(listOf("x", "y")), LinkFraming.decodeDigest(records[0].payload))
    }
}
