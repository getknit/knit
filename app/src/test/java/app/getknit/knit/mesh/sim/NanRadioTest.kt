package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lab.LabTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [NanRadio] over a sidewalk of passes ([PassBy]), without stacks. */
class NanRadioTest {
    @get:Rule
    val tmp = TemporaryFolder()

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

    /**
     * The plane a pipe reports to custody follows its air (the custody-carry-policy contract's §2): a pair on its Aware data
     * path is paced as Wi-Fi Aware's, the same pair back on Bluetooth as Bluetooth's, and a lab link with no air as the
     * transport's own kind, which custody paces as Bluetooth.
     */
    @Test
    fun aPipeOnADataPathReportsWifiAwareAndOneWithoutReportsBluetooth() {
        val radio = nan(windowMs = SHORT_WINDOW_MS)
        val onPath =
            (0L..RUN_MS step TICK_MS).firstNotNullOfOrNull { atMs ->
                val changes = radio.step(atMs, (0 until 12).map { sidewalk.position(it, atMs) })
                (changes.rerated + changes.raised).firstOrNull { radio.airOf(it).bytesPerSecond == NanRadio.NAN_BYTES_PER_S }
            }
        val link = checkNotNull(onPath) { "no pair ever rode a data path" }
        val (a, b, c) =
            listOf(
                "aaaa",
                "bbbb",
                "cccc",
            ).map { LabTransport(it, tmp.newFolder(), PartialBlobs(tmp.newFolder(), minBytes = 1)) }
        a.connect(b, air = radio.airOf(link))
        a.connect(c)

        assertEquals(TransportKind.WifiAware, a.carrier("bbbb")?.kind)
        assertEquals(TransportKind.WifiAware, b.carrier("aaaa")?.kind)
        val token = a.carrier("bbbb")?.link
        assertTrue("its link carries on the plane it reports", a.carries(TransportKind.WifiAware, "bbbb", token))

        a.setAir(b, radio.air)
        assertEquals(TransportKind.Bluetooth, a.carrier("bbbb")?.kind)
        assertFalse("the data path's plane no longer carries it", a.carries(TransportKind.WifiAware, "bbbb", token))
        assertEquals("a link with no air keeps the lab's own kind", TransportKind.Other, a.carrier("cccc")?.kind)
        a.disconnectAll()
    }

    private companion object {
        const val RANGE_M = 15.0
        const val DROP_M = 21.0
        const val TICK_MS = 500L
        const val RUN_MS = 120_000L
        const val SHORT_WINDOW_MS = 3_000L
    }
}
