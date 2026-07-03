package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.ScanDemandPolicy
import app.getknit.knit.mesh.bluetooth.ScanDemandPolicy.Demand
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [ScanDemandPolicy] — the pure Boost/Floor scan-cadence decision + chase fold, virtual clock. */
class ScanDemandPolicyTest {

    private fun decide(
        linkCount: Int,
        candidates: Set<String> = emptySet(),
        chase: ScanDemandPolicy.ChaseState = ScanDemandPolicy.ChaseState(),
        sighted: Set<String> = emptySet(),
        linked: Set<String> = emptySet(),
        now: Long = 0,
    ) = ScanDemandPolicy.decide(linkCount, candidates, chase, sighted, linked, now)

    // --- decide ---

    @Test
    fun boostsWhenAPromotableCandidateExists() {
        assertEquals(Demand.Boost, decide(linkCount = 2, candidates = setOf("a")))
    }

    @Test
    fun floorsWhenAllReachableAreLinkedAndAtLeastOneLink() {
        assertEquals(Demand.Floor, decide(linkCount = 2))
    }

    @Test
    fun neverFloorsWhenIsolated() {
        // 0 links → keep the aggressive lonely-rejoin cadence even with nothing to promote.
        assertEquals(Demand.Boost, decide(linkCount = 0))
    }

    @Test
    fun boostsForAForeignPeerInsideItsChaseWindow() {
        val chase = ScanDemandPolicy.ChaseState(mapOf("z" to 100L))
        assertEquals(Demand.Boost, decide(linkCount = 1, chase = chase, now = 50))
    }

    @Test
    fun floorsAfterTheChaseWindowExpires() {
        val chase = ScanDemandPolicy.ChaseState(mapOf("z" to 100L))
        assertEquals(Demand.Floor, decide(linkCount = 1, chase = chase, now = 150))
    }

    @Test
    fun aForeignPeerAlreadyBleSightedDoesNotCountAsChase() {
        // Once we can see it on BLE, promotion is driven by the candidate set, not the chase — so with no
        // candidate it floors instead of chasing a peer we already have presence for.
        val chase = ScanDemandPolicy.ChaseState(mapOf("z" to 100L))
        assertEquals(Demand.Floor, decide(linkCount = 1, chase = chase, sighted = setOf("z"), now = 50))
    }

    @Test
    fun aForeignPeerAlreadyLinkedDoesNotCountAsChase() {
        val chase = ScanDemandPolicy.ChaseState(mapOf("z" to 100L))
        assertEquals(Demand.Floor, decide(linkCount = 1, chase = chase, linked = setOf("z"), now = 50))
    }

    // --- onForeign (chase fold) ---

    @Test
    fun armsChaseDeadlineOnRisingEdge() {
        val s = ScanDemandPolicy.onForeign(ScanDemandPolicy.ChaseState(), setOf("z"), now = 0, chaseMs = 60_000)
        assertEquals(60_000L, s.deadlines["z"])
    }

    @Test
    fun keepsOriginalDeadlineWhileContinuouslyPresent() {
        val armed = ScanDemandPolicy.onForeign(ScanDemandPolicy.ChaseState(), setOf("z"), now = 0, chaseMs = 60_000)
        val later = ScanDemandPolicy.onForeign(armed, setOf("z"), now = 30_000, chaseMs = 60_000)
        assertEquals("not re-armed while it stays in the set", 60_000L, later.deadlines["z"])
    }

    @Test
    fun dropsADepartedPeer() {
        val armed = ScanDemandPolicy.onForeign(ScanDemandPolicy.ChaseState(), setOf("z"), now = 0, chaseMs = 60_000)
        val gone = ScanDemandPolicy.onForeign(armed, emptySet(), now = 10_000, chaseMs = 60_000)
        assertEquals(emptyMap<String, Long>(), gone.deadlines)
    }

    @Test
    fun reArmsOnPresenceReset() {
        val armed = ScanDemandPolicy.onForeign(ScanDemandPolicy.ChaseState(), setOf("z"), now = 0, chaseMs = 60_000)
        val gone = ScanDemandPolicy.onForeign(armed, emptySet(), now = 10_000, chaseMs = 60_000)
        val back = ScanDemandPolicy.onForeign(gone, setOf("z"), now = 90_000, chaseMs = 60_000)
        assertEquals("a returning peer arms a fresh window", 150_000L, back.deadlines["z"])
    }
}
