package app.getknit.knit.ui.chatlist

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioWarningTest {
    private fun status(
        kind: TransportKind,
        health: TransportHealth,
    ) = TransportStatus(kind = kind, health = health, linked = 0, nearby = 0)

    @Test
    fun noRadioHardware_noWarning() {
        // No transport entries at all = no radio hardware; nothing the user can fix, so no banner.
        assertNull(radioWarningFor(emptyList()))
    }

    @Test
    fun bothRadiosUp_noWarning() {
        assertNull(
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Healthy),
                    status(TransportKind.WifiAware, TransportHealth.Healthy),
                ),
            ),
        )
    }

    @Test
    fun aSearchRefusedOffScreen_isNotARadioOff() {
        // ForegroundOnly (ADR 2026-09.535d) is the OS's rule, not a switch the user can flip: no banner.
        assertNull(
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Healthy),
                    status(TransportKind.WifiAware, TransportHealth.ForegroundOnly),
                ),
            ),
        )
    }

    @Test
    fun bluetoothOffWifiUp_bluetoothWarning() {
        assertEquals(
            RadioWarning.BluetoothOff,
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                    status(TransportKind.WifiAware, TransportHealth.Healthy),
                ),
            ),
        )
    }

    @Test
    fun wifiOffBluetoothUp_wifiWarning() {
        assertEquals(
            RadioWarning.WifiOff,
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Healthy),
                    status(TransportKind.WifiAware, TransportHealth.Unavailable),
                ),
            ),
        )
    }

    @Test
    fun bothRadiosOff_allRadiosWarning() {
        assertEquals(
            RadioWarning.AllRadiosOff,
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                    status(TransportKind.WifiAware, TransportHealth.Unavailable),
                ),
            ),
        )
    }

    @Test
    fun singleRadioDeviceWithRadioOff_allRadiosWarning() {
        // A device with only Bluetooth hardware contributes a single status; its being off means it can't connect.
        assertEquals(
            RadioWarning.AllRadiosOff,
            radioWarningFor(listOf(status(TransportKind.Bluetooth, TransportHealth.Unavailable))),
        )
    }

    @Test
    fun degradedRadioIsNotTreatedAsOff() {
        // Degraded (radio on but momentarily seized) is transient/self-healing — not a banner state.
        assertNull(
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Degraded),
                    status(TransportKind.WifiAware, TransportHealth.Healthy),
                ),
            ),
        )
    }

    @Test
    fun disabledLoraChildRaisesNoBannerWhileANamedRadioIsUp() {
        // A LoRa plane that is off/unpaired reports Unavailable; with a named radio up, that must not warn
        // (LoRa is opt-in, not a radio the user forgot to switch on).
        assertNull(
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Healthy),
                    status(TransportKind.LoRa, TransportHealth.Unavailable),
                ),
            ),
        )
    }

    @Test
    fun everyRadioOffIncludingLoraIsAllRadiosOff() {
        assertEquals(
            RadioWarning.AllRadiosOff,
            radioWarningFor(
                listOf(
                    status(TransportKind.Bluetooth, TransportHealth.Unavailable),
                    status(TransportKind.LoRa, TransportHealth.Unavailable),
                ),
            ),
        )
    }
}
