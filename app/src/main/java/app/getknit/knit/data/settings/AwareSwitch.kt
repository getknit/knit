package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The person's Wi-Fi Aware switch, Settings › Wi-Fi Aware (GitHub #44), over the settings DataStore rather than grown
 * into [SettingsStore], like [LowPowerSwitch]. On by default; off stops the plane outright through `SwitchableTransport`
 * (`awareSwitchedOff`), and Bluetooth carries the mesh alone. Phone-bound: `wifi_aware_` is in
 * `SettingsKeys.TRANSIENT_PREFIXES`, because whether this phone's Wi-Fi can spare the airtime is about this phone.
 *
 * **The one-time move from low-power mode.** Low-power mode is no longer offered (`LOW_POWER_OFFERED`), and its only
 * Aware lever was switching the plane off. A phone that had it on keeps Aware off: until [migrateFromLowPower] has run,
 * [on] reads a set `low_power_mode` as this switch off, so the plane never attaches at launch only to stop again, and
 * [migrateFromLowPower] then writes that answer and its journal ([MIGRATED]) once. After the journal, `low_power_mode`
 * is never read here again; it is left as it was (the mode's code is kept, and its overlay never writes).
 */
class AwareSwitch(
    private val dataStore: DataStore<Preferences>,
) {
    val on: Flow<Boolean> = dataStore.data.map(::readOn).distinctUntilChanged()

    suspend fun set(on: Boolean) {
        dataStore.edit {
            it[KEY] = on
            it[MIGRATED] = true // the person's own choice outranks anything the move would have written
        }
    }

    /** Writes the low-power move once ([on]'s reading of it), and its journal. Idempotent; safe at every start. */
    suspend fun migrateFromLowPower() {
        dataStore.edit {
            if (it[MIGRATED] == true) return@edit
            if (it[KEY] == null && it[LOW_POWER] == true) it[KEY] = false
            it[MIGRATED] = true
        }
    }

    internal companion object {
        val KEY = booleanPreferencesKey(SettingsKeys.WIFI_AWARE_ENABLED)
        val MIGRATED = booleanPreferencesKey(SettingsKeys.WIFI_AWARE_MIGRATED)
        private val LOW_POWER = booleanPreferencesKey(SettingsKeys.LOW_POWER_MODE)

        /** The switch as stored, or, before the move has been journaled, low-power mode's answer for it. */
        fun readOn(prefs: Preferences): Boolean = prefs[KEY] ?: (prefs[MIGRATED] == true || prefs[LOW_POWER] != true)
    }
}
