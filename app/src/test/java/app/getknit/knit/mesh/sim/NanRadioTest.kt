package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.BulkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NanRadio] over a sidewalk of passes ([PassBy]), without stacks. */
class NanRadioTest {
    private val sidewalk = PassBy.of(seed = 5, pairs = 6, dropM = DROP_M)

    // Every step's changes over two minutes of the sidewalk.
    private fun run(radio: Radio): List<LinkChanges> =
        (0L..RUN_MS step TICK_MS).map { atMs -> radio.step(atMs, (0 until 12).map { sidewalk.position(it, atMs) }) }

    private fun nan(
        failShare: Double = 0.0,
        windowMs: Long = NanRadio.WINDOW_MS,
    ) = NanRadio(seed = 5, connectM = RANGE_M, dropM = DROP_M, failShare = failShare, windowMs = windowMs)

    @Test
    fun drawsAreTheSameForTheSameSeed() {
        assertEquals(run(nan()), run(nan()))
    }

    @Test
    fun withNoDataPathItIsBluetoothAlone() {
        val nan = nan(failShare = 1.0)
        val ble = BleRadio(seed = 5, connectM = RANGE_M, dropM = DROP_M)
        val withNan = run(nan)
        val alone = run(ble)
        // As sets: Bluetooth raises a step's links nearest first, this radio in walker order.
        assertEquals(
            "the links come and go as Bluetooth's",
            alone.map { it.raised.toSet() to it.dropped.toSet() },
            withNan.map { it.raised.toSet() to it.dropped.toSet() },
        )
        assertTrue("nothing moved plane", withNan.all { it.rerated.isEmpty() })
        assertFalse("no route read linked", withNan.any { c -> BulkRoute.Linked in c.routes.values })
        assertTrue("discovery made routes reachable", withNan.any { c -> BulkRoute.Reachable in c.routes.values })
    }

    @Test
    fun aDataPathRaisesTheRateWhileItIsUpAndTheLinkFallsBackAfter() {
        val radio = nan(windowMs = SHORT_WINDOW_MS)
        var sawFast = false
        var sawBack = false
        for (atMs in 0L..RUN_MS step TICK_MS) {
            val changes = radio.step(atMs, (0 until 12).map { sidewalk.position(it, atMs) })
            for (link in changes.rerated + changes.raised) {
                val air = checkNotNull(radio.airOf(link))
                if (air.bytesPerSecond == NanRadio.NAN_BYTES_PER_S) {
                    sawFast = true
                    assertFalse("a data path shares the Bluetooth radio", air.sharesNodeAir)
                    assertEquals("a pair on a data path reads linked", BulkRoute.Linked, changes.routes[link])
                } else if (link in changes.rerated) {
                    sawBack = true
                    assertEquals(radio.air, air)
                }
            }
        }
        assertTrue("no pair ever rode a data path", sawFast)
        assertTrue("no data path ever ended under a link", sawBack)
    }

    @Test
    fun theBluetoothHalfIsUntouchedByAware() {
        // The union of the links is Bluetooth's plus the data paths': every Bluetooth link is up on this radio too.
        val nan = nan()
        val ble = BleRadio(seed = 5, connectM = RANGE_M, dropM = DROP_M)
        for (atMs in 0L..RUN_MS step TICK_MS) {
            val positions = (0 until 12).map { sidewalk.position(it, atMs) }
            nan.step(atMs, positions)
            ble.step(atMs, positions)
            assertTrue("at $atMs a Bluetooth link is missing", nan.links.containsAll(ble.links))
        }
    }

    private companion object {
        const val RANGE_M = 15.0
        const val DROP_M = 21.0
        const val TICK_MS = 500L
        const val RUN_MS = 120_000L
        const val SHORT_WINDOW_MS = 3_000L
    }
}
