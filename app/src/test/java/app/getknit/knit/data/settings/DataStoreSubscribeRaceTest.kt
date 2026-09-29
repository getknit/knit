package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #97: a `DataStore.data` collector that subscribes while an edit is being written must still see that edit.
 * DataStore 1.2.1 labels the stale file it reads mid-write with the write's new version and then drops the
 * cache's real update as not newer (fixed upstream in 1.3.0-alpha03, b/431787506). It is how
 * `MeshManager.watchProfileChanges` missed a rename made while the mesh started — the mesh lab's "profile
 * edit was never published" setup failure (#88 case 1). A race, so it counts misses over many rounds: 11 of
 * 3000 on a workstation under 1.2.1, which is why the catalog pins 1.3.0-alpha03 until 1.3.0 is stable.
 */
class DataStoreSubscribeRaceTest {
    @Test
    fun aCollectorThatSubscribesDuringAWriteSeesTheWrite() =
        runBlocking {
            val key = intPreferencesKey("n")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dir = Files.createTempDirectory("dsrace").toFile()
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "race.preferences_pb") }
            store.edit { it[key] = 0 }
            var missed = 0
            for (i in 1..ROUNDS) {
                val seen = async(Dispatchers.Default) { withTimeoutOrNull(SEEN_MS) { store.data.map { it[key] }.first { it == i } } }
                launch(Dispatchers.Default) { store.edit { it[key] = i } }
                if (seen.await() == null) missed++
            }
            scope.cancel()
            dir.deleteRecursively()
            assertEquals("collectors that subscribed during a write and never saw it, of $ROUNDS", 0, missed)
        }

    private companion object {
        const val ROUNDS = 3_000
        const val SEEN_MS = 2_000L
    }
}
