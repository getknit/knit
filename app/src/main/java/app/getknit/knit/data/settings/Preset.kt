package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The starting point a person picks at onboarding, and can change in Settings: it supplies the default of every switch
 * in [PresetSetting.all], and the person's own toggle of one always outranks it. Stored by name under
 * [SettingsKeys.SETTINGS_PRESET]; absent or unknown reads [STANDARD], whose values are exactly the defaults every
 * install had before presets, so an install or a backup that predates them reads what it always read.
 *
 * The relay plane is not a folded default: switching it on is a disclosed act (`SettingsStore.spoolConsented`), so a
 * preset that wants relays on writes the consent and the switch once, in [PresetStore.apply], with its card's
 * disclosure on screen ([PresetDefaults.relays]).
 */
enum class Preset(
    val defaults: PresetDefaults,
) {
    /** What every install read before presets; never offered, only read where no preset was picked. */
    STANDARD(PresetDefaults()),

    /** Relays and link previews on, larger Bluetooth downloads without asking. */
    EVERYDAY(
        PresetDefaults(
            relays = true,
            linkPreviews = true,
            bluetoothDownloadLimit = BluetoothDownloadLimit.MB_5,
        ),
    ),

    /**
     * Tell others as little as possible: no read or typing cue, no language on the wire, no screenshots of Knit, and the
     * faces in a photo covered before it goes.
     */
    PRIVATE(
        PresetDefaults(
            typingIndicators = false,
            readReceipts = false,
            translation = false,
            secureScreens = true,
            coverFaces = true,
        ),
    ),

    /** Wi-Fi Aware off where the phone has it, and no speech model at work after a call. */
    BATTERY(
        PresetDefaults(
            wifiAware = false,
            callTranscripts = false,
        ),
    ),
    ;

    companion object {
        val KEY = stringPreferencesKey(SettingsKeys.SETTINGS_PRESET)

        /** The ones a person picks, in the order the cards show them. */
        val offered: List<Preset> = listOf(EVERYDAY, PRIVATE, BATTERY)

        fun of(stored: String?): Preset = entries.firstOrNull { it.name == stored } ?: STANDARD

        fun of(prefs: Preferences): Preset = of(prefs[KEY])
    }
}

/** One preset's values. The constructor's defaults are [Preset.STANDARD]'s, the defaults before presets. */
data class PresetDefaults(
    val relays: Boolean = false,
    val linkPreviews: Boolean = false,
    val typingIndicators: Boolean = true,
    val readReceipts: Boolean = true,
    val translation: Boolean = true,
    val wifiAware: Boolean = true,
    val callTranscripts: Boolean = true,
    val bluetoothDownloadLimit: BluetoothDownloadLimit = BluetoothDownloadLimit.DEFAULT,
    val secureScreens: Boolean = false,
    val coverFaces: Boolean = false,
)

/**
 * A switch whose default a [Preset] supplies: its key, as the store that owns it stores it, and the preset's value in
 * that form. Read through [orPreset]; [all] is what [PresetStore] resets and reads as customised.
 */
class PresetSetting<T : Any>(
    val key: Preferences.Key<T>,
    val pick: (PresetDefaults) -> T,
) {
    /** Whether [prefs] holds the person's own value for this switch, and it is not what [preset] would give. */
    fun overrides(
        prefs: Preferences,
        preset: Preset,
    ): Boolean = prefs[key]?.let { it != pick(preset.defaults) } ?: false

    companion object {
        val LINK_PREVIEWS = PresetSetting(booleanPreferencesKey(SettingsKeys.LINK_PREVIEWS)) { it.linkPreviews }
        val TYPING_INDICATORS = PresetSetting(booleanPreferencesKey(SettingsKeys.TYPING_INDICATORS)) { it.typingIndicators }
        val READ_RECEIPTS = PresetSetting(booleanPreferencesKey(SettingsKeys.READ_RECEIPTS)) { it.readReceipts }
        val TRANSLATION = PresetSetting(booleanPreferencesKey(SettingsKeys.TRANSLATION_ENABLED)) { it.translation }
        val WIFI_AWARE = PresetSetting(booleanPreferencesKey(SettingsKeys.WIFI_AWARE_ENABLED)) { it.wifiAware }
        val CALL_TRANSCRIPTS = PresetSetting(booleanPreferencesKey(SettingsKeys.CALL_TRANSCRIPTS)) { it.callTranscripts }
        val SECURE_SCREENS = PresetSetting(booleanPreferencesKey(SettingsKeys.SECURE_SCREENS)) { it.secureScreens }
        val COVER_FACES = PresetSetting(booleanPreferencesKey(SettingsKeys.COVER_FACES)) { it.coverFaces }

        // Stored by name, as `SettingsStore.bluetoothDownloadLimit` reads it with `BluetoothDownloadLimit.of`.
        val BLUETOOTH_DOWNLOAD_LIMIT =
            PresetSetting(stringPreferencesKey(SettingsKeys.BLUETOOTH_DOWNLOAD_LIMIT)) { it.bluetoothDownloadLimit.name }

        val all: List<PresetSetting<*>> =
            listOf(
                LINK_PREVIEWS,
                TYPING_INDICATORS,
                READ_RECEIPTS,
                TRANSLATION,
                WIFI_AWARE,
                CALL_TRANSCRIPTS,
                BLUETOOTH_DOWNLOAD_LIMIT,
                SECURE_SCREENS,
                COVER_FACES,
            )
    }
}

/** The person's own value for [setting], else the picked preset's. */
fun <T : Any> Preferences.orPreset(setting: PresetSetting<T>): T = this[setting.key] ?: setting.pick(Preset.of(this).defaults)

/** One covered switch a preset switch moves, and whether moving it tells others more about this phone than before. */
data class PresetChange(
    val switch: Switch,
    val on: Boolean,
) {
    enum class Switch {
        RELAYS,
        LINK_PREVIEWS,
        READ_RECEIPTS,
        TYPING_INDICATORS,
        TRANSLATION,
        SECURE_SCREENS,
        COVER_FACES,
        WIFI_AWARE,
        TRANSCRIPTS,
    }

    /** Turning this on (or, for the screenshot block and covering faces, off) shares more than before; listed first. */
    val weakensPrivacy: Boolean
        get() =
            when (switch) {
                Switch.SECURE_SCREENS, Switch.COVER_FACES -> !on
                Switch.WIFI_AWARE, Switch.TRANSCRIPTS -> false
                else -> on
            }
}

/**
 * What moving from [current] to [target] (the covered switches as they read before and after) changes, privacy-weakening
 * changes first. The Bluetooth limit is left out: the preset's card line says it.
 */
fun presetChanges(
    current: PresetDefaults,
    target: PresetDefaults,
): List<PresetChange> {
    val pairs =
        listOf(
            PresetChange.Switch.RELAYS to (current.relays to target.relays),
            PresetChange.Switch.LINK_PREVIEWS to (current.linkPreviews to target.linkPreviews),
            PresetChange.Switch.READ_RECEIPTS to (current.readReceipts to target.readReceipts),
            PresetChange.Switch.TYPING_INDICATORS to (current.typingIndicators to target.typingIndicators),
            PresetChange.Switch.TRANSLATION to (current.translation to target.translation),
            PresetChange.Switch.SECURE_SCREENS to (current.secureScreens to target.secureScreens),
            PresetChange.Switch.COVER_FACES to (current.coverFaces to target.coverFaces),
            PresetChange.Switch.WIFI_AWARE to (current.wifiAware to target.wifiAware),
            PresetChange.Switch.TRANSCRIPTS to (current.callTranscripts to target.callTranscripts),
        )
    return pairs
        .filter { (_, values) -> values.first != values.second }
        .map { (switch, values) -> PresetChange(switch, values.second) }
        .sortedByDescending { it.weakensPrivacy }
}
