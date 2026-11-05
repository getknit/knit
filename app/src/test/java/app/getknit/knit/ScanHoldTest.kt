package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.ScanHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ScanHold]: the debug scan hold's bound, its expiry, its release and its renewal (#285). */
class ScanHoldTest {
    private val minute = 60_000L

    @Test
    fun aHoldLastsThirtyMinutesUnlessAskedAndNeverPastTwoHours() {
        assertEquals(30, ScanHold.minutes(null))
        assertEquals(5, ScanHold.minutes(5))
        assertEquals(120, ScanHold.minutes(600))
        assertEquals("a zero or negative ask still holds a minute", 1, ScanHold.minutes(0))
        assertEquals(1, ScanHold.minutes(-3))
    }

    @Test
    fun aHoldStandsUntilItExpiresAndSaysSoOnce() {
        val hold = ScanHold()
        assertFalse(hold.held(0))
        assertNull(hold.remainingMs(0))
        assertEquals(ScanHold.Started(30, renewed = false), hold.start(1_000, null))
        assertTrue(hold.held(1_000))
        assertEquals(30 * minute, hold.remainingMs(1_000))
        assertFalse(hold.expire(1_000 + 30 * minute - 1))
        assertTrue(hold.held(1_000 + 30 * minute - 1))
        assertFalse("ended exactly at its time", hold.held(1_000 + 30 * minute))
        assertNull(hold.remainingMs(1_000 + 30 * minute))
        assertTrue(hold.expire(1_000 + 30 * minute))
        assertFalse("an expiry is reported once", hold.expire(1_000 + 31 * minute))
        assertFalse("an expired hold has nothing to release", hold.end())
        assertFalse("a start after an expiry is new", hold.start(40 * minute, 5).renewed)
    }

    /** A bridge start after the deadline but before the scan loop's `expire` ran: the lapsed hold reads as new, so it wakes. */
    @Test
    fun aStartPastTheDeadlineIsNewBeforeTheLoopExpiresIt() {
        val hold = ScanHold()
        hold.start(0, 5)
        assertEquals(ScanHold.Started(5, renewed = false), hold.start(5 * minute, 5))
    }

    @Test
    fun aReleaseOrAStopEndsAHoldAtOnce() {
        val hold = ScanHold()
        assertFalse("nothing to end", hold.end())
        hold.start(0, 10)
        assertTrue(hold.end())
        assertFalse(hold.held(1))
        assertFalse("a released hold never expires later", hold.expire(11 * minute))
        assertFalse("a start after a release is new", hold.start(12 * minute, 5).renewed)
    }

    @Test
    fun aRenewalRunsFromNowAndRestartsTheCount() {
        val hold = ScanHold()
        hold.start(0, 10)
        hold.onHeldGap()
        hold.onHeldGap()
        assertEquals(2, hold.heldGaps())
        assertEquals("a renewal wakes nothing", ScanHold.Started(10, renewed = true), hold.start(9 * minute, 10))
        assertEquals(0, hold.heldGaps())
        assertTrue(hold.held(15 * minute))
        assertEquals(10 * minute, hold.remainingMs(9 * minute))
        assertTrue(hold.expire(19 * minute))
    }
}
