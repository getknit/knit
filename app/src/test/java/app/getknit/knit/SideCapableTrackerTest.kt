package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.SideCapableTracker
import app.getknit.knit.mesh.bluetooth.SideCapableTracker.Audience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SideCapableTracker] — who nearby can hear the BLE side channel, on a virtual clock. */
class SideCapableTrackerTest {
    private val linger = 1_000L
    private val tracker = SideCapableTracker(lingerMs = linger)

    @Test
    fun aFlaggedSightingCountsUntilItsLingerRunsOut() {
        assertFalse(tracker.anyCapable(now = 0, linked = emptySet()))
        tracker.note("a", capable = true, now = 0)
        assertTrue(tracker.anyCapable(now = linger - 1, linked = emptySet()))
        assertFalse(tracker.anyCapable(now = linger, linked = emptySet()))
    }

    @Test
    fun aLinkedPeerKeepsItsFlagPastTheLinger() {
        // Presence prunes a linked peer the floored scan stops re-sighting; the link retains what it was sighted with.
        tracker.note("a", capable = true, now = 0)
        assertTrue(tracker.anyCapable(now = linger * 10, linked = setOf("a")))
        assertFalse("…but only while linked", tracker.anyCapable(now = linger * 10, linked = emptySet()))
    }

    @Test
    fun anUnflaggedSightingClearsTheFlagEvenForALinkedPeer() {
        tracker.note("a", capable = true, now = 0)
        tracker.note("a", capable = false, now = 1)
        assertFalse(tracker.anyCapable(now = 1, linked = setOf("a")))
    }

    @Test
    fun aPeerNeverSightedWithTheFlagDoesNotCountJustBecauseItIsLinked() {
        assertFalse(tracker.anyCapable(now = 0, linked = setOf("inbound")))
    }

    @Test
    fun theAudienceSaysWhetherALinkAlreadyReachesEveryone() {
        assertEquals(Audience.Nobody, tracker.audience(now = 0, linked = setOf("a")))
        tracker.note("a", capable = true, now = 0)
        assertEquals(Audience.SomeUnlinked, tracker.audience(now = 0, linked = emptySet()))
        assertEquals(Audience.AllLinked, tracker.audience(now = 0, linked = setOf("a")))
        tracker.note("b", capable = true, now = 0)
        assertEquals("one unlinked flagged peer is enough", Audience.SomeUnlinked, tracker.audience(now = 0, linked = setOf("a")))
        assertEquals(Audience.AllLinked, tracker.audience(now = 0, linked = setOf("a", "b")))
        assertEquals(
            "a linked peer never flagged does not widen it",
            Audience.AllLinked,
            tracker.audience(now = 0, linked = setOf("a", "b", "c")),
        )
    }

    @Test
    fun aDroppedLinkLingersFromItsEndOnceTouched() {
        // Sighted long ago, linked since: the stamp is stale. Untouched, the drop prunes it at once.
        tracker.note("a", capable = true, now = 0)
        assertEquals(Audience.AllLinked, tracker.audience(now = linger * 10, linked = setOf("a")))
        assertEquals(Audience.Nobody, tracker.audience(now = linger * 10, linked = emptySet()))
        // Touched at the drop (what the transport does at teardown), it is the unlinked peer a page reaches.
        tracker.note("a", capable = true, now = 0)
        tracker.touch("a", now = linger * 10)
        assertEquals(Audience.SomeUnlinked, tracker.audience(now = linger * 11 - 1, linked = emptySet()))
        assertEquals(Audience.Nobody, tracker.audience(now = linger * 11, linked = emptySet()))
    }

    @Test
    fun touchCannotMakeAnUnknownPeerCapable() {
        tracker.touch("never-flagged", now = 0)
        assertEquals(Audience.Nobody, tracker.audience(now = 0, linked = emptySet()))
        tracker.note("a", capable = true, now = 0)
        tracker.note("a", capable = false, now = 1)
        tracker.touch("a", now = 2)
        assertEquals(
            "an unflagged sighting is final until the next flagged one",
            Audience.Nobody,
            tracker.audience(now = 2, linked = setOf("a")),
        )
    }

    @Test
    fun forgetAndClear() {
        tracker.note("a", capable = true, now = 0)
        tracker.note("b", capable = true, now = 0)
        tracker.forget("a")
        assertTrue(tracker.anyCapable(now = 0, linked = emptySet()))
        tracker.clear()
        assertFalse(tracker.anyCapable(now = 0, linked = setOf("a", "b")))
    }
}
