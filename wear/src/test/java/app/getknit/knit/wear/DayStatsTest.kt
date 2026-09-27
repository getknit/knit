package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayStatsTest {
    private val midnight = 1_790_000_000_000L
    private val min = 60_000L

    private fun at(
        minutes: Long,
        state: MeshState = MeshState.Linked,
        nearby: Int = 2,
        relayed: Long = 0,
    ) = Sample(midnight + minutes * min, state, nearby, far = 0, relayed = relayed, carrying = null)

    @Test
    fun `relayed today sums the rises across today's readings`() {
        val samples = listOf(at(60, relayed = 100), at(65, relayed = 110), at(70, relayed = 125))
        assertEquals(25L, DayStats.today(samples, midnight, midnight + 75 * min).relayed)
    }

    @Test
    fun `a single reading is not yet a day's count`() {
        assertNull(DayStats.today(listOf(at(60, relayed = 100)), midnight, midnight + 61 * min).relayed)
    }

    @Test
    fun `the pair across midnight counts only when it is one span apart`() {
        val close = listOf(at(-4, relayed = 100), at(1, relayed = 104))
        assertEquals(4L, DayStats.today(close, midnight, midnight + 2 * min).relayed)
        val overnight = listOf(at(-300, relayed = 100), at(420, relayed = 180), at(425, relayed = 181))
        assertEquals(1L, DayStats.today(overnight, midnight, midnight + 430 * min).relayed)
    }

    @Test
    fun `a counter that falls is a new baseline, not a negative day`() {
        val restored = listOf(at(60, relayed = 900), at(65, relayed = 3), at(70, relayed = 8))
        assertEquals(5L, DayStats.today(restored, midnight, midnight + 75 * min).relayed)
    }

    @Test
    fun `each reading stands for its state until the next, but never beyond one span`() {
        val samples =
            listOf(
                at(0, MeshState.Linked),
                at(5, MeshState.Alone, nearby = 0),
                // A gap of an hour: the Alone reading covers ten minutes of it, no more.
                at(65, MeshState.Paused, nearby = 0),
            )
        val share = DayStats.today(samples, midnight, midnight + 70 * min).share
        assertEquals(5 * min, share.linkedMs)
        assertEquals(10 * min, share.aloneMs)
        assertEquals(5 * min, share.restingMs)
        assertEquals(0L, share.weakMs)
    }

    @Test
    fun `the linked run and the last peer`() {
        val linked = listOf(at(0, MeshState.Alone, 0), at(5), at(10), at(15))
        val now = midnight + 16 * min
        assertEquals(midnight + 5 * min, DayStats.today(linked, midnight, now).linkedSinceMs)
        assertNull(DayStats.today(linked, midnight, now).lastPeerAtMs)

        val gone = linked + at(20, MeshState.Alone, 0)
        val later = DayStats.today(gone, midnight, midnight + 21 * min)
        assertNull(later.linkedSinceMs)
        assertEquals(midnight + 15 * min, later.lastPeerAtMs)

        // A Linked reading gone stale is no longer a run.
        assertNull(DayStats.today(linked, midnight, midnight + 60 * min).linkedSinceMs)
    }

    @Test
    fun `the busiest moment is today's most peers, first reached`() {
        val samples = listOf(at(-30, nearby = 20), at(10, nearby = 3), at(20, nearby = 9), at(30, nearby = 9))
        assertEquals(9 to midnight + 20 * min, DayStats.today(samples, midnight, midnight + 40 * min).busiest)
    }

    @Test
    fun `the chart takes each step's most and leaves gaps where there is no reading`() {
        val samples = listOf(at(0, nearby = 1), at(5, nearby = 4), at(35, nearby = 2))
        val series = DayStats.nearbySeries(samples, midnight, midnight + 40 * min, 4)
        assertEquals(listOf(4, null, null, 2), series)
    }
}
