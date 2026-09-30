package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LonelyDialPolicy
import app.getknit.knit.mesh.bluetooth.LonelyDialPolicy.Candidate
import app.getknit.knit.mesh.power.PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LonelyDialPolicy]: when a node alone on Bluetooth may dial a larger id, and which one (ADR 2026-09.hj4a, #103). */
class LonelyDialPolicyTest {
    private val local = "m000"
    private val alone = LONELY_AGGRESSIVE_WINDOW_MS

    private fun cand(
        nodeId: String,
        rssi: Double = -60.0,
        dwell: Long = 15_000,
        backedOff: Boolean = false,
    ) = Candidate(nodeId, rssi, dwell, backedOff)

    private fun pick(
        candidates: List<Candidate>,
        aloneForMs: Long = alone,
        linkCount: Int = 0,
        inFlight: Boolean = false,
    ) = LonelyDialPolicy.pick(local, linkCount, aloneForMs, candidates, inFlight)?.nodeId

    private fun due(
        candidates: List<Candidate>,
        aloneForMs: Long = alone,
        linkCount: Int = 0,
        inFlight: Boolean = false,
    ) = LonelyDialPolicy.msUntilDue(local, linkCount, aloneForMs, candidates, inFlight)

    @Test
    fun underTheWindowNothingIsDialed() {
        assertNull(pick(listOf(cand("z1")), aloneForMs = alone - 1))
        assertNull(pick(listOf(cand("z1")), aloneForMs = 0))
    }

    @Test
    fun atTheWindowTheStrongestEligibleLargerPeerIsDialed() {
        assertEquals("z2", pick(listOf(cand("z1", rssi = -70.0), cand("z2", rssi = -55.0), cand("z3", rssi = -80.0))))
        assertEquals("z1", pick(listOf(cand("z1")), aloneForMs = alone * 10))
    }

    @Test
    fun oneHeldLinkEndsLoneliness() {
        assertNull(pick(listOf(cand("z1")), linkCount = 1, aloneForMs = alone * 10))
        assertNull(due(listOf(cand("z1", dwell = 0)), linkCount = 1, aloneForMs = 0))
    }

    @Test
    fun aBackedOffWeakOrFreshPeerIsSkipped() {
        assertNull(pick(listOf(cand("z1", backedOff = true))))
        assertNull(pick(listOf(cand("z1", rssi = -90.5))))
        assertNull(pick(listOf(cand("z1", dwell = 11_999))))
        // The gates are PromotionConfig's own: exactly at the floor and the threshold passes.
        assertEquals("z1", pick(listOf(cand("z1", rssi = -90.0, dwell = 12_000))))
        // A skipped stronger peer does not hide an eligible weaker one.
        assertEquals("z2", pick(listOf(cand("z1", rssi = -40.0, backedOff = true), cand("z2", rssi = -75.0))))
    }

    @Test
    fun aLonelyDialAlreadyInFlightHoldsTheNext() {
        assertNull(pick(listOf(cand("z1"), cand("z2")), inFlight = true))
        assertNull(due(listOf(cand("z1", dwell = 0)), inFlight = true))
    }

    @Test
    fun onlySmallerPeersSightedIsTheOrdinaryPathsCase() {
        assertNull(pick(listOf(cand("a1"), cand("b2", rssi = -30.0))))
        assertNull(pick(listOf(cand(local))))
        assertEquals("z1", pick(listOf(cand("a1", rssi = -30.0), cand("z1", rssi = -80.0))))
    }

    @Test
    fun theLoopIsWokenAtTheWindowOrTheDwellEdgeWhicheverIsLater() {
        assertEquals(60_000L, due(listOf(cand("z1")), aloneForMs = alone - 60_000))
        assertEquals(7_000L, due(listOf(cand("z1", dwell = 5_000))))
        assertEquals(10_000L, due(listOf(cand("z1", dwell = 2_000), cand("z2", dwell = 10_000)), aloneForMs = alone - 10_000))
        assertEquals(0L, due(listOf(cand("z1")), aloneForMs = alone * 2))
    }

    @Test
    fun nothingEligibleLeavesTheWakeToAnEvent() {
        assertNull(due(emptyList(), aloneForMs = 0))
        assertNull(due(listOf(cand("a1", dwell = 0))))
        assertNull(due(listOf(cand("z1", backedOff = true), cand("z2", rssi = -95.0))))
    }
}
