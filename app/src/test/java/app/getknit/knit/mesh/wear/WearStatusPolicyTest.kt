package app.getknit.knit.mesh.wear

import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the watch is told, from what the phone's own surfaces read. */
class WearStatusPolicyTest {
    private val now = 1_790_000_000_000L

    private fun status(
        kind: TransportKind,
        health: TransportHealth,
    ) = TransportStatus(kind = kind, health = health, linked = 0, nearby = 0)

    private val running =
        WearInputs(
            enabled = true,
            pausedUntil = null,
            nearby = 0,
            statuses =
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Healthy),
                    status(TransportKind.WifiAware, TransportHealth.Healthy),
                ),
            lora = LoraPlane.Off,
            relay = RelayPlane.Off,
            relayed = 42,
        )

    @Test
    fun `a stopped or paused mesh reports no radio, however healthy its last report was`() {
        val off = WearStatusPolicy.of(running.copy(enabled = false, nearby = 3, lora = LoraPlane.Live), now)
        assertEquals(MeshState.Off, off.state)
        assertEquals(0, off.nearby)
        assertEquals(listOf(Plane.Absent, Plane.Absent, Plane.Absent, Plane.Absent), listOf(off.ble, off.nan, off.lora, off.spool))
        assertEquals(42, off.relayed)

        val paused = WearStatusPolicy.of(running.copy(pausedUntil = now + 60_000, nearby = 3), now)
        assertEquals(MeshState.Paused, paused.state)
        assertEquals(0, paused.nearby)
        assertEquals(Plane.Absent, paused.ble)
    }

    @Test
    fun `a pause that lapsed since the last input reads as running`() {
        assertEquals(MeshState.Alone, WearStatusPolicy.of(running.copy(pausedUntil = now - 1), now).state)
    }

    @Test
    fun `peers in range are Linked, whatever the radios' health`() {
        val degraded = running.copy(nearby = 2, statuses = listOf(status(TransportKind.Bluetooth, TransportHealth.Degraded)))
        val s = WearStatusPolicy.of(degraded, now)
        assertEquals(MeshState.Linked, s.state)
        assertEquals(2, s.nearby)
    }

    @Test
    fun `alone, the best short-range radio decides, and LoRa does not count`() {
        assertEquals(MeshState.Alone, WearStatusPolicy.of(running, now).state)
        val impaired =
            running.copy(
                statuses =
                    listOf(
                        status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                        status(TransportKind.WifiAware, TransportHealth.ForegroundOnly),
                        status(TransportKind.LoRa, TransportHealth.Healthy),
                    ),
            )
        assertEquals(MeshState.Degraded, WearStatusPolicy.of(impaired, now).state)
        val dark =
            running.copy(
                statuses =
                    listOf(
                        status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                        status(TransportKind.LoRa, TransportHealth.Healthy),
                    ),
            )
        assertEquals(MeshState.NoRadio, WearStatusPolicy.of(dark, now).state)
        assertEquals(MeshState.NoRadio, WearStatusPolicy.of(running.copy(statuses = emptyList()), now).state)
    }

    @Test
    fun `each plane maps from its own source`() {
        val s =
            WearStatusPolicy.of(
                running.copy(
                    statuses =
                        listOf(
                            status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                            status(TransportKind.WifiAware, TransportHealth.Degraded),
                        ),
                    lora = LoraPlane.Live,
                    relay = RelayPlane.Down,
                ),
                now,
            )
        assertEquals(Plane.Down, s.ble)
        assertEquals(Plane.Degraded, s.nan)
        assertEquals(Plane.Live, s.lora)
        assertEquals(Plane.Down, s.spool)

        val noNan = WearStatusPolicy.of(running.copy(statuses = listOf(status(TransportKind.Bluetooth, TransportHealth.Healthy))), now)
        assertEquals(Plane.Live, noNan.ble)
        assertEquals(Plane.Absent, noNan.nan)
        assertEquals(Plane.Absent, noNan.lora)
        assertEquals(Plane.Absent, noNan.spool)
    }

    @Test
    fun `the stamp is the read's clock in seconds`() {
        assertEquals(now / 1000, WearStatusPolicy.of(running, now).stampSec)
    }
}
