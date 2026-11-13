package app.getknit.knit.data.settings

import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.LoraPlaneState
import kotlinx.serialization.json.Json

/**
 * One LoRa plane instance's [LoraPlaneState] over the settings DataStore: one JSON blob under one key per instance, its
 * [kind]'s (ADR 2026-11.gsqu, Stage 2) — `lora_plane_state` for Meshtastic byte for byte, `meshcore_plane_state` for
 * MeshCore, so two children never write one blob and clobber each other's re-probe backoff.
 *
 * DataStore rather than the encrypted database because none of it is user content — it is which Meshtastic nodes the
 * auto-reply has answered and when the re-probe is next due, the same class of thing as the board binding beside it.
 * One blob rather than a key per limiter because the snapshot is read and written whole, and a half-applied one would
 * be worse than none. The same key as when it carried the airtime ledger too (ADR 2026-10.ug4a dropped that): a new
 * key would orphan the old blob, and with it the two limiters it still holds.
 *
 * A blob that fails to parse (a downgrade, a truncated write) reads as absent: the plane then starts with no
 * auto-reply memory and no re-probe schedule, which costs one reply per sender and one early re-probe at most.
 */
internal class LoraPlaneStateStore(
    private val settings: SettingsStore,
    private val kind: BoardKind = BoardKind.MESHTASTIC,
) : LoraPlaneState {
    override suspend fun load(): LoraPlaneSnapshot? =
        settings.planeState(kind)?.let { blob ->
            runCatching { json.decodeFromString<LoraPlaneSnapshot>(blob) }.getOrNull()
        }

    override suspend fun save(snapshot: LoraPlaneSnapshot) {
        settings.setPlaneState(kind, json.encodeToString(snapshot))
    }

    private companion object {
        // Unknown keys are ignored so a snapshot written by a newer build downgrades to what this one knows
        // rather than being thrown away whole, and so an older build's blob — its `air` ledger included — still
        // hands back the limiters this one keeps.
        val json = Json { ignoreUnknownKeys = true }
    }
}
