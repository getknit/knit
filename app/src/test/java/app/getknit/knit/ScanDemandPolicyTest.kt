package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.ScanDemandPolicy
import app.getknit.knit.mesh.bluetooth.ScanDemandPolicy.Demand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- chasing ---

    @Test
    fun aChaseRunsInsideItsWindowUntilBluetoothSeesOrLinksThePeer() {
        val chase = ScanDemandPolicy.ChaseState(mapOf("far" to 60_000L))
        assertTrue(ScanDemandPolicy.chasing(chase, bleSighted = emptySet(), bleLinked = emptySet(), now = 0))
        assertFalse("the window ran out", ScanDemandPolicy.chasing(chase, emptySet(), emptySet(), now = 60_000))
        assertFalse("sighted: promotion takes it from here", ScanDemandPolicy.chasing(chase, setOf("far"), emptySet(), now = 0))
        assertFalse(ScanDemandPolicy.chasing(chase, emptySet(), setOf("far"), now = 0))
    }

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

    // --- the blinding writer (ADR 2026-11.xp6d, step 5b's W6) ---

    @Test
    fun aRefusedLegacyDialersBoostRunsUntilItsEnd() {
        assertEquals(Demand.Floor, decide(linkCount = 1, now = 10))
        assertEquals(
            Demand.Boost,
            ScanDemandPolicy.decide(1, emptySet(), ScanDemandPolicy.ChaseState(), emptySet(), emptySet(), 10, boostUntil = 11),
        )
        assertEquals(
            Demand.Floor,
            ScanDemandPolicy.decide(1, emptySet(), ScanDemandPolicy.ChaseState(), emptySet(), emptySet(), 11, boostUntil = 11),
        )
    }

    @Test
    fun aHandleFirstSightedAfterAChaseBeganEndsItEarlyAtMostOncePerCooldown() {
        val chaseMs = 60_000L
        // Two chases: one began at 1 000, one at 30 000.
        val state = ScanDemandPolicy.ChaseState(mapOf("a" to 1_000L + chaseMs, "b" to 30_000L + chaseMs))
        // A handle first sighted at 20 000 (its capture stamp): it ends the chase that began before it, not the later one.
        val ended = ScanDemandPolicy.endEarly(state, chaseMs, firstSightedAt = 20_000, lastEndAt = null, cooldownMs = 300_000, now = 25_000)
        assertEquals("ended: expired at now, kept", 25_000L, ended!!.deadlines["a"])
        assertEquals(30_000L + chaseMs, ended.deadlines["b"])
        // A handle sighted before every chase began ends nothing (a stationary stranger seen all along).
        assertEquals(
            null,
            ScanDemandPolicy.endEarly(state, chaseMs, firstSightedAt = 500, lastEndAt = null, cooldownMs = 300_000, now = 25_000),
        )
        // Inside the cooldown of the last early end: nothing, however new the handle.
        assertEquals(
            null,
            ScanDemandPolicy.endEarly(state, chaseMs, firstSightedAt = 40_000, lastEndAt = 0, cooldownMs = 300_000, now = 45_000),
        )
        // An expired chase is not "ended".
        assertEquals(
            null,
            ScanDemandPolicy.endEarly(state, chaseMs, firstSightedAt = 40_000, lastEndAt = null, cooldownMs = 300_000, now = 200_000),
        )
    }

    @Test
    fun anEndedChaseIsNotReArmedByTheNextReachChange() {
        val chaseMs = 60_000L
        val state = ScanDemandPolicy.onForeign(ScanDemandPolicy.ChaseState(), setOf("a"), now = 1_000, chaseMs = chaseMs)
        val ended = ScanDemandPolicy.endEarly(state, chaseMs, firstSightedAt = 5_000, lastEndAt = null, cooldownMs = 300_000, now = 6_000)!!
        // Aware's reach moves: the same id plus a new one.
        val next = ScanDemandPolicy.onForeign(ended, setOf("a", "b"), now = 7_000, chaseMs = chaseMs)
        assertFalse(
            "the ended chase stays ended",
            ScanDemandPolicy.chasing(
                ScanDemandPolicy.ChaseState(
                    next.deadlines.filterKeys {
                        it ==
                            "a"
                    },
                ),
                emptySet(),
                emptySet(),
                7_000,
            ),
        )
        assertTrue("the new one is chased", ScanDemandPolicy.chasing(next, emptySet(), emptySet(), 7_000))
    }
}
