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

/** The chats the user muted, as the settings keep them (ADR 2026-11.sp7k). */
class ChatMuteStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }

    @Test
    fun mutesRoundTripAndTheLastUnmuteClearsTheKey() =
        runTest {
            val data = dataStore()
            val store = ChatMuteStore(data)
            assertEquals(emptyMap<String, Long>(), store.mutedUntil.first())

            store.mute("g-abc", until = 2_000L, now = 1_000L)
            store.mute("nearby", until = ChatMutes.ALWAYS, now = 1_000L)
            assertEquals(mapOf("g-abc" to 2_000L, "nearby" to ChatMutes.ALWAYS), store.mutedUntil.first())

            // A second mute of the same chat replaces its deadline.
            store.mute("g-abc", until = 9_000L, now = 1_000L)
            assertEquals(mapOf("g-abc" to 9_000L, "nearby" to ChatMutes.ALWAYS), store.mutedUntil.first())

            store.unmute("g-abc")
            store.unmute("not-muted")
            assertEquals(mapOf("nearby" to ChatMutes.ALWAYS), store.mutedUntil.first())

            store.unmute("nearby")
            assertNull("an empty set is no key at all", data.data.first()[KEY])
        }

    @Test
    fun aMuteDropsEveryEntryAlreadyPast() =
        runTest {
            val store = ChatMuteStore(dataStore())
            store.mute("a", until = 1_500L, now = 1_000L)
            store.mute("b", until = 3_000L, now = 1_000L)

            store.mute("c", until = ChatMutes.ALWAYS, now = 2_000L)

            assertEquals(mapOf("b" to 3_000L, "c" to ChatMutes.ALWAYS), store.mutedUntil.first())
        }

    @Test
    fun aMalformedEntryIsSkippedNotThrownOn() =
        runTest {
            val data = dataStore()
            data.edit { it[KEY] = setOf("good@5", "no-deadline", "@7", "bad@x") }

            assertEquals(mapOf("good" to 5L), ChatMuteStore(data).mutedUntil.first())
        }

    @Test
    fun settingsStoreReadsTheSameMutesForTheGate() =
        runTest {
            val data = dataStore()
            val settings = SettingsStore(data)
            settings.chatMutes.mute("peer", until = 5_000L, now = 1_000L)

            assertEquals(mapOf("peer" to 5_000L), settings.chatMutedUntil.first())
            assertEquals(mapOf("peer" to 5_000L), ChatMuteStore(data).mutedUntil.first())
        }

    private companion object {
        val KEY = stringSetPreferencesKey("chat_muted_until")
    }
}
