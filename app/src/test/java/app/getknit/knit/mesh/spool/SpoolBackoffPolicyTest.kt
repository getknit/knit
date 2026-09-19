package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [SpoolBackoffPolicy] — the two-tier reconnect curve a relay worker runs. */
class SpoolBackoffPolicyTest {
    @Test
    fun aReachedSessionReconnectsAfterASecond() {
        assertEquals(1_000L, SpoolBackoffPolicy.waitMs(0))
        assertEquals(1_000L, SpoolBackoffPolicy.waitMs(-3))
    }

    @Test
    fun theFirstTierDoublesToAMinute() {
        // The curve every existing timing test rides: 2, 4, 8 s after the first three failures.
        assertEquals(2_000L, SpoolBackoffPolicy.waitMs(1))
        assertEquals(4_000L, SpoolBackoffPolicy.waitMs(2))
        assertEquals(8_000L, SpoolBackoffPolicy.waitMs(3))
        assertEquals(32_000L, SpoolBackoffPolicy.waitMs(5))
        assertEquals(60_000L, SpoolBackoffPolicy.waitMs(6))
        assertEquals(60_000L, SpoolBackoffPolicy.waitMs(SpoolBackoffPolicy.LONG_TIER_AFTER))
    }

    @Test
    fun theLongTierDoublesOnToFifteenMinutes() {
        val after = SpoolBackoffPolicy.LONG_TIER_AFTER
        assertEquals(120_000L, SpoolBackoffPolicy.waitMs(after + 1))
        assertEquals(240_000L, SpoolBackoffPolicy.waitMs(after + 2))
        assertEquals(480_000L, SpoolBackoffPolicy.waitMs(after + 3))
        assertEquals(900_000L, SpoolBackoffPolicy.waitMs(after + 4))
        assertEquals(900_000L, SpoolBackoffPolicy.waitMs(after + 5))
    }

    @Test
    fun aHugeStreakDoesNotOverflow() {
        assertEquals(900_000L, SpoolBackoffPolicy.waitMs(100))
        assertEquals(900_000L, SpoolBackoffPolicy.waitMs(Int.MAX_VALUE))
    }
}
