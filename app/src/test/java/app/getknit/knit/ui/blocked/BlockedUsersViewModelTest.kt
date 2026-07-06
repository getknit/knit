package app.getknit.knit.ui.blocked

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BlockedUsersViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)

    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.blockedNodeIds } returns blockedFlow
        every { peers.observePeers() } returns peersFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blockedListJoinsCachedNamesSortedAlphabetically() =
        runTest {
            val vm = BlockedUsersViewModel(settings, peers)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.blocked.collect {} }
            blockedFlow.value = setOf("a", "b")
            peersFlow.value = listOf(peer("a", name = "Zed"), peer("b", name = "Amy"))
            advanceUntilIdle()

            // Sorted by display name (case-insensitive): Amy (b) before Zed (a).
            assertEquals(listOf("Amy", "Zed"), vm.blocked.value.map { it.displayName })
            assertEquals(listOf("b", "a"), vm.blocked.value.map { it.nodeId })
        }

    @Test
    fun unblockPassesTheCachedDeviceTagSoTheBlockDoesNotStickByTag() =
        runTest {
            coEvery { peers.find("a") } returns PeerEntity(nodeId = "a", deviceTag = "tag-a")
            val vm = BlockedUsersViewModel(settings, peers)

            vm.unblock("a")
            advanceUntilIdle()

            coVerify { settings.unblock("a", "tag-a") }
        }
}
