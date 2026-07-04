package app.getknit.knit

import app.getknit.knit.ui.util.compactTimeAgo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class RelativeTimeTest {
    // Fixed reference point so the relative output is deterministic: 2023-11-14T22:13:20Z (a Tuesday).
    private val now = 1_700_000_000_000L
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US

    private fun label(deltaMs: Long) = compactTimeAgo(now - deltaMs, now, utc, us)

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun underAMinuteIsNow() {
        assertEquals("now", label(0))
        assertEquals("now", label(30 * 1_000))
        assertEquals("now", label(minute - 1))
    }

    @Test
    fun futureTimestampClampsToNow() {
        // Clock skew (sender's clock ahead) must not produce a negative/garbage label.
        assertEquals("now", compactTimeAgo(now + 5 * minute, now, utc, us))
    }

    @Test
    fun minutesWithinTheHour() {
        assertEquals("5m", label(5 * minute))
        assertEquals("59m", label(59 * minute))
    }

    @Test
    fun hoursWithinTheDay() {
        assertEquals("1h", label(hour))
        assertEquals("2h", label(2 * hour))
        assertEquals("23h", label(23 * hour))
    }

    @Test
    fun yesterdayBeyond24Hours() {
        // 25h ago crosses into the previous calendar day.
        assertEquals("Yesterday", label(25 * hour))
    }

    @Test
    fun weekdayWithinTheLastWeek() {
        // 3 days before Tue 2023-11-14 is Sat 2023-11-11.
        assertEquals("Sat", label(3 * day))
    }

    @Test
    fun shortDateForOlderThanAWeek() {
        // 30 days before 2023-11-14 is 2023-10-15; US short date is M/d/yy.
        assertEquals("10/15/23", label(30 * day))
    }
}
