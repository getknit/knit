@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.relay

import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
 * The relays screen's direct links switch (ADR 2026-10.a5yj): it reads this phone's stored choice only in a build with
 * links, and writes straight through either way, with no sheet of its own.
 */
class InternetRelayDirectLinksTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val relayStatus = mockk<RelayStatusRepository>(relaxed = true)
    private val stored = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.spoolEnabled } returns flowOf(true)
        every { settings.spoolUrls } returns flowOf(emptySet())
        every { settings.disabledSpoolUrls } returns flowOf(emptySet())
        every { settings.directLinksEnabled } returns stored
        every { relayStatus.statuses } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(directLinks: Boolean) = InternetRelayViewModel(settings, relayStatus, directLinks = directLinks)

    private fun TestScope.state(vm: InternetRelayViewModel): InternetRelayUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        advanceUntilIdle()
        return vm.state.value
    }

    @Test
    fun aBuildWithoutLinksDrawsNoSwitchWhateverIsStored() =
        runTest {
            stored.value = true
            val state = state(vm(directLinks = false))
            // Read from the stores, not the "not read yet" initial state, whose null would pass too.
            assertTrue(state.loaded)
            assertNull(state.directLinks)
        }

    @Test
    fun aBuildWithLinksFollowsTheStoredChoice() =
        runTest {
            val vm = vm(directLinks = true)
            assertEquals(false, state(vm).directLinks)

            stored.value = true
            advanceUntilIdle()
            assertEquals(true, vm.state.value.directLinks)
        }

    @Test
    fun theSwitchWritesStraightThroughEitherWay() =
        runTest {
            // No sheet, unlike the relays' own switch: the row's line is the disclosure.
            val vm = vm(directLinks = true)

            vm.setDirectLinks(true)
            advanceUntilIdle()
            coVerify { settings.setDirectLinksEnabled(true) }

            vm.setDirectLinks(false)
            advanceUntilIdle()
            coVerify { settings.setDirectLinksEnabled(false) }
            assertFalse(vm.showConsent.value)
        }
}
