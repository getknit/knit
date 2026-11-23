package app.getknit.knit.mesh.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DialBookings]: how a dial from a stopped session releases its booking (#292). */
class DialBookingsTest {
    @Test
    fun aStaleDialReleasesOnlyItsOwnBookingNeverAnEqualOne() {
        // A dial that outlived a stop, and the next session's dial to the same peer, kind and address, hold records that
        // are equal as data. The stale one's end must leave the live booking in place.
        val stale = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, "peer", "AA:BB", linkedAtStart = false)
        val live = stale.copy()
        assertEquals("the two records are equal as data, the case this pins", stale, live)
        val inFlight = hashMapOf("peer" to live)
        assertFalse(DialBookings.releaseOwn(inFlight, "peer", stale))
        assertTrue("the live session's booking stands", inFlight["peer"] === live)
        assertTrue(DialBookings.releaseOwn(inFlight, "peer", live))
        assertTrue("its own dial releases it", inFlight.isEmpty())
        assertFalse("nothing booked, nothing released", DialBookings.releaseOwn(inFlight, "peer", live))
    }
}
