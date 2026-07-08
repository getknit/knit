package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
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
import app.getknit.knit.ui.group
import app.getknit.knit.ui.msg
import io.mockk.coEvery
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
import org.junit.runner.RunWith

/**
 * Robolectric-hosted (the state combine calls `context.getString`, incl. format args in `previewFor`, so a
 * real Context returns the actual strings). Covers the unread-count watermark math, room/group/DM assembly
 * + sort, and the own-message preview label.
 */
@RunWith(AndroidJUnit4::class)
class ChatListViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val mesh = FakeMeshController()
    private val groups = mockk<GroupRepository>(relaxed = true)

    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val lastReadFlow = MutableStateFlow(emptyMap<String, Long>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessages() } returns messagesFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { groups.observeGroups() } returns groupsFlow
        every { peers.observePeers() } returns peersFlow
        every { settings.lastReadAll } returns lastReadFlow
        every { settings.acceptedConversations } returns acceptedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ChatListViewModel(messages, peers, settings, identity, mesh, groups, context)

    @Test
    fun nearbyRoomIsAlwaysPresentEvenWithNoMessages() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            val nearby =
                vm.state.value.conversations
                    .first { it.id == Conversations.NEARBY }
            assertTrue(nearby.isRoom)
            assertEquals(context.getString(R.string.nearby_title), nearby.title)
        }

    @Test
    fun unreadCountsOnlyOthersNormalMessagesAfterTheWatermark() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "me", sentAt = 200, conversationId = Conversations.NEARBY),
                )
            lastReadFlow.value = mapOf(Conversations.NEARBY to 50L)
            advanceUntilIdle()

            val nearby =
                vm.state.value.conversations
                    .first { it.id == Conversations.NEARBY }
            // bob's (100 > 50, not us) counts; our own 200 is excluded even though it's past the watermark.
            assertEquals(1, nearby.unreadCount)
        }

    @Test
    fun blockedPeersDmThreadIsDropped() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            blockedFlow.value = setOf("b")
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .none { it.id == "b" },
            )
        }

    @Test
    fun conversationsSortMostRecentFirstAndEmptyGroupSortsByCreatedAt() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "ada", sentAt = 200, conversationId = "ada", recipientId = "me"),
                )
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdAt = 50))
            // ada + g-1 are accepted so they stay in the main list; this test asserts sort order, not the
            // request partition (covered by aStrangerDmRequestIsPartitionedOutOfTheListButCounted).
            acceptedFlow.value = setOf("ada", "g-1")
            advanceUntilIdle()

            val convos = vm.state.value.conversations
            // ada DM (200) > nearby (100) > empty group (createdAt 50, its stand-in lastMessageAt).
            assertEquals(listOf("ada", Conversations.NEARBY, "g-1"), convos.map { it.id })
            assertEquals(50L, convos.first { it.id == "g-1" }.lastMessageAt)
        }

    @Test
    fun ownMessagePreviewIsLabelledWithTheSelfName() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "me", body = "hello", sentAt = 100, conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            val nearby =
                vm.state.value.conversations
                    .first { it.id == Conversations.NEARBY }
            val expected =
                context.getString(
                    R.string.chat_list_preview_with_sender,
                    context.getString(R.string.chat_self_name),
                    "hello",
                )
            assertEquals(expected, nearby.lastPreview)
        }

    @Test
    fun aStrangerDmRequestIsPartitionedOutOfTheListButCounted() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "stranger", sentAt = 100, conversationId = "stranger", recipientId = "me"))
            advanceUntilIdle()

            // The stranger's DM is not in the main list (it's a pending request)...
            assertTrue(
                vm.state.value.conversations
                    .none { it.id == "stranger" },
            )
            // ...but it is counted for the top-bar badge.
            assertEquals(1, vm.state.value.requestCount)
        }

    @Test
    fun anAcceptedDmStaysInTheListAndIsNotCounted() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "friend", sentAt = 100, conversationId = "friend", recipientId = "me"))
            acceptedFlow.value = setOf("friend")
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .any { it.id == "friend" },
            )
            assertEquals(0, vm.state.value.requestCount)
        }
}
