package app.getknit.knit.ui.diagnostics

import app.getknit.knit.R
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Transports section's row list: what the composite built, filled in with what it could not and why. */
class TransportRowsTest {
    private fun status(
        kind: TransportKind,
        health: TransportHealth = TransportHealth.Healthy,
    ) = TransportStatus(kind = kind, health = health, linked = 0, nearby = 0)

    private val ble = status(TransportKind.Bluetooth)
    private val nan = status(TransportKind.WifiAware)
    private val lora = status(TransportKind.LoRa, TransportHealth.Unavailable)

    @Test
    fun aTwoRadioPhoneListsExactlyWhatTheCompositeBuilt() {
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Live(nan)),
            transportRows(listOf(ble, nan), RadioSupport.ALL, LoraPlane.Off),
        )
    }

    @Test
    fun aPhoneWithoutWifiAwareGetsTheRowItNeverHad() {
        // The RedMagic 11 case (getknit/knit discussion #8): BLE-only mesh, and until now nothing said why.
        val radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NoHardware)
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NoHardware)),
            transportRows(listOf(ble), radios, LoraPlane.Off),
        )
    }

    @Test
    fun anAndroid11PhoneWithTheHardwareIsToldTheFloorNotTheHardware() {
        val radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NeedsAndroid12)
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NeedsAndroid12)),
            transportRows(listOf(ble), radios, LoraPlane.Off),
        )
    }

    @Test
    fun bluetoothGetsTheSameTreatment_andTheOrderIsAlwaysBluetoothWifiAwareLoRa() {
        val radios = RadioSupport(bluetooth = PlaneSupport.NoHardware, wifiAware = PlaneSupport.Supported)
        assertEquals(
            listOf(
                TransportRow.Absent(TransportKind.Bluetooth, PlaneSupport.NoHardware),
                TransportRow.Live(nan),
                TransportRow.Live(lora, LoraPlane.Off),
            ),
            // The composite's own order (LoRa last, but here scrambled) never leaks into the list.
            transportRows(listOf(lora, nan), radios, LoraPlane.Off),
        )
    }

    @Test
    fun aLiveStatusAlwaysWinsOverTheVerdict() {
        // A stale or disagreeing verdict must not hide a plane that is demonstrably running.
        val radios = RadioSupport(bluetooth = PlaneSupport.NoHardware, wifiAware = PlaneSupport.NoHardware)
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Live(nan)),
            transportRows(listOf(ble, nan), radios, LoraPlane.Off),
        )
    }

    @Test
    fun aSupportedPlaneWithNoStatusGetsNoRowRatherThanAWrongOne() {
        // The demo transport is a single `Other` entry; a mesh not yet started has none. Neither is "absent".
        val other = status(TransportKind.Other)
        assertEquals(listOf(TransportRow.Live(other)), transportRows(listOf(other), RadioSupport.ALL, LoraPlane.Off))
        assertEquals(emptyList<TransportRow>(), transportRows(emptyList(), RadioSupport.ALL, LoraPlane.Off))
    }

    @Test
    fun loRaIsNeverSynthesized_butALoRaStatusCarriesTheBoardVerdict() {
        // A build without the plane has no LoRa status and a constant Off — and no row, as before.
        val noLora = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NoHardware)
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NoHardware)),
            transportRows(listOf(ble), noLora, LoraPlane.Off),
        )
        for (plane in LoraPlane.entries) {
            assertEquals(
                "plane=$plane",
                listOf(TransportRow.Live(ble), TransportRow.Live(lora, plane)),
                transportRows(listOf(ble, lora), RadioSupport.ALL, plane),
            )
        }
        // The board verdict never lands on a phone radio.
        val bleRow = transportRows(listOf(ble), RadioSupport.ALL, LoraPlane.Live).single() as TransportRow.Live
        assertNull(bleRow.lora)
    }

    @Test
    fun theRadiosOffHintNamesTheRadioThePhoneActuallyHas() {
        assertEquals(R.string.diagnostics_status_unavailable_hint, unavailableHintFor(RadioSupport.ALL))
        assertEquals(
            R.string.diagnostics_status_unavailable_hint_ble_only,
            unavailableHintFor(RadioSupport(PlaneSupport.Supported, PlaneSupport.NoHardware)),
        )
        assertEquals(
            "the API floor is an absence too, as far as this hint is concerned",
            R.string.diagnostics_status_unavailable_hint_ble_only,
            unavailableHintFor(RadioSupport(PlaneSupport.Supported, PlaneSupport.NeedsAndroid12)),
        )
        assertEquals(
            R.string.diagnostics_status_unavailable_hint_nan_only,
            unavailableHintFor(RadioSupport(PlaneSupport.NoHardware, PlaneSupport.Supported)),
        )
        assertEquals(
            R.string.diagnostics_status_unavailable_hint_no_radios,
            unavailableHintFor(RadioSupport(PlaneSupport.NoHardware, PlaneSupport.NoHardware)),
        )
    }

    /** ADR 2026-11.gsqu, Stage 2: two LoRa children share TransportKind.LoRa, and the section shows the bound kind's alone. */
    @Test
    fun onlyTheBoundBoardKindsLoraRowIsShown() {
        val meshtastic = lora.copy(boardKind = BoardKind.MESHTASTIC)
        val meshcore = lora.copy(boardKind = BoardKind.MESHCORE)
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Live(meshtastic, LoraPlane.Down)),
            transportRows(listOf(ble, meshtastic, meshcore), RadioSupport.ALL, LoraPlane.Down),
        )
        assertEquals(
            listOf(TransportRow.Live(ble), TransportRow.Live(meshcore, LoraPlane.Live)),
            transportRows(listOf(ble, meshtastic, meshcore), RadioSupport.ALL, LoraPlane.Live, BoardKind.MESHCORE),
        )
    }
}
