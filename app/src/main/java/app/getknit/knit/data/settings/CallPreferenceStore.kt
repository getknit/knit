package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.VoxSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which call a chat puts first (docs/VOICE_CALLS.md C-3.11-3), over the settings DataStore beside [IncomingCallStore].
 * Off by default, so the chat picks by the route; on, its top bar always offers the mesh call and its overflow the live
 * one. Bound in every build for the Settings row, read by the chat only where both call kinds are wired; rides a Knit
 * backup like every other choice the person made.
 *
 * Also how readily a hands-free walkie opens a burst on a voice (C-3.11-11): [VoxSensitivity.DEFAULT] until changed.
 */
class CallPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The Calls screen's "Prefer mesh calls" switch. */
    val preferMesh: Flow<Boolean> = dataStore.data.map { it[KEY_PREFER_MESH] ?: false }

    suspend fun setPreferMesh(value: Boolean) = dataStore.edit { it[KEY_PREFER_MESH] = value }

    /** The Calls screen's "Hands-free sensitivity": a stored name this build does not know reads as the default. */
    val voxSensitivity: Flow<VoxSensitivity> = dataStore.data.map { VoxSensitivity.of(it[KEY_VOX_SENSITIVITY]) }

    suspend fun setVoxSensitivity(value: VoxSensitivity) = dataStore.edit { it[KEY_VOX_SENSITIVITY] = value.name }

    internal companion object {
        val KEY_PREFER_MESH = booleanPreferencesKey("calls_prefer_mesh")
        val KEY_VOX_SENSITIVITY = stringPreferencesKey("calls_vox_sensitivity")
    }
}
