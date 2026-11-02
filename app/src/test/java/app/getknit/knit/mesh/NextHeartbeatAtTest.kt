package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The heartbeat's re-arm keeps the nominal 15-minute grid `setInexactRepeating` kept, and a chain whose tick was lost
 * reads as lapsed only a whole interval past the end of its window (ADR 2026-11.82s7).
 */
class NextHeartbeatAtTest {
    private val interval = 15 * 60_000L
    private val window = interval * 3 / 4

    @Test
    fun `the first arm is one interval out`() {
        assertEquals(1_000L + interval, nextHeartbeatAt(lastNominal = null, now = 1_000L, interval = interval))
    }

    @Test
    fun `a tick on time keeps the grid`() {
        assertEquals(2 * interval, nextHeartbeatAt(lastNominal = interval, now = interval, interval = interval))
    }

    @Test
    fun `a tick late in its window keeps the grid, not the delivery time`() {
        val late = interval + 11 * 60_000L
        assertEquals(2 * interval, nextHeartbeatAt(lastNominal = interval, now = late, interval = interval))
    }

    @Test
    fun `a tick held past its next grid point starts a fresh grid from now`() {
        val held = interval + 40 * 60_000L
        assertEquals(held + interval, nextHeartbeatAt(lastNominal = interval, now = held, interval = interval))
    }

    @Test
    fun `a tick landing exactly on the next grid point starts a fresh grid, never one due now`() {
        assertEquals(3 * interval, nextHeartbeatAt(lastNominal = interval, now = 2 * interval, interval = interval))
    }

    @Test
    fun `a heartbeat never armed has not lapsed`() {
        assertEquals(false, heartbeatLapsed(nominal = null, now = 10 * interval, interval = interval, window = window))
    }

    @Test
    fun `a heartbeat late past its window but inside the next interval has not lapsed`() {
        assertEquals(false, heartbeatLapsed(nominal = interval, now = interval + window + interval, interval = interval, window = window))
    }

    @Test
    fun `a heartbeat a whole interval past its window has lapsed`() {
        assertEquals(
            true,
            heartbeatLapsed(nominal = interval, now = interval + window + interval + 1, interval = interval, window = window),
        )
    }
}
