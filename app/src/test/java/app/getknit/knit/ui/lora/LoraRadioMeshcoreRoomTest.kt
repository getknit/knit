package app.getknit.knit.ui.lora

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.notifications.Notifier
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The LoRa screen's MeshCore room switch (ADR 2026-11.gsqu's Stage 6 amendment): drawn while a MeshCore board is bound,
 * it writes `meshcoreRoomEnabled` alone and clears its own room's notifications, never the Meshtastic room's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraRadioMeshcoreRoomTest {
    private val meshcoreRoom = MutableStateFlow(true)
    private val bound = MutableStateFlow<BoardKind?>(BoardKind.MESHTASTIC)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { loraEnabled } returns MutableStateFlow(true)
            every { loraDmEnabled } returns MutableStateFlow(true)
            every { loraRoomEnabled } returns MutableStateFlow(true)
            every { meshcoreRoomEnabled } returns meshcoreRoom
            every { boundBoardKind } returns bound
            every { loraDeviceAddress } returns MutableStateFlow(null)
            every { loraChannelIndex } returns MutableStateFlow(1)
            every { loraBoardSetup } returns MutableStateFlow(null)
        }
    private val lora =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = MutableStateFlow(LoraStatus())

            override suspend fun provisionKnitChannel(
                mode: ProvisionMode,
                previous: BoardSettings?,
            ): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
        }
    private val boards =
        object : BoardDirectory {
            override fun bonded(): List<BoardRef> = emptyList()
        }
    private val notifier = mockk<Notifier>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the switch shows for a bound MeshCore board and clears its own room's notifications`() =
        runTest {
            val vm = LoraRadioViewModel(settings, lora, boards, notifier)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse("a Meshtastic board bound: no MeshCore switch", vm.state.value.meshcoreBound)

            bound.value = BoardKind.MESHCORE
            meshcoreRoom.value = false
            advanceUntilIdle()
            assertTrue(vm.state.value.meshcoreBound)
            assertFalse(vm.state.value.meshcoreRoomEnabled)
            assertTrue("the Meshtastic room's own switch is untouched", vm.state.value.roomEnabled)

            vm.onToggleMeshcoreRoom(false)
            advanceUntilIdle()
            coVerify { settings.setMeshcoreRoomEnabled(false) }
            verify { notifier.clearConversation(Conversations.MESHCORE_PUBLIC) }
            verify(exactly = 0) { notifier.clearConversation(Conversations.MESHTASTIC) }
            coVerify(exactly = 0) { settings.setLoraRoomEnabled(any()) }

            vm.onToggleMeshcoreRoom(true)
            advanceUntilIdle()
            coVerify { settings.setMeshcoreRoomEnabled(true) }
            verify(exactly = 1) { notifier.clearConversation(Conversations.MESHCORE_PUBLIC) }
        }
}
