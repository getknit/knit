package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The pause rules `MeshService`, the chat list and the debug bridge share — pinned so no surface drifts. */
class MeshPauseTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `a deadline is now plus the offered span, and nothing for a span never offered`() {
        assertEquals(now + 15 * 60_000L, MeshPause.deadline(now, MeshPause.SHORT_MINUTES))
        assertEquals(now + 60 * 60_000L, MeshPause.deadline(now, MeshPause.LONG_MINUTES))
        assertNull(MeshPause.deadline(now, 0))
        assertNull(MeshPause.deadline(now, 720))
        assertNull(MeshPause.deadline(now, -15))
    }

    @Test
    fun `only a deadline ahead of the clock is a pause`() {
        assertNull(MeshPause.activeDeadline(null, now))
        assertNull(MeshPause.activeDeadline(now - 1, now))
        assertNull(MeshPause.activeDeadline(now, now))
        assertEquals(now + 1, MeshPause.activeDeadline(now + 1, now))
    }

    @Test
    fun `a deadline names its day only once it is past midnight`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val evening = ZonedDateTime.of(2026, 9, 20, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
        val hourLater = evening + 60 * 60_000L
        val twoHoursLater = evening + 2 * 60 * 60_000L
        assertFalse(MeshPause.crossesDay(hourLater, evening, zone))
        assertTrue(MeshPause.crossesDay(twoHoursLater, evening, zone))
        // The calendar is the zone's, not UTC's: 22:30 Los Angeles is already the next day in UTC.
        assertFalse(MeshPause.crossesDay(hourLater, evening, ZoneId.of("UTC")))
    }
}
