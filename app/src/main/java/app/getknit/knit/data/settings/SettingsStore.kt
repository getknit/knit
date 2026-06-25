package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.identity.DeviceIdSource
import app.getknit.knit.identity.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * User/device settings backed by a Preferences DataStore (replaces the legacy
 * SharedPreferences). Holds the stable node identity and the profile/mesh toggles.
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val deviceIdSource: DeviceIdSource,
) {

    val displayName: Flow<String> = dataStore.data.map { it[KEY_NAME] ?: "" }
    val status: Flow<String> = dataStore.data.map { it[KEY_STATUS] ?: "" }
    val advertisingEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ADVERTISING] ?: true }
    val discoveryEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DISCOVERY] ?: true }

    /** Bumped whenever the avatar image changes, so profile re-broadcasts can be triggered. */
    val avatarUpdatedAt: Flow<Long> = dataStore.data.map { it[KEY_AVATAR_UPDATED_AT] ?: 0L }

    /**
     * Per-conversation read watermarks: for each conversation id, the [MessageEntity.sentAt] of the
     * newest message the local user has seen there. The chat list counts messages newer than the
     * watermark (from other senders) as that conversation's unread badge. Stored under one dynamic
     * key per conversation (see [lastReadKey]); [lastReadAll] reads them back as a map for the list.
     */
    val lastReadAll: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs.asMap()
            .filterKeys { it.name.startsWith(LAST_READ_PREFIX) }
            .entries
            .associate { (key, value) -> key.name.removePrefix(LAST_READ_PREFIX) to (value as? Long ?: 0L) }
    }

    /** Read watermark for a single conversation (0 until the user has read anything there). */
    fun lastReadAt(conversationId: String): Flow<Long> =
        dataStore.data.map { it[lastReadKey(conversationId)] ?: 0L }

    /**
     * Returns the persisted 8-char node id, generating and storing one on first call. New ids are
     * derived deterministically from the device id (see [NodeId]), so clearing app data regenerates
     * the same id instead of a fresh random one. An already-persisted id is always returned as-is.
     */
    suspend fun getOrCreateNodeId(): String {
        dataStore.data.first()[KEY_NODE_ID]?.let { return it }
        val generated = newNodeId()
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

    suspend fun setLastReadAt(conversationId: String, value: Long) =
        dataStore.edit { it[lastReadKey(conversationId)] = value }

    /** Device-derived id, or a random fallback when the platform reports no stable device id. */
    private fun newNodeId(): String =
        deviceIdSource.rawDeviceId()?.takeIf { it.isNotBlank() }
            ?.let { NodeId.derive(it) }
            ?: randomNodeId()

    private fun randomNodeId(): String =
        (1..NodeId.LENGTH).map { NodeId.ALPHABET[Random.nextInt(NodeId.ALPHABET.length)] }
            .joinToString("")

    /** Dynamic per-conversation read-watermark key, e.g. "last_read_nearby" / "last_read_<nodeId>". */
    private fun lastReadKey(conversationId: String) =
        longPreferencesKey(LAST_READ_PREFIX + conversationId)

    private companion object {
        const val LAST_READ_PREFIX = "last_read_"

        val KEY_NODE_ID = stringPreferencesKey("node_id")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_ADVERTISING = booleanPreferencesKey("advertising_enabled")
        val KEY_DISCOVERY = booleanPreferencesKey("discovery_enabled")
        val KEY_AVATAR_UPDATED_AT = longPreferencesKey("avatar_updated_at")
    }
}
