@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.draft.DraftEntity
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.InMemoryMessages
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Someone's reaction to a message we wrote speaks for its row once it is the newest thing in the thread (ADR
 * 2026-10.59fu): its own line, its time and its place, and neither a tick nor an unread count. Over a real
 * in-memory database, so the reaction query's join runs as it does on a phone; its own class rather than more of
 * [ChatListViewModelTest], which is at detekt's size ceiling.
 */
@RunWith(AndroidJUnit4::class)
class ChatListReactionPreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var store: InMemoryMessages
    private lateinit var reactions: ReactionRepository
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val mesh = FakeMeshController()
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val transfers = mockk<TransferManager>(relaxed = true)

    private val peersFlow = MutableStateFlow(listOf(peer("bob", "Bob", verified = true)))
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())
    private val draftsFlow = MutableStateFlow(emptyMap<String, DraftEntity>())
    private val transfersFlow = MutableStateFlow(emptyMap<String, TransferState>())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryMessages(mainDispatcher)
        reactions = ReactionRepository(store.db.reactionDao(), store.db)
        coEvery { identity.nodeId() } returns ME
        every { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
        every { groups.observeGroups() } returns groupsFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.lastReadAll } returns MutableStateFlow(emptyMap())
        every { settings.acceptedConversations } returns acceptedFlow
        every { settings.contentFilteringEnabled } returns MutableStateFlow(true)
        every { settings.translationEnabled } returns MutableStateFlow(true)
        // A relaxed mock's Flow never emits, and one silent arm stalls the whole combine.
        every { settings.cloneSeenAt } returns MutableStateFlow(0L)
        every { settings.cloneDismissedAt } returns MutableStateFlow(0L)
        every { settings.meshPausedUntil } returns MutableStateFlow(null)
        every { settings.meshEnabled } returns MutableStateFlow(true)
        every { drafts.all } returns draftsFlow
        every { transfers.states } returns transfersFlow
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun vm() =
        ChatListViewModel(
            store.repo,
            peers,
            settings,
            identity,
            mesh,
            groups,
            drafts,
            transfers,
            MutableStateFlow(RelayFacts()),
            MutableStateFlow(LoraFacts()),
            context,
            reactions = reactions,
        )

    private fun TestScope.started(): ChatListViewModel {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private fun ChatListViewModel.row(id: String) = state.value.conversations.first { it.id == id }

    private fun ChatListViewModel.order() = state.value.conversations.map { it.id }

    private suspend fun react(
        messageId: String,
        reactor: String,
        emoji: String?,
        at: Long,
    ) = reactions.apply(ReactionEntity(messageId, reactor, emoji, at))

    /** The DM thread: Bob's message, then ours at 100; a Nearby post at 150 sits above it until Bob reacts. */
    private suspend fun seedDm() =
        store.set(
            msg(senderId = "bob", sentAt = 50, conversationId = "bob", body = "free tonight?"),
            msg(senderId = ME, sentAt = 100, conversationId = "bob", recipientId = "bob", body = "lunch?", id = LUNCH),
            msg(senderId = "carol", sentAt = 150, conversationId = Conversations.NEARBY, body = "anyone around?"),
        )

    @Test
    fun aReactionToOurMessageHeadsItsRowWithoutATickOrAnUnreadCount() =
        runTest {
            val vm = started()
            seedDm()
            advanceUntilIdle()
            assertEquals("You: lunch?", vm.row("bob").lastPreview)
            assertNotNull("our own message carries its tick", vm.row("bob").lastStatus)
            assertEquals(listOf(Conversations.NEARBY, "bob"), vm.order())

            react(LUNCH, "bob", "👍", at = 200)
            advanceUntilIdle()

            val row = vm.row("bob")
            assertEquals("Reacted 👍 to “lunch?”", row.lastPreview)
            assertEquals(200L, row.lastMessageAt)
            assertNull("Bob's reaction is not a send of ours", row.lastStatus)
            assertEquals("only Bob's message counts", 1, row.unreadCount)
            assertEquals(listOf("bob", Conversations.NEARBY), vm.order())
        }

    @Test
    fun aMessageAfterTheReactionTakesTheRowBack() =
        runTest {
            val vm = started()
            seedDm()
            react(LUNCH, "bob", "👍", at = 200)
            store.add(msg(senderId = "bob", sentAt = 300, conversationId = "bob", body = "see you there"))
            advanceUntilIdle()

            assertEquals("see you there", vm.row("bob").lastPreview)
            assertEquals(300L, vm.row("bob").lastMessageAt)
        }

    @Test
    fun aReactionStampedAtTheMessagesOwnInstantLeavesTheRowToTheMessage() =
        runTest {
            val vm = started()
            seedDm()
            react(LUNCH, "bob", "👍", at = 100)
            advanceUntilIdle()

            assertEquals("You: lunch?", vm.row("bob").lastPreview)
        }

    @Test
    fun aRetractedReactionGivesTheRowBackToTheMessage() =
        runTest {
            val vm = started()
            seedDm()
            react(LUNCH, "bob", "👍", at = 200)
            advanceUntilIdle()
            react(LUNCH, "bob", null, at = 250)
            advanceUntilIdle()

            assertEquals("You: lunch?", vm.row("bob").lastPreview)
            assertEquals(100L, vm.row("bob").lastMessageAt)
        }

    // A room: anyone reads it, so only the "someone else" rule keeps our own reaction to our own post off the row.
    @Test
    fun ourOwnReactionNeverHeadsTheRow() =
        runTest {
            val vm = started()
            store.set(msg(senderId = ME, sentAt = 100, conversationId = Conversations.NEARBY, body = "anyone around?", id = POST))
            react(POST, ME, "❤️", at = 200)
            advanceUntilIdle()

            assertEquals("You: anyone around?", vm.row(Conversations.NEARBY).lastPreview)
            assertEquals(100L, vm.row(Conversations.NEARBY).lastMessageAt)
        }

    @Test
    fun aGroupReactionNamesWhoReacted() =
        runTest {
            val vm = started()
            val hikers = group(groupId = "g-1", members = listOf(ME, "bob"), createdAt = 10)
            // The repository mock speaks for the list's groups; the reaction query reads the roster from the table.
            store.db.groupDao().upsert(hikers)
            groupsFlow.value = listOf(hikers)
            acceptedFlow.value = setOf("g-1")
            store.set(msg(senderId = ME, sentAt = 100, conversationId = "g-1", body = "trail at 9?", id = TRAIL))
            react(TRAIL, "bob", "🔥", at = 200)
            advanceUntilIdle()

            assertEquals("Bob reacted 🔥 to “trail at 9?”", vm.row("g-1").lastPreview)
            assertEquals(200L, vm.row("g-1").lastMessageAt)
        }

    @Test
    fun aDraftSpeaksOnlyWhileItIsNewerThanTheReaction() =
        runTest {
            val vm = started()
            seedDm()
            react(LUNCH, "bob", "👍", at = 200)
            draftsFlow.value = mapOf("bob" to DraftEntity("bob", "on my", updatedAt = 150))
            advanceUntilIdle()
            assertNull("the reaction came after the draft", vm.row("bob").draft)

            draftsFlow.value = mapOf("bob" to DraftEntity("bob", "on my way", updatedAt = 250))
            advanceUntilIdle()
            assertEquals("on my way", vm.row("bob").draft)
            assertEquals("a draft never moves the row", 200L, vm.row("bob").lastMessageAt)
        }

    private companion object {
        const val ME = "me"
        const val LUNCH = "m-lunch"
        const val TRAIL = "m-trail"
        const val POST = "m-post"
    }
}
