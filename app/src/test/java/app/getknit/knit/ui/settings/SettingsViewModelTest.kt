@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.settings

import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.power.LowPowerPosture
import app.getknit.knit.ui.theme.ThemeMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app-settings half of what used to be one ViewModel: the three switches, the two plane summaries, and
 * the header row that stands in for the profile editor behind it.
 */
class SettingsViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val nameFlow = MutableStateFlow("Alice")
    private val avatarHashFlow = MutableStateFlow<String?>(null)
    private val filteringFlow = MutableStateFlow(true)
    private val linkPreviewsFlow = MutableStateFlow(false)
    private val typingIndicatorsFlow = MutableStateFlow(true)
    private val relayAllCallsFlow = MutableStateFlow(false)
    private val ownIceFlow = MutableStateFlow(OwnIceServers())
    private val dynamicColorFlow = MutableStateFlow(false)
    private val themeModeFlow = MutableStateFlow(ThemeMode.System)
    private val loraEnabledFlow = MutableStateFlow(false)
    private val loraDeviceNameFlow = MutableStateFlow<String?>(null)

    private val relayFacts = MutableStateFlow(RelayFacts())
    private val loraFacts = MutableStateFlow(LoraFacts())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns NODE_ID
        every { settings.displayName } returns nameFlow
        every { settings.ownAvatarHash } returns avatarHashFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { settings.linkPreviewsEnabled } returns linkPreviewsFlow
        every { settings.typingIndicatorsEnabled } returns typingIndicatorsFlow
        every { settings.callsRelayAll } returns relayAllCallsFlow
        every { settings.dynamicColor } returns dynamicColorFlow
        every { settings.themeMode } returns themeModeFlow
        every { settings.loraEnabled } returns loraEnabledFlow
        every { settings.loraDeviceName } returns loraDeviceNameFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() =
        SettingsViewModel(settings, identity, relayFacts, loraFacts, ownIceFlow, mockk(relaxed = true), flowOf(LowPowerPosture.OFF))

    /**
     * The load-bearing one. This ViewModel outlives a trip to the profile editor — Save there pops straight
     * back onto this row — so the header must track the store rather than read it once the way the editor's
     * own fields deliberately do. A one-shot read would leave the old name sitting here forever, and nothing
     * else in the build would notice.
     */
    @Test
    fun theHeaderFollowsALaterNameChange() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.header.collect {} }
            advanceUntilIdle()
            assertEquals("Alice", vm.header.value.name)

            nameFlow.value = "Bob"
            advanceUntilIdle()
            assertEquals("Bob", vm.header.value.name)
        }

    /** With a name set, the alias sits under it — it is what tells two same-named people apart (ADR 058). */
    @Test
    fun theHeaderCarriesTheAliasBesideAStoredName() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.header.collect {} }
            advanceUntilIdle()
            assertEquals(Alias.aliasFor(NODE_ID), vm.header.value.alias)
        }

    /** With no name stored the name *is* the alias, so the separate alias line drops rather than repeat it. */
    @Test
    fun theHeaderFallsBackToTheAliasAndDropsTheAliasLine() =
        runTest {
            nameFlow.value = ""
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.header.collect {} }
            advanceUntilIdle()
            assertEquals(Alias.aliasFor(NODE_ID), vm.header.value.name)
            assertNull(vm.header.value.alias)
        }

    @Test
    fun contentFilteringMirrorsTheStoreAndPersistsOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.contentFilteringEnabled.collect {} }
            advanceUntilIdle()
            assertTrue(vm.contentFilteringEnabled.value)

            vm.setContentFilteringEnabled(false)
            advanceUntilIdle()
            coVerify { settings.setContentFilteringEnabled(false) }
        }

    /** Off until the user says otherwise, and a toggle writes straight through to the store. */
    @Test
    fun linkPreviewsMirrorTheStoreAndPersistOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.linkPreviewsEnabled.collect {} }
            advanceUntilIdle()
            assertFalse(vm.linkPreviewsEnabled.value)
            linkPreviewsFlow.value = true
            advanceUntilIdle()
            assertTrue(vm.linkPreviewsEnabled.value)

            vm.setLinkPreviewsEnabled(false)
            advanceUntilIdle()
            coVerify { settings.setLinkPreviewsEnabled(false) }
        }

    /** On until the user says otherwise, and a toggle writes straight through to the store. */
    @Test
    fun typingIndicatorsMirrorTheStoreAndPersistOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.typingIndicatorsEnabled.collect {} }
            advanceUntilIdle()
            assertTrue(vm.typingIndicatorsEnabled.value)
            typingIndicatorsFlow.value = false
            advanceUntilIdle()
            assertFalse(vm.typingIndicatorsEnabled.value)

            vm.setTypingIndicatorsEnabled(true)
            advanceUntilIdle()
            coVerify { settings.setTypingIndicatorsEnabled(true) }
        }

    /** The Calls row's summary (§3.14): relay-all, and only the own servers a call would use — on and valid. */
    @Test
    fun theCallsSummaryFollowsTheStore() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.callsSummary.collect {} }
            advanceUntilIdle()
            assertEquals(CallsSummary(), vm.callsSummary.value)

            relayAllCallsFlow.value = true
            // On but unsaved: a call still uses the relays' TURN, so the row must not claim an own one.
            ownIceFlow.value = OwnIceServers(stunOn = true, stunUrls = listOf("stun:stun.example.org"), turnOn = true)
            advanceUntilIdle()
            assertEquals(CallsSummary(relayAll = true, ownStun = true, ownTurn = false), vm.callsSummary.value)
        }

    @Test
    fun dynamicColorMirrorsTheStoreAndPersistsOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.dynamicColor.collect {} }
            advanceUntilIdle()
            assertFalse(vm.dynamicColor.value)
            dynamicColorFlow.value = true
            advanceUntilIdle()
            assertTrue(vm.dynamicColor.value)

            vm.setDynamicColor(false)
            advanceUntilIdle()
            coVerify { settings.setDynamicColor(false) }
        }

    @Test
    fun themeModeMirrorsTheStoreAndPersistsOnSelect() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.themeMode.collect {} }
            advanceUntilIdle()
            assertEquals(ThemeMode.System, vm.themeMode.value)
            themeModeFlow.value = ThemeMode.Dark
            advanceUntilIdle()
            assertEquals(ThemeMode.Dark, vm.themeMode.value)

            vm.setThemeMode(ThemeMode.Light)
            advanceUntilIdle()
            coVerify { settings.setThemeMode(ThemeMode.Light) }
        }

    /** The relay row's subtitle is driven straight off the plane's facts. */
    @Test
    fun theRelaySummaryMapsTheFacts() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.relaySummary.collect {} }
            relayFacts.value = RelayFacts(enabled = true, configured = 3, active = 2, connected = 1)
            advanceUntilIdle()

            assertEquals(RelaySummary(enabled = true, configured = 3, active = 2, connected = 1), vm.relaySummary.value)
        }

    /** The LoRa row needs both halves: the stored settings and the live link the board is actually on. */
    @Test
    fun theLoraSummaryCombinesTheStoredSettingsWithTheLivePlane() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.loraSummary.collect {} }
            loraEnabledFlow.value = true
            loraDeviceNameFlow.value = "Meshtastic_1a2b"
            loraFacts.value = LoraFacts(plane = LoraPlane.Live)
            advanceUntilIdle()

            assertEquals(true, vm.loraSummary.value.enabled)
            assertEquals("Meshtastic_1a2b", vm.loraSummary.value.boardName)
            assertEquals(LoraPlane.Live, vm.loraSummary.value.plane)
        }

    private companion object {
        const val NODE_ID = "node-abc"
    }
}
