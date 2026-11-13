package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.mesh.lora.BoardKind

/**
 * One board kind's own settings keys (ADR 2026-11.gsqu, Stage 2): the binding, what the bound board reported, and the
 * plane's persisted limiters. Meshtastic's are the `lora_*` keys byte for byte, with no migration; MeshCore's are their
 * `meshcore_*` peers ([SettingsKeys.MESHCORE_DEVICE_ADDRESS] and on). The shared switches (`lora_enabled`,
 * `lora_dm_enabled`) are no kind's, and are not here.
 *
 * The backup split follows the same lines ([SettingsKeys.TRANSIENT_PREFIXES]): [address], [name] and [planeState] are
 * this phone's and stay, [reported] rides the profile and rides a backup.
 */
internal class BoardKeys private constructor(
    /** The bound board's BLE address: what makes this kind the bound one ([BoardKind.boundOf]). */
    val address: Preferences.Key<String>,
    /** The bound board's name, for the settings row. */
    val name: Preferences.Key<String>,
    /**
     * What the bound board reported about itself and the signed profile advertises: Meshtastic's node number and key
     * (`ProfileContent.loraNode` / `loraKey`), MeshCore's 32-byte public key. Forgotten when another board is bound.
     */
    val reported: List<Preferences.Key<*>>,
    /** The plane instance's persisted limiters (`LoraPlaneStateStore`): one blob per instance, so two never clobber. */
    val planeState: Preferences.Key<String>,
) {
    /** Everything that names this kind's bound board to a peer, directly or through the signed profile. */
    val identity: List<Preferences.Key<*>> get() = listOf(address, name) + reported

    companion object {
        val LORA_NODE: Preferences.Key<Long> = longPreferencesKey("lora_board_node")
        val LORA_KEY: Preferences.Key<String> = stringPreferencesKey("lora_board_key")
        val MESHCORE_PUBKEY: Preferences.Key<String> = stringPreferencesKey(SettingsKeys.MESHCORE_BOARD_PUBKEY)

        val MESHTASTIC =
            BoardKeys(
                address = stringPreferencesKey("lora_device_address"),
                name = stringPreferencesKey("lora_device_name"),
                reported = listOf(LORA_NODE, LORA_KEY),
                planeState = stringPreferencesKey("lora_plane_state"),
            )

        val MESHCORE =
            BoardKeys(
                address = stringPreferencesKey(SettingsKeys.MESHCORE_DEVICE_ADDRESS),
                name = stringPreferencesKey(SettingsKeys.MESHCORE_DEVICE_NAME),
                reported = listOf(MESHCORE_PUBKEY),
                planeState = stringPreferencesKey(SettingsKeys.MESHCORE_PLANE_STATE),
            )

        fun of(kind: BoardKind): BoardKeys =
            when (kind) {
                BoardKind.MESHTASTIC -> MESHTASTIC
                BoardKind.MESHCORE -> MESHCORE
            }
    }
}

/**
 * Binding a board (ADR 2026-11.gsqu, Stage 2: v1 binds one board at a time), as one pure edit of the preferences, so the
 * caller applies it inside one `DataStore.edit` and the profile watcher sees one change. Every writer of a board's address
 * goes through it: the radio screen, the debug bridge's `LORA`, the demo seeder.
 *
 * - **Clears the other kinds' identity** ([BoardKeys.identity]): address, name and what their board reported. For
 *   Meshtastic that is `lora_device_address`, `lora_device_name`, `lora_board_node` and `lora_board_key`. Otherwise
 *   `SettingsStore.loraBoard` keeps advertising `loraNode` / `loraKey` and `CAP_LORA_DIRECT` for a board this phone no
 *   longer reads.
 * - **Forgets this kind's last report**, as `setLoraDevice` always did: a different board has a different node and key.
 * - **Never touches the shared switches** (`lora_enabled`, `lora_dm_enabled`). `clearLoraDevice` is not reused for this,
 *   because it switches off the plane the user just bound.
 * - **Keeps the other kinds' setup record and prior settings** (`lora_setup_address`, `lora_prior_*`; MeshCore's
 *   `meshcore_setup_*` / `meshcore_prior_*`), so that board can still be restored when it is bound again (ADR 045). They
 *   are about the user's own board and never ride a backup. MeshCore's contact snapshot is not in DataStore at all.
 */
internal object BoardBindPolicy {
    fun bind(
        prefs: MutablePreferences,
        kind: BoardKind,
        address: String,
        name: String,
    ) {
        BoardKind.entries.filter { it != kind }.forEach { other -> BoardKeys.of(other).identity.forEach { prefs.remove(it) } }
        val own = BoardKeys.of(kind)
        own.reported.forEach { prefs.remove(it) }
        prefs[own.address] = address
        prefs[own.name] = name
    }
}
