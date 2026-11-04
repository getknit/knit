package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.PsmRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PsmRotation] (ADR 2026-11.xp6d's writer, step 5b's W4; the maintainer's decision of 2026-11-03): a fresh PSM per
 * rotation behind a random count of throwaway servers, the new server open before the old is retired, the old one kept
 * for its grace and then handed back to close, and a server that will not open leaving the old one current.
 */
class PsmRotationTest {
    private class Server(
        val psm: Int,
    ) {
        override fun toString() = "S$psm"
    }

    private val events = ArrayList<String>()
    private var nextPsm = 0x80
    private var failNext = false
    private var opens = 0
    private val failOpens = HashSet<Int>()

    private val rotation =
        PsmRotation(
            open = {
                opens++
                if (failNext || opens in failOpens) {
                    events += "open failed"
                    null
                } else {
                    Server(nextPsm++).also { events += "open $it" }
                }
            },
            close = { events += "close $it" },
            graceMs = 30_000,
        )

    @Test
    fun throwawaysOpenAndCloseBeforeTheAdvertisedServerOpens() {
        val first = rotation.rotate(now = 0, throwaways = 0)!!
        events.clear()
        val second = rotation.rotate(now = 1_000, throwaways = 3)!!
        assertEquals(listOf("open S129", "close S129", "open S130", "close S130", "open S131", "close S131", "open S132"), events)
        assertEquals("the advertised server opens after the throwaways", 0x84, second.psm)
        assertEquals(second, rotation.current)
        assertTrue("the old server is not closed at the rotation", events.none { it == "close $first" })
    }

    @Test
    fun theOldServerKeepsItsGraceThenIsHandedBackToClose() {
        val first = rotation.rotate(now = 0, throwaways = 0)!!
        rotation.rotate(now = 10_000, throwaways = 0)
        assertEquals(40_000L, rotation.nextDueAt())
        assertTrue("still accepting inside its grace", rotation.due(39_999).isEmpty())
        assertEquals(listOf(first), rotation.due(40_000))
        assertNull(rotation.nextDueAt())
        assertTrue(rotation.due(100_000).isEmpty())
    }

    @Test
    fun aServerThatWillNotOpenLeavesTheOldOneCurrent() {
        val first = rotation.rotate(now = 0, throwaways = 0)!!
        failNext = true
        assertNull(rotation.rotate(now = 1_000, throwaways = 0))
        assertEquals("the advert keeps a PSM rather than naming none", first, rotation.current)
        assertNull("nothing retired", rotation.nextDueAt())
    }

    @Test
    fun theThrowawayDrawIsBounded() {
        rotation.rotate(now = 0, throwaways = 1_000)
        assertEquals(PsmRotation.MAX_THROWAWAYS + 1, events.count { it.startsWith("open") })
        assertTrue(PsmRotation.GRACE_MS > 25_000)
    }

    @Test
    fun closeAllHandsBackTheCurrentAndEveryRetiringServer() {
        val first = rotation.rotate(now = 0, throwaways = 0)!!
        val second = rotation.rotate(now = 1, throwaways = 0)!!
        assertEquals(setOf(first, second), rotation.closeAll().toSet())
        assertNull(rotation.current)
        assertNull(rotation.nextDueAt())
    }

    @Test
    fun aThrowawayThatWillNotOpenMidwayStillLeavesTheAdvertisedServerLast() {
        rotation.rotate(now = 0, throwaways = 0)
        events.clear()
        failOpens += opens + 2 // the second throwaway's open fails
        val fresh = rotation.rotate(now = 1_000, throwaways = 3)!!
        assertEquals(listOf("open S129", "close S129", "open failed", "open S130", "close S130", "open S131"), events)
        assertEquals(fresh, rotation.current)
    }
}
