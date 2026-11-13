package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.mesh.MeshcoreClaimJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The sealed profile's MeshCore claim over the settings DataStore (ADR 2026-11.gsqu, Stage 4): the bound board's public
 * key (`meshcore_board_pubkey`, [BoardKeys.MESHCORE_PUBKEY], which [BoardBindPolicy] clears on a cross-kind bind and which
 * rides a backup) and the sealed-only version a claim change was published under, with the fingerprint of what it
 * published (`meshcore_sealed_version`, `meshcore_published`). Both of the last two ride a backup, as the badge's do
 * ([BadgeStore]): a restored phone must never publish below a version its peers hold.
 */
class MeshcoreClaimStore(
    private val dataStore: DataStore<Preferences>,
) : MeshcoreClaimJournal {
    override val boardKey: Flow<String?> = dataStore.data.map { it[BoardKeys.MESHCORE_PUBKEY] }.distinctUntilChanged()

    override val sealedVersion: Flow<Long> = dataStore.data.map { it[SEALED_VERSION] ?: 0L }.distinctUntilChanged()

    override val publishedFingerprint: Flow<String?> = dataStore.data.map { it[PUBLISHED] }.distinctUntilChanged()

    override suspend fun putSealed(
        version: Long,
        fingerprint: String,
    ) {
        dataStore.edit {
            it[SEALED_VERSION] = version
            it[PUBLISHED] = fingerprint
        }
    }

    /**
     * The bound MeshCore board reported its public key: the writer Stage 5's plane calls, under the same rule as
     * `SettingsStore.setLoraBoard`. [dialled] given, the write lands only while `meshcore_device_address` still equals it,
     * checked inside the same edit, so a handshake that finishes after an unbind or a cross-kind bind never writes the old
     * board's key back into the sealed profile. Null writes unconditionally (the lab, whose boards bind no address). Null
     * [key] forgets it. The key is stored as given: every reader checks it (`MeshcoreKey`).
     */
    suspend fun setBoardKey(
        key: String?,
        dialled: String? = null,
    ) {
        dataStore.edit {
            if (dialled != null && it[BoardKeys.MESHCORE.address] != dialled) return@edit
            if (key != null) it[BoardKeys.MESHCORE_PUBKEY] = key else it.remove(BoardKeys.MESHCORE_PUBKEY)
        }
    }

    /** Names nothing it holds. */
    override fun toString(): String = "MeshcoreClaimStore"

    companion object {
        val SEALED_VERSION = longPreferencesKey(SettingsKeys.MESHCORE_SEALED_VERSION)
        val PUBLISHED = stringPreferencesKey(SettingsKeys.MESHCORE_PUBLISHED)
    }
}
