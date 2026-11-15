package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.mesh.MeshcoreBoardReport
import app.getknit.knit.mesh.MeshcoreClaimJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The sealed profile's MeshCore claim over the settings DataStore (ADR 2026-11.gsqu, Stage 4 and 5d): the bound board's
 * public key (`meshcore_board_pubkey`, [BoardKeys.MESHCORE_PUBKEY]) and its signature binding our node id
 * (`meshcore_board_bind`, [BoardKeys.MESHCORE_BIND]) — both in [BoardKeys.MESHCORE]'s `reported`, so [BoardBindPolicy]
 * forgets them together on any bind, and both riding a backup — and the sealed-only version a claim change was
 * published under, with the fingerprint of what it published (`meshcore_sealed_version`, `meshcore_published`). Both of
 * the last two ride a backup, as the badge's do ([BadgeStore]): a restored phone must never publish below a version its
 * peers hold.
 */
class MeshcoreClaimStore(
    private val dataStore: DataStore<Preferences>,
) : MeshcoreClaimJournal {
    override val boardKey: Flow<String?> = dataStore.data.map { it[BoardKeys.MESHCORE_PUBKEY] }.distinctUntilChanged()

    override val board: Flow<MeshcoreBoardReport> =
        dataStore.data.map { MeshcoreBoardReport(it[BoardKeys.MESHCORE_PUBKEY], it[BoardKeys.MESHCORE_BIND]) }.distinctUntilChanged()

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
     * The bound MeshCore board reported its public [key] and, when it signed our bind, its signature [sig] (canonical
     * base64; ADR 2026-11.gsqu, Stage 5d): the one writer Stage 5's plane calls, from the consent the session publishes,
     * under the same rule as `SettingsStore.setLoraBoard`. [dialled] given, the write lands only while
     * `meshcore_device_address` still equals it, checked inside the same edit, so a handshake that finishes after an unbind
     * or a cross-kind bind never writes the old board's key back into the sealed profile. Null writes unconditionally (the
     * lab, whose boards bind no address). One edit, so the claim watcher never sees a key beside another key's signature:
     *
     * - null [key]: both forgotten (a withdrawal: an unbind, a Restore, a board never set up);
     * - a [sig]: both written;
     * - no [sig], [key] the one stored: the stored signature is left as it is, so a consent re-published without one (a
     *   bind that failed this connection) never erases a proof the board already gave for this key, and never mints;
     * - no [sig], a different [key]: the key written and the signature forgotten, since the old board's proof must not
     *   survive beside the new board's key.
     *
     * Stored as given: every reader checks the key ([MeshcoreKey]) and the signature ([MeshcoreBind]).
     *
     * [keep], when given, is asked inside the same edit about the stored pair when [key] is the stored key and a
     * signature is stored beside it: true leaves that proof as it is, as a null [sig] would (Stage 5d, no rewrite on a
     * re-sign, `di/writeMeshcoreClaim`). Asked of the stored state the edit itself sees, so no writer between a read
     * and this edit can make it write the key without its proof. Null, the rule above alone.
     */
    suspend fun setBoardKey(
        key: String?,
        sig: String? = null,
        dialled: String? = null,
        keep: ((storedKey: String, storedSig: String) -> Boolean)? = null,
    ) {
        dataStore.edit {
            if (dialled != null && it[BoardKeys.MESHCORE.address] != dialled) return@edit
            // The same key without a signature changes nothing: the stored proof stands.
            if (key != null && sig == null && it[BoardKeys.MESHCORE_PUBKEY] == key) return@edit
            // The same key with a stored proof [keep] accepts: that proof stands too.
            val proof = it[BoardKeys.MESHCORE_BIND]?.takeIf { _ -> key != null && it[BoardKeys.MESHCORE_PUBKEY] == key }
            if (proof != null && key != null && keep?.invoke(key, proof) == true) return@edit
            if (key == null) it.remove(BoardKeys.MESHCORE_PUBKEY) else it[BoardKeys.MESHCORE_PUBKEY] = key
            if (key != null && sig != null) it[BoardKeys.MESHCORE_BIND] = sig else it.remove(BoardKeys.MESHCORE_BIND)
        }
    }

    /** Names nothing it holds. */
    override fun toString(): String = "MeshcoreClaimStore"

    companion object {
        val SEALED_VERSION = longPreferencesKey(SettingsKeys.MESHCORE_SEALED_VERSION)
        val PUBLISHED = stringPreferencesKey(SettingsKeys.MESHCORE_PUBLISHED)
    }
}
