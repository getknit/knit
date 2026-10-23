package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.getknit.knit.mesh.spool.PublicSpool
import app.getknit.knit.mesh.spool.PublicSpools
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The home relay as the settings keep it (ADR 2026-10.8kfx): an install that predates homes keeps `public-1`, a fresh
 * one picks by hash, and only a dropped or draining home moves.
 */
class HomeStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }

    private fun spool(
        n: Int,
        state: String = PublicSpool.OPEN,
        weight: Int = 1,
    ) = PublicSpool("public-$n.spool.getknit.app", state, weight)

    @Test
    fun anInstallThatSeededBeforeHomesKeepsPublicOne() =
        runTest {
            val data = dataStore()
            data.edit { it[booleanPreferencesKey(SettingsKeys.SPOOL_DEFAULTS_SEEDED)] = true }
            val store = HomeStore(data)
            store.keepLegacyHome()
            // Even against a directory whose hash would send this node elsewhere.
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5)), at = 1L)
            assertNull(store.settle(NODE))
            assertEquals(PublicSpools.LEGACY_HOST, store.home.first())
        }

    @Test
    fun aFreshInstallPicksByHashAndKeepsItsPick() =
        runTest {
            val store = HomeStore(dataStore())
            store.keepLegacyHome()
            assertNull(store.home.first())
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5)), at = 1L)
            assertEquals(null to "public-3.spool.getknit.app", store.settle(NODE))
            // A new relay joining does not move a home.
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5), spool(4, weight = 64)), at = 2L)
            assertNull(store.settle(NODE))
            assertEquals("public-3.spool.getknit.app", store.home.first())
        }

    @Test
    fun aStoredHomeSurvivesTheLegacyCheckOnALaterStart() =
        runTest {
            // A fresh install's second start: the seed marker is set by now, and the hashed home must stay.
            val data = dataStore()
            val store = HomeStore(data)
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5)), at = 1L)
            assertEquals(null to "public-3.spool.getknit.app", store.settle(NODE))
            data.edit { it[booleanPreferencesKey(SettingsKeys.SPOOL_DEFAULTS_SEEDED)] = true }
            store.keepLegacyHome()
            assertEquals("public-3.spool.getknit.app", store.home.first())
            assertNull(store.settle(NODE))
        }

    @Test
    fun aFreshInstallWithNoDirectoryPicksFromTheShippedCopy() =
        runTest {
            val store = HomeStore(dataStore())
            assertEquals(null to PublicSpools.LEGACY_HOST, store.settle(NODE))
            assertEquals(PublicSpools.BAKED, store.directory.first())
        }

    @Test
    fun aFreshInstallFacingNoOpenRelayTakesPublicOne() =
        runTest {
            val store = HomeStore(dataStore())
            store.putDirectory(listOf(spool(2, PublicSpool.DRAINING)), at = 1L)
            assertEquals(null to PublicSpools.LEGACY_HOST, store.settle(NODE))
        }

    @Test
    fun aDrainingOrDroppedHomeMovesAndAnUnknownStateDoesNot() =
        runTest {
            val store = HomeStore(dataStore())
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5)), at = 1L)
            store.settle(NODE)
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, "resting", 5)), at = 2L)
            assertNull(store.settle(NODE))
            store.putDirectory(listOf(spool(1), spool(2, weight = 3), spool(3, PublicSpool.DRAINING, 5)), at = 3L)
            assertEquals("public-3.spool.getknit.app" to "public-2.spool.getknit.app", store.settle(NODE))
            store.putDirectory(listOf(spool(1)), at = 4L)
            assertEquals("public-2.spool.getknit.app" to PublicSpools.LEGACY_HOST, store.settle(NODE))
            // Nothing open: the home stays where it was rather than going nowhere.
            store.putDirectory(listOf(spool(1, PublicSpool.DRAINING)), at = 5L)
            assertNull(store.settle(NODE))
            assertEquals(PublicSpools.LEGACY_HOST, store.home.first())
            assertEquals(5L, store.directoryReadAt())
        }

    private companion object {
        // The vector node that picks public-3 with all three open, public-2 with public-3 draining (PublicSpoolsTest).
        const val NODE = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
