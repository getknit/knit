package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BleAdvertPayload] — the pure fixed-size BLE advert service-data codec. */
class BleAdvertPayloadTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val bytes = BleAdvertPayload.encode(
            nodeId = "abcd1234",
            capabilities = 0xFL,
            digestVersion = 0x123456789ABCDEF0L,
            psm = 0x0081,
        )
        assertEquals(BleAdvertPayload.SIZE, bytes.size)
        val p = BleAdvertPayload.parse(bytes)!!
        assertEquals("abcd1234", p.nodeId)
        assertEquals(0xFL, p.capabilities)
        assertEquals("digest cue is the low 32 bits of the version", 0x123456789ABCDEF0L.toInt(), p.digestCue)
        assertEquals(0x0081, p.psm)
    }

    @Test
    fun payloadFitsALegacyAdvertisementBudget() {
        // 16-byte service data + Flags(3) + Service-UUID-list 16-bit(4) + Service-Data-16bit header(4) = 27/31.
        assertEquals(16, BleAdvertPayload.SIZE)
        val advOverhead = 3 + 4 + 4
        assertTrue("payload + AD overhead must fit 31 bytes", BleAdvertPayload.SIZE + advOverhead <= 31)
    }

    @Test
    fun shortOrNullDataDecodesToNull() {
        assertNull(BleAdvertPayload.parse(null))
        assertNull(BleAdvertPayload.parse(ByteArray(10)))
        assertNull(BleAdvertPayload.parse(ByteArray(BleAdvertPayload.SIZE - 1)))
    }

    @Test
    fun aNewerFormatWithExtraTrailingBytesStillParsesThePrefix() {
        val base = BleAdvertPayload.encode("zzzz0000", 0x1L, 7L, 200)
        // A future build bumps the format byte and appends bytes; fixed offsets keep the prefix decodable.
        val forward = base.copyOf(base.size + 4)
        forward[0] = 99
        val p = BleAdvertPayload.parse(forward)!!
        assertEquals("zzzz0000", p.nodeId)
        assertEquals(0x1L, p.capabilities)
        assertEquals(7, p.digestCue)
        assertEquals(200, p.psm)
    }

    @Test
    fun onlyTheLow32BitsOfTheVersionFormTheDigestCue() {
        // Two versions with different high halves but identical low 32 bits → identical cue (both peers truncate).
        val a = BleAdvertPayload.parse(BleAdvertPayload.encode("nodenode", 0L, 0x1_0000_00AAL, 1))!!
        val b = BleAdvertPayload.parse(BleAdvertPayload.encode("nodenode", 0L, 0x7_0000_00AAL, 1))!!
        assertEquals(a.digestCue, b.digestCue)
        assertEquals(0xAA, a.digestCue)
    }
}
