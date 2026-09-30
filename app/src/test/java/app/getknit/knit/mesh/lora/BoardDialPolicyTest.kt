package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.BoardDialPolicy.DIRECT_NET_MS
import app.getknit.knit.mesh.lora.BoardDialPolicy.HEALTHY_SESSION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dial-mode table behind [MeshtasticSession]'s reconnect (ADR 2026-09.hp88). */
class BoardDialPolicyTest {
    @Test
    fun theFirstDialsAfterADropAreDirect() {
        for (streak in 0 until BoardDialPolicy.DIRECT_ATTEMPTS) {
            assertEquals(DialMode.Direct, BoardDialPolicy.mode(streak, now = 10_000, lastDirectAt = 9_000))
        }
    }

    @Test
    fun aBoardThatStaysAwayIsAwaitedInTheBackground() {
        assertEquals(DialMode.Background, BoardDialPolicy.mode(3, now = 10_000, lastDirectAt = 9_000))
        assertEquals(DialMode.Background, BoardDialPolicy.mode(50, now = DIRECT_NET_MS - 1, lastDirectAt = 0))
    }

    @Test
    fun anHourOfBackgroundWaitingEarnsOneDirectDial() {
        assertEquals(DialMode.Direct, BoardDialPolicy.mode(50, now = DIRECT_NET_MS, lastDirectAt = 0))
        assertEquals("no direct dial on record yet", DialMode.Direct, BoardDialPolicy.mode(5, now = 0, lastDirectAt = null))
    }

    @Test
    fun theBackgroundWindowRunsUntilTheNetIsDue() {
        assertEquals(DIRECT_NET_MS - 20_000, BoardDialPolicy.backgroundWindowMs(now = 35_000, lastDirectAt = 15_000))
        assertEquals(0L, BoardDialPolicy.backgroundWindowMs(now = DIRECT_NET_MS + 1, lastDirectAt = 0))
        assertEquals(0L, BoardDialPolicy.backgroundWindowMs(now = 1, lastDirectAt = null))
    }

    @Test
    fun onlyASessionReadyForFiveMinutesIsHealthy() {
        assertTrue(BoardDialPolicy.healthy(readyAt = 1_000, endedAt = 1_000 + HEALTHY_SESSION_MS))
        assertFalse(BoardDialPolicy.healthy(readyAt = 1_000, endedAt = HEALTHY_SESSION_MS))
        assertFalse("a session that never went Ready", BoardDialPolicy.healthy(readyAt = null, endedAt = Long.MAX_VALUE))
    }
}
