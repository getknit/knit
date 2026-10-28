package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun everyAdvertCarriedCapabilityBitSurvivesIncludingTheTranscoders() {
        // The advert carries the low 8 bits; ADR 060 spent the last of them (0x80), and 0x100+ is profile-only.
        val p = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, Protocol.LOCAL_CAPABILITIES, 0L, 1))!!
        assertEquals(Protocol.LOCAL_CAPABILITIES and 0xFFL, p.capabilities)
        assertTrue(p.capabilities and Protocol.CAP_FRAME_TRANSCODE != 0L)
        assertEquals(0L, p.capabilities and Protocol.CAP_CRYPTO_V3)
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
        // 24-byte service data + Flags(3) + Service-Data-16bit AD header(4) = 31/31: the flags byte took the
        // last spare byte. There is NO separate service-UUID-list AD — it was dropped to free the 4 bytes the
        // 16 raw id bytes need; the scanner filters on the service data instead.
        assertEquals(1 + NodeId.BYTES + 4 + 2, BleAdvertPayload.SIZE_V1)
        assertEquals(23, BleAdvertPayload.SIZE_V1)
        assertEquals(24, BleAdvertPayload.SIZE)
        val advOverhead = 3 + 4 // Flags + service-data AD header (16-bit UUID)
        assertTrue("payload + AD overhead must fit 31 bytes", BleAdvertPayload.SIZE + advOverhead <= 31)
    }

    @Test
    fun theFlagsByteRoundTripsAndAnOlderAdvertReadsAsUnflagged() {
        val flagged = BleAdvertPayload.encode(nodeA, 0L, 0L, 1, flags = BleAdvertPayload.FLAG_SIDE_CHANNEL)
        assertEquals(BleAdvertPayload.SIZE, flagged.size)
        assertTrue(BleAdvertPayload.parse(flagged)!!.sideChannel)
        assertFalse(BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1))!!.sideChannel)
        // A build before the flags byte advertises 23 bytes: the same fields, no flag — never a parse failure.
        val older = flagged.copyOf(BleAdvertPayload.SIZE_V1)
        val p = BleAdvertPayload.parse(older)!!
        assertEquals(nodeA, p.nodeId)
        assertEquals(0, p.flags)
        assertFalse(p.sideChannel)
    }

    @Test
    fun theDialsGattPeersBitRoundTripsBesideTheSideChannelBit() {
        val both = BleAdvertPayload.FLAG_SIDE_CHANNEL or BleAdvertPayload.FLAG_DIALS_GATT_PEERS
        val p = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, flags = both))!!
        assertTrue(p.sideChannel)
        assertTrue(p.dialsGattPeers)
        val side = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, BleAdvertPayload.FLAG_SIDE_CHANNEL))!!
        assertFalse("bit 1 is its own", side.dialsGattPeers)
        assertFalse(BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1))!!.dialsGattPeers)
    }

    @Test
    fun theIosGattValueParsesWithTheDialsGattPeersFlag() {
        // knit-ios `LinkTests.theGATTValueIsThePayloadWithItsCueZeroed`: caps 0x09, cue 0, PSM 0x80, flags 0x02.
        val vector = "09294213fa67091e03c2f2ae8d071b9d6700000000008002"
        val bytes = ByteArray(vector.length / 2) { vector.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val p = BleAdvertPayload.parse(bytes)!!
        assertEquals(0x09L, p.capabilities)
        assertEquals(0, p.digestCue)
        assertEquals(0x80, p.psm)
        assertEquals(BleAdvertPayload.FLAG_DIALS_GATT_PEERS, p.flags)
        assertTrue(p.dialsGattPeers)
        assertFalse(p.sideChannel)
        val again = BleAdvertPayload.encode(p.nodeId, p.capabilities, 0L, p.psm, p.flags)
        assertEquals(vector, again.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun shortOrNullDataDecodesToNull() {
        assertNull(BleAdvertPayload.parse(null))
        assertNull(BleAdvertPayload.parse(ByteArray(10)))
        assertNull(BleAdvertPayload.parse(ByteArray(BleAdvertPayload.SIZE_V1 - 1)))
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

    @Test
    fun theFlagsByteClaimsOnlyWhatThisNodeRuns() {
        assertEquals(0, BleAdvertPayload.flags(sideLive = false, gattPeers = false, aware = false))
        assertEquals(BleAdvertPayload.FLAG_SIDE_CHANNEL, BleAdvertPayload.flags(sideLive = true, gattPeers = false, aware = false))
        assertEquals(BleAdvertPayload.FLAG_DIALS_GATT_PEERS, BleAdvertPayload.flags(sideLive = false, gattPeers = true, aware = false))
        assertEquals(
            BleAdvertPayload.FLAG_SIDE_CHANNEL or BleAdvertPayload.FLAG_DIALS_GATT_PEERS,
            BleAdvertPayload.flags(sideLive = true, gattPeers = true, aware = false),
        )
    }

    @Test
    fun theAwareBitIsBit2AndItsOwn() {
        // GitHub #44, 2t49's 2026-10-27 amendment: positive polarity, set while a peer may count on this phone's Aware.
        assertEquals(0x04, BleAdvertPayload.FLAG_AWARE)
        assertEquals(BleAdvertPayload.FLAG_AWARE, BleAdvertPayload.flags(sideLive = false, gattPeers = false, aware = true))
        val all = BleAdvertPayload.flags(sideLive = true, gattPeers = true, aware = true)
        assertEquals(0x07, all)
        val p = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, all))!!
        assertTrue(p.aware)
        assertTrue(p.sideChannel)
        assertTrue(p.dialsGattPeers)
        val side = BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, 0xFB))!!
        assertFalse("the other bits never read as it", side.aware)
    }

    @Test
    fun theAwareBitIsSetOnlyWhenAware() {
        // Bit layout only: aware=true adds bit 2 alone, aware=false sets neither bit 2 nor bit 3. The polarity's
        // regression pins are AwareCandidateTrackerTest's (a clear bit is no candidate) and AwareFlagTest's.
        for (side in listOf(false, true)) {
            for (gatt in listOf(false, true)) {
                val off = BleAdvertPayload.flags(sideLive = side, gattPeers = gatt, aware = false)
                val on = BleAdvertPayload.flags(sideLive = side, gattPeers = gatt, aware = true)
                assertEquals("flags($side, $gatt, false)", 0, off and 0x0C)
                assertEquals("flags($side, $gatt, true)", 0x04, on xor off)
                assertEquals("bits 3..7 stay reserved", 0, on and 0xF8)
            }
        }
    }

    @Test
    fun theAdvertOnAirSaysAwareOnlyWhenAware() {
        // The bytes BluetoothMeshTransport.readvertise puts on the presence and Coded sets.
        for (aware in listOf(false, true)) {
            val bytes = BleAdvertPayload.advert(nodeA, 0x7L, 42L, 0x0081, sideLive = true, gattPeers = false, aware = aware)
            assertEquals(BleAdvertPayload.SIZE, bytes.size)
            val flags = bytes[BleAdvertPayload.SIZE_V1].toInt() and 0xFF
            assertEquals(if (aware) 0x05 else 0x01, flags)
            val p = BleAdvertPayload.parse(bytes)!!
            assertEquals(aware, p.aware)
            assertTrue(p.sideChannel)
        }
    }

    @Test
    fun anOlderBuildsAdvertDoesNotSayAware() {
        // A 23-byte advert (before the flags byte) and a released build's flags byte (bit 2 never set) read not-aware.
        val v1 = BleAdvertPayload.encode(nodeA, 0L, 0L, 1).copyOf(BleAdvertPayload.SIZE_V1)
        assertFalse(BleAdvertPayload.parse(v1)!!.aware)
        assertFalse(BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, 0x03))!!.aware)
        assertFalse(BleAdvertPayload.parse(BleAdvertPayload.encode(nodeA, 0L, 0L, 1, 0x00))!!.aware)
    }
}
