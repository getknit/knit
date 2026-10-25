package app.getknit.knit.ui.devview

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mesh HUD's line and panel (ADR 2026-10.s7wg), and the minute its frame counts move over. */
class HudLineTest {
    private val ble = TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 2, nearby = 5)

    @Test
    fun aRunningMeshNamesEachPlanesStateThenRelaysCustodyAndTheMinutesFrames() {
        val reading =
            HudReading(
                posture = MeshPosture.Running,
                statuses =
                    listOf(
                        ble,
                        TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 0, nearby = 0, dormant = true),
                        TransportStatus(TransportKind.LoRa, TransportHealth.Degraded, linked = 1, nearby = 3),
                    ),
                spoolsConnected = 1,
                spools = 2,
                custody = 41,
                rates = HudRates(FrameCounts(delivered = 12, sent = 3, dropped = 0), spanMs = RateWindow.WINDOW_MS),
            )

        assertEquals("BLE 2/5 · NAN dormant · LoRa 1/3! · relay 1/2 · cust 41 · 12↓ 3↑ 0✕ /min", HudLine.line(reading))
    }

    @Test
    fun aPlaneThatCarriesNothingSaysWhy() {
        fun line(status: TransportStatus) = HudLine.line(HudReading(MeshPosture.Running, statuses = listOf(status), custody = 0))

        // Knit's own switch outranks every health, and dormancy outranks a health that only reads it.
        assertEquals("NAN off · cust 0", line(ble.copy(kind = TransportKind.WifiAware, switchedOff = true, dormant = true)))
        assertEquals("BLE down · cust 0", line(ble.copy(health = TransportHealth.Unavailable)))
        assertEquals("NAN fg-only · cust 0", line(ble.copy(kind = TransportKind.WifiAware, health = TransportHealth.ForegroundOnly)))
    }

    @Test
    fun aMeshThatIsNotRunningSaysSoInPlaceOfThePlanes() {
        assertEquals("mesh stopped · cust …", HudLine.line(HudReading(MeshPosture.Stopped, statuses = listOf(ble))))
        assertEquals("mesh paused · cust 3", HudLine.line(HudReading(MeshPosture.Paused, custody = 3)))
        assertEquals("mesh start deferred · cust 3", HudLine.line(HudReading(MeshPosture.StartDeferred, custody = 3)))
        assertEquals("no radios · cust 3", HudLine.line(HudReading(MeshPosture.Running, custody = 3)))
    }

    @Test
    fun aWindowNotYetWholeNamesTheSecondsItCovers() {
        val rates = HudRates(FrameCounts(1, 0, 2), spanMs = 12_400L)

        assertEquals(
            "BLE 2/5 · cust 1 · 1↓ 0↑ 2✕ /12s",
            HudLine.line(HudReading(MeshPosture.Running, listOf(ble), custody = 1, rates = rates)),
        )
    }

    @Test
    fun thePanelListsEveryStatusFieldAndTheMeshAsAWhole() {
        val reading =
            HudReading(
                posture = MeshPosture.Running,
                statuses = listOf(ble.copy(contended = true, initiatorHeld = true)),
                neighbors = 5,
                indirect = 2,
                custody = 41,
            )

        val panel = HudLine.panel(reading).associate { group -> group.title to group.rows.associate { it.key to it.value } }

        assertEquals("Healthy · linked 2 · nearby 5 · contended · initiator held", panel.getValue("planes")["BLE"])
        val mesh = panel.getValue("mesh")
        assertEquals("Running", mesh["posture"])
        assertEquals("5", mesh["neighbors"])
        assertEquals("2", mesh["heardIndirectly"])
        assertEquals("none", mesh["spools"])
        assertEquals("41", mesh["forward_store live"])
        assertEquals(InspectFormat.ABSENT, mesh["frames"])
    }

    @Test
    fun theWindowAnswersNothingFromOneSampleThenTheMovementSinceItsOldest() {
        val window = RateWindow(windowMs = 60_000L)

        assertNull(window.add(0L, FrameCounts(10, 4, 1)))
        assertEquals(HudRates(FrameCounts(2, 1, 0), spanMs = 1_000L), window.add(1_000L, FrameCounts(12, 5, 1)))
    }

    @Test
    fun theWindowDropsWhatFallsOutOfTheMinuteAndSpansExactlyOneOnceWhole() {
        val window = RateWindow(windowMs = 60_000L)
        for (second in 0..60) window.add(second * 1_000L, FrameCounts(delivered = second.toLong(), sent = 0, dropped = 0))

        // At 61 s the sample from 0 s has gone: the minute runs from 1 s, one delivery a second.
        assertEquals(HudRates(FrameCounts(60, 0, 0), spanMs = 60_000L), window.add(61_000L, FrameCounts(61, 0, 0)))
    }

    @Test
    fun aCounterThatWentBackwardsStartsTheWindowOver() {
        val window = RateWindow(windowMs = 60_000L)
        window.add(0L, FrameCounts(100, 50, 5))
        window.add(1_000L, FrameCounts(110, 55, 5))

        // A fresh MeshMetrics: what the old one counted measures nothing now.
        assertNull(window.add(2_000L, FrameCounts(1, 0, 0)))
        assertEquals(HudRates(FrameCounts(2, 1, 0), spanMs = 1_000L), window.add(3_000L, FrameCounts(3, 1, 0)))
    }
}
