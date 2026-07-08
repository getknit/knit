package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanCueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [NanCueCodec] — the pure coordination-plane cue / SSI-digest codec. */
class NanCueCodecTest {
    @Test
    fun encodeParseCueRoundTrips() {
        val cue = NanCueCodec.parseCue(NanCueCodec.encodeCue("node8id0", 42L))
        assertEquals(NanCueCodec.Cue("node8id0", 42L), cue)
    }

    @Test
    fun parseCueSplitsOnTheLastSeparator() {
        // Real nodeIds never contain '|', but the split is defined as lastIndexOf so a stray separator in the
        // id half can't steal the version.
        assertEquals(NanCueCodec.Cue("a|b", 42L), NanCueCodec.parseCue("a|b|42".encodeToByteArray()))
    }

    @Test
    fun parseCueRejectsMalformedPayloads() {
        assertNull("empty bytes", NanCueCodec.parseCue(ByteArray(0)))
        assertNull("no separator", NanCueCodec.parseCue("nosep".encodeToByteArray()))
        assertNull("separator at 0 (empty nodeId)", NanCueCodec.parseCue("|42".encodeToByteArray()))
        assertNull("non-numeric version", NanCueCodec.parseCue("node|abc".encodeToByteArray()))
        assertNull("malformed bytes decode to a separatorless string", NanCueCodec.parseCue(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun encodeParseSsiDigestRoundTrips() {
        for (v in listOf(0L, 1L, 42L, Long.MAX_VALUE)) {
            assertEquals(v, NanCueCodec.parseSsiDigest(NanCueCodec.encodeSsiDigest(v)))
        }
    }

    @Test
    fun parseSsiDigestFindsTheTrailingSegmentInAFullAdvert() {
        // The digest rides as the fourth |-segment of a many-|-separated publish SSI; parsing must pick the
        // trailing |d segment out of the whole advert string.
        val ssi = "knit|3|node8id0" + NanCueCodec.encodeSsiDigest(777L)
        assertEquals(777L, NanCueCodec.parseSsiDigest(ssi))
    }

    @Test
    fun parseSsiDigestPicksTheLastPrefixOccurrence() {
        assertEquals(2L, NanCueCodec.parseSsiDigest("a|d1|d2"))
    }

    @Test
    fun parseSsiDigestRejectsMissingOrNonNumericSegment() {
        assertNull("no |d segment (older build)", NanCueCodec.parseSsiDigest("knit|3|node8id0"))
        assertNull("non-numeric digest", NanCueCodec.parseSsiDigest("knit|dabc"))
    }
}
