package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * User/device settings backed by a Preferences DataStore (replaces the legacy
 * SharedPreferences). Holds the stable node identity and the profile/mesh toggles.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val displayName: Flow<String> = dataStore.data.map { it[KEY_NAME] ?: "" }
    val status: Flow<String> = dataStore.data.map { it[KEY_STATUS] ?: "" }
    val advertisingEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ADVERTISING] ?: true }
    val discoveryEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DISCOVERY] ?: true }

    /** Bumped whenever the avatar image changes, so profile re-broadcasts can be triggered. */
    val avatarUpdatedAt: Flow<Long> = dataStore.data.map { it[KEY_AVATAR_UPDATED_AT] ?: 0L }

    /** Returns the persisted 8-char node id, generating and storing one on first call. */
    suspend fun getOrCreateNodeId(): String {
        dataStore.data.first()[KEY_NODE_ID]?.let { return it }
        val generated = randomNodeId()
        dataStore.edit { prefs ->
            // Re-check inside the transaction to avoid a race generating two ids.
            prefs[KEY_NODE_ID] ?: run { prefs[KEY_NODE_ID] = generated }
        }
        return dataStore.data.first()[KEY_NODE_ID] ?: generated
    }

    suspend fun setDisplayName(value: String) = dataStore.edit { it[KEY_NAME] = value }
    suspend fun setStatus(value: String) = dataStore.edit { it[KEY_STATUS] = value }
    suspend fun setAdvertisingEnabled(value: Boolean) = dataStore.edit { it[KEY_ADVERTISING] = value }
    suspend fun setDiscoveryEnabled(value: Boolean) = dataStore.edit { it[KEY_DISCOVERY] = value }
    suspend fun setAvatarUpdatedAt(value: Long) = dataStore.edit { it[KEY_AVATAR_UPDATED_AT] = value }

    private fun randomNodeId(): String =
        (1..NODE_ID_LENGTH).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    private companion object {
        const val NODE_ID_LENGTH = 8
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        val KEY_NODE_ID = stringPreferencesKey("node_id")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_ADVERTISING = booleanPreferencesKey("advertising_enabled")
        val KEY_DISCOVERY = booleanPreferencesKey("discovery_enabled")
        val KEY_AVATAR_UPDATED_AT = longPreferencesKey("avatar_updated_at")
    }
}
