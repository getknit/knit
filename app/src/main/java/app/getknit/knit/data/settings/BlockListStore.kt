package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the block list's presentation hooks keep outside Room (ADR 2026-11.nsbf, no schema change): the faces an unblock
 * still owes, and the block list as `mesh/BlockListWatch` last acted on it.
 */
interface BlockListState {
    /**
     * The faces an unblock still owes, each `"<nodeId> <hash>"`. An unblock brings a person's face back from the photo their
     * held profile advertises, and a linked neighbour never pushes a photo twice, so a want that lapsed (the fetch TTL, a
     * restart) would leave the monogram for good. `InboundPipeline.rewantOwedFaces` re-wants each one on every database
     * re-arm until the face shows, a newer profile takes over, or the person is blocked again.
     */
    val owedFaces: Flow<Set<String>>

    /** Records that [nodeId]'s face [hash] is owed (see [owedFaces]). */
    suspend fun oweFace(
        nodeId: String,
        hash: String,
    )

    /** Drops the owed-face entry [entry], exactly as [owedFaces] lists it. */
    suspend fun settleFace(entry: String)

    /**
     * The block list as `mesh/BlockListWatch` last acted on it, null before it first did. The watch diffs its first value
     * against this, so a block or an unblock made while the mesh was stopped is acted on at the next start.
     */
    val actedOn: Flow<Set<String>?>

    suspend fun setActedOn(ids: Set<String>)
}

/**
 * [BlockListState] over the settings DataStore rather than grown into [SettingsStore], as `CallTombStore` is. Both keys
 * ride a backup with the database they describe: a restored phone's rows and held profiles are the ones they name.
 */
class BlockListStore(
    private val dataStore: DataStore<Preferences>,
) : BlockListState {
    override val owedFaces: Flow<Set<String>> = dataStore.data.map { it[OWED_FACES] ?: emptySet() }

    override suspend fun oweFace(
        nodeId: String,
        hash: String,
    ) {
        dataStore.edit { it[OWED_FACES] = (it[OWED_FACES] ?: emptySet()) + "$nodeId $hash" }
    }

    override suspend fun settleFace(entry: String) {
        dataStore.edit { it[OWED_FACES] = (it[OWED_FACES] ?: emptySet()) - entry }
    }

    override val actedOn: Flow<Set<String>?> = dataStore.data.map { it[ACTED_ON] }

    override suspend fun setActedOn(ids: Set<String>) {
        dataStore.edit { it[ACTED_ON] = ids }
    }

    private companion object {
        val OWED_FACES = stringSetPreferencesKey("owed_faces")
        val ACTED_ON = stringSetPreferencesKey("block_list_acted_on")
    }
}
