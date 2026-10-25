@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain are experimental kotlinx APIs

package app.getknit.knit.ui.devview

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.fieldlog.BtDownReason
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.ui.directoryOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/**
 * The event console's screen state (ADR 2026-10.s7wg): newest first, each peer by the reader's label, filtered by
 * plane; paused, it holds what it showed while the console records on; cleared, it empties the console and goes live.
 */
class EventConsoleViewModelTest {
    private val console = EventConsole().apply { setActive(true) }
    private val directory = MutableStateFlow(directoryOf(listOf(PeerEntity(nodeId = PEER, name = "Kai", pubKey = "", updatedAt = 1L))))
    private val peers = mockk<PeerRepository> { every { observeDirectory() } returns directory }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(): EventConsoleViewModel {
        val vm = EventConsoleViewModel(console, peers, zone = { ZoneOffset.UTC })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun theRowsReadNewestFirstWithEachPeerByTheReadersLabel() =
        runTest {
            val vm = vm()
            console.saw(1_700_000_000_000L, FieldEvent.MeshOn(34))
            console.saw(1_700_000_001_000L, FieldEvent.BtDown(PEER, BtDownReason.EOF, lifetimeMs = 5_000, links = 0))
            runCurrent()

            assertEquals(
                listOf(
                    ConsoleRow("22:13:21.000", "bt_down", ConsolePlane.Bluetooth, "peer=Kai (hzwyqdbj) reason=EOF life=5000 links=0"),
                    ConsoleRow("22:13:20.000", "mesh_on", ConsolePlane.Mesh, "vc=34"),
                ),
                vm.state.value.rows,
            )
        }

    @Test
    fun aPlaneShowsOnlyItsOwnEventsAndAllShowsThemAgain() =
        runTest {
            val vm = vm()
            console.saw(1L, FieldEvent.MeshOn(34))
            console.saw(2L, FieldEvent.BtDown(PEER, BtDownReason.EOF, lifetimeMs = 5_000, links = 0))

            vm.showPlane(ConsolePlane.Bluetooth)
            runCurrent()
            assertEquals(
                listOf("bt_down"),
                vm.state.value.rows
                    .map { it.kind },
            )
            assertEquals("the count is the console's, before the filter", 2, vm.state.value.held)

            vm.showPlane(null)
            runCurrent()
            assertEquals(
                listOf("bt_down", "mesh_on"),
                vm.state.value.rows
                    .map { it.kind },
            )
        }

    @Test
    fun pausedTheScreenHoldsWhatItShowedWhileTheConsoleRecordsOn() =
        runTest {
            val vm = vm()
            console.saw(1L, FieldEvent.MeshOn(34))
            runCurrent()

            vm.togglePause()
            console.saw(2L, FieldEvent.MeshOff)
            runCurrent()
            assertEquals(true, vm.state.value.paused)
            assertEquals(
                listOf("mesh_on"),
                vm.state.value.rows
                    .map { it.kind },
            )
            assertEquals("the console itself recorded on", 2, console.snapshot().size)

            vm.togglePause()
            runCurrent()
            assertEquals(
                listOf("mesh_off", "mesh_on"),
                vm.state.value.rows
                    .map { it.kind },
            )
        }

    @Test
    fun clearingEmptiesTheConsoleAndAPausedScreenGoesLive() =
        runTest {
            val vm = vm()
            console.saw(1L, FieldEvent.MeshOn(34))
            vm.togglePause()
            runCurrent()

            vm.clear()
            runCurrent()

            assertEquals(false, vm.state.value.paused)
            assertEquals(emptyList<ConsoleRow>(), vm.state.value.rows)
            assertEquals(emptyList<ConsoleEvent>(), console.snapshot())
        }

    private companion object {
        const val PEER = "hzwyqdbj74hu6gkurmcyxomaca"
    }
}
