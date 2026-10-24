package app.getknit.knit.ui.chatlist

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus

/**
 * Which radio-off warning the chat list should surface. [BluetoothOff]/[WifiOff] are "still meshing over the
 * other radio, but degraded" callouts (dismissible); [AllRadiosOff] means the app can't reach anyone at all
 * (not dismissible).
 */
enum class RadioWarning { BluetoothOff, WifiOff, AllRadiosOff }

/**
 * The banner (if any) to show for the current per-radio [statuses] (from `MeshManager.transportStatuses`).
 *
 * A [TransportStatus] entry exists only for a radio whose hardware is present, so an absent radio contributes
 * no entry and is never warned about; `health == Unavailable` is a present-but-off radio. `Degraded` (radio on
 * but momentarily seized) is deliberately not a banner state — it's transient and already shown in the header.
 * A single-radio device whose one radio is off maps to [RadioWarning.AllRadiosOff] (it truly can't connect).
 * A plane Knit switched off itself ([TransportStatus.switchedOff]: low-power mode, ADR 2026-10.d44b) is counted as
 * absent: its radio is on, so a banner asking the user to turn it on would be wrong and could not help. A dormant plane
 * ([TransportStatus.dormant], Wi-Fi Aware resting while no phone nearby can use it, GitHub #44) reads Healthy while its
 * radio is on, so it raises nothing; it reads Unavailable only on evidence the radio is off, and then warns as any does.
 */
fun radioWarningFor(statuses: List<TransportStatus>): RadioWarning? {
    val present = statuses.filterNot { it.switchedOff }
    if (present.isEmpty()) return null // no radio hardware at all — nothing the user can fix here
    val off = present.filter { it.health == TransportHealth.Unavailable }.map { it.kind }
    return when {
        off.isEmpty() -> null

        // every present radio is up (or only Degraded)
        off.size == present.size -> RadioWarning.AllRadiosOff

        TransportKind.Bluetooth in off -> RadioWarning.BluetoothOff

        TransportKind.WifiAware in off -> RadioWarning.WifiOff

        else -> null // only an unnamed ("Other") radio is off while a named one is up — nothing actionable
    }
}
