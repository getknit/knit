package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.bluetooth.PromotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [PromotionPolicy] — the pure dwell+RSSI promotion/eviction decision with a virtual clock. */
class PromotionPolicyTest {
    private val cfg =
        PromotionConfig(
            dwellThresholdMs = 12_000,
            rssiFloorDbm = -80,
            maxLinks = 3,
            rssiHysteresisDb = 8,
            linkMinHoldMs = 20_000,
        )

    private fun cand(
        nodeId: String,
        rssi: Double,
        dwell: Long = 15_000,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = 0L,
            psm = 100,
            digestCue = 0,
            smoothedRssi = rssi,
            dwellMs = dwell,
            lastSeenAgoMs = 0,
        )

    private fun link(
        nodeId: String,
        rssi: Double,
        age: Long = 60_000,
    ) = PromotionPolicy.LinkSnapshot(nodeId, smoothedRssi = rssi, ageMs = age, idleMs = 0)

    @Test
    fun fillsFreeSlotsWithStrongestFirst() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("a", -70.0), cand("b", -50.0), cand("c", -60.0)),
                links = emptyList(),
                backoff = emptySet(),
                config = cfg,
            )
        assertEquals(listOf("b", "c", "a"), d.promote) // strongest RSSI first
        assertTrue(d.evict.isEmpty())
    }

    @Test
    fun rssiFloorAndDwellGatePromotion() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("weak", -85.0), cand("brief", -50.0, dwell = 5_000), cand("ok", -55.0)),
                links = emptyList(),
                backoff = emptySet(),
                config = cfg,
            )
        assertEquals("only the close, long-dwelling peer promotes", listOf("ok"), d.promote)
    }

    @Test
    fun backoffPeersAreExcluded() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("a", -50.0), cand("b", -55.0)),
                links = emptyList(),
                backoff = setOf("a"),
                config = cfg,
            )
        assertEquals(listOf("b"), d.promote)
    }

    @Test
    fun budgetCapsPromotions() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("a", -50.0), cand("b", -55.0), cand("c", -60.0), cand("d", -65.0)),
                links = emptyList(),
                backoff = emptySet(),
                config = cfg, // maxLinks = 3
            )
        assertEquals(listOf("a", "b", "c"), d.promote)
    }

    @Test
    fun atBudgetASlightlyStrongerCandidateDoesNotThrashALink() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("new", -71.0)), // only 3 dB better than the weakest link
                links = listOf(link("x", -60.0), link("y", -65.0), link("weak", -74.0)),
                backoff = emptySet(),
                config = cfg, // hysteresis 8 dB
            )
        assertTrue("within hysteresis → no churn", d.promote.isEmpty() && d.evict.isEmpty())
    }

    @Test
    fun atBudgetAClearlyStrongerCandidateReplacesTheWeakestLink() {
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("new", -55.0)), // ~19 dB stronger than the weakest link
                links = listOf(link("x", -60.0), link("y", -65.0), link("weak", -74.0)),
                backoff = emptySet(),
                config = cfg,
            )
        assertEquals(listOf("new"), d.promote)
        assertEquals(listOf("weak"), d.evict)
    }

    @Test
    fun freshLinksYoungerThanMinHoldAreNotEvicted() {
        // Every link is younger than linkMinHoldMs → none is evictable, so even a very strong candidate waits.
        val d =
            PromotionPolicy.decide(
                candidates = listOf(cand("new", -50.0)),
                links = listOf(link("x", -60.0, age = 5_000), link("y", -65.0, age = 5_000), link("weak", -74.0, age = 5_000)),
                backoff = emptySet(),
                config = cfg,
            )
        assertTrue("min-hold protects fresh links", d.evict.isEmpty() && d.promote.isEmpty())
    }

    @Test
    fun strictOverflowShedsTheWeakestEvictableLinks() {
        // Five links against a budget of 3 (e.g. after accepting inbound links) → shed the two weakest.
        val d =
            PromotionPolicy.decide(
                candidates = emptyList(),
                links = listOf(link("a", -50.0), link("b", -55.0), link("c", -60.0), link("d", -70.0), link("e", -75.0)),
                backoff = emptySet(),
                config = cfg,
            )
        assertEquals(setOf("e", "d"), d.evict.toSet())
        assertTrue(d.promote.isEmpty())
    }
}
