package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BleAdvertPayload] — the pure fixed-size BLE advert service-data codec. */
class BleAdvertPayloadTest {
    // A canonical 26-char base32 id (derived, so it round-trips through its raw 16 bytes).
    private val nodeA = NodeId.derive("peer-a")

    @Test
    fun encodeDecodeRoundTrips() {
        val bytes =
            BleAdvertPayload.encode(
                nodeId = nodeA,
                capabilities = 0xFL,
                digestVersion = 0x123456789ABCDEF0L,
                psm = 0x0081,
            )
        assertEquals(BleAdvertPayload.SIZE, bytes.size)
        val p = BleAdvertPayload.parse(bytes)!!
        assertEquals(nodeA, p.nodeId)
        assertEquals(0xFL, p.capabilities)
        assertEquals("digest cue is the low 32 bits of the version", 0x123456789ABCDEF0L.toInt(), p.digestCue)
        assertEquals(0x0081, p.psm)
    }

    @Test
    fun theFull128BitIdSurvivesTheAdvert() {
        // The point of the raw-16-byte layout: the advert carries the entire id, not a truncated prefix.
        assertEquals(NodeId.LENGTH, nodeA.length)
        val p = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1))!!
        assertEquals(nodeA, p.nodeId)
    }

    @Test
    fun payloadFitsALegacyAdvertisementBudget() {
        // 23-byte service data + Flags(3) + Service-Data-16bit AD header(4) = 30/31. There is NO separate
        // service-UUID-list AD — it was dropped to free the 4 bytes the 16 raw id bytes need; the scanner
        // filters on the service data instead.
        assertEquals(1 + NodeId.BYTES + 4 + 2, BleAdvertPayload.SIZE)
        assertEquals(23, BleAdvertPayload.SIZE)
        val advOverhead = 3 + 4 // Flags + service-data AD header (16-bit UUID)
        assertTrue("payload + AD overhead must fit 31 bytes", BleAdvertPayload.SIZE + advOverhead <= 31)
    }

    @Test
    fun shortOrNullDataDecodesToNull() {
        assertNull(BleAdvertPayload.parse(null))
        assertNull(BleAdvertPayload.parse(ByteArray(10)))
        assertNull(BleAdvertPayload.parse(ByteArray(BleAdvertPayload.SIZE - 1)))
    }

    @Test
    fun extraTrailingBytesStillParseThePrefix() {
        val base = BleAdvertPayload.encode(nodeA, 0x1L, 7L, 200)
        // A future build appends bytes; the fixed offsets keep the prefix decodable.
        val forward = base.copyOf(base.size + 4)
        val p = BleAdvertPayload.parse(forward)!!
        assertEquals(nodeA, p.nodeId)
        assertEquals(0x1L, p.capabilities)
        assertEquals(7, p.digestCue)
        assertEquals(200, p.psm)
    }

    @Test
    fun onlyTheLow32BitsOfTheVersionFormTheDigestCue() {
        // Two versions with different high halves but identical low 32 bits → identical cue (both peers truncate).
        val a = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0x1_0000_00AAL, 1))!!
        val b = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0x7_0000_00AAL, 1))!!
        assertEquals(a.digestCue, b.digestCue)
        assertEquals(0xAA, a.digestCue)
    }
}
