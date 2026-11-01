package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Diagnostics hidden rows as the settings keep them (GitHub #54, ADR 2026-11.b37x). */
class HiddenKnownStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }

    @Test
    fun hidesRoundTripAndAnEmptyMapClearsTheKey() =
        runTest {
            val data = dataStore()
            val store = HiddenKnownStore(data)
            assertEquals(emptyMap<String, Long>(), store.hidden.first())

            store.edit { it + mapOf("a1b2" to 1_700_000_000_000L, "c3d4" to 0L) }
            assertEquals(mapOf("a1b2" to 1_700_000_000_000L, "c3d4" to 0L), store.hidden.first())

            store.edit { it - "a1b2" }
            assertEquals(mapOf("c3d4" to 0L), store.hidden.first())

            store.edit { emptyMap() }
            assertNull("an empty set is no key at all", data.data.first()[KEY])
        }

    @Test
    fun aMalformedEntryIsSkippedNotThrownOn() =
        runTest {
            val data = dataStore()
            data.edit { it[KEY] = setOf("good@5", "no-version", "@7", "bad@x") }

            assertEquals(mapOf("good" to 5L), HiddenKnownStore(data).hidden.first())
        }

    private companion object {
        val KEY = stringSetPreferencesKey("diagnostics_hidden_known")
    }
}
