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
        assertEquals(524_288, pace.plan(TransportKind.WifiAware).bytes)
        assertEquals(262_144, pace.plan(TransportKind.DirectLink).bytes)
        assertEquals("any other plane is cut as Bluetooth", pace.bluetooth, pace.plan(TransportKind.Other))
    }

    /**
     * How soon a round follows a drain (§2): looked for every 100 ms where a link neither signals nor estimates, and,
     * Android's own choice, at most a second beside a signal or an estimate. A failed round's retry-after is a second.
     */
    @Test
    fun theLooksAreTheContractsAndAndroidsOwn() {
        assertEquals(100L, CustodyPace.ROUND_CHECK_MS)
        assertEquals(1_000L, CustodyPace.SIGNAL_FALLBACK_MS)
        assertEquals(1_000L, ForwardSync.CUSTODY_RETRY_MS)
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

    /**
     * The estimate's look ([RoundPlan.lookIn]): (unwritten − round) at the plane's rate, rounded up, never under a
     * millisecond and never past the fallback; a link that ended ([LinkOutbound.NOT_DRAINING]) or gives no rate is looked
     * at at the fallback, with no overflow.
     */
    @Test
    fun theEstimatesLookIsTheRemainderAtTheRate() {
        val plan = CustodyPace.CONTRACT.bluetooth
        assertEquals(501L, plan.lookIn(LinkOutbound(16_384L + 8_192L, drainBytesPerSec = 16_384L)))
        assertEquals(1L, plan.lookIn(LinkOutbound(16_384L, drainBytesPerSec = 1_000_000L)))
        assertEquals(1_000L, plan.lookIn(LinkOutbound(16_384L + 100_000L, drainBytesPerSec = 1_024L)))
        assertEquals(1_000L, plan.lookIn(LinkOutbound(LinkOutbound.NOT_DRAINING, drainBytesPerSec = 4_096L)))
        assertEquals(1_000L, plan.lookIn(LinkOutbound(40_000L)))
        assertEquals(1_000L, plan.lookIn(LinkOutbound(40_000L, drainBytesPerSec = 0L)))
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
