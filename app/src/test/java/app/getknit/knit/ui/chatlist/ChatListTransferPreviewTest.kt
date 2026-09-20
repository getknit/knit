@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftEntity
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.InMemoryMessages
import app.getknit.knit.ui.directoryOf
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
 * A direct-transfer row is the one `kind` the conversation list shows: it speaks for its thread, carries the
 * row's time, and reads a different sentence in each state the transfer can be in. Its own class rather than
 * more of [ChatListViewModelTest], which is at detekt's size ceiling.
 *
 * Robolectric-hosted for the same reason that one is: the preview lines come out of `context.getString`.
 */
@RunWith(AndroidJUnit4::class)
class ChatListTransferPreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var store: InMemoryMessages
    private val messages get() = store.repo
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val mesh = FakeMeshController()
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val transfers = mockk<TransferManager>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val draftsFlow = MutableStateFlow(emptyMap<String, DraftEntity>())
    private val transfersFlow = MutableStateFlow(emptyMap<String, TransferState>())
    private val relayFlow = MutableStateFlow(RelayFacts())
    private val loraFlow = MutableStateFlow(LoraFacts())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryMessages(mainDispatcher)
        coEvery { identity.nodeId() } returns "me"
        every { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
        every { groups.observeGroups() } returns MutableStateFlow(emptyList<GroupEntity>())
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.lastReadAll } returns MutableStateFlow(emptyMap())
        every { settings.acceptedConversations } returns MutableStateFlow(emptySet())
        // The clone notice's stamps (ADR 2026-09.ypcc): a relaxed mock's Flow never emits and stalls the combine.
        every { settings.cloneSeenAt } returns MutableStateFlow(0L)
        every { settings.cloneDismissedAt } returns MutableStateFlow(0L)
        // A relaxed mock hands back a Flow that never emits, and one silent arm stalls the whole combine.
        every { drafts.all } returns draftsFlow
        every { transfers.states } returns transfersFlow
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun vm() = ChatListViewModel(messages, peers, settings, identity, mesh, groups, drafts, transfers, relayFlow, loraFlow, context)

    private fun xferRow(
        phase: TransferPhase,
        outgoing: Boolean,
        sentAt: Long = 100L,
    ) = TransferRecord(id = "t1", outgoing = outgoing, name = "clip.mp4", size = 2_000L, mime = "video/mp4", phase = phase)
        .toEntity(peerId = "bob", selfId = "me", sentAt = sentAt)

    /** Live state matching the record, so a non-terminal phase reads as running rather than interrupted. */
    private fun xferLive(
        phase: TransferPhase,
        outgoing: Boolean,
    ) = mapOf("t1" to TransferState("t1", "bob", outgoing, "clip.mp4", 2_000L, "video/mp4", phase))

    @Test
    fun everyTransferStateGetsItsOwnPreviewLine() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", "Bob", verified = true))
            val expected =
                mapOf(
                    (TransferPhase.Offered to true) to "Offered clip.mp4",
                    (TransferPhase.Offered to false) to "Wants to send you clip.mp4",
                    (TransferPhase.Connecting to true) to "Connecting to send clip.mp4",
                    (TransferPhase.Connecting to false) to "Connecting to receive clip.mp4",
                    (TransferPhase.Transferring to true) to "Sending clip.mp4",
                    (TransferPhase.Transferring to false) to "Receiving clip.mp4",
                    (TransferPhase.Done to true) to "Sent clip.mp4",
                    (TransferPhase.Done to false) to "Received clip.mp4",
                    (TransferPhase.Declined to true) to "They declined clip.mp4",
                    (TransferPhase.Declined to false) to "You declined clip.mp4",
                    (TransferPhase.Expired to true) to "No answer about clip.mp4",
                    (TransferPhase.Expired to false) to "No answer about clip.mp4",
                    (TransferPhase.Cancelled to true) to "Cancelled clip.mp4",
                    (TransferPhase.Cancelled to false) to "Cancelled clip.mp4",
                    (TransferPhase.Failed to true) to "Couldn't send clip.mp4",
                    (TransferPhase.Failed to false) to "Couldn't receive clip.mp4",
                )
            // Every phase the enum has, from both ends — a new one added without a line here fails the count.
            assertEquals(TransferPhase.entries.size * 2, expected.size)

            for ((key, line) in expected) {
                val (phase, outgoing) = key
                store.set(xferRow(phase, outgoing))
                transfersFlow.value = xferLive(phase, outgoing)
                advanceUntilIdle()
                assertEquals(
                    "$phase outgoing=$outgoing",
                    line,
                    vm.state.value.conversations
                        .first { it.id == "bob" }
                        .lastPreview,
                )
            }
        }

    /**
     * The transfer speaks for the row and carries its time — but it was never sent over the mesh, so it grows
     * no delivery tick, and the card in the thread is what wants answering, so it adds nothing to the badge.
     */
    @Test
    fun aTransferSpeaksForTheRowWithoutEarningATickOrAnUnreadCount() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", "Bob", verified = true))
            store.set(
                msg(senderId = "bob", sentAt = 50, conversationId = "bob", body = "you around?"),
                xferRow(TransferPhase.Done, outgoing = false, sentAt = 100),
            )
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .first { it.id == "bob" }
            assertEquals("Received clip.mp4", row.lastPreview)
            assertTrue("so the row draws the feature's mark beside it", row.previewIsTransfer)
            assertEquals("the row's time moves with it", 100L, row.lastMessageAt)
            assertNull(row.lastStatus)
            assertEquals("only the real message counts", 1, row.unreadCount)

            // A row whose newest thing is an ordinary message wears no mark.
            store.add(msg(senderId = "bob", sentAt = 200, conversationId = "bob", body = "thanks"))
            advanceUntilIdle()
            assertFalse(
                vm.state.value.conversations
                    .first { it.id == "bob" }
                    .previewIsTransfer,
            )
        }

    /** A record no live state stands behind is one a process death left hanging, whatever phase it froze at. */
    @Test
    fun aTransferLeftHangingByAProcessDeathReadsAsStoppedPartway() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", "Bob", verified = true))
            store.set(xferRow(TransferPhase.Transferring, outgoing = true))
            transfersFlow.value = xferLive(TransferPhase.Transferring, outgoing = true)
            advanceUntilIdle()
            assertEquals(
                "Sending clip.mp4",
                vm.state.value.conversations
                    .first { it.id == "bob" }
                    .lastPreview,
            )

            transfersFlow.value = emptyMap()
            advanceUntilIdle()
            assertEquals(
                "Stopped partway through clip.mp4",
                vm.state.value.conversations
                    .first { it.id == "bob" }
                    .lastPreview,
            )
        }
}
