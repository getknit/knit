package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The Settings › Wi-Fi Aware switch (GitHub #44) over a real Preferences DataStore, as [LowPowerSwitchTest] runs it. */
class AwareSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private val lowPower = booleanPreferencesKey(SettingsKeys.LOW_POWER_MODE)
    private val enabled = booleanPreferencesKey(SettingsKeys.WIFI_AWARE_ENABLED)
    private val migrated = booleanPreferencesKey(SettingsKeys.WIFI_AWARE_MIGRATED)

    private fun store(scope: CoroutineScope): DataStore<Preferences> {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun onByDefaultAndTheSwitchWritesOnlyItsOwnKeys() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val switch = AwareSwitch(data)
            settings.setDynamicColor(true)
            val before = data.data.first().asMap()
            assertTrue(switch.on.first())

            switch.set(false)
            assertFalse(switch.on.first())
            val after = data.data.first().asMap()
            assertEquals(before, after - enabled - migrated)
            assertEquals(false, after[enabled])

            switch.set(true)
            assertTrue(switch.on.first())
        }

    @Test
    fun aPhoneThatHadLowPowerModeOnKeepsAwareOffThroughTheMoveAndAfter() =
        runTest {
            val data = store(backgroundScope)
            data.edit { it[lowPower] = true }
            val switch = AwareSwitch(data)
            // Before the move is journaled the switch already reads off, so the plane never attaches only to stop.
            assertFalse(switch.on.first())

            switch.migrateFromLowPower()
            val after = data.data.first()
            assertEquals(false, after[enabled])
            assertEquals(true, after[migrated])
            assertEquals("the mode's own key is left as it was", true, after[lowPower])
            assertFalse(switch.on.first())

            // Journaled: once moved, low-power mode's key is never read here again — the move's own answer stands.
            data.edit { it[lowPower] = false }
            assertFalse(switch.on.first())
        }

    @Test
    fun onceJournaledALaterLowPowerKeyIsNotRead() =
        runTest {
            // The journal without a stored switch (a move that found low-power mode off): low_power_mode set afterwards
            // must not read as Aware off, nor must a second move write it.
            val data = store(backgroundScope)
            data.edit { it[migrated] = true }
            val switch = AwareSwitch(data)
            data.edit { it[lowPower] = true }
            assertTrue(switch.on.first())
            switch.migrateFromLowPower()
            assertEquals(null, data.data.first()[enabled])
            assertTrue(switch.on.first())
        }

    @Test
    fun theMoveRunsOnceAndAPhoneWithoutLowPowerModeStaysOn() =
        runTest {
            val data = store(backgroundScope)
            val switch = AwareSwitch(data)
            switch.migrateFromLowPower()
            assertTrue(switch.on.first())
            assertEquals(true, data.data.first()[migrated])
            // A later low-power key (the bridge's LOWPOWER can still write one) moves nothing, and a second run is a no-op.
            data.edit { it[lowPower] = true }
            switch.migrateFromLowPower()
            assertTrue(switch.on.first())
        }

    @Test
    fun thePersonsChoiceBeforeTheMoveWins() =
        runTest {
            val data = store(backgroundScope)
            data.edit { it[lowPower] = true }
            val switch = AwareSwitch(data)
            switch.set(true)
            switch.migrateFromLowPower()
            assertTrue(switch.on.first())
        }
}
