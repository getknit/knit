@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

class ContactsViewModelTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val messages = mockk<MessageRepository>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val conversationsFlow = MutableStateFlow(emptyList<String>())
    private val authoredFlow = MutableStateFlow(emptyList<String>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())
    private val groupSendersFlow = MutableStateFlow(emptyMap<String, Set<String>>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.acceptedConversations } returns acceptedFlow
        every { messages.observeConversations(any()) } returns conversationsFlow
        every { messages.observeConversationsIAuthoredIn(any()) } returns authoredFlow
        every { groups.observeGroups() } returns groupsFlow
        every { messages.observeGroupSenders(any()) } returns groupSendersFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ContactsViewModel(peers, mesh, identity, settings, groups, messages)

    /** Collects [ContactsViewModel.state] on the background scope so the flow stays hot for assertions. */
    private fun TestScope.startCollecting(vm: ContactsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    /**
     * The picker must be able to tell "still resolving" from "you have nobody": the screen draws its
     * "no contacts yet" empty state on the latter, and flashing it over the chat-list → picker transition
     * was what made that transition read as jagged.
     */
    @Test
    fun stateIsLoadingUntilTheFlowsFirstEmitThenEmptyIsEmptyNotLoading() =
        runTest {
            val vm = vm()
            assertTrue(vm.state.value.isLoading)
            startCollecting(vm)
            advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertTrue(
                vm.state.value.contacts
                    .isEmpty(),
            )
        }

    /** Our own node id resolves asynchronously; the gap before it lands is loading, not an empty list. */
    @Test
    fun stateStaysLoadingWhileOurOwnNodeIdIsStillResolving() =
        runTest {
            val gate = CompletableDeferred<String>()
            coEvery { identity.nodeId() } coAnswers { gate.await() }
            val vm = vm()
            startCollecting(vm)
            peersFlow.value = listOf(peer("val", name = "Val", verified = true))
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoading)

            gate.complete("me")
            advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertEquals(
                listOf("val"),
                vm.state.value.contacts
                    .map { it.nodeId },
            )
        }

    /** A contact imported from a link is accepted before any message exists; the picker must list it. */
    @Test
    fun anAcceptedPeerWithNoMessagesYetIsAContact() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            peersFlow.value = listOf(peer("linked", name = "Linked"))
            acceptedFlow.value = setOf("linked", "g-00112233445566778899aabb")
            advanceUntilIdle()
            assertEquals(
                listOf("linked"),
                vm.state.value.contacts
                    .map { it.nodeId },
            )
            blockedFlow.value = setOf("linked")
            advanceUntilIdle()
            assertEquals(
                emptyList<String>(),
                vm.state.value.contacts
                    .map { it.nodeId },
            )
        }

    /** Two contacts who both call themselves Alice are told apart by their alias (ADR 058). */
    @Test
    fun sameNamedContactsAreLabelledWithTheirAlias() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            peersFlow.value =
                listOf(
                    peer("a1", name = "Alice", verified = true),
                    peer("a2", name = "alice", verified = true),
                    peer("bob", name = "Bob", verified = true),
                )
            advanceUntilIdle()

            val byId =
                vm.state.value.contacts
                    .associateBy { it.nodeId }
            assertEquals("Alice (${Alias.aliasFor("a1")})", byId.getValue("a1").displayName)
            assertEquals(Alias.aliasFor("a1"), byId.getValue("a1").discriminator)
            assertEquals("alice (${Alias.aliasFor("a2")})", byId.getValue("a2").displayName)
            assertEquals("Bob", byId.getValue("bob").displayName)
            assertNull(byId.getValue("bob").discriminator)
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
                vm.state.value.contacts
                    .map { it.nodeId }
                    .toSet(),
            )
            assertEquals(
                "Val",
                vm.state.value.contacts
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
            // Two DM threads exist; I have written in "pat" (an engaged conversation) and never in "rando",
            // who DM'd me and was never replied to or accepted -> a pending request, excluded.
            conversationsFlow.value = listOf("pat", "rando")
            authoredFlow.value = listOf("pat")
            advanceUntilIdle()

            assertEquals(
                setOf("pat"),
                vm.state.value.contacts
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
            conversationsFlow.value = listOf("rando")
            acceptedFlow.value = setOf("rando")
            advanceUntilIdle()

            assertEquals(
                setOf("rando"),
                vm.state.value.contacts
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun groupCoMembersAreContactsExceptSelfAndLeftGroups() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            val ours = Conversations.groupIdFor(listOf("me", "amy", "bob"))
            groupsFlow.value =
                listOf(
                    group(groupId = ours, members = listOf("me", "amy", "bob")),
                    // A left group's members must not leak into the picker.
                    group(groupId = "g-left", members = listOf("me", "gone"), left = true),
                )
            // We created (or posted in) both, so neither is a request; only `left` tells them apart.
            authoredFlow.value = listOf(ours, "g-left")
            advanceUntilIdle()

            assertEquals(
                setOf("amy", "bob"),
                vm.state.value.contacts
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    /**
     * #82: a stranger's group invitation we never answered is a message request on the chat list, so its
     * members are not contacts either — until the group is accepted the chat list's way, here by a known
     * peer posting in it (the senders come from the messages table, like the chat list's).
     */
    @Test
    fun aRequestGroupsMembersAreNotContactsUntilAKnownPeerSpeaksInIt() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            groupsFlow.value = listOf(group(groupId = "g-ridge", members = listOf("river", "me", "val")))
            groupSendersFlow.value = mapOf("g-ridge" to setOf("river"))
            peersFlow.value = listOf(peer("val", name = "Val", verified = true))
            advanceUntilIdle()
            assertEquals(
                listOf("val"),
                vm.state.value.contacts
                    .map { it.nodeId },
            )

            groupSendersFlow.value = mapOf("g-ridge" to setOf("river", "val"))
            advanceUntilIdle()
            assertEquals(
                setOf("river", "val"),
                vm.state.value.contacts
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

            assertTrue(
                vm.state.value.contacts
                    .isEmpty(),
            )
        }

    @Test
    fun onlineContactsSortFirstThenByName() =
        runTest {
            val vm = vm()
            startCollecting(vm)
            groupsFlow.value =
                listOf(group(groupId = "g-hike", members = listOf("me", "zoe", "amy")))
            authoredFlow.value = listOf("g-hike")
            peersFlow.value = listOf(peer("zoe", name = "Zoe"), peer("amy", name = "Amy"))
            mesh.neighbors.value = setOf(Peer("zoe"))
            advanceUntilIdle()

            val result = vm.state.value.contacts
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
            // …and the Internet plane is nudged to mint the group's spool root now: we are the creator, so
            // spec §3.2 makes us the preferred minter, and without this the thread reads "Not covered by
            // relays yet" until the next heal (heartbeat / motion / foreground resume).
            assertEquals(1, mesh.mintGroupRootsCount)
            // The creator never receives a frame about their own group, so without this write they would be
            // the one member who never sees the "created this group" line every other member gets on first
            // sight. Both writers mint the same deterministic id, so the two can never double up.
            val groupId = Conversations.groupIdFor(listOf("a", "b", "me"))
            val notice = slot<MessageEntity>()
            coVerify { messages.save(capture(notice)) }
            assertEquals(MessageEntity.KIND_GROUP_CREATED, notice.captured.kind)
            assertEquals("created:$groupId", notice.captured.id)
            assertEquals("me", notice.captured.senderId)
            assertEquals(groupId, notice.captured.conversationId)
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
            // Nothing was created, so there is no new root to mint — reopening must not nudge the plane,
            // and must not post a second "created this group" line into a thread that already has one.
            assertEquals(0, mesh.mintGroupRootsCount)
            coVerify(exactly = 0) { messages.save(any()) }
        }
}
