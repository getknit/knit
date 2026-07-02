package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.PresenceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BlePresenceTracker] — the pure per-peer RSSI/dwell/linger model, driven by a virtual clock. */
class BlePresenceTrackerTest {

    private fun sighting(nodeId: String, rssi: Int, psm: Int = 100, cue: Int = 0) =
        BlePresenceTracker.Sighting(nodeId, rssi, protoVersion = 1, capabilities = 0xFL, psm = psm, digestCue = cue)

    @Test
    fun firstSightingSeedsRssiAndZeroDwell() {
        val t = BlePresenceTracker()
        t.onSighting(sighting("p", -60), now = 1_000)
        val snap = t.snapshots(1_000).single()
        assertEquals(-60.0, snap.smoothedRssi, 0.0001)
        assertEquals(0L, snap.dwellMs)
        assertEquals(0L, snap.lastSeenAgoMs)
    }

    @Test
    fun ewmaSmoothsRssiTowardNewSamples() {
        val t = BlePresenceTracker(PresenceConfig(rssiEwmaAlpha = 0.5))
        t.onSighting(sighting("p", -60), now = 0)
        t.onSighting(sighting("p", -80), now = 1_000)
        // 0.5*-80 + 0.5*-60 = -70
        assertEquals(-70.0, t.snapshots(1_000).single().smoothedRssi, 0.0001)
    }

    @Test
    fun dwellAccruesWhilePresenceIsContinuous() {
        val t = BlePresenceTracker()
        t.onSighting(sighting("p", -50), now = 0)
        t.onSighting(sighting("p", -50), now = 3_000) // within the gap-reset window
        assertEquals(5_000L, t.snapshots(5_000).single().dwellMs)
    }

    @Test
    fun aGapLongerThanResetRestartsDwellAndReseedsRssi() {
        val t = BlePresenceTracker(PresenceConfig(presenceGapResetMs = 8_000))
        t.onSighting(sighting("p", -50), now = 0)
        t.onSighting(sighting("p", -90), now = 9_000) // gap 9s > 8s → reset
        val snap = t.snapshots(9_000).single()
        assertEquals("dwell restarts", 0L, snap.dwellMs)
        assertEquals("RSSI reseeds, not averaged across the gap", -90.0, snap.smoothedRssi, 0.0001)
    }

    @Test
    fun aPeerIsPrunedAfterTheLingerWindow() {
        val t = BlePresenceTracker(PresenceConfig(reachableLingerMs = 90_000))
        t.onSighting(sighting("p", -50), now = 0)
        assertTrue(t.snapshots(90_000).isNotEmpty()) // exactly at the window still present
        assertTrue("pruned once past the linger", t.snapshots(90_001).isEmpty())
        assertNull(t.psmFor("p"))
    }

    @Test
    fun reachableExposesSeenPeersAndPsmIsLookedUp() {
        val t = BlePresenceTracker()
        t.onSighting(sighting("aaaa1111", -55, psm = 137), now = 0)
        t.onSighting(sighting("bbbb2222", -70, psm = 141), now = 0)
        assertEquals(setOf("aaaa1111", "bbbb2222"), t.reachable(0).map { it.nodeId }.toSet())
        assertEquals(137, t.psmFor("aaaa1111"))
    }
}
