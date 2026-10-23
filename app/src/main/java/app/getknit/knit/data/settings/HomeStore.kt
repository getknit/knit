package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.getknit.knit.mesh.spool.HomeJournal
import app.getknit.knit.mesh.spool.PublicSpool
import app.getknit.knit.mesh.spool.PublicSpools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * This phone's **home** relay and the public directory it was picked from (ADR 2026-10.8kfx, knit-spool-coordinator
 * ADR 2026-10.txjf), over the settings DataStore beside the relay list.
 *
 * - The home is picked once and stored: an install that predates homes keeps [PublicSpools.LEGACY_HOST]
 *   ([keepLegacyHome], before the default relay is seeded), and a fresh one hashes its node id over the directory
 *   ([settle]). It changes only when the directory drops it or marks it `draining`. It rides a backup.
 * - The directory is the last copy read ([putDirectory]), else the one this build ships ([PublicSpools.BAKED]). Phone
 *   state, out of a backup.
 * - The conversations a scope config is still owed to ([owed]), riding a backup, so a move a restore interrupts is
 *   finished on the other phone.
 */
class HomeStore(
    private val dataStore: DataStore<Preferences>,
) : HomeJournal {
    /** The home relay's host, or null before [settle] first ran. */
    override val home: Flow<String?> = dataStore.data.map { it[KEY_HOME] }.distinctUntilChanged()

    /** The directory this phone holds: the last one read, else [PublicSpools.BAKED]. */
    override val directory: Flow<List<PublicSpool>> =
        dataStore.data
            .map { prefs -> prefs[KEY_DIRECTORY]?.let(PublicSpools::parse) ?: PublicSpools.BAKED }
            .distinctUntilChanged()

    /** When the directory was last read (epoch millis), or 0 for never. */
    override suspend fun directoryReadAt(): Long = dataStore.data.first()[KEY_DIRECTORY_AT] ?: 0L

    /**
     * An install that seeded the default relay before homes existed keeps it as its home (txjf: every install that
     * exists when this ships stays on `public-1`). Run on every start, before the seed, so a fresh install's first
     * seed is never mistaken for an old one; a no-op once a home is stored.
     */
    suspend fun keepLegacyHome() {
        dataStore.edit { prefs ->
            if (prefs[KEY_HOME] == null && prefs[KEY_SEEDED] == true) prefs[KEY_HOME] = PublicSpools.LEGACY_HOST
        }
    }

    /**
     * Picks or keeps the home for [nodeId] against the directory held: a phone with none picks; a phone whose home
     * the directory dropped or marks `draining` picks again over the open entries; any other keeps it. Returns the
     * change as (previous, current), or null when nothing changed. A directory with no open relay leaves a home as it
     * was, and gives a phone with none [PublicSpools.LEGACY_HOST].
     */
    override suspend fun settle(nodeId: String): Pair<String?, String>? {
        var change: Pair<String?, String>? = null
        dataStore.edit { prefs ->
            val entries = prefs[KEY_DIRECTORY]?.let(PublicSpools::parse) ?: PublicSpools.BAKED
            val current = prefs[KEY_HOME]
            if (current != null && !PublicSpools.mustRehome(current, entries)) return@edit
            // With nothing open a phone keeps its home, and a fresh one takes public-1: never a home of nothing (txjf).
            val picked = PublicSpools.pickHome(nodeId, entries) ?: current ?: PublicSpools.LEGACY_HOST
            if (picked == current) return@edit
            prefs[KEY_HOME] = picked
            change = current to picked
        }
        return change
    }

    /** Keeps a directory just read: its entries (already parsed and filtered) and when. */
    override suspend fun putDirectory(
        entries: List<PublicSpool>,
        at: Long,
    ) {
        dataStore.edit {
            it[KEY_DIRECTORY] = PublicSpools.format(entries)
            it[KEY_DIRECTORY_AT] = at
        }
    }

    /** Marks a read that brought nothing usable, so the next try waits a day too. */
    override suspend fun markDirectoryRead(at: Long) {
        dataStore.edit { it[KEY_DIRECTORY_AT] = at }
    }

    override suspend fun owed(): Set<String> = dataStore.data.first()[KEY_OWED].orEmpty()

    override suspend fun owe(conversationId: String) {
        dataStore.edit { it[KEY_OWED] = it[KEY_OWED].orEmpty() + conversationId }
    }

    override suspend fun paid(conversationId: String) {
        dataStore.edit { prefs ->
            val left = prefs[KEY_OWED].orEmpty() - conversationId
            if (left.isEmpty()) prefs.remove(KEY_OWED) else prefs[KEY_OWED] = left
        }
    }

    private companion object {
        val KEY_OWED = stringSetPreferencesKey(SettingsKeys.SPOOL_CONFIG_OWED)
        val KEY_HOME = stringPreferencesKey(SettingsKeys.SPOOL_HOME)
        val KEY_DIRECTORY = stringPreferencesKey(SettingsKeys.SPOOL_DIRECTORY)
        val KEY_DIRECTORY_AT = longPreferencesKey(SettingsKeys.SPOOL_DIRECTORY_AT)
        val KEY_SEEDED = booleanPreferencesKey(SettingsKeys.SPOOL_DEFAULTS_SEEDED)
    }
}
