package app.getknit.knit.wear

import app.getknit.knit.wear.PhoneStatusReader.FAIL_FLOOR_MS
import app.getknit.knit.wear.PhoneStatusReader.FRESH_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an unforced kick starts a read job — the gate that keeps a read's own redraws from starting another. */
class ReadDueTest {
    private val now = 10_000_000L

    @Test
    fun `no copy yet is due`() {
        assertTrue(PhoneStatusReader.due(fetchedAtMs = null, failedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `a copy within the fresh minute is not due, one past it is`() {
        assertFalse(PhoneStatusReader.due(now - FRESH_MS, failedAtMs = 0L, nowMs = now))
        assertTrue(PhoneStatusReader.due(now - FRESH_MS - 1, failedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `a read that found no phone holds off the next for the floor`() {
        val old = now - 10 * FRESH_MS
        assertFalse(PhoneStatusReader.due(old, failedAtMs = now - FAIL_FLOOR_MS + 1, nowMs = now))
        assertTrue(PhoneStatusReader.due(old, failedAtMs = now - FAIL_FLOOR_MS, nowMs = now))
        assertFalse(PhoneStatusReader.due(null, failedAtMs = now, nowMs = now))
    }

    @Test
    fun `a copy stamped ahead of the clock is due, as the reader treats it`() {
        assertTrue(PhoneStatusReader.due(now + 5_000L, failedAtMs = 0L, nowMs = now))
    }
}
