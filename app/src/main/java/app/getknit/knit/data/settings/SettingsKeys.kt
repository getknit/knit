package app.getknit.knit.data.settings

/**
 * The few DataStore facts that live outside [SettingsStore]: the file the store is built over and the key
 * names the backup restore writes or strips **without** the store (`data/backup/SettingsSnapshot` runs on
 * a `Preferences` value it read from a file, before any Koin graph exists on the restoring phone). Named
 * here, once, so the store and the snapshot cannot disagree on a spelling.
 */
object SettingsKeys {
    /** The one preferences DataStore's name — `filesDir/datastore/<NAME>.preferences_pb`. */
    const val DATASTORE_NAME = "knit_settings"

    /** Set by a restore, cleared by the mesh's first-start hooks; see [SettingsStore.restorePending]. */
    const val RESTORE_PENDING = "restore_pending"

    /** See [SettingsStore.onboardingSeen]; a restore sets it so the name page never overwrites the restored name. */
    const val ONBOARDING_SEEN = "onboarding_seen"

    /**
     * Keys a backup never carries: state about *this phone's* radios and ROM (the Aware give-up and
     * initiator-hold journals, the model poison-pill latch), and the paired LoRa board's address, names and
     * pre-setup values — the BLE bond is per phone, so the user pairs the board again and those come back
     * with it. The board's node number and key stay: they ride the profile and are worth keeping. Matched
     * as prefixes against the key name. The clone watch's two stamps (`clone_seen_at`, `clone_dismissed_at`)
     * are about this phone too: a backup taken while its banner showed must not plant it on the next one, and
     * so is the mesh pause deadline (`mesh_pause_until`): a backup taken mid-pause must not pause the other phone.
     * Debug-build diagnostic knobs (`debug_`, e.g. the Bluetooth link cap) are about this phone's test run.
     */
    val TRANSIENT_PREFIXES: List<String> =
        listOf(
            "clone_",
            "mesh_pause_",
            "aware_give_up_stamp",
            "nan_initiator_hold_",
            "model_load_",
            "lora_device_address",
            "lora_device_name",
            "lora_setup_address",
            "lora_prior_",
            "lora_plane_state",
            "debug_",
        )
}
