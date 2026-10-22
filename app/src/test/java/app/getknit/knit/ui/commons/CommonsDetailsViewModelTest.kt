@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.commons

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

/** Plain JVM: the one resource the ViewModel reads, the generic room title, comes off a mocked [Context]. */
class CommonsDetailsViewModelTest {
    private val room = Conversations.commonsIdFor("ab".repeat(32))
    private val context = mockk<Context> { every { getString(R.string.commons_title) } returns "Commons" }
    private val commons = mockk<CommonsRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)

    private val roomFlow = MutableStateFlow<CommonsEntity?>(null)
    private val membersFlow = MutableStateFlow(emptyList<String>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val facts = MutableStateFlow(RelayFacts())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { commons.observe(room) } returns roomFlow
        every { commons.observeMemberIds(room) } returns membersFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(store: CommonsRepository? = commons) = CommonsDetailsViewModel(room, store, peers, mesh, facts, identity, context)

    private fun joined(name: String? = "Home") =
        CommonsEntity(
            conversationId = room,
            spoolUrl = "wss://relay.test/spool/v1?k=secret-token",
            secret = ByteArray(32),
            name = name,
            joinedAt = 1L,
        )

    @Test
    fun membersListSelfFirstThenOnlineThenAlphabeticalAndNameTheRelayByHostAlone() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("a", name = "Alice"), peer("b", name = "Bob"), peer("c", name = "Carol"))
            roomFlow.value = joined()
            // The room records whoever it has seen, ourselves included or not: we are listed once, first, either way.
            membersFlow.value = listOf("c", "me", "a", "b")
            mesh.neighbors.value = setOf(Peer("b"))
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(listOf("me", "b", "a", "c"), state.members.map { it.nodeId })
            assertTrue(state.members.first().isSelf)
            assertEquals("Home", state.title)
            assertEquals("never the relay's token", "relay.test", state.relayHost)
            assertFalse(state.stale)
            assertTrue(state.exists)
        }

    @Test
    fun aRoomItsRelayNoLongerRunsSaysSo() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            roomFlow.value = joined(name = null)
            facts.value = RelayFacts(staleRooms = setOf(room))
            advanceUntilIdle()

            assertTrue(vm.state.value.stale)
            assertEquals("the generic title when the operator named none", "Commons", vm.state.value.title)
        }

    @Test
    fun leavingRemovesTheRoomReDerivesTheScopeTableAndSaysItLeft() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            val left = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.left.collect { left += it } }
            roomFlow.value = joined()
            advanceUntilIdle()

            vm.leave()
            advanceUntilIdle()

            coVerify(exactly = 1) { commons.leave(room) }
            assertTrue(mesh.relaysRefreshed)
            assertEquals(1, left.size)
        }

    @Test
    fun aRoomGoneOrABuildWithoutTheCommonsReadsAsNotThere() =
        runTest {
            val vm = vm(store = null)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertFalse(vm.state.value.exists)
            vm.leave() // nothing to leave, and nothing to refresh
            advanceUntilIdle()
            assertFalse(mesh.relaysRefreshed)
        }
}
