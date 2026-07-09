package app.getknit.knit.ui.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.ui.group
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactsViewModelTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val messages = mockk<MessageRepository>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { peers.observePeers() } returns peersFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.acceptedConversations } returns acceptedFlow
        every { messages.observeMessages() } returns messagesFlow
        every { groups.observeGroups() } returns groupsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ContactsViewModel(peers, mesh, identity, settings, groups, messages)

    /** Collects [ContactsViewModel.contacts] on the background scope so the flow stays hot for assertions. */
    private fun TestScope.startCollecting(vm: ContactsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.contacts.collect {} }
    }

    @Test
    fun verifiedPeerAppearsButAPlainNearbyStrangerDoesNot() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            // "val" is verified (a QR-verified contact, possibly never chatted); "stranger" is only a cached
            // Nearby profile — no DM/group/verify — so it must not be composable.
            peersFlow.value = listOf(peer("val", name = "Val", verified = true), peer("stranger", name = "Stranger"))
            advanceUntilIdle()

            assertEquals(
                setOf("val"),
                vm.contacts.value
                    .map { it.nodeId }
                    .toSet(),
            )
            assertEquals(
                "Val",
                vm.contacts.value
                    .single()
                    .displayName,
            )
        }

    @Test
    fun aDmIRepliedToIsAContactButAnUnansweredRequestIsNot() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            peersFlow.value = listOf(peer("pat", name = "Pat"), peer("rando", name = "Rando"))
            messagesFlow.value =
                listOf(
                    // I authored a DM to "pat" -> accepted (an engaged conversation).
                    msg(senderId = "me", conversationId = "pat", recipientId = "pat"),
                    // "rando" DM'd me and I never replied/accepted -> a pending request, excluded.
                    msg(senderId = "rando", conversationId = "rando", recipientId = "me"),
                )
            advanceUntilIdle()

            assertEquals(
                setOf("pat"),
                vm.contacts.value
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun acceptingARequestMakesItsPeerAContact() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            peersFlow.value = listOf(peer("rando", name = "Rando"))
            messagesFlow.value = listOf(msg(senderId = "rando", conversationId = "rando", recipientId = "me"))
            acceptedFlow.value = setOf("rando")
            advanceUntilIdle()

            assertEquals(
                setOf("rando"),
                vm.contacts.value
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun groupCoMembersAreContactsExceptSelfAndLeftGroups() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            groupsFlow.value =
                listOf(
                    group(groupId = Conversations.groupIdFor(listOf("me", "amy", "bob")), members = listOf("me", "amy", "bob")),
                    // A left group's members must not leak into the picker.
                    group(groupId = "g-left", members = listOf("me", "gone"), left = true),
                )
            advanceUntilIdle()

            assertEquals(
                setOf("amy", "bob"),
                vm.contacts.value
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun blockedContactsAreExcluded() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            peersFlow.value = listOf(peer("val", name = "Val", verified = true))
            blockedFlow.value = setOf("val")
            advanceUntilIdle()

            assertTrue(vm.contacts.value.isEmpty())
        }

    @Test
    fun onlineContactsSortFirstThenByName() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            groupsFlow.value =
                listOf(group(groupId = "g-hike", members = listOf("me", "zoe", "amy")))
            peersFlow.value = listOf(peer("zoe", name = "Zoe"), peer("amy", name = "Amy"))
            mesh.neighbors.value = setOf(Peer("zoe"))
            advanceUntilIdle()

            val result = vm.contacts.value
            // "zoe" is online so it sorts ahead of "amy" despite the later name.
            assertEquals(listOf("zoe", "amy"), result.map { it.nodeId })
            assertTrue(result.first { it.nodeId == "zoe" }.online)
            assertFalse(result.first { it.nodeId == "amy" }.online)
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
