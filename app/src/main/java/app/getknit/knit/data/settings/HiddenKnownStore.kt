package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The *Known, not reachable* rows the user hid in Diagnostics (GitHub #54, ADR 2026-11.b37x): node id → the profile
 * version (`PeerEntity.updatedAt`) the row showed when it was hidden. A row stays hidden while the peer's profile is that
 * version or older, so a node that publishes a newer one comes back. Presentation only: no mesh code reads it.
 */
interface HiddenKnownRows {
    val hidden: Flow<Map<String, Long>>

    /** Rewrites [hidden] in one step; an empty map clears it. */
    suspend fun edit(transform: (Map<String, Long>) -> Map<String, Long>)
}

/** [HiddenKnownRows] over the settings DataStore, as `id@version` entries in one string set. It rides a backup. */
class HiddenKnownStore(
    private val dataStore: DataStore<Preferences>,
) : HiddenKnownRows {
    override val hidden: Flow<Map<String, Long>> =
        dataStore.data.map { decode(it[KEY].orEmpty()) }.distinctUntilChanged()

    override suspend fun edit(transform: (Map<String, Long>) -> Map<String, Long>) {
        dataStore.edit { prefs ->
            val next = transform(decode(prefs[KEY].orEmpty()))
            if (next.isEmpty()) {
                prefs.remove(KEY)
            } else {
                prefs[KEY] = next.mapTo(HashSet()) { (id, version) -> "$id$SEP$version" }
            }
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("diagnostics_hidden_known")
        const val SEP = '@'

        /** `id@version` entries back to a map; a malformed entry is skipped, never thrown on. */
        fun decode(entries: Set<String>): Map<String, Long> =
            entries
                .mapNotNull { entry ->
                    val at = entry.lastIndexOf(SEP)
                    val version = entry.substring(at + 1).toLongOrNull()
                    if (at <= 0 || version == null) null else entry.substring(0, at) to version
                }.toMap()
    }
}
