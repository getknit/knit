package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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

    // Low-power mode's switch (ADR 2026-10.d44b, `LowPowerSwitch`), backup-transient under "low_power_".
    const val LOW_POWER_MODE = "low_power_mode"

    // The relay list and its parked subset (`SettingsStore.spoolUrls`), here because `RelayRename` rewrites both in the
    // same edit as the per-host keys below (ADR 2026-10.qpja).
    const val SPOOL_URLS = "spool_urls"
    const val SPOOL_URLS_DISABLED = "spool_urls_disabled"

    // Set once the shipped default relay was seeded (`SettingsStore.seedDefaultSpools`); `HomeStore` reads it to tell an
    // install that predates homes, which keeps `public-1`, from a fresh one (ADR 2026-10.8kfx).
    const val SPOOL_DEFAULTS_SEEDED = "spool_defaults_seeded"

    // This phone's home relay host (`HomeStore`, ADR 2026-10.8kfx). Rides a backup: a restored phone keeps its home,
    // and the hash would pick the same one anyway. The directory it was picked from is phone state under
    // "spool_directory", in [TRANSIENT_PREFIXES].
    const val SPOOL_HOME = "spool_home"

    // The conversations this phone still owes a scope config (`HomeStore`, ADR 2026-10.8kfx). Rides a backup: a phone
    // restored in the middle of a move finishes it.
    const val SPOOL_CONFIG_OWED = "spool_config_owed"
    const val SPOOL_DIRECTORY = "spool_directory"
    const val SPOOL_DIRECTORY_AT = "spool_directory_at"

    // A held entitlement ticket and its verdict, per spool host name (ADR 2026-10.xkp4). Both start with
    // "spool_ticket", the backup-transient prefix in [TRANSIENT_PREFIXES]; neither prefix is a prefix of the other.
    // Here rather than in the store because `TicketKeyStore` forgets a host's ticket in the same edit as its key.
    const val SPOOL_TICKET_PREFIX = "spool_ticket:"
    const val SPOOL_TICKET_VERDICT_PREFIX = "spool_ticket_verdict:"

    fun spoolTicketKey(host: String) = stringPreferencesKey(SPOOL_TICKET_PREFIX + host)

    fun spoolTicketVerdictKey(host: String) = stringPreferencesKey(SPOOL_TICKET_VERDICT_PREFIX + host)

    // A customer key and its issuer, per relay host name, and what the refresher knows about asking with it (ADR
    // 2026-10.3sk7). The key rides a Knit backup, as the relay's `?k=` token does; the state is about this phone's
    // asks and does not ("spool_key_state" is in [TRANSIENT_PREFIXES]). Neither prefix is a prefix of the other.
    const val SPOOL_KEY_PREFIX = "spool_key:"
    const val SPOOL_KEY_STATE_PREFIX = "spool_key_state:"

    fun spoolKeyKey(host: String) = stringPreferencesKey(SPOOL_KEY_PREFIX + host)

    fun spoolKeyStateKey(host: String) = stringPreferencesKey(SPOOL_KEY_STATE_PREFIX + host)

    // Which device-key pair the issuer last accepted, per customer key's slot (`mesh/spool/DeviceKeys`, ADR 2026-10.wk3m).
    // The pairs live in the Keystore, which no backup carries, so neither does this ("spool_device" is in
    // [TRANSIENT_PREFIXES]): a restored phone signs with its oldest pair until the issuer accepts one.
    const val SPOOL_DEVICE_PREFIX = "spool_device:"

    fun spoolDeviceKey(slot: String) = stringPreferencesKey(SPOOL_DEVICE_PREFIX + slot)

    // The free half (docs/VOICE_CALLS.md C-7.6-33 to C-7.6-38): a free key made at a free issuer, the account route's
    // backoff, the person's answer to the notice, and a debug build's own free issuer, per relay host name. All under
    // "spool_free_", which is in [TRANSIENT_PREFIXES]: a free key never rides a backup (a restored phone makes its own),
    // and a restored phone asks the person again before it contacts an issuer. No prefix here is a prefix of another.
    const val SPOOL_FREE_KEY_PREFIX = "spool_free_key:"
    const val SPOOL_FREE_STATE_PREFIX = "spool_free_state:"
    const val SPOOL_FREE_CONSENT_PREFIX = "spool_free_consent:"
    const val SPOOL_FREE_ISSUER_PREFIX = "spool_free_issuer:"

    fun spoolFreeKeyKey(host: String) = stringPreferencesKey(SPOOL_FREE_KEY_PREFIX + host)

    fun spoolFreeStateKey(host: String) = stringPreferencesKey(SPOOL_FREE_STATE_PREFIX + host)

    fun spoolFreeConsentKey(host: String) = booleanPreferencesKey(SPOOL_FREE_CONSENT_PREFIX + host)

    fun spoolFreeIssuerKey(host: String) = stringPreferencesKey(SPOOL_FREE_ISSUER_PREFIX + host)

    // The calls this phone saw end (`CallTombStore`, docs/VOICE_CALLS.md C-3.4-3): about this phone's calls, so in
    // [TRANSIENT_PREFIXES].
    const val CALL_TOMBS = "call_tombs"

    // Whether this phone may host a Wi-Fi Direct group (`DirectHostStore`, ADR 2026-10.w3dw) and which microphone a
    // call records from (`CaptureStore`, ADR 2026-10.kw29): facts about this phone's ROM, so in [TRANSIENT_PREFIXES].
    const val DIRECT_HOST_PREFIX = "direct_host_"
    const val CALL_CAPTURE_PREFIX = "call_capture_"

    // The supporter badge (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s): the "Show my supporter badge" choice rides a
    // backup; the held badges and their ask state are this phone's ("badge_held" is in [TRANSIENT_PREFIXES]), so a
    // restored phone draws a fresh `r` before it shows anything.
    const val BADGE_SHOW = "badge_show"
    const val BADGE_HELD = "badge_held"
    const val BADGE_HELD_STATE = "badge_held_state"

    // The version a badge-only change was published under and what it published; both ride a backup, since a restored
    // phone must never publish below a version its peers hold. Neither starts with "badge_held".
    const val BADGE_SEALED_VERSION = "badge_sealed_version"
    const val BADGE_PUBLISHED = "badge_published"

    /**
     * Keys a backup never carries: state about *this phone's* radios and ROM (the Aware give-up and
     * initiator-hold journals, the model poison-pill latch), and the paired LoRa board's address, names and
     * pre-setup values — the BLE bond is per phone, so the user pairs the board again and those come back
     * with it. The board's node number and key stay: they ride the profile and are worth keeping. Matched
     * as prefixes against the key name. The clone watch's two stamps (`clone_seen_at`, `clone_dismissed_at`)
     * are about this phone too: a backup taken while its banner showed must not plant it on the next one, and
     * so is the mesh pause deadline (`mesh_pause_until`): a backup taken mid-pause must not pause the other phone.
     * Debug-build diagnostic knobs (`debug_`, e.g. the Bluetooth link cap) are about this phone's test run. A spool
     * entitlement ticket (`spool_ticket…`, ADR 2026-10.xkp4) is a bearer grant to this phone for a day, and must not
     * ride a backup file anywhere; what the ticket refresher knows about its asks (`spool_key_state…`, ADR 2026-10.3sk7)
     * is this phone's schedule, so a restored phone asks at once. The customer key beside it rides, like a `?k=` token;
     * a free key (`spool_free_…`) does not, nor does the person's answer to the free-account notice, so a restored phone
     * asks again before it contacts an issuer.
     * The calls this phone saw end (`call_tombs`) are this phone's too: a restored phone holds new sessions. So are the
     * Wi-Fi Direct host guard (`direct_host_`) and the call microphone's verdict (`call_capture_`): another phone's ROM
     * is not this one's. The supporter badges this phone holds (`badge_held…`) carry the `r` it drew for them, which
     * stays on the phone that drew it.
     * Low-power mode (`low_power_mode`, ADR 2026-10.d44b) is about this phone's battery, not the person.
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
            "spool_ticket",
            "spool_key_state",
            "spool_device",
            "spool_free_",
            SPOOL_DIRECTORY,
            CALL_TOMBS,
            DIRECT_HOST_PREFIX,
            CALL_CAPTURE_PREFIX,
            BADGE_HELD,
            "low_power_",
        )
}
