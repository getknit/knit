package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy
import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy.ALONE_PERIOD_MS
import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy.LINKED_PERIOD_MS
import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy.MIN_WAIT_MS
import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy.SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [AdvertReassertPolicy] — when the presence set is enabled again (#112). */
class AdvertReassertPolicyTest {
    private fun keeper(at: Long = 0L) = AdvertReassertPolicy.Keeper().also { it.start(at) }

    @Test
    fun theNetIsTenSecondsAloneAndAMinuteLinked() {
        val k = keeper(at = 1_000L)
        assertFalse(k.isDue(1_000L + ALONE_PERIOD_MS - 1, linked = false))
        assertTrue(k.isDue(1_000L + ALONE_PERIOD_MS, linked = false))
        assertFalse(k.isDue(1_000L + ALONE_PERIOD_MS, linked = true))
        assertTrue(k.isDue(1_000L + LINKED_PERIOD_MS, linked = true))
        assertEquals(ALONE_PERIOD_MS, k.waitMs(1_000L, linked = false))
        assertEquals(LINKED_PERIOD_MS, k.waitMs(1_000L, linked = true))
    }

    @Test
    fun aConnectionEdgeComesDueAfterTheSettleAndKeepsTheEarliest() {
        val k = keeper()
        k.onConnectionEdge(1_000L)
        k.onConnectionEdge(2_000L) // a second edge never pushes the first one back
        assertFalse(k.isDue(1_000L + SETTLE_MS - 1, linked = true))
        assertTrue(k.isDue(1_000L + SETTLE_MS, linked = true))
        assertEquals(SETTLE_MS, k.waitMs(1_000L, linked = true))
    }

    @Test
    fun anEnableSpendsTheEdgeAndRestartsTheNet() {
        val k = keeper()
        k.onConnectionEdge(1_000L)
        val at = 1_000L + SETTLE_MS
        k.reasserted(at)
        assertFalse(k.isDue(at + 1, linked = false))
        assertEquals(ALONE_PERIOD_MS, k.waitMs(at, linked = false))
    }

    @Test
    fun anEnableLeavesALaterEdgeStanding() {
        val k = keeper()
        k.onConnectionEdge(5_000L) // due at 7_500, after the enable below
        k.reasserted(6_000L)
        assertEquals(1_500L, k.waitMs(6_000L, linked = true))
    }

    @Test
    fun aRefusalIsRetriedOnADoublingWaitThatCaps() {
        val k = keeper()
        assertEquals(2_500L, k.onRefused(0L))
        assertEquals(5_000L, k.onRefused(0L))
        assertEquals(10_000L, k.onRefused(0L))
        assertEquals(20_000L, k.onRefused(0L)) // the first wait at the cap is still reported
        assertNull("a refusal repeated at the cap is quiet", k.onRefused(0L))
        assertEquals(20_000L, k.waitMs(0L, linked = true))
    }

    @Test
    fun theRetryComesDueBeforeTheNet() {
        val k = keeper()
        k.onRefused(1_000L)
        assertTrue(k.isDue(3_500L, linked = true))
        k.reasserted(3_500L)
        assertEquals(LINKED_PERIOD_MS, k.waitMs(3_500L, linked = true)) // spent; the next refusal schedules its own
    }

    @Test
    fun anEnableAfterRefusalsSaysHowLongAndResetsTheWait() {
        val k = keeper()
        k.onRefused(1_000L)
        k.onRefused(3_500L)
        assertEquals(7_000L, k.onEnabled(8_000L))
        assertNull("nothing was refused since", k.onEnabled(9_000L))
        assertEquals(2_500L, k.onRefused(10_000L)) // the wait starts over
    }

    @Test
    fun aHealIsDueAtOnce() {
        val k = keeper()
        k.dueNow(4_000L)
        assertTrue(k.isDue(4_000L, linked = true))
    }

    @Test
    fun theWaitNeverDropsBelowTheFloor() {
        val k = keeper()
        k.dueNow(4_000L)
        assertEquals(MIN_WAIT_MS, k.waitMs(9_000L, linked = true))
    }

    @Test
    fun startClearsAnEarlierRun() {
        val k = keeper()
        k.onRefused(0L)
        k.onConnectionEdge(0L)
        k.start(100_000L)
        assertNull(k.onEnabled(100_001L))
        assertEquals(ALONE_PERIOD_MS, k.waitMs(100_000L, linked = false))
    }
}
