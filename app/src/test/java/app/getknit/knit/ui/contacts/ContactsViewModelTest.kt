package app.getknit.knit.ui.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.ui.group
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactsViewModelTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { peers.observePeers() } returns peersFlow
        every { settings.blockedNodeIds } returns blockedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ContactsViewModel(peers, mesh, identity, settings, groups)

    @Test
    fun contactsAreKnownPeersUnionNeighborsMinusSelfAndBlocked() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.contacts.collect {} }
            peersFlow.value = listOf(peer("a", name = "Alice"), peer("b", name = "Bob"))
            mesh.neighbors.value = setOf(Peer("c"), Peer("me"))
            blockedFlow.value = setOf("b")
            advanceUntilIdle()

            val result = vm.contacts.value
            // "me" (self) and "b" (blocked) are excluded; "c" appears as a live neighbor with no cached profile.
            assertEquals(setOf("a", "c"), result.map { it.nodeId }.toSet())
            // Connected-first ordering: "c" is in the neighbor set, "a" is only a cached peer.
            assertEquals("c", result.first().nodeId)
            assertTrue(result.first { it.nodeId == "c" }.online)
            assertEquals("Alice", result.first { it.nodeId == "a" }.displayName)
        }

    @Test
    fun createGroupUpsertsAndEmitsForNewGroup() =
        runTest {
            coEvery { groups.find(any()) } returns null
            val vm = vm()
            val created = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.created.collect { created += it } }

            vm.createGroup(listOf("a", "b"))
            advanceUntilIdle()

            coVerify { groups.upsert(any()) }
            // The id is derived from the member set (self added), so the same people always resolve identically.
            assertEquals(listOf(Conversations.groupIdFor(listOf("a", "b", "me"))), created)
        }

    @Test
    fun createGroupForExistingGroupJustReopensWithoutUpsert() =
        runTest {
            coEvery { groups.find(any()) } returns group(groupId = "g-existing", members = listOf("a", "me"))
            val vm = vm()
            val created = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.created.collect { created += it } }

            vm.createGroup(listOf("a"))
            advanceUntilIdle()

            coVerify(exactly = 0) { groups.upsert(any()) }
            assertEquals(1, created.size)
        }
}
