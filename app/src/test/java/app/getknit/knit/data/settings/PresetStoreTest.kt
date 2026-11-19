package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The onboarding starting point ([Preset]) and what it supplies, over a real Preferences DataStore. */
class PresetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun store(scope: CoroutineScope): DataStore<Preferences> {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun standardIsTheDefaultsEveryInstallHadBeforePresets() {
        // Pinned literally: an install or a backup with no preset must read exactly what it read before presets.
        val standard = Preset.STANDARD.defaults
        assertFalse(standard.relays)
        assertFalse(standard.linkPreviews)
        assertTrue(standard.typingIndicators)
        assertTrue(standard.readReceipts)
        assertTrue(standard.translation)
        assertTrue(standard.wifiAware)
        assertTrue(standard.callTranscripts)
        assertEquals(BluetoothDownloadLimit.MB_1, standard.bluetoothDownloadLimit)
        assertFalse(standard.secureScreens)
    }

    @Test
    fun anAbsentOrUnknownPresetReadsStandardAndStandardIsNeverOffered() {
        assertEquals(Preset.STANDARD, Preset.of(emptyPreferences()))
        assertEquals(Preset.STANDARD, Preset.of(preferencesOf(Preset.KEY to "NO_SUCH_PRESET")))
        assertEquals(Preset.PRIVATE, Preset.of(preferencesOf(Preset.KEY to "PRIVATE")))
        assertFalse(Preset.STANDARD in Preset.offered)
    }

    @Test
    fun eachGetterReadsThePresetUntilThePersonSetsItsSwitch() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val transcripts = TranscriptStore(data)
            val aware = AwareSwitch(data)
            val presets = PresetStore(data)

            presets.apply(Preset.PRIVATE, resetOverrides = true)
            assertFalse(settings.readReceiptsEnabled.first())
            assertFalse(settings.typingIndicatorsEnabled.first())
            assertFalse(settings.translationEnabled.first())
            assertTrue(settings.secureScreens.first())

            presets.apply(Preset.BATTERY, resetOverrides = true)
            assertFalse(settings.secureScreens.first())
            assertFalse(aware.on.first())
            assertFalse(transcripts.enabled.first())
            assertTrue(settings.readReceiptsEnabled.first())

            presets.apply(Preset.EVERYDAY, resetOverrides = true)
            assertEquals(BuildConfig.INTERNET_PLANE, settings.linkPreviewsEnabled.first())
            assertEquals(BluetoothDownloadLimit.MB_5, settings.bluetoothDownloadLimit.first())
            assertTrue(aware.on.first())

            // The person's own value outranks the preset, and keeps doing so when the preset changes without a reset.
            settings.setReadReceiptsEnabled(false)
            aware.set(false)
            presets.apply(Preset.BATTERY, resetOverrides = false)
            assertFalse(settings.readReceiptsEnabled.first())
            assertFalse(aware.on.first())
            presets.apply(Preset.EVERYDAY, resetOverrides = false)
            assertFalse(aware.on.first())
        }

    @Test
    fun customisedIsAnOwnValueTheCurrentPresetWouldNotGive() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val presets = PresetStore(data)
            presets.apply(Preset.PRIVATE, resetOverrides = true)
            assertFalse(presets.customised.first())

            // Writing the value the preset already gives is not a change from it.
            settings.setReadReceiptsEnabled(false)
            assertFalse(presets.customised.first())

            settings.setReadReceiptsEnabled(true)
            assertTrue(presets.customised.first())

            presets.apply(Preset.PRIVATE, resetOverrides = true)
            assertFalse(presets.customised.first())
            assertFalse(settings.readReceiptsEnabled.first())
        }

    @Test
    fun aResetForgetsEveryOwnValueItSuppliesAndNothingElse() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val presets = PresetStore(data)
            settings.setDynamicColor(true)
            settings.setTypingIndicatorsEnabled(false)
            settings.setBluetoothDownloadLimit(BluetoothDownloadLimit.NEVER)
            AwareSwitch(data).set(false)

            presets.apply(Preset.STANDARD, resetOverrides = true)
            val after = data.data.first()
            PresetSetting.all.forEach { assertNull(it.key.name, after[it.key]) }
            assertTrue("a switch no preset supplies is kept", settings.dynamicColor.first())
            assertTrue(settings.typingIndicatorsEnabled.first())
            assertEquals(BluetoothDownloadLimit.MB_1, settings.bluetoothDownloadLimit.first())
        }

    @Test
    fun relaysOnAreTheConsentAndTheSwitchInOneEditAndOffKeepsTheConsent() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val presets = PresetStore(data)
            settings.addSpoolUrl("wss://relay.example/spool/v1")

            presets.apply(Preset.EVERYDAY, resetOverrides = false)
            assertFalse("only a reset moves the relay plane", settings.spoolEnabled.first())

            presets.apply(Preset.EVERYDAY, resetOverrides = true)
            assertEquals(BuildConfig.INTERNET_PLANE, settings.spoolEnabled.first())
            assertEquals(BuildConfig.INTERNET_PLANE, settings.spoolConsented.first())

            presets.apply(Preset.PRIVATE, resetOverrides = true)
            assertFalse(settings.spoolEnabled.first())
            assertEquals("consent is about having read it once", BuildConfig.INTERNET_PLANE, settings.spoolConsented.first())
            assertEquals("the relay list is never touched", setOf("wss://relay.example/spool/v1"), settings.spoolUrls.first())
        }

    @Test
    fun relaysNotWhereThePresetHasThemReadAsCustomised() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val presets = PresetStore(data)
            presets.apply(Preset.PRIVATE, resetOverrides = true)
            assertFalse(presets.customised.first())

            // Privacy-first with relays on must never read as plain Privacy-first.
            settings.acceptSpoolConsent()
            assertEquals(BuildConfig.INTERNET_PLANE, presets.customised.first())

            // Keep my changes leaves relays on, and the row keeps saying so.
            presets.apply(Preset.BATTERY, resetOverrides = false)
            assertEquals(BuildConfig.INTERNET_PLANE, presets.customised.first())
            presets.apply(Preset.BATTERY, resetOverrides = true)
            assertFalse(presets.customised.first())
        }

    @Test
    fun theChangesAreWhatTheSwitchDoesAndWhatSharesMoreComesFirst() =
        runTest {
            val data = store(backgroundScope)
            val settings = SettingsStore(data)
            val presets = PresetStore(data)
            presets.apply(Preset.PRIVATE, resetOverrides = true)

            val reset = presets.changes(Preset.BATTERY, resetOverrides = true)
            val switches = reset.map { it.switch to it.on }
            // Leaving Privacy-first turns read receipts, typing and the language tag back on, and the screenshot block
            // off: each is named, ahead of what shares nothing.
            assertEquals(
                listOf(
                    PresetChange.Switch.READ_RECEIPTS to true,
                    PresetChange.Switch.TYPING_INDICATORS to true,
                    PresetChange.Switch.TRANSLATION to true,
                    PresetChange.Switch.SECURE_SCREENS to false,
                    PresetChange.Switch.WIFI_AWARE to false,
                    PresetChange.Switch.TRANSCRIPTS to false,
                ),
                switches,
            )
            assertTrue(reset.takeWhile { it.weakensPrivacy }.size == 4)

            // With the person's own read receipts kept, Keep my changes does not list them.
            settings.setReadReceiptsEnabled(false)
            val keep = presets.changes(Preset.BATTERY, resetOverrides = false)
            assertFalse(keep.any { it.switch == PresetChange.Switch.READ_RECEIPTS })

            // The preview changed nothing.
            assertEquals(Preset.PRIVATE, presets.preset.first())

            // And it is what apply then does.
            presets.apply(Preset.BATTERY, resetOverrides = false)
            assertFalse(settings.readReceiptsEnabled.first())
            assertTrue(settings.typingIndicatorsEnabled.first())
        }

    @Test
    fun aSwitchToEverydayNamesRelaysFirst() =
        runTest {
            val data = store(backgroundScope)
            val presets = PresetStore(data)
            presets.apply(Preset.PRIVATE, resetOverrides = true)
            val reset = presets.changes(Preset.EVERYDAY, resetOverrides = true)
            if (BuildConfig.INTERNET_PLANE) {
                assertEquals(PresetChange(PresetChange.Switch.RELAYS, on = true), reset.first())
            }
            assertFalse(presets.changes(Preset.EVERYDAY, resetOverrides = false).any { it.switch == PresetChange.Switch.RELAYS })
        }

    @Test
    fun thePresetRidesABackupWhileAwareStaysPhoneBoundAndFallsBackToIt() {
        val preset = stringPreferencesKey(SettingsKeys.SETTINGS_PRESET)
        val aware = booleanPreferencesKey(SettingsKeys.WIFI_AWARE_ENABLED)
        val transient = { name: String -> SettingsKeys.TRANSIENT_PREFIXES.any { name.startsWith(it) } }
        assertFalse(transient(preset.name))
        assertTrue(transient(aware.name))
        // What a restore of a Battery saver phone leaves: the preset, no Aware key.
        assertFalse(AwareSwitch.readOn(preferencesOf(preset to Preset.BATTERY.name)))
        assertTrue(AwareSwitch.readOn(preferencesOf(preset to Preset.EVERYDAY.name)))
    }

    @Test
    fun lowPowerModesOldAnswerOutranksThePresetUntilTheMoveIsJournaled() =
        runTest {
            val data = store(backgroundScope)
            data.edit {
                it[booleanPreferencesKey(SettingsKeys.LOW_POWER_MODE)] = true
                it[Preset.KEY] = Preset.EVERYDAY.name
            }
            val aware = AwareSwitch(data)
            assertFalse(aware.on.first())
            aware.migrateFromLowPower()
            assertFalse(aware.on.first())
        }
}
