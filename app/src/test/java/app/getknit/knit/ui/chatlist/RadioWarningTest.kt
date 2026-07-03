package app.getknit.knit.ui.chatlist

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioWarningTest {

    private fun status(kind: TransportKind, health: TransportHealth) =
        TransportStatus(kind = kind, health = health, linked = 0, nearby = 0)

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
}
