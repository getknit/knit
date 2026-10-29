@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.settings

import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.data.settings.SettingsStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings › Chats: each switch mirrors its store flow and writes straight through on a toggle. */
class ChatSettingsViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)

    private val filteringFlow = MutableStateFlow(true)
    private val linkPreviewsFlow = MutableStateFlow(false)
    private val typingIndicatorsFlow = MutableStateFlow(true)
    private val downloadLimitFlow = MutableStateFlow(BluetoothDownloadLimit.DEFAULT)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { settings.linkPreviewsEnabled } returns linkPreviewsFlow
        every { settings.typingIndicatorsEnabled } returns typingIndicatorsFlow
        every { settings.bluetoothDownloadLimit } returns downloadLimitFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ChatSettingsViewModel(settings)

    @Test
    fun contentFilteringMirrorsTheStoreAndPersistsOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.contentFilteringEnabled.collect {} }
            advanceUntilIdle()
            assertTrue(vm.contentFilteringEnabled.value)
            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(vm.contentFilteringEnabled.value)

            vm.setContentFilteringEnabled(true)
            advanceUntilIdle()
            coVerify { settings.setContentFilteringEnabled(true) }
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

    /** The large-download limit (ADR 2026-10.js2p) follows the store, and a pick writes straight through. */
    @Test
    fun theDownloadLimitMirrorsTheStoreAndPersistsOnSelect() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.bluetoothDownloadLimit.collect {} }
            advanceUntilIdle()
            assertEquals(BluetoothDownloadLimit.DEFAULT, vm.bluetoothDownloadLimit.value)
            downloadLimitFlow.value = BluetoothDownloadLimit.MB_5
            advanceUntilIdle()
            assertEquals(BluetoothDownloadLimit.MB_5, vm.bluetoothDownloadLimit.value)

            vm.setBluetoothDownloadLimit(BluetoothDownloadLimit.NEVER)
            advanceUntilIdle()
            coVerify { settings.setBluetoothDownloadLimit(BluetoothDownloadLimit.NEVER) }
        }
}
