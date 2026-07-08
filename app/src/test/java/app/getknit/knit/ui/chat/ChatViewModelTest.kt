package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The richest ViewModel: a 5-way state combine plus the send double-submit guard and the attach room-vs-DM
 * flagged branch. Robolectric-hosted for `context.getString`. Every flow feeding the combine (including the
 * inner `blobState` combine — hashes + flagged + filtering) is stubbed, or the whole state stalls.
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val reactions = mockk<ReactionRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val notifier = mockk<Notifier>(relaxed = true)
    private val attachments = mockk<AttachmentStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val imageScreening = mockk<ImageScreeningService>(relaxed = true)
    private val gallerySaver = mockk<GallerySaver>(relaxed = true)

    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val reactionsFlow = MutableStateFlow(emptyList<ReactionEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val hashesFlow = MutableStateFlow(emptyList<String>())
    private val flaggedFlow = MutableStateFlow(emptyList<String>())
    private val filteringFlow = MutableStateFlow(true)
    private val groupFlow = MutableStateFlow<GroupEntity?>(null)
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val nameFlow = MutableStateFlow("Alice")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessages(Conversations.NEARBY) } returns messagesFlow
        every { reactions.observeReactions() } returns reactionsFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { blobs.observeHashes() } returns hashesFlow
        every { imageScreening.observeFlaggedHashes() } returns flaggedFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { groups.observeGroup(Conversations.NEARBY) } returns groupFlow
        every { peers.observePeers() } returns peersFlow
        every { settings.displayName } returns nameFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(conversationId: String = Conversations.NEARBY) =
        ChatViewModel(
            conversationId,
            messages,
            groups,
            peers,
            reactions,
            mesh,
            identity,
            settings,
            notifier,
            attachments,
            blobs,
            imageScreening,
            gallerySaver,
            context,
        )

    @Test
    fun rowsProjectMessagesAndResolveSenderNames() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", body = "hi", id = "m0", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "bob", body = "yo", id = "m1", sentAt = 200, conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()

            val rows = vm.state.value.rows
            assertEquals(2, rows.size)
            val mine = rows.first { it.id == "m0" }
            assertTrue(mine.mine)
            assertEquals("Alice", mine.senderName) // own name is the persisted display name
            val theirs = rows.first { it.id == "m1" }
            assertFalse(theirs.mine)
            assertEquals("Bob", theirs.senderName)
            assertTrue(vm.state.value.isRoom)
        }

    @Test
    fun blockedSendersRowsAreFilteredOut() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY),
                    msg(senderId = "me", id = "m2", conversationId = Conversations.NEARBY),
                )
            blockedFlow.value = setOf("bob")
            advanceUntilIdle()

            assertEquals(
                listOf("m2"),
                vm.state.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun reactionsAreTalliedPerEmojiWithTheMineFlag() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            reactionsFlow.value =
                listOf(
                    reaction("m1", "me", "👍"),
                    reaction("m1", "carol", "👍"),
                    reaction("m1", "dave", "❤️"),
                )
            advanceUntilIdle()

            val tallies =
                vm.state.value.rows
                    .single { it.id == "m1" }
                    .reactions
                    .associateBy { it.emoji }
            assertEquals(2, tallies.getValue("👍").count)
            assertTrue("we reacted with the thumbs-up", tallies.getValue("👍").mine)
            assertEquals(1, tallies.getValue("❤️").count)
            assertFalse(tallies.getValue("❤️").mine)
        }

    @Test
    fun attachmentReadinessAndFlaggingTrackTheBlobFlowsAndFilteringToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1"))
            advanceUntilIdle()
            // Not present yet → loading; not ready.
            assertFalse(
                vm.state.value.rows
                    .single()
                    .attachmentReady,
            )

            hashesFlow.value = listOf("h1")
            flaggedFlow.value = listOf("h1")
            advanceUntilIdle()
            assertTrue(
                vm.state.value.rows
                    .single()
                    .attachmentReady,
            )
            assertTrue(
                "filtering on → flagged attachment is blurred",
                vm.state.value.rows
                    .single()
                    .attachmentFlagged,
            )

            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(
                "filtering off → not blurred",
                vm.state.value.rows
                    .single()
                    .attachmentFlagged,
            )
        }

    @Test
    fun moderationFlagIsGatedOnTheContentFilteringToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "bob",
                        id = "m1",
                        conversationId = Conversations.NEARBY,
                        moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
                    ),
                )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.rows
                    .single()
                    .moderationFlagged,
            )

            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(
                vm.state.value.rows
                    .single()
                    .moderationFlagged,
            )
        }

    @Test
    fun sendGuardBlocksDoubleSubmitUntilTheInputIsCleared() =
        runTest {
            val vm = vm()

            vm.send("hi")
            vm.send("hi") // re-entrant tap while the first send holds the guard
            advanceUntilIdle()
            assertEquals(1, mesh.sentChats.size)

            vm.onInputCleared() // screen reports the field cleared → guard released
            vm.send("again")
            advanceUntilIdle()
            assertEquals(2, mesh.sentChats.size)
        }

    @Test
    fun aBlockedSendReleasesTheGuardAndEmitsTheBlockedEvent() =
        runTest {
            mesh.sendChatResult = false // moderator flags the text
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.send("bad")
            advanceUntilIdle()
            assertTrue(events.contains(R.string.moderation_text_blocked))

            // Guard was released (not held on a rejected send), so a follow-up send goes through.
            vm.send("bad-again")
            advanceUntilIdle()
            assertEquals(2, mesh.sentChats.size)
        }

    @Test
    fun attachingAFlaggedImageInTheRoomBlocksItInsteadOfStaging() =
        runTest {
            val uri = Uri.parse("content://images/1")
            val ingested = AttachmentStore.Ingested(hash = "h1", mime = "image/jpeg")
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attach(uri)
            advanceUntilIdle()

            assertNull("flagged image is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h1") }
        }

    @Test
    fun attachingACleanImageStagesItForSending() =
        runTest {
            val uri = Uri.parse("content://images/2")
            val ingested = AttachmentStore.Ingested(hash = "h2", mime = "image/jpeg")
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm()

            vm.attach(uri)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }
}
