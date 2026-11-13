package app.getknit.knit.ui.diagnostics

import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlane

/**
 * A line in the Diagnostics Transports section: a plane the composite runs, or one it could not build and
 * why. A Diagnostics-only shape on purpose — `MeshController.transportStatuses` carries only the planes that
 * exist, and the chat list's radio banner (`radioWarningFor`) reads every entry there as present hardware, so
 * an absent plane must never be smuggled in as a synthetic `TransportStatus`.
 */
sealed interface TransportRow {
    val kind: TransportKind

    /**
     * The composite built this plane. [lora] is the board verdict on the LoRa row, null on the phone radios:
     * that child is constructed whether or not a board is bound (ADR 2026-09.5bqu), so its health alone
     * cannot tell "no board" from "board out of reach".
     */
    data class Live(
        val status: TransportStatus,
        val lora: LoraPlane? = null,
    ) : TransportRow {
        override val kind: TransportKind get() = status.kind
    }

    /** Never constructed: no hardware, or below the API floor. */
    data class Absent(
        override val kind: TransportKind,
        val why: PlaneSupport,
    ) : TransportRow
}

/**
 * Bluetooth, Wi-Fi Aware, LoRa, then anything else. Every live status is kept but a LoRa child of another board kind
 * than [loraBoard], the bound one (`LoraFacts.board`, ADR 2026-11.gsqu Stage 2): every kind is its own child under
 * the one [TransportKind.LoRa], and v1 binds one board, so the section shows that board's row alone. A phone radio
 * with no status is filled in as [TransportRow.Absent] only when [radios] says it cannot exist — a plane the verdict calls
 * supported but the composite has no entry for (the demo transport's single `Other` entry, a mesh not yet
 * started) gets no row rather than a wrong one. LoRa is never synthesized: a build without the plane has no
 * row, as before.
 */
fun transportRows(
    statuses: List<TransportStatus>,
    radios: RadioSupport,
    lora: LoraPlane,
    loraBoard: BoardKind = BoardKind.MESHTASTIC,
): List<TransportRow> {
    val live =
        statuses.filter { it.boardKind == null || it.boardKind == loraBoard }.map { status ->
            TransportRow.Live(status, lora = lora.takeIf { status.kind == TransportKind.LoRa })
        }
    val present = statuses.mapTo(mutableSetOf()) { it.kind }
    val absent =
        buildList {
            if (TransportKind.Bluetooth !in present && radios.bluetooth != PlaneSupport.Supported) {
                add(TransportRow.Absent(TransportKind.Bluetooth, radios.bluetooth))
            }
            if (TransportKind.WifiAware !in present && radios.wifiAware != PlaneSupport.Supported) {
                add(TransportRow.Absent(TransportKind.WifiAware, radios.wifiAware))
            }
        }
    return (live + absent).sortedBy { it.kind.ordinal }
}

/**
 * The hint under "Mesh status: Radios off", chosen by which radios the phone has: telling a phone with no
 * Wi-Fi Aware to "turn on Wi-Fi or Bluetooth" contradicts the Transports row right below it.
 */
@StringRes
fun unavailableHintFor(radios: RadioSupport): Int =
    when {
        radios.bluetooth == PlaneSupport.Supported && radios.wifiAware == PlaneSupport.Supported -> {
            R.string.diagnostics_status_unavailable_hint
        }

        radios.bluetooth == PlaneSupport.Supported -> {
            R.string.diagnostics_status_unavailable_hint_ble_only
        }

        radios.wifiAware == PlaneSupport.Supported -> {
            R.string.diagnostics_status_unavailable_hint_nan_only
        }

        else -> {
            R.string.diagnostics_status_unavailable_hint_no_radios
        }
    }
