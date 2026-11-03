package app.getknit.knit

import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FileHeaderWire
import app.getknit.knit.mesh.link.LinkFraming
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        val records =
            roundTrip(
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
        val header =
            byteArrayOf(
                LinkFraming.Type.FRAME.tag,
                (tooBig ushr 24).toByte(),
                (tooBig ushr 16).toByte(),
                (tooBig ushr 8).toByte(),
                tooBig.toByte(),
            )
        assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(header)) }
    }

    @Test
    fun aNegativeLengthPrefixThrowsRatherThanAllocating() {
        // 0xFFFFFFFF reads back as -1: a hostile or desynced peer must get a dropped link, not a NegativeArraySize.
        val header = byteArrayOf(LinkFraming.Type.FRAME.tag, -1, -1, -1, -1)
        assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(header)) }
    }

    @Test
    fun aProofRecordRoundTripsUnderTagEight() {
        // ADR 2026-11.264w: written only toward a HELLO carrying CAP_LINK_PROOF, since every older build throws on it.
        val payload = ByteArray(128) { it.toByte() }
        val bytes = LinkFraming.encode(LinkFraming.Type.PROOF, payload)
        assertEquals(8.toByte(), bytes[0])
        assertEquals(LinkFraming.Message(LinkFraming.Type.PROOF, payload), LinkFraming.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun theTagAfterTagNextIsStillUnknownAndThrows() {
        assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(byteArrayOf(10, 0, 0, 0, 0))) }
    }

    @Test
    fun aTagNextRecordRoundTripsUnderTagNine() {
        // ADR 2026-11.xp6d: written only toward a proven HELLO carrying CAP_BLIND_ADVERT, since every older build throws.
        val tag = ByteArray(LinkFraming.TAG_NEXT_BYTES) { (0xC0 + it).toByte() }
        val bytes = LinkFraming.encodeTagNext(tag)
        assertEquals(9.toByte(), bytes[0])
        assertEquals(LinkFraming.Message(LinkFraming.Type.TAG_NEXT, tag), LinkFraming.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun aTagNextOfAnyOtherLengthIsAFramingErrorNeverARecord() {
        for (len in listOf(0, 15, 17, 1024)) {
            val bytes = LinkFraming.encode(LinkFraming.Type.TAG_NEXT, ByteArray(len))
            assertThrows("length $len", IOException::class.java) { LinkFraming.read(ByteArrayInputStream(bytes)) }
        }
        assertThrows(IllegalArgumentException::class.java) { LinkFraming.encodeTagNext(ByteArray(15)) }
    }

    @Test
    fun anUnknownRecordTypeThrows() {
        for (tag in listOf(0, 0x7F, 0xFF)) {
            val record = byteArrayOf(tag.toByte(), 0, 0, 0, 0)
            assertThrows(IOException::class.java) { LinkFraming.read(ByteArrayInputStream(record)) }
        }
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
    fun aFileHeaderCarriesTheStreamsSize() {
        val header = FileHeaderWire(kind = "ATTACHMENT", key = KEY, mime = "image/jpeg", size = 203_807)
        assertEquals(header, LinkFraming.decodeFileHeader(LinkFraming.encodeFileHeader(header)))
    }

    @Test
    fun aHeaderFromABuildBeforeTheSizeDecodesWithNone() {
        // The bytes a build that predates the field writes (#115): three fields, no size.
        val older = """{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg"}"""
        assertEquals(FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = null), LinkFraming.decodeFileHeader(older.encodeToByteArray()))
    }

    @Test
    fun aSizeThatWillNotDecodeCostsTheLabelNotTheFile() {
        // A failed header decode aborts the file behind it, so a size some later sender spells wrong must fall
        // back to no total rather than to no file.
        listOf("\"big\"", "1.5", "99999999999999999999", "{}", "[1]", "null").forEach { bad ->
            val json = """{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg","size":$bad}"""
            assertEquals(
                bad,
                FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = null),
                LinkFraming.decodeFileHeader(json.encodeToByteArray()),
            )
        }
    }

    @Test
    fun aHeaderMissingAFieldTheReceiverRoutesOnStillDecodesToNull() {
        val noMime = """{"kind":"ATTACHMENT","key":"$KEY","size":10}"""
        assertNull(LinkFraming.decodeFileHeader(noMime.encodeToByteArray()))
    }

    @Test
    fun anOlderBuildReadsAHeaderThatCarriesTheSize() {
        // An older build's decoder: the three fields it knows, under the link's own JSON config, skipping the key
        // it has never heard of. This is what makes the field additive on a config that encodes defaults.
        val olderJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val bytes = LinkFraming.encodeFileHeader(FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = 203_807))
        assertEquals(OlderFileHeader("ATTACHMENT", KEY, "image/jpeg"), olderJson.decodeFromString<OlderFileHeader>(bytes.decodeToString()))
    }

    @Test
    fun aResumedHeaderCarriesItsOffsetAfterTheTailsSize() {
        // The rest of a cut transfer (#116): `size` keeps its meaning — the bytes that follow — and the offset rides
        // last, so the whole blob is offset + size.
        val header = FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = 400, offset = 600)
        val bytes = LinkFraming.encodeFileHeader(header)
        assertEquals("""{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg","size":400,"offset":600}""", bytes.decodeToString())
        assertEquals(header, LinkFraming.decodeFileHeader(bytes))
    }

    @Test
    fun aWholeFileHeaderIsByteIdenticalToTheOneBeforeTheOffset() {
        // The link JSON encodes defaults, so without EncodeDefault.NEVER every header would gain "offset":null and
        // the pinned fileHeader vector would move. A whole file is the header every build has always sent.
        val bytes = LinkFraming.encodeFileHeader(FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = 203_807))
        assertEquals("""{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg","size":203807}""", bytes.decodeToString())
    }

    @Test
    fun anOffsetThatWillNotDecodeRefusesTheFile() {
        // Unlike the size, the offset says where the bytes go: a receiver that cannot read it cannot place them,
        // so the header is no header and its stream is skipped.
        listOf("\"far\"", "1.5", "99999999999999999999", "{}").forEach { bad ->
            val json = """{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg","size":10,"offset":$bad}"""
            assertNull(bad, LinkFraming.decodeFileHeader(json.encodeToByteArray()))
        }
    }

    @Test
    fun aSizeThatWillNotDecodeStillKeepsTheOffset() {
        val json = """{"kind":"ATTACHMENT","key":"$KEY","mime":"image/jpeg","size":"big","offset":600}"""
        assertEquals(
            FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = null, offset = 600),
            LinkFraming.decodeFileHeader(json.encodeToByteArray()),
        )
    }

    @Test
    fun anOlderBuildReadsAResumedHeaderAsItsFields() {
        // Never sent to one (only a build that reads the offset asks with one), but a stray must not break it.
        val olderJson = Json { ignoreUnknownKeys = true }
        val bytes = LinkFraming.encodeFileHeader(FileHeaderWire("ATTACHMENT", KEY, "image/jpeg", size = 400, offset = 600))
        assertEquals(OlderFileHeader("ATTACHMENT", KEY, "image/jpeg"), olderJson.decodeFromString<OlderFileHeader>(bytes.decodeToString()))
    }

    /** `FileHeaderWire` as every build before #115 declares it. */
    @Serializable
    private data class OlderFileHeader(
        val kind: String,
        val key: String,
        val mime: String,
    )

    @Test
    fun digestRoundTripsAndGarbageDecodesToNull() {
        val digest = DigestWire(ids = listOf("a1b2c3", "d4e5f6", "g7h8i9"))
        assertEquals(digest, LinkFraming.decodeDigest(LinkFraming.encodeDigest(digest)))
        assertNull(LinkFraming.decodeDigest("not json".encodeToByteArray()))
    }

    private companion object {
        val KEY = "ab".repeat(32)
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
