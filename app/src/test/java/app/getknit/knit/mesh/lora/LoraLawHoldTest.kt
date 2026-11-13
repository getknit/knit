package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer's and the ledger's halves of MeshCore's band law (ADR 2026-11.gsqu's Stage 5a-1 and 5a-2 amendments): a hold of
 * every frame the pacer sleeps to and never ticks through, a surcharge booked as air with no packet of its own, and a
 * ledger that forgets a board's radio rather than price the next board by it.
 */
class LoraLawHoldTest {
    private val board = RadioParams(8, 62_500, 5)

    private fun pace(): LoraPacePolicy {
        val airtime = LoraAirtime(traits = MeshcoreTraits).apply { onRadio(board, MeshcoreTraits.dutyPolicy(869_525, 62_500)) }
        return LoraPacePolicy(minGapMs = 0, airtime = airtime)
    }

    private fun dm() =
        OutboundFrame(
            listOf(ByteArray(80)),
            "dm",
            FrameClass.DIRECT,
            direct = DirectSend("bob", BoardAddr.MeshCore(ByteArray(6)), "f", DirectKind.MESSAGE, airings = MeshcoreTraits.airings),
        )

    @Test
    fun theLawHoldsEveryFrameUntilItsReleaseAndThePacerSleepsToIt() {
        val pace = pace()
        val frame = dm()
        pace.enqueue(frame)
        pace.holdForLaw(60_000)
        assertTrue(pace.lawHeld(1_000))
        assertNull("held, even a DM", pace.take(1_000))
        assertEquals("the pacer sleeps to the release, never a 1-s tick", 60_000L, pace.nextDueAt())
        assertSame(frame, pace.take(60_000))
        assertFalse(pace.lawHeld(60_000))
    }

    @Test
    fun aLiftedHoldLetsTheQueueGoAtOnce() {
        val pace = pace()
        val frame = dm()
        pace.enqueue(frame)
        pace.holdForLaw(3_600_000)
        assertNull(pace.take(10))
        pace.holdForLaw(null)
        assertNotNull(pace.take(11))
    }

    @Test
    fun aSurchargeIsAirWithNoPacketOfItsOwnAndCountsOnlyTowardItsCeiling() {
        val airtime = LoraAirtime(traits = MeshcoreTraits)
        airtime.onRadio(board, MeshcoreTraits.dutyPolicy(869_525, 62_500))
        airtime.recordMs(12, 1_000)
        assertEquals(12L, airtime.usedMs(1_000))
        assertEquals("a DM's surcharge is no flood of the room's", 0L, airtime.snapshot(1_000).floodUsedMs)
        airtime.recordMs(0, 1_000)
        airtime.recordMs(-5, 1_000)
        assertEquals(12L, airtime.usedMs(1_000))
        assertEquals("rolls off with the window", 0L, airtime.usedMs(1_000 + LoraAirtime.WINDOW_MS))
    }

    @Test
    fun aBoardWithNoRadioLeavesTheLedgerOnTheFallbacksNotThePreviousBoards() {
        val airtime = LoraAirtime(traits = MeshcoreTraits)
        airtime.onRadio(board, MeshcoreTraits.dutyPolicy(869_525, 62_500))
        assertTrue(airtime.dutyKnown())
        val before = airtime.airMsOfPhy(40)
        airtime.forgetRadio()
        assertFalse(airtime.dutyKnown())
        assertEquals(MeshcoreTraits.airMs(40, MeshcoreTraits.fallbackRadio), airtime.airMsOfPhy(40))
        assertEquals(MeshcoreTraits.airMs(40, board), before)
    }
}
