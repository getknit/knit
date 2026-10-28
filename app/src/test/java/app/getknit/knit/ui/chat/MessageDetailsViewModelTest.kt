@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.LoraRadioReached
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.runner.RunWith

/**
 * The screen that answers "who are those three 👍?" — and "which of them actually got it?". The contract
 * worth pinning: the per-reactor identity survives (the chat's [ReactionSummary] tally aggregates it away),
 * an unknown reactor still reads as a stable alias rather than a raw node id, the filter chips agree with
 * the row count they filter, and the delivery split appears only where "who" is answerable and never
 * invents an answer for a message acked before the receipts table existed.
 */
@RunWith(AndroidJUnit4::class)
class MessageDetailsViewModelTest {
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val reactions = mockk<ReactionRepository>(relaxed = true)
    private val receipts = mockk<MessageReceiptRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val messageFlow = MutableStateFlow<MessageEntity?>(msg(senderId = "me", body = "snacks", id = MSG))
    private val reactionsFlow = MutableStateFlow(emptyList<ReactionEntity>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val receiptsFlow = MutableStateFlow(emptyList<MessageReceiptEntity>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val filteringFlow = MutableStateFlow(true)
    private val reachedFlow = MutableStateFlow(emptyMap<String, LoraRadioReached>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessage(MSG) } returns messageFlow
        every { reactions.observeReactionsFor(MSG) } returns reactionsFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { receipts.observeForMessage(MSG) } returns receiptsFlow
        every { groups.observeGroups() } returns groupsFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(blobs: BlobRepository? = null) =
        MessageDetailsViewModel(MSG, messages, reactions, receipts, groups, peers, settings, identity, reachedFlow, blobs)

    /** Seeds a group we're in, and points the message at it. */
    private fun inGroup(vararg members: String) {
        groupsFlow.value =
            listOf(
                GroupEntity(
                    groupId = GROUP,
                    name = "Trailhead Crew",
                    members = GroupMembersStore.encode(members.toList()),
                    createdBy = "me",
                    createdAt = 0L,
                ),
            )
    }

    private fun ack(
        acker: String,
        notedAt: Long,
        via: DeliveryPlane = DeliveryPlane.Nearby,
    ) = MessageReceiptEntity(MSG, acker, notedAt, via.code)

    @Test
    fun `every reactor keeps its identity, resolved to a display name`() =
        runTest {
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"))
            reactionsFlow.value =
                listOf(
                    reaction(MSG, "sam", "👍", updatedAt = 10L),
                    reaction(MSG, "me", "❤️", updatedAt = 20L),
                )

            val state = collect(viewModel())

            assertEquals(listOf("Sam Rivera", "You"), state.reactors.map { if (it.isSelf) "You" else it.displayName })
            assertEquals(listOf("👍", "❤️"), state.reactors.map { it.emoji })
            assertEquals(listOf(false, true), state.reactors.map { it.isSelf })
        }

    @Test
    fun `a reactor whose profile never arrived reads as an alias, not a node id`() =
        runTest {
            reactionsFlow.value = listOf(reaction(MSG, "stranger01", "👍", updatedAt = 1L))

            val state = collect(viewModel())

            val name = state.reactors.single().displayName
            assertEquals(Alias.aliasFor("stranger01"), name)
            assertFalse(name.contains("stranger01"))
        }

    @Test
    fun `filters count per emoji, most-reacted first`() =
        runTest {
            reactionsFlow.value =
                listOf(
                    reaction(MSG, "a", "❤️", updatedAt = 1L),
                    reaction(MSG, "b", "👍", updatedAt = 2L),
                    reaction(MSG, "c", "👍", updatedAt = 3L),
                )

            val state = collect(viewModel())

            assertEquals(listOf("👍" to 2, "❤️" to 1), state.filters.map { it.emoji to it.count })
            // The All chip's total is the row count it filters — the two can't disagree.
            assertEquals(state.reactors.size, state.filters.sumOf { it.count })
        }

    @Test
    fun `metadata comes off the stored row — sender, sent time, delivery, plane`() =
        runTest {
            messageFlow.value =
                msg(senderId = "sam", sentAt = 4_242L, received = true, id = MSG)
                    .copy(receivedVia = DeliveryPlane.Internet.code, arrivedAt = 4_900L)
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"))

            val state = collect(viewModel())

            assertEquals("Sam Rivera", state.senderName)
            assertEquals(4_242L, state.sentAt)
            // Sam's clock and ours, side by side — the gap is what the screen exists to show.
            assertEquals(4_900L, state.arrivedAt)
            assertFalse(state.mine)
            assertEquals(DeliveryStatus.Delivered, state.delivery)
            assertEquals(DeliveryPlane.Internet, state.plane)
        }

    @Test
    fun `a file a direct link carried names the link, not the relay its message crossed`() =
        runTest {
            // ADR 2026-10.a5yj: our DM photo whose peer proved it holds the bytes over a link.
            messageFlow.value =
                msg(senderId = "me", conversationId = "bob", attachmentHash = "h1", received = true, id = MSG)
                    .copy(receivedVia = DeliveryPlane.Internet.code)
            val blobs = mockk<BlobRepository>()
            every { blobs.observeLinkHolders(setOf("h1")) } returns flowOf(mapOf("h1" to setOf("bob")))

            assertEquals(DeliveryPlane.DirectLink, collect(viewModel(blobs)).plane)
            // Without that proof, the message's own plane.
            every { blobs.observeLinkHolders(setOf("h1")) } returns flowOf(emptyMap())
            assertEquals(DeliveryPlane.Internet, collect(viewModel(blobs)).plane)
        }

    @Test
    fun `a message we sent has no arrival time, and one stored before the column has none either`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = Conversations.NEARBY, id = MSG)

            val state = collect(viewModel())

            assertTrue(state.mine)
            assertNull(state.arrivedAt)
        }

    @Test
    fun `a never-sealed DM reports Pending, not Sent`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", id = MSG).copy(pendingKey = true)

            assertEquals(DeliveryStatus.Pending, collect(viewModel()).delivery)
        }

    @Test
    fun `deleting a message we were looking at marks it vanished so the screen can close`() =
        runTest {
            val vm = viewModel()
            assertFalse(collect(vm).vanished)

            messageFlow.value = null

            assertTrue(collect(vm).vanished)
        }

    @Test
    fun `a row that has not been written yet is not vanished — the screen must stay open`() =
        runTest {
            // A deep link can outrun the write (the seeded build does exactly this). Reading null before
            // ever seeing the row must not read as a delete, or the screen closes the moment it opens.
            messageFlow.value = null
            val vm = viewModel()
            assertFalse(collect(vm).vanished)

            messageFlow.value = msg(senderId = "me", body = "snacks", id = MSG)
            val arrived = collect(vm)
            assertFalse(arrived.vanished)
            assertEquals("snacks", arrived.body)
        }

    @Test
    fun `a group send splits the roster into delivered and waiting, self excluded`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, received = true, id = MSG)
            inGroup("me", "sam", "priya", "theo")
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"), peer("priya", name = "Priya Nair"))
            receiptsFlow.value = listOf(ack("priya", notedAt = 20L), ack("sam", notedAt = 30L))

            val state = collect(viewModel())

            assertTrue(state.showRecipients)
            // Ordered by when each receipt reached us, not by roster position.
            assertEquals(listOf("Priya Nair", "Sam Rivera"), state.deliveredTo.map { it.displayName })
            assertEquals(listOf(20L, 30L), state.deliveredTo.map { it.deliveredAt })
            assertEquals(listOf(Alias.aliasFor("theo")), state.waitingOn.map { it.displayName })
            assertEquals(listOf<Long?>(null), state.waitingOn.map { it.deliveredAt })
            // "of 3" — we never ack our own message, so our own row would wait forever.
            assertEquals(3, state.recipientTotal)
        }

    @Test
    fun `each member who has a group send carries the plane their receipt crossed`() =
        runTest {
            // The group's one tick names whichever member acked first; the split names each (ADR 2026-10.a5yj).
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, received = true, id = MSG)
            inGroup("me", "sam", "priya", "theo")
            receiptsFlow.value =
                listOf(ack("priya", notedAt = 20L, via = DeliveryPlane.Internet), ack("sam", notedAt = 30L, via = DeliveryPlane.DirectLink))

            val state = collect(viewModel())

            assertEquals(
                listOf("priya" to DeliveryPlane.Internet, "sam" to DeliveryPlane.DirectLink),
                state.deliveredTo.map { it.nodeId to it.plane },
            )
            assertEquals("a member still waiting has no plane", listOf(DeliveryPlane.Unknown), state.waitingOn.map { it.plane })
        }

    @Test
    fun `a group send nobody has acked yet lists the whole roster as waiting`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, id = MSG)
            inGroup("me", "sam", "theo")

            val state = collect(viewModel())

            assertTrue(state.showRecipients)
            assertTrue(state.deliveredTo.isEmpty())
            assertEquals(2, state.waitingOn.size)
        }

    @Test
    fun `a group send acked before the receipts table existed shows no split at all`() =
        runTest {
            // Already ✓✓ with nothing recorded: somebody got it and we cannot say who. Naming the whole
            // roster as waiting would contradict the tick right above it.
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, received = true, id = MSG)
            inGroup("me", "sam", "theo")

            val state = collect(viewModel())

            assertFalse(state.showRecipients)
            assertTrue(state.waitingOn.isEmpty())
        }

    @Test
    fun `a message someone else sent never shows a delivery split`() =
        runTest {
            messageFlow.value = msg(senderId = "sam", conversationId = GROUP, id = MSG)
            inGroup("me", "sam", "theo")
            receiptsFlow.value = listOf(ack("theo", notedAt = 5L))

            assertFalse(collect(viewModel()).showRecipients)
        }

    @Test
    fun `a DM never shows a delivery split — its single tick already names the recipient`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            receiptsFlow.value = listOf(ack("bob", notedAt = 5L))

            assertFalse(collect(viewModel()).showRecipients)
        }

    @Test
    fun `a DM still reports when it was delivered, off the recipient's own receipt`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            receiptsFlow.value = listOf(ack("bob", notedAt = 5L))

            val state = collect(viewModel())

            // No roster (ADR 036 rule 3 stands) — but the receipt that flipped the tick knows the time.
            assertFalse(state.showRecipients)
            assertEquals(5L, state.deliveredAt)
        }

    @Test
    fun `a DM with no receipt yet reports no delivery time`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)

            assertNull(collect(viewModel()).deliveredAt)
        }

    @Test
    fun `a DM's delivery time comes from the addressed recipient, never a stray row`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            receiptsFlow.value = listOf(ack("mallory", notedAt = 5L))

            assertNull(collect(viewModel()).deliveredAt)
        }

    @Test
    fun `a group and a room post report no single delivery time — the roster answers instead`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, received = true, id = MSG)
            inGroup("me", "sam")
            receiptsFlow.value = listOf(ack("sam", notedAt = 5L))
            assertNull(collect(viewModel()).deliveredAt)

            messageFlow.value = msg(senderId = "me", conversationId = Conversations.NEARBY, received = true, id = MSG)
            assertNull(collect(viewModel()).deliveredAt)
        }

    @Test
    fun `the broadcast room lists who was heard from, with no total and nobody waiting`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = Conversations.NEARBY, received = true, id = MSG)
            peersFlow.value = listOf(peer("sam", name = "Sam Rivera"))
            receiptsFlow.value = listOf(ack("sam", notedAt = 7L))

            val state = collect(viewModel())

            assertTrue(state.showRecipients)
            assertEquals(listOf("Sam Rivera"), state.deliveredTo.map { it.displayName })
            assertTrue(state.waitingOn.isEmpty())
            // No roster, so no denominator — the header reads "Received by 1", not "1 of N".
            assertEquals(0, state.recipientTotal)
        }

    @Test
    fun `a broadcast message nobody has acked shows nothing — an empty list is not a fact`() =
        runTest {
            messageFlow.value = msg(senderId = "me", conversationId = Conversations.NEARBY, id = MSG)

            assertFalse(collect(viewModel()).showRecipients)
        }

    @Test
    fun `a member who left is dropped from the split, acked or not`() =
        runTest {
            // The stored roster is already the EFFECTIVE one (departures subtracted), so a leaver's old
            // receipt must not resurrect them into a list the group screen no longer shows.
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, received = true, id = MSG)
            inGroup("me", "sam")
            receiptsFlow.value = listOf(ack("sam", notedAt = 10L), ack("theo", notedAt = 11L))

            val state = collect(viewModel())

            assertEquals(listOf("sam"), state.deliveredTo.map { it.nodeId })
            assertTrue(state.waitingOn.isEmpty())
            assertEquals(1, state.recipientTotal)
        }

    // --- their LoRa radio acked it (ADR 040's 2026-10-31 amendment) ---

    @Test
    fun `a DM their LoRa radio acked says so with the time, and the receipt then wins`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            reachedFlow.value = mapOf(MSG to LoraRadioReached(atMs = 42L, late = false))
            val vm = viewModel()

            val reached = collect(vm)
            assertEquals(DeliveryStatus.RadioReached, reached.delivery)
            assertEquals(42L, reached.radioReachedAt)
            assertNull("their phone has not confirmed: no delivery time", reached.deliveredAt)

            // Their ✓✓ lands; the in-memory entry stays, and is ignored.
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG, received = true)
            receiptsFlow.value = listOf(ack("bob", notedAt = 50L))
            val delivered = collect(vm)
            assertEquals(DeliveryStatus.Delivered, delivered.delivery)
            assertNull(delivered.radioReachedAt)
            assertEquals(50L, delivered.deliveredAt)
        }

    @Test
    fun `a late ack still reads as their radio but shows no time`() =
        runTest {
            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            reachedFlow.value = mapOf(MSG to LoraRadioReached(atMs = 42L, late = true))

            val state = collect(viewModel())

            assertEquals(DeliveryStatus.RadioReached, state.delivery)
            assertNull("a replayed ack's time says nothing", state.radioReachedAt)
        }

    @Test
    fun `a group post, a received DM and another DM never read the radio's ack`() =
        runTest {
            reachedFlow.value = mapOf(MSG to LoraRadioReached(atMs = 42L, late = false))
            messageFlow.value = msg(senderId = "me", conversationId = GROUP, id = MSG)
            inGroup("me", "sam")
            assertEquals(DeliveryStatus.Sent, collect(viewModel()).delivery)

            messageFlow.value = msg(senderId = "bob", recipientId = "me", conversationId = "bob", id = MSG)
            assertEquals(DeliveryStatus.Sent, collect(viewModel()).delivery)

            messageFlow.value = msg(senderId = "me", recipientId = "bob", conversationId = "bob", id = MSG)
            reachedFlow.value = mapOf("another" to LoraRadioReached(atMs = 42L, late = false))
            assertEquals(DeliveryStatus.Sent, collect(viewModel()).delivery)
        }

    /** Subscribes (the state is `WhileSubscribed`) and lets the combine settle. */
    private fun TestScope.collect(vm: MessageDetailsViewModel): MessageDetailsUiState {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        job.cancel()
        return vm.state.value
    }

    private companion object {
        const val MSG = "m1"
        const val GROUP = "g-trailhead"
    }
}
