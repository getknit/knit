package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.getknit.knit.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The person's [Preset], over the settings DataStore rather than grown into [SettingsStore], like [AwareSwitch]. The
 * switches it supplies are read by the stores that own them, through [orPreset]; this class only writes the pick.
 */
class PresetStore(
    private val dataStore: DataStore<Preferences>,
) {
    val preset: Flow<Preset> = dataStore.data.map { Preset.of(it) }.distinctUntilChanged()

    /**
     * Whether any switch [Preset] supplies holds the person's own value, different from what the preset gives, or the
     * relay plane is not where the preset has it. Relays count although they are no folded default: a Privacy-first
     * row must not read plainly "Privacy-first" while relays carry this phone's messages.
     */
    val customised: Flow<Boolean> = dataStore.data.map(::customised).distinctUntilChanged()

    /** What the covered switches read now, relays included: what a switch away from here changes from. */
    val effective: Flow<PresetDefaults> = dataStore.data.map(::effective).distinctUntilChanged()

    /**
     * Picks [preset], in **one** edit. With [resetOverrides] the switches it supplies forget the person's own values and
     * read the preset's, and the relay plane is put where the preset has it: on writes the disclosure's consent and the
     * switch together, as `SettingsStore.acceptSpoolConsent` does, so the caller must have shown that disclosure; off
     * clears the switch and keeps the consent, which is about having read it once. Without [resetOverrides] only the
     * pick is written, and every switch the person set keeps its value. The relay list is never touched.
     */
    suspend fun apply(
        preset: Preset,
        resetOverrides: Boolean,
    ) {
        dataStore.edit { it.pick(preset, resetOverrides) }
    }

    /**
     * What [apply] with these arguments would move, from the switches as they read now: the same edit run on a copy, so
     * the confirmation can never say something the switch does not do. Privacy-weakening changes first.
     */
    suspend fun changes(
        preset: Preset,
        resetOverrides: Boolean,
    ): List<PresetChange> {
        val now = dataStore.data.first()
        val after = now.toMutablePreferences().apply { pick(preset, resetOverrides) }
        return presetChanges(effective(now), effective(after))
    }

    private fun MutablePreferences.pick(
        preset: Preset,
        resetOverrides: Boolean,
    ) {
        this[Preset.KEY] = preset.name
        if (!resetOverrides) return
        PresetSetting.all.forEach { remove(it.key) }
        putRelays(preset.defaults.relays)
    }

    internal fun customised(prefs: Preferences): Boolean {
        val preset = Preset.of(prefs)
        return PresetSetting.all.any { it.overrides(prefs, preset) } ||
            relaysOn(prefs) != (preset.defaults.relays && BuildConfig.INTERNET_PLANE)
    }

    internal fun effective(prefs: Preferences): PresetDefaults =
        PresetDefaults(
            relays = relaysOn(prefs),
            linkPreviews = BuildConfig.INTERNET_PLANE && prefs.orPreset(PresetSetting.LINK_PREVIEWS),
            typingIndicators = prefs.orPreset(PresetSetting.TYPING_INDICATORS),
            readReceipts = prefs.orPreset(PresetSetting.READ_RECEIPTS),
            translation = prefs.orPreset(PresetSetting.TRANSLATION),
            wifiAware = AwareSwitch.readOn(prefs),
            callTranscripts = prefs.orPreset(PresetSetting.CALL_TRANSCRIPTS),
            bluetoothDownloadLimit = BluetoothDownloadLimit.of(prefs.orPreset(PresetSetting.BLUETOOTH_DOWNLOAD_LIMIT)),
            secureScreens = prefs.orPreset(PresetSetting.SECURE_SCREENS),
            // Folded with the build as the review is: a build without face redaction never covers, whatever is stored.
            coverFaces = BuildConfig.FACE_REDACTION && prefs.orPreset(PresetSetting.COVER_FACES),
        )

    private fun relaysOn(prefs: Preferences): Boolean = BuildConfig.INTERNET_PLANE && prefs[SPOOL_ENABLED] == true

    private fun MutablePreferences.putRelays(on: Boolean) {
        if (on && BuildConfig.INTERNET_PLANE) {
            this[SPOOL_CONSENTED] = true
            this[SPOOL_ENABLED] = true
        } else {
            remove(SPOOL_ENABLED)
        }
    }

    private companion object {
        val SPOOL_ENABLED = booleanPreferencesKey(SettingsKeys.SPOOL_ENABLED)
        val SPOOL_CONSENTED = booleanPreferencesKey(SettingsKeys.SPOOL_CONSENTED)
    }
}
