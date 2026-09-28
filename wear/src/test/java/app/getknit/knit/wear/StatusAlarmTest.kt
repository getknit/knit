package app.getknit.knit.wear

import org.junit.Assert.assertTrue
import org.junit.Test

class StatusAlarmTest {
    @Test
    fun `the latest an inexact alarm is delivered lands on the interval, not three quarters past it`() {
        for (interval in listOf(StatusAlarm.INTERVAL_MS, StatusAlarm.AWAY_INTERVAL_MS)) {
            val lead = StatusAlarm.lead(interval)
            val latest = lead + lead * 3 / 4
            assertTrue("$latest > $interval", latest <= interval)
            assertTrue("$latest is more than a second early", interval - latest < 1_000L)
        }
    }
}
