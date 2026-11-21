package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every constant in the custody-carry-policy contract's table, pinned where each lives: each is the same on every Android
 * build, and knit-ios pins the shared ones in its own tests, so a change here is a paired change.
 */
class CustodyConstantsTest {
    @Test
    fun theRoundsAreTheContractsTable() {
        val pace = CustodyPace.CONTRACT
        assertEquals(16_384, pace.plan(TransportKind.Bluetooth).bytes)
        assertEquals(2_000L, pace.plan(TransportKind.Bluetooth).intervalMs)
        assertEquals(524_288, pace.plan(TransportKind.WifiAware).bytes)
        assertEquals(1_000L, pace.plan(TransportKind.WifiAware).intervalMs)
        assertEquals(262_144, pace.plan(TransportKind.DirectLink).bytes)
        assertEquals(1_000L, pace.plan(TransportKind.DirectLink).intervalMs)
        assertEquals("any other plane is paced as Bluetooth", pace.bluetooth, pace.plan(TransportKind.Other))
    }

    @Test
    fun theAnswerTheLedgerTheMemoAndTheReofferAreTheContractsTable() {
        assertEquals(120_000L, ForwardSync.ANSWER_MAX_AGE_MS)
        assertEquals(8, CarryLedger.SPREAD_MAX)
        assertEquals(8, CarryLedger.MAX_MEMBERS)
        assertEquals(20_000L, CarryLedger.JUDGE_AFTER_BLUETOOTH_MS)
        assertEquals(5_000L, CarryLedger.JUDGE_AFTER_FAST_MS)
        assertEquals(600_000L, CarryLedger.NOT_KEPT_LAPSE_MS)
        assertEquals(64, CarryLedger.MEMO_MAX_PEERS)
        assertEquals(60_000L, ForwardSync.REOFFER_MS)
        assertEquals(600_000L, ForwardSync.REOFFER_MAX_MS)
        assertEquals(30_000L, ForwardSync.REOFFER_TOLERANCE_MS)
    }

    /** A round charges a frame its encoded `WireEnvelope` as the link writes it, from the two lengths the index reads. */
    @Test
    fun aFramesCostIsItsEncodedWireEnvelope() {
        val lengths = listOf(0, 1, 23, 24, 64, 255, 256, 15_335, 65_535, 65_536, 70_000)
        for (sig in listOf(0, 64)) {
            for (signed in lengths) {
                val wire = WireEnvelope(sig = ByteArray(sig), signed = ByteArray(signed))
                assertEquals("sig $sig, signed $signed", WireCodec.encodeWire(wire).size, IndexedFrame.wireCost(sig, signed))
            }
        }
    }
}
