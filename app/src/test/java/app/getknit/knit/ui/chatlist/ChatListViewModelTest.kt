@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftEntity
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.InMemoryMessages
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.msg
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric-hosted (the state combine calls `context.getString`, incl. format args in `previewFor`, so a
 * real Context returns the actual strings). Covers the unread-count watermark math, room/group/DM assembly
 * + sort, and the own-message preview label.
 *
 * The messages are a real in-memory database ([InMemoryMessages]) rather than a stubbed flow: the list reads
 * per-thread summary queries, and the rules under test here — which row speaks for a thread, what counts
 * as unread, who has spoken in a group — now live in that SQL. Room's own work rides the same unconfined
 * dispatcher as Main, so a seeded write reaches `state.value` before `store.set` returns.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one shared vm()/InMemoryMessages harness, as ChatViewModelTest
class ChatListViewModelTest {
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
    private val commons = mockk<CommonsRepository>(relaxed = true)

    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val commonsFlow = MutableStateFlow(emptyList<CommonsEntity>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val lastReadFlow = MutableStateFlow(emptyMap<String, Long>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())
    private val draftsFlow = MutableStateFlow(emptyMap<String, DraftEntity>())

    // The clone notice's two stamps (ADR 2026-09.ypcc); seen past dismissed lights the banner.
    private val cloneSeenFlow = MutableStateFlow(0L)
    private val cloneDismissedFlow = MutableStateFlow(0L)
    private val pausedUntilFlow = MutableStateFlow<Long?>(null)

    // A finite stand-in for the production poller, which never idles under a virtual clock.
    private val relayFlow = MutableStateFlow(RelayFacts())
    private val loraFlow = MutableStateFlow(LoraFacts())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryMessages(mainDispatcher)
        coEvery { identity.nodeId() } returns "me"
        every { settings.blockedNodeIds } returns blockedFlow
        every { groups.observeGroups() } returns groupsFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.lastReadAll } returns lastReadFlow
        every { settings.acceptedConversations } returns acceptedFlow
        // Not optional: a relaxed mock hands back a Flow that never emits, and one silent arm stalls the
        // whole combine — every assertion in this class would then read the loading seed.
        every { drafts.all } returns draftsFlow
        every { commons.observeAll() } returns commonsFlow
        every { settings.cloneSeenAt } returns cloneSeenFlow
        every { settings.cloneDismissedAt } returns cloneDismissedFlow
        every { settings.meshPausedUntil } returns pausedUntilFlow
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun vm() =
        ChatListViewModel(
            messages,
            peers,
            settings,
            identity,
            mesh,
            groups,
            drafts,
            mockk(relaxed = true) { every { states } returns MutableStateFlow(emptyMap()) },
            relayFlow,
            loraFlow,
            context,
            commons,
        )

    @Test
    fun aJoinedCommonsHasARowBeforeAnyoneHasPostedAndLeavesWithTheRoom() =
        runTest {
            // A room exists from the join: the row is titled with the relay's name for it and sits with the
            // rooms, like a freshly created group sits before its first message. Deleting it is leaving it.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            val room = Conversations.commonsIdFor("ab".repeat(32))
            commonsFlow.value = listOf(CommonsEntity(room, "wss://home.test/spool/v1", ByteArray(32), "Home", joinedAt = 1L))
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .single { it.id == room }
            assertEquals("Home", row.title)
            assertTrue(row.isRoom)
            assertTrue(
                vm.state.value.conversations
                    .indexOf(row) < vm.state.value.conversations.size,
            )

            // A member's post previews under the member's own name — a pinned peer, not a stranger.
            peersFlow.value = listOf(peer("peer-a", "Ann"))
            store.set(
                msg(
                    senderId = "peer-a",
                    body = "dinner at 7?",
                    sentAt = 100,
                    conversationId = room,
                    receivedVia = DeliveryPlane.Internet.code,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "Ann: dinner at 7?",
                vm.state.value.conversations
                    .single { it.id == room }
                    .lastPreview,
            )

            vm.deleteConversation(room)
            advanceUntilIdle()
            coVerify(exactly = 1) { commons.leave(room) }
            assertTrue(mesh.relaysRefreshed)
        }

    @Test
    fun theRadioRoomIsHiddenOutrightWhenTheUserSwitchesItOff() =
        runTest {
            // The switch hides the row *including* its history, which is the whole of what "hidden" means —
            // the rows stay in the database and the switch brings them back. A rule that only hid the empty
            // row would leave a phone that had ever heard a post with a permanent one.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "LongFast")
            store.set(
                msg(
                    senderId = "gw",
                    body = "anyone around?",
                    sentAt = 100,
                    conversationId = Conversations.MESHTASTIC,
                    originNode = 0x1234abcd,
                    originName = "Bob",
                    originChannel = "LongFast",
                ),
            )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.conversations
                    .any { it.id == Conversations.MESHTASTIC },
            )

            loraFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "LongFast", room = false)
            advanceUntilIdle()
            assertTrue(
                "a live board does not bring the row back",
                vm.state.value.conversations
                    .none { it.id == Conversations.MESHTASTIC },
            )

            // And the history it hid is still there to come back to.
            loraFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "LongFast")
            advanceUntilIdle()
            assertEquals(
                100L,
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
                    .lastMessageAt,
            )
        }

    @Test
    fun theRadioRoomAppearsWithABoundBoardOrAHistory() =
        runTest {
            // It is this phone's own radio's channel: with no radio bound and nothing heard, no row — a
            // standing empty row would offer what this install cannot have.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(
                vm.state.value.conversations
                    .none { it.id == Conversations.MESHTASTIC },
            )

            // Bound (even while the link is down): the room exists, empty, under the generic title.
            loraFlow.value = LoraFacts(plane = LoraPlane.Down)
            advanceUntilIdle()
            val empty =
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
            assertEquals(context.getString(R.string.meshtastic_title), empty.title)
            assertNull(empty.lastPreview)
            assertTrue(empty.isRoom && empty.isBridged)
            assertTrue("an empty radio room is nothing to open, so the hint stays", vm.state.value.showGettingStarted)

            // Unbound again with history: the history keeps the row.
            loraFlow.value = LoraFacts()
            store.set(
                msg(
                    senderId = "gw",
                    body = "anyone around?",
                    sentAt = 100,
                    conversationId = Conversations.MESHTASTIC,
                    originNode = 0x1234abcd,
                    originName = "Bob",
                    originChannel = "LongFast",
                ),
            )
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
            assertTrue("it draws the room glyph", row.isRoom)
            assertTrue("but it can be cleared, unlike Nearby", row.isBridged)
            assertEquals("with no board connected the newest post names the channel", "LongFast", row.title)

            // A connected board names it — its own slot 0, whatever that is set to.
            loraFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "MediumFast")
            advanceUntilIdle()
            assertEquals(
                "MediumFast",
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
                    .title,
            )
        }

    @Test
    fun aHeardPreviewNamesTheContactWhoseBoardItCameFrom() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam"))
            store.set(
                msg(
                    senderId = "me",
                    body = "hi",
                    sentAt = 100,
                    conversationId = Conversations.MESHTASTIC,
                    originNode = 0x1234abcd,
                    originName = "Knit 1a2b",
                    originPeerId = "sam",
                ),
            )
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
            assertEquals("Sam: hi", row.lastPreview)
        }

    @Test
    fun aHeardPreviewNamesTheSpeakerNotUs() =
        runTest {
            // The row's senderId is `me` by convention (our board heard it) — so without the origin the
            // preview would read "You: …" over a stranger's words.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(
                msg(
                    senderId = "me",
                    body = "anyone around?",
                    sentAt = 100,
                    conversationId = Conversations.MESHTASTIC,
                    originNode = 0x1234abcd,
                    originName = "Bob",
                ),
                msg(
                    senderId = "me",
                    body = "still here",
                    sentAt = 200,
                    conversationId = Conversations.MESHTASTIC,
                    originNode = 0xdeadbeef,
                ),
            )
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .first { it.id == Conversations.MESHTASTIC }
            // No NODEINFO name for the newest speaker, so its `!hex` id stands in — as in every Meshtastic client.
            assertEquals("!deadbeef: still here", row.lastPreview)
            assertNull("nor does a post we merely heard grow a delivery tick", row.lastStatus)
            assertEquals("and both count as unread — we wrote neither", 2, row.unreadCount)
        }

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

    /** The clone banner (ADR 2026-09.ypcc) is the two persisted stamps compared, and a dismissal writes the second. */
    @Test
    fun theCloneBannerFollowsTheSeenStampPastTheDismissal() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.cloneVisible)

            cloneSeenFlow.value = 5_000
            advanceUntilIdle()
            assertTrue(vm.state.value.cloneVisible)

            vm.dismissClone()
            advanceUntilIdle()
            coVerify { settings.setCloneDismissedAt(more(5_000L)) }
            cloneDismissedFlow.value = 6_000
            advanceUntilIdle()
            assertFalse(vm.state.value.cloneVisible)

            // The other phone published again after the dismissal: back up.
            cloneSeenFlow.value = 7_000
            advanceUntilIdle()
            assertTrue(vm.state.value.cloneVisible)
        }

    /**
     * The paused banner is the store's deadline while it is ahead of the clock; Resume writes it away, and a
     * radio-off warning is withheld under it (a stopped transport's last health is stale).
     */
    @Test
    fun theMeshPausedBannerFollowsTheDeadlineAndResumeClearsIt() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            mesh.transportStatuses.value = listOf(TransportStatus(TransportKind.Bluetooth, TransportHealth.Unavailable, 0, 0))
            advanceUntilIdle()
            assertNull(vm.state.value.pausedUntil)
            assertEquals(RadioWarning.AllRadiosOff, vm.state.value.radioWarning)

            // A deadline already behind the clock is not a pause.
            pausedUntilFlow.value = System.currentTimeMillis() - 1_000
            advanceUntilIdle()
            assertNull(vm.state.value.pausedUntil)

            val until = System.currentTimeMillis() + 15 * 60_000L
            pausedUntilFlow.value = until
            advanceUntilIdle()
            assertEquals(until, vm.state.value.pausedUntil)
            assertNull(vm.state.value.radioWarning)

            vm.resumeMesh()
            advanceUntilIdle()
            coVerify { settings.setMeshPausedUntil(null) }
            pausedUntilFlow.value = null
            advanceUntilIdle()
            assertNull(vm.state.value.pausedUntil)
            assertEquals(RadioWarning.AllRadiosOff, vm.state.value.radioWarning)
        }

    @Test
    fun theGettingStartedHintShowsOnAFreshInstallAndRetiresOnTheFirstNearbyMessage() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            // Nothing but the (empty) Nearby row: the list looks populated but has nothing to open.
            assertTrue(vm.state.value.showGettingStarted)

            store.set(msg(senderId = "bob", sentAt = 100, conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            assertFalse(vm.state.value.showGettingStarted)
        }

    @Test
    fun theGettingStartedHintRetiresForAGroupADmOrAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

            // An empty group the user just created — no messages anywhere, but there is a thread to open.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdAt = 50))
            acceptedFlow.value = setOf("g-1")
            advanceUntilIdle()
            assertFalse(vm.state.value.showGettingStarted)

            // An accepted DM.
            groupsFlow.value = emptyList()
            store.set(msg(senderId = "friend", sentAt = 100, conversationId = "friend", recipientId = "me"))
            acceptedFlow.value = setOf("friend")
            advanceUntilIdle()
            assertFalse(vm.state.value.showGettingStarted)

            // A stranger's DM is partitioned into the requests inbox rather than the list, but it is still
            // somewhere to go, so the hint stays retired.
            acceptedFlow.value = emptySet()
            advanceUntilIdle()
            assertEquals(1, vm.state.value.requestCount)
            assertFalse(vm.state.value.showGettingStarted)
        }

    @Test
    fun unreadCountsOnlyOthersNormalMessagesAfterTheWatermark() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(
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
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
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
            store.set(
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
    fun aPhotoLessGroupRowCarriesItsOtherMembersFacesByNodeId() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("x", name = "Xi"), peer("b", name = "Bea", avatarHash = "hb"))
            // Roster order is x-first; the faces are by node id, and never include us.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), createdAt = 50))
            acceptedFlow.value = setOf("g-1")
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .single { it.id == "g-1" }
            assertEquals(listOf(GroupFace("b", "Bea", "hb"), GroupFace("x", "Xi", null)), row.faces)
        }

    @Test
    fun ownMessagePreviewIsLabelledWithTheSelfName() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(msg(senderId = "me", body = "hello", sentAt = 100, conversationId = Conversations.NEARBY))
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
            store.set(msg(senderId = "stranger", sentAt = 100, conversationId = "stranger", recipientId = "me"))
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
    fun aGroupAKnownPeerHasPostedInStaysInTheListAndIsNotCounted() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            // Verified peer "b" posts in the group: it reads as a normal chat, not a request — so it stays
            // in the list and isn't counted for the badge.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), createdAt = 50))
            store.set(msg(senderId = "b", sentAt = 100, conversationId = "g-1"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .any { it.id == "g-1" },
            )
            assertEquals(0, vm.state.value.requestCount)
        }

    @Test
    fun aGroupWhereAKnownPeerIsAMemberButOnlyAStrangerPostedIsPartitionedOutAndCounted() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            // Verified "b" is a member but silent; only stranger "x" has posted. Membership alone doesn't
            // promote it — it stays a request.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), createdAt = 50))
            store.set(msg(senderId = "x", sentAt = 100, conversationId = "g-1"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .none { it.id == "g-1" },
            )
            assertEquals(1, vm.state.value.requestCount)
        }

    @Test
    fun anAcceptedDmStaysInTheListAndIsNotCounted() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(msg(senderId = "friend", sentAt = 100, conversationId = "friend", recipientId = "me"))
            acceptedFlow.value = setOf("friend")
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .any { it.id == "friend" },
            )
            assertEquals(0, vm.state.value.requestCount)
        }

    /** Two accepted DM peers who both call themselves Friend are told apart by their alias (ADR 058). */
    @Test
    fun sameNamedDmPeersAreTitledWithTheirAlias() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("friend", name = "Friend"), peer("friend2", name = "Friend"), peer("pal", name = "Pal"))
            store.set(
                msg(senderId = "friend", sentAt = 100, conversationId = "friend", recipientId = "me"),
                msg(senderId = "friend2", sentAt = 200, conversationId = "friend2", recipientId = "me"),
                msg(senderId = "pal", sentAt = 300, conversationId = "pal", recipientId = "me"),
            )
            acceptedFlow.value = setOf("friend", "friend2", "pal")
            advanceUntilIdle()

            val rows =
                vm.state.value.conversations
                    .associateBy { it.id }
            assertEquals("Friend (${Alias.aliasFor("friend")})", rows.getValue("friend").title)
            assertEquals(Alias.aliasFor("friend"), rows.getValue("friend").discriminator)
            assertEquals("Friend (${Alias.aliasFor("friend2")})", rows.getValue("friend2").title)
            assertEquals("Pal", rows.getValue("pal").title)
            assertNull(rows.getValue("pal").discriminator)
        }

    @Test
    fun lastStatusTracksOurOwnNewestMessageAndItsReceiptPlane() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(
                msg(senderId = "friend", sentAt = 100, conversationId = "friend", recipientId = "me"),
                msg(
                    senderId = "me",
                    sentAt = 200,
                    conversationId = "friend",
                    recipientId = "friend",
                    received = true,
                    receivedVia = DeliveryPlane.Internet.code,
                ),
            )
            advanceUntilIdle()

            val dm =
                vm.state.value.conversations
                    .first { it.id == "friend" }
            assertEquals(DeliveryStatus.Delivered, dm.lastStatus)
            assertEquals(DeliveryPlane.Internet, dm.lastDeliveredVia)
        }

    @Test
    fun lastStatusIsSentWithNoReceiptAndPendingWhileTheirKeyIsMissing() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            store.set(
                msg(senderId = "me", sentAt = 100, conversationId = "a", recipientId = "a"),
                msg(senderId = "me", sentAt = 100, conversationId = "b", recipientId = "b", pendingKey = true),
            )
            acceptedFlow.value = setOf("a", "b")
            advanceUntilIdle()

            val rows =
                vm.state.value.conversations
                    .associateBy { it.id }
            assertEquals(DeliveryStatus.Sent, rows.getValue("a").lastStatus)
            assertEquals(DeliveryPlane.Unknown, rows.getValue("a").lastDeliveredVia)
            assertEquals(DeliveryStatus.Pending, rows.getValue("b").lastStatus)
        }

    @Test
    fun noTickWhenTheNewestMessageIsNotOurs() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            // Ours is older; theirs is the newest, so delivery isn't ours to report on this row.
            store.set(
                msg(senderId = "me", sentAt = 100, conversationId = "friend", recipientId = "friend"),
                msg(senderId = "friend", sentAt = 200, conversationId = "friend", recipientId = "me"),
            )
            advanceUntilIdle()

            val rows =
                vm.state.value.conversations
                    .associateBy { it.id }
            assertNull(rows.getValue("friend").lastStatus)
            // An empty thread has none either.
            assertNull(rows.getValue(Conversations.NEARBY).lastStatus)
        }

    @Test
    fun aStatusNoticeIsInvisibleToTheChatList() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdAt = 50))
            // A notice arriving AFTER the newest real message must not speak for the thread: the preview,
            // the timestamp and the tick all keep describing the last thing someone actually said. A
            // notice is worth a line inside the thread and is not worth re-sorting someone's chat list.
            store.set(
                msg(senderId = "me", sentAt = 100, conversationId = "g-1", body = "see you"),
                msg(
                    senderId = "x",
                    sentAt = 200,
                    conversationId = "g-1",
                    body = "",
                    kind = MessageEntity.KIND_MEMBER_LEFT,
                ),
            )
            advanceUntilIdle()

            val row =
                vm.state.value.conversations
                    .first { it.id == "g-1" }
            assertEquals("You: see you", row.lastPreview)
            assertEquals(100L, row.lastMessageAt)
            // The tick still belongs to that real send of ours...
            assertEquals(DeliveryStatus.Sent, row.lastStatus)
        }

    @Test
    fun aGroupHoldingOnlyNoticesStillSortsByItsCreationTime() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdAt = 50))
            // ...and with no real message there is nothing to describe, so the group falls back to the
            // empty-group behaviour rather than borrowing the notice's clock. A notice's senderId is the
            // event's SUBJECT rather than an author, so it must not grow a tick either — which is what
            // would happen if the filter keyed on authorship instead of on kind.
            store.set(
                msg(
                    senderId = "me",
                    sentAt = 200,
                    conversationId = "g-1",
                    body = "",
                    kind = MessageEntity.KIND_GROUP_CREATED,
                ),
            )
            advanceUntilIdle()

            // The group is in the chat list at all only because we are its creator — see the
            // request-inbox test below for the case where a notice is the only thing a stranger sent.
            val row =
                vm.state.value.conversations
                    .first { it.id == "g-1" }
            assertNull(row.lastPreview)
            assertEquals(50L, row.lastMessageAt)
            assertNull(row.lastStatus)
        }

    @Test
    fun aStatusNoticeRaisesNoUnreadBadge() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdAt = 50))
            store.set(
                // Ours, so the thread is an accepted chat rather than a request.
                msg(senderId = "me", sentAt = 100, conversationId = "g-1"),
                msg(senderId = "x", sentAt = 200, conversationId = "g-1"),
                msg(
                    senderId = "x",
                    sentAt = 300,
                    conversationId = "g-1",
                    body = "",
                    kind = MessageEntity.KIND_PEER_RENAMED,
                ),
            )
            advanceUntilIdle()

            // One unread, not two: the notice is quiet even though it is unread, from someone else, and
            // newer than everything else in the thread.
            assertEquals(
                1,
                vm.state.value.conversations
                    .first { it.id == "g-1" }
                    .unreadCount,
            )
        }

    @Test
    fun aDraftSpeaksForTheRowOnlyWhileItIsTheNewestThingInTheThread() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            // Verified, so the thread is a chat rather than a message request and reaches this list at all.
            peersFlow.value = listOf(peer("x", "Ann", verified = true))
            store.set(msg(senderId = "x", sentAt = 100, conversationId = "x", body = "you around?"))
            draftsFlow.value = mapOf("x" to DraftEntity("x", "half a sentence", updatedAt = 200))
            advanceUntilIdle()

            val withDraft =
                vm.state.value.conversations
                    .first { it.id == "x" }
            assertEquals("half a sentence", withDraft.draft)
            // The message the draft speaks over is still the thread's last one: the draft replaces the
            // preview line, not the row's place in the list or the time beside it.
            assertEquals("you around?", withDraft.lastPreview)
            assertEquals(100L, withDraft.lastMessageAt)

            // A message lands after it and the conversation has moved on; the draft waits in the composer.
            store.add(msg(senderId = "x", sentAt = 300, conversationId = "x", body = "still there?"))
            advanceUntilIdle()
            assertNull(
                vm.state.value.conversations
                    .first { it.id == "x" }
                    .draft,
            )
        }

    @Test
    fun aNoticeAloneDoesNotAcceptAStrangersGroup() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), createdBy = "x"))
            // A notice's senderId is the event's subject, so it is not someone "having spoken here": a
            // group whose only row is a stranger's rename stays a message request. Were notices counted
            // as speech, renaming yourself would be enough to promote your group into someone's chat list.
            store.set(
                msg(
                    senderId = "x",
                    sentAt = 300,
                    conversationId = "g-1",
                    body = "",
                    kind = MessageEntity.KIND_PEER_RENAMED,
                ),
            )
            advanceUntilIdle()

            assertTrue(
                vm.state.value.conversations
                    .none { it.id == "g-1" },
            )
            assertEquals(1, vm.state.value.requestCount)
        }
}
