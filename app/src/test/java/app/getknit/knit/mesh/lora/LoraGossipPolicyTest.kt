package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraGossipPolicyTest {
    /** No jitter, so the transmit point is exactly the interval's midpoint and the schedule is readable. */
    private fun policy(
        min: Long = 5 * 60_000,
        max: Long = 15 * 60_000,
        redundancy: Int = 1,
    ) = LoraGossipPolicy(minIntervalMs = min, maxIntervalMs = max, redundancy = redundancy, random = { 0 })

    /**
     * A policy whose first interval starts at t=0. The timer arms on its first question rather than at
     * construction — which is what the transport's gossip loop does on its first pass — so a test that starts
     * asking at t=100_000 would otherwise be measuring an interval that began there.
     */
    private fun armed(
        min: Long = 5 * 60_000,
        max: Long = 15 * 60_000,
        redundancy: Int = 1,
    ) = policy(min, max, redundancy).also { it.nextDueAt(0) }

    @Test
    fun theFirstTransmitLandsInTheSecondHalfOfTheFirstInterval() {
        val p = policy()
        assertEquals(150_000L, p.nextDueAt(0))
        assertFalse("listen first", p.takeTransmitSlot(149_999))
        assertTrue(p.takeTransmitSlot(150_000))
    }

    @Test
    fun oneIntervalTransmitsAtMostOnce() {
        val p = armed()
        assertTrue(p.takeTransmitSlot(150_000))
        assertFalse(p.takeTransmitSlot(160_000))
        assertFalse(p.takeTransmitSlot(299_999))
    }

    @Test
    fun theIntervalDoublesWhileNothingChangesAndStopsAtTheCeiling() {
        val p = armed()
        assertEquals(5 * 60_000L, p.interval)
        p.nextDueAt(5 * 60_000) // interval 1 (0..5 min) elapsed
        assertEquals(10 * 60_000L, p.interval)
        p.nextDueAt(15 * 60_000) // interval 2 (5..15 min) elapsed
        assertEquals(15 * 60_000L, p.interval)
        p.nextDueAt(30 * 60_000)
        assertEquals("capped", 15 * 60_000L, p.interval)
    }

    @Test
    fun aResetSnapsBackToTheFloor() {
        val p = armed()
        p.nextDueAt(5 * 60_000)
        p.nextDueAt(15 * 60_000)
        assertEquals(15 * 60_000L, p.interval)
        p.reset(20 * 60_000)
        assertEquals(5 * 60_000L, p.interval)
        assertEquals("and re-arms from now", 20 * 60_000L + 150_000, p.nextDueAt(20 * 60_000))
    }

    @Test
    fun anIdenticalOfferFromSomeoneElseSuppressesOurs() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse("theirs said exactly what ours would", p.takeTransmitSlot(150_000))
    }

    @Test
    fun anOfferAnnouncingADifferentSetDoesNotSuppressOurs() {
        // A peer holding a superset has not spoken on our behalf — it has said the opposite of what we need
        // to say, which is "here is what I am missing".
        val p = armed()
        p.onOffer(sameSet = false, now = 100_000)
        assertTrue(p.takeTransmitSlot(150_000))
    }

    @Test
    fun anOfferAnnouncingADifferentSetSnapsABackedOffIntervalToTheFloor() {
        // The other half of Trickle: an inconsistent transmission resets the timer whether or not we act on
        // it. Hearing a set that is not ours is the first sign a divergent pocket is on the channel.
        val p = armed()
        p.nextDueAt(5 * 60_000)
        val backedOff = p.nextDueAt(15 * 60_000) // interval 3 is 15..30 min, transmit at 22.5 min
        assertEquals(15 * 60_000L, p.interval)

        p.onOffer(sameSet = false, now = 16 * 60_000)

        assertEquals(5 * 60_000L, p.interval)
        val due = p.nextDueAt(16 * 60_000)
        assertTrue("and the transmit point moves up", due < backedOff)
        assertEquals(16 * 60_000L + 150_000, due)
    }

    @Test
    fun aResetDoesNotPushAnAlreadyPendingTransmitLater() {
        // A reset draws a fresh transmit point, which can land after one this interval already picked and has
        // not spent. News must never delay the very transmission it asks for, so the earlier slot stands.
        val p = armed()
        p.nextDueAt(5 * 60_000)
        p.nextDueAt(15 * 60_000)
        assertEquals("interval 3 runs 15..30 min", 22 * 60_000L + 30_000, p.nextDueAt(15 * 60_000))

        // A fresh floor interval opened at 21 min would transmit at 23.5 min, which is later than that.
        p.onOffer(sameSet = false, now = 21 * 60_000)

        assertEquals(5 * 60_000L, p.interval)
        assertEquals("the earlier pending point stands", 22 * 60_000L + 30_000, p.nextDueAt(21 * 60_000))
    }

    @Test
    fun aResetDoesNotCancelATransmitThatIsAlreadyDue() {
        val p = armed()
        p.reset(200_000) // past this interval's 150_000 transmit point, which the loop has not woken for yet
        assertTrue("still due, not pushed to the new interval's own point", p.takeTransmitSlot(200_000))
    }

    @Test
    fun sustainedDivergenceDoesNotBeatTheFloorCadence() {
        // Two gateways that cannot converge — the serve cap spent, or a permanent superset — would otherwise
        // reset each other on every offer forever, spending the bridge budget on gossip rather than backfill.
        val p = armed()
        assertTrue(p.takeTransmitSlot(150_000))
        p.onOffer(sameSet = false, now = 160_000)
        p.onOffer(sameSet = false, now = 200_000)
        assertFalse("the slot stays spent", p.takeTransmitSlot(250_000))
        assertEquals("and this interval still ends where it did", 300_000L, p.nextDueAt(250_000))
    }

    @Test
    fun aSuppressedIntervalStillConsumesItsSlotRatherThanRetryingEveryWakeUp() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse(p.takeTransmitSlot(150_000))
        assertFalse(p.takeTransmitSlot(160_000))
    }

    @Test
    fun suppressionDoesNotCarryIntoTheNextInterval() {
        val p = armed()
        p.onOffer(sameSet = true, now = 100_000)
        assertFalse(p.takeTransmitSlot(150_000))
        // Interval 2 begins when the timer is next consulted after interval 1 expires — it runs
        // 300_000..900_000, so with no jitter its transmit point is 600_000.
        p.nextDueAt(300_000)
        assertTrue(p.takeTransmitSlot(600_000))
    }

    @Test
    fun theFirstIntervalIsNotWreckedByTheNeverSentinel() {
        // `now - Long.MIN_VALUE` wraps; the same overflow that once blocked the first LoRa profile beacon.
        val p = policy()
        assertTrue(p.nextDueAt(0) > 0)
        assertTrue(p.takeTransmitSlot(150_000))
    }
}
