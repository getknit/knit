package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanClaimRefusalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanClaimRefusalPolicyTest {
    private var now = 0L
    private val policy = NanClaimRefusalPolicy(now = { now })

    @Test
    fun anUnmarkedPeerIsNeverHeld() {
        assertFalse(policy.isHeld("p"))
        assertFalse("forgetting nothing says so", policy.forget("p"))
    }

    @Test
    fun aRefusalHoldsThePeerForTheBaseWaitThenLets() {
        assertEquals(NanClaimRefusalPolicy.BASE_MS, policy.refused("p"))
        assertTrue(policy.isHeld("p"))
        assertFalse("other peers are untouched", policy.isHeld("q"))
        now = NanClaimRefusalPolicy.BASE_MS - 1
        assertTrue(policy.isHeld("p"))
        now = NanClaimRefusalPolicy.BASE_MS
        assertFalse("the wait ends exactly at its length", policy.isHeld("p"))
    }

    @Test
    fun theWaitOutlastsTheWatchdogsFirstTier() {
        // A refusal must never read as an owed sync long enough for Tier 1 (45 s) to cycle the session for it.
        assertTrue(NanClaimRefusalPolicy.waitMs(1) > 45_000L)
    }

    @Test
    fun aPeerRefusingAllDayWalksTheCurveToItsCeilingAndStaysThere() {
        // The real cadence: each wait runs out, the next dial is refused again at once.
        val waits = mutableListOf<Long>()
        repeat(12) {
            val wait = policy.refused("p")
            waits += wait
            now += wait
            assertFalse("held only for the wait it was given", policy.isHeld("p"))
        }
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L), waits.take(5))
        assertTrue("saturates at the ceiling", waits.drop(4).all { it == NanClaimRefusalPolicy.MAX_MS })
    }

    @Test
    fun aFreshHandleEndsTheWaitButKeepsTheGrowth() {
        policy.refused("p")
        policy.refused("p") // strikes 2: a 4 min wait
        assertTrue(policy.isHeld("p"))
        policy.lift("p")
        assertFalse("one more dial on the fresh handle", policy.isHeld("p"))
        assertEquals("refused again: the curve goes on from where it was", 480_000L, policy.refused("p"))
    }

    @Test
    fun liftingAnUnmarkedPeerDoesNothing() {
        policy.lift("p")
        assertFalse(policy.isHeld("p"))
        assertEquals(NanClaimRefusalPolicy.BASE_MS, policy.refused("p"))
    }

    @Test
    fun forgettingDropsTheWaitAndTheGrowth() {
        // LinkClaims' capable edge (a proof elsewhere, the profile gaining the bit) or released edge (it lost it).
        policy.refused("p")
        policy.refused("p")
        assertTrue(policy.forget("p"))
        assertFalse(policy.isHeld("p"))
        assertEquals("starts over at the base", NanClaimRefusalPolicy.BASE_MS, policy.refused("p"))
    }

    @Test
    fun marksAreBoundedOldestFirst() {
        repeat(NanClaimRefusalPolicy.MAX_ENTRIES) { policy.refused("id$it") }
        policy.refused("id0") // refreshed: now the newest
        policy.refused("newcomer") // evicts the oldest, id1
        assertTrue(policy.isHeld("id0"))
        assertFalse(policy.isHeld("id1"))
        assertTrue(policy.isHeld("newcomer"))
    }
}
