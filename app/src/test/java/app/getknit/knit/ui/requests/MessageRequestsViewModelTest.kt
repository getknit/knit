package app.getknit.knit.ui.requests

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.InMemoryMessages
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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
import org.junit.runner.RunWith

/**
 * Robolectric-hosted (the request list calls `context.getString` for group titles + previews). Covers the
 * request/accepted partition (mirroring the notify gate's [app.getknit.knit.data.message.Conversations.isAccepted])
 * for both DMs and groups, and the Accept / Block / Delete actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessageRequestsViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var store: InMemoryMessages
    private val messages get() = store.repo
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val acceptedFlow = MutableStateFlow(emptySet<String>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryMessages(mainDispatcher)
        coEvery { identity.nodeId() } returns "me"
        every { settings.acceptedConversations } returns acceptedFlow
        every { settings.contentFilteringEnabled } returns MutableStateFlow(true)
        every { settings.blockedNodeIds } returns blockedFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { groups.observeGroups() } returns groupsFlow
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun vm() = MessageRequestsViewModel(messages, settings, peers, groups, drafts, identity, context)

    @Test
    fun aStrangerDmIsAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me", body = "hi there"))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals("b", row.conversationId)
            assertFalse(row.isGroup)
            assertEquals("hi there", row.lastPreview)
        }

    @Test
    fun aStrangerGroupInviteIsAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), name = "Hikers"))
            store.set(msg(senderId = "x", sentAt = 100, conversationId = "g-1", body = "welcome"))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals("g-1", row.conversationId)
            assertTrue(row.isGroup)
            assertEquals("Hikers", row.title)
        }

    @Test
    fun aStrangerGroupRequestCarriesTheOtherMembersAsFaces() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            // Two strangers the peer table has never named: the faces still come, labelled by alias, no photos.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "river7x2", "noah9q1"), name = "Ridge Run"))
            store.set(msg(senderId = "river7x2", sentAt = 100, conversationId = "g-1", body = "join us"))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals(listOf("noah9q1", "river7x2"), row.faces.map { it.nodeId })
            assertTrue(row.faces.all { it.avatarHash == null && it.name.isNotBlank() })
        }

    // The chat list's badge counts an active stranger's group with no messages, so the inbox must list it.
    @Test
    fun aStrangerGroupWithNoMessagesYetIsAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), name = "Hikers", createdBy = "x", createdAt = 50))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals("g-1", row.conversationId)
            assertNull(row.lastPreview)
            assertEquals(50L, row.lastMessageAt)
        }

    @Test
    fun aGroupAKnownPeerHasPostedInIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            // Verified peer "b" speaks in the group — a known sender, so it isn't a cold request.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), name = "Hikers"))
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "g-1", body = "welcome"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aGroupWhereAKnownPeerIsAMemberButOnlyAStrangerHasPostedIsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            // Verified peer "b" is in the roster but hasn't spoken; only stranger "x" has. Membership alone
            // doesn't bypass the inbox — the sender must be known.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), name = "Hikers"))
            store.set(msg(senderId = "x", sentAt = 100, conversationId = "g-1", body = "welcome"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertEquals(
                "g-1",
                vm.requests.value
                    .single()
                    .conversationId,
            )
        }

    @Test
    fun anExplicitlyAcceptedConversationIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            acceptedFlow.value = setOf("b")
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aVerifiedPeerIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aThreadWeHaveRepliedInIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(
                msg(senderId = "c", sentAt = 100, conversationId = "c", recipientId = "me"),
                msg(senderId = "me", sentAt = 200, conversationId = "c", recipientId = "c"),
            )
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aBlockedSenderIsNotShownAsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            blockedFlow.value = setOf("b")
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun theNearbyRoomIsNeverARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            store.set(msg(senderId = "b", sentAt = 100))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aLeftGroupIsNotShownAsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), left = true))
            store.set(msg(senderId = "x", sentAt = 100, conversationId = "g-1"))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun acceptPersistsTheConversationToTheAcceptedSet() =
        runTest {
            val vm = vm()
            vm.accept("b")
            advanceUntilIdle()

            coVerify { settings.accept("b") }
        }

    /**
     * The screen navigates on this emission, which tears down this ViewModel (and its scope) — so the
     * accept must be persisted *before* it fires, or the write would be cancelled mid-flight.
     */
    @Test
    fun acceptEmitsTheConversationIdOnlyAfterTheWritePersists() =
        runTest {
            val persisted = CompletableDeferred<Unit>()
            coEvery { settings.accept("b") } coAnswers {
                persisted.await()
                emptyPreferences() // SettingsStore.accept returns the edited Preferences
            }
            val vm = vm()
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.accepted.toList(opened) }

            vm.accept("b")
            advanceUntilIdle()
            assertTrue("accepted fired before the write persisted", opened.isEmpty())

            persisted.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("b"), opened)
        }

    @Test
    fun blockPassesTheCachedDeviceTagForTheDmPeer() =
        runTest {
            coEvery { peers.find("b") } returns PeerEntity(nodeId = "b", deviceTag = "tag-b")
            val vm = vm()

            vm.block("b")
            advanceUntilIdle()

            coVerify { settings.block("b", "tag-b") }
        }

    @Test
    fun deleteClearsADmThreadButHardDeletesAGroup() =
        runTest {
            val vm = vm()
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))

            vm.delete("b") // a DM (bare node id)
            vm.delete("g-1") // a group
            advanceUntilIdle()

            assertNull(messages.observeMessage("b#b#100").first())
            coVerify { groups.delete("g-1") }
        }
}
