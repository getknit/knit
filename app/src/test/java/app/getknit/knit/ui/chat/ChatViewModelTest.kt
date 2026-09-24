@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.location.FakeLocationSource
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationFixPolicy
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.PublicPostPolicy
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.transfer.OfferOutcome
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferRefusal
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
import app.getknit.knit.ui.voice.VoicePlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

/**
 * The richest ViewModel: a 5-way state combine plus the send double-submit guard and the attach room-vs-DM
 * flagged branch. Robolectric-hosted for `context.getString`. Every flow feeding the combine (including the
 * inner `blobState` combine — hashes + flagged + filtering) is stubbed, or the whole state stalls.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one shared vm()/stubDm() harness, as MeshManagerTest
class ChatViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val reactions = mockk<ReactionRepository>(relaxed = true)
    private val receipts = mockk<MessageReceiptRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val notifier = mockk<Notifier>(relaxed = true)
    private val attachments = mockk<AttachmentStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val imageScreening = mockk<ImageScreeningService>(relaxed = true)
    private val gallerySaver = mockk<GallerySaver>(relaxed = true)
    private val voicePlayer = mockk<VoicePlayer>(relaxed = true)
    private val linkCards = mockk<LinkCardStore>(relaxed = true)
    private val linkPreviews = mockk<LinkPreviewService>(relaxed = true)
    private val locationSource = FakeLocationSource()
    private val transfers = mockk<TransferManager>(relaxed = true)
    private val transfersFlow = MutableStateFlow(emptyMap<String, TransferState>())

    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val reactionsFlow = MutableStateFlow(emptyList<ReactionEntity>())
    private val recentsFlow = MutableStateFlow(RecentReactions.DEFAULTS)
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val sizesFlow = MutableStateFlow(emptyMap<String, Int>())
    private val flaggedFlow = MutableStateFlow(emptyList<String>())
    private val filteringFlow = MutableStateFlow(true)
    private val groupFlow = MutableStateFlow<GroupEntity?>(null)
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val nameFlow = MutableStateFlow("Alice")
    private val spoolEnabledFlow = MutableStateFlow(false)
    private val spoolUrlsFlow = MutableStateFlow(emptySet<String>())
    private val activeSpoolUrlsFlow = MutableStateFlow(emptySet<String>())
    private val relayFactsFlow = MutableStateFlow(RelayFacts())
    private val relayRoomNoticeDismissedFlow = MutableStateFlow(false)
    private val loraFactsFlow = MutableStateFlow(LoraFacts())
    private val deliveredCountsFlow = MutableStateFlow(emptyMap<String, Int>())
    private val cardsFlow = MutableStateFlow(emptyMap<String, LinkCard>())
    private val onlineFlow = MutableStateFlow(true)
    private val linkPreviewsEnabledFlow = MutableStateFlow(false)
    private val publicConsentFlow = MutableStateFlow(false)
    private val locationConsentFlow = MutableStateFlow(false)
    private val transferConsentFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        stubThread(Conversations.NEARBY)
        every { reactions.observeReactionsIn(any()) } returns reactionsFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.recentReactions } returns recentsFlow
        // Keyed by the window's hashes in production; the test map simply holds every hash a test names.
        every { blobs.observeSizes(any()) } returns sizesFlow
        every { imageScreening.observeFlaggedHashes() } returns flaggedFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { groups.observeGroup(Conversations.NEARBY) } returns groupFlow
        stubThread(GROUP)
        every { groups.observeGroup(GROUP) } returns groupFlow
        stubThread(Conversations.MESHTASTIC)
        every { groups.observeGroup(Conversations.MESHTASTIC) } returns groupFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.displayName } returns nameFlow
        // Same trap as the relay flows below: this one is combined with displayName to build the state, so a
        // relaxed mock's never-emitting Flow would stall every assertion in the class rather than just this one.
        every { settings.meshtasticPostConsented } returns publicConsentFlow
        // A relaxed mock would hand back a Flow that never emits, and RelayStatusRepository
        // combines these — one silent flow would stall every state assertion in this class.
        every { settings.spoolEnabled } returns spoolEnabledFlow
        every { settings.spoolUrls } returns spoolUrlsFlow
        every { settings.activeSpoolUrls } returns activeSpoolUrlsFlow
        every { settings.relayRoomNoticeDismissed } returns relayRoomNoticeDismissedFlow
        every { receipts.observeDeliveredCounts(any(), any()) } returns deliveredCountsFlow
        // Same trap: the decoded-card map is an arm of the blob-state combine.
        every { linkCards.cards } returns cardsFlow
        every { linkPreviews.online } returns onlineFlow
        every { settings.linkPreviewsEnabled } returns linkPreviewsEnabledFlow
        every { settings.locationShareConsented } returns locationConsentFlow
        every { settings.directTransferConsented } returns transferConsentFlow
        every { transfers.states } returns transfersFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun group(
        groupId: String,
        members: List<String>,
    ) = GroupEntity(
        groupId = groupId,
        name = "Trailhead Crew",
        members = GroupMembersStore.encode(members),
        createdBy = "me",
        createdAt = 0L,
    )

    /** The per-thread flows setUp stubs only for the room and the group — a DM thread needs its own. */
    private fun stubDm(peerId: String) {
        stubThread(peerId)
        every { groups.observeGroup(peerId) } returns groupFlow
    }

    /** Every window limit the ViewModel has asked the repository for, oldest first — see [stubThread]. */
    private val requestedLimits = mutableListOf<Int>()

    /**
     * Stands in for the windowed read of one thread. [messagesFlow] holds the *whole* conversation and the
     * stub hands back its newest `limit`, which is exactly what
     * `MessageDao.observeNewestForConversation` + the repository's `asReversed()` produce. Every pre-existing
     * test in this class seeds a handful of messages, so `takeLast` is the identity there and none of them
     * change behaviour; recording the limits is what lets the paging tests prove the window was narrowed at
     * the query rather than filtered in memory afterwards.
     */
    private fun stubThread(conversationId: String) {
        every { messages.observeNewestMessages(conversationId, any()) } answers {
            val limit = secondArg<Int>()
            requestedLimits += limit
            messagesFlow.map { all -> all.takeLast(limit) }
        }
        every { messages.observeSendersIn(conversationId) } returns
            messagesFlow.map { all ->
                all.filter { it.kind == MessageEntity.KIND_NORMAL }.map { it.senderId }.distinct()
            }
    }

    private fun vm(conversationId: String = Conversations.NEARBY) =
        ChatViewModel(
            conversationId,
            messages,
            groups,
            peers,
            reactions,
            receipts,
            drafts,
            mesh,
            identity,
            settings,
            notifier,
            attachments,
            blobs,
            imageScreening,
            gallerySaver,
            voicePlayer,
            linkCards,
            linkPreviews,
            locationSource,
            // A finite flow, not the production poller: RelayStatusRepository emits on an infinite
            // `while(true) { emit; delay }`, and under runTest's virtual clock that delay is instant, so
            // `advanceUntilIdle()` below would never reach idle.
            relayFactsFlow,
            loraFactsFlow,
            context,
            transfers,
        )

    @Test
    fun theThreadReadsAsLoadingUntilItsFlowsHaveFirstEmitted() =
        runTest {
            // The seed is the whole cold open: nothing emits until all five arms of the combine have, and the
            // Room arm reads the entire thread first. Without this flag the seed's empty row list is
            // indistinguishable from a conversation that genuinely has nothing in it — and the screen says so,
            // on a thread with hundreds of messages in it.
            val vm = vm()
            assertTrue("the seed is the cold-open frame", vm.state.value.isLoading)

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "them", body = "hi", sentAt = 100))
            advanceUntilIdle()

            assertFalse("a real emission is never loading", vm.state.value.isLoading)
            assertEquals(1, vm.state.value.rows.size)
        }

    // --- Reading a long thread a window at a time --------------------------------------------------------

    /** A conversation the size the retention caps actually permit; `sentAt` doubles as each row's ordinal. */
    private fun longThread(
        count: Int = 1_000,
        senderId: String = "them",
    ) = (1..count).map { msg(senderId = senderId, body = "m$it", sentAt = it.toLong()) }

    private fun TestScope.collectState(vm: ChatViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    @Test
    fun aThousandMessageThreadOpensOnItsNewestWindowNotItsWholeHistory() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("the cold open folds a window, not the thread", ChatWindow.INITIAL, state.rows.size)
            assertEquals("anchored at the newest message", 1_000L, state.rows.last().sentAt)
            assertEquals("and reaching exactly INITIAL back", 941L, state.rows.first().sentAt)
            assertTrue("with history still behind it", state.hasOlder)
            assertEquals(
                "the narrowing happened in the query, not in memory afterwards",
                listOf(ChatWindow.INITIAL),
                requestedLimits,
            )
        }

    @Test
    fun aThreadShorterThanTheWindowReportsNothingOlder() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread(count = 40)
            advanceUntilIdle()

            assertEquals(40, vm.state.value.rows.size)
            assertFalse("fewer rows back than asked for means the whole thread is loaded", vm.state.value.hasOlder)
        }

    @Test
    fun loadOlderAddsAPageOfHistoryWithoutDisturbingTheNewestEnd() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread()
            advanceUntilIdle()
            val newestBefore =
                vm.state.value.rows
                    .last()
                    .id

            vm.loadOlder()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(ChatWindow.INITIAL + ChatWindow.PAGE, state.rows.size)
            assertEquals("the page is read from Room", ChatWindow.INITIAL + ChatWindow.PAGE, requestedLimits.last())
            // The screen's follow-to-bottom effect keys on the last row's id, and the LazyColumn's index 0 is
            // that same row. A page that changed either would yank the reader out of the history they are
            // reading, which is the whole reason the window grows at the old end.
            assertEquals("the newest message is still the newest", newestBefore, state.rows.last().id)
            assertEquals("and the window reaches a page further back", 841L, state.rows.first().sentAt)
        }

    @Test
    fun repeatedLoadOlderWalksBackOnePageAtATimeAndNeverSkips() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread()
            advanceUntilIdle()
            requestedLimits.clear()

            repeat(5) {
                vm.loadOlder()
                advanceUntilIdle()
            }

            // Each call grows from the window that actually rendered, not from whatever the limit has been
            // set to, so the pages step evenly instead of compounding into 60 → 260 → 660 while Room is still
            // answering the first one.
            assertEquals(
                (1..5).map { ChatWindow.INITIAL + it * ChatWindow.PAGE },
                requestedLimits,
            )
            assertEquals(ChatWindow.INITIAL + 5 * ChatWindow.PAGE, vm.state.value.rows.size)
            assertEquals(
                "still anchored at the newest message",
                1_000L,
                vm.state.value.rows
                    .last()
                    .sentAt,
            )
        }

    @Test
    fun loadOlderDoesNothingOnceTheWholeThreadIsLoaded() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread(count = 10)
            advanceUntilIdle()
            requestedLimits.clear()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals(10, vm.state.value.rows.size)
            assertTrue("no page was requested", requestedLimits.isEmpty())
        }

    @Test
    fun theReadWatermarkFollowsTheThreadsNewestMessageNotTheWindowsPage() =
        runTest {
            val vm = vm()
            collectState(vm)
            vm.onChatForeground()
            messagesFlow.value = longThread()
            advanceUntilIdle()

            // The window is anchored at the newest end, so "newest loaded" and "newest in the thread" are the
            // same message. If the window ever drifted, every thread the user read would keep an unread badge.
            coVerify { settings.setLastReadAt(Conversations.NEARBY, 1_000L) }
        }

    @Test
    fun mentionCandidatesIncludeSomeoneWhoOnlySpokeBeyondTheWindow() =
        runTest {
            val vm = vm()
            collectState(vm)
            // Carol spoke once, a thousand messages ago. She is nowhere in the loaded window, and deriving the
            // candidates from those rows would mean you could only @-mention her after scrolling back to her.
            messagesFlow.value = listOf(msg(senderId = "carol", body = "hello", sentAt = 1L)) + longThread(senderId = "bob")
            advanceUntilIdle()

            val candidates =
                vm.state.value.mentionCandidates
                    .map { it.nodeId }
            assertTrue("carol is still mentionable: $candidates", "carol" in candidates)
            assertTrue("bob is too", "bob" in candidates)
            assertFalse("we are never our own candidate", "me" in candidates)
        }

    @Test
    fun aBlockedSenderIsNeitherMentionableNorAbleToHideTheRestOfTheThread() =
        runTest {
            blockedFlow.value = setOf("mallory")
            val vm = vm()
            collectState(vm)
            // A whole window of blocked messages folds to zero rows. Measuring "is there more?" on those rows
            // would report an empty conversation with all its history sitting right behind it.
            messagesFlow.value = longThread(senderId = "bob") +
                (1..ChatWindow.INITIAL).map {
                    msg(senderId = "mallory", body = "spam$it", sentAt = 1_000L + it)
                }
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("nothing from a blocked sender is rendered", state.rows.none { it.senderNodeId == "mallory" })
            assertTrue("but the thread still reads as having more", state.hasOlder)
            assertFalse("and they are not mentionable", "mallory" in state.mentionCandidates.map { it.nodeId })
        }

    @Test
    fun revealMessageOpensTheWindowFarEnoughToReachAQuotedOriginal() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread()
            advanceUntilIdle()
            val target = messagesFlow.value.first().id // the oldest message, far outside the window
            assertTrue(
                "precondition: not loaded",
                vm.state.value.rows
                    .none { it.id == target },
            )
            coEvery { messages.depthOf(Conversations.NEARBY, target) } returns 1_000

            vm.revealMessage(target)
            advanceUntilIdle()

            assertTrue(
                "the quoted original is now in the window",
                vm.state.value.rows
                    .any { it.id == target },
            )
        }

    @Test
    fun revealMessageLeavesTheWindowAloneForAMessageRetentionHasTrimmed() =
        runTest {
            val vm = vm()
            collectState(vm)
            messagesFlow.value = longThread()
            advanceUntilIdle()
            requestedLimits.clear()
            // Depth 0 is how the query reports "not stored" — the screen's existing behaviour for a quote it
            // cannot follow is to do nothing, and growing the window to no purpose would be worse.
            coEvery { messages.depthOf(Conversations.NEARBY, "gone") } returns 0

            vm.revealMessage("gone")
            advanceUntilIdle()

            assertEquals(ChatWindow.INITIAL, vm.state.value.rows.size)
            assertTrue(requestedLimits.isEmpty())
        }

    @Test
    fun aHeardPostIsAttributedToItsSpeakerNotToUs() =
        runTest {
            // The row's senderId is this phone, by convention (its board heard the post) — but the person who
            // *spoke* is a Meshtastic node the directory knows nothing about. Rendering us here would put our
            // own name on somebody else's words.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "anyone around?",
                        id = "mp1",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = "Bob",
                        originChannel = "LongFast",
                        originHops = 2,
                        originSnrDeci = -73,
                        originViaMqtt = true,
                    ),
                )
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "mp1" }
            assertEquals("Bob", row.senderName)
            assertFalse("not ours, whatever the sender column says", row.mine)
            assertNull("a stranger wears no face", row.avatarHash)
            assertEquals("!1234abcd", row.origin?.nodeLabel)
            assertNull(row.origin?.peerId)
            assertEquals(true, row.origin?.viaMqtt)
        }

    @Test
    fun aHeardPostFromAContactWearsTheirNameAndAvatar() =
        runTest {
            // Lined up at ingest with the contact whose profile claimed the speaker's board, and shown
            // exactly as it came off the air.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", avatarHash = "sam-avatar"))
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "hi",
                        id = "mp-sam",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = "Knit 1a2b",
                        originPeerId = "sam",
                    ),
                )
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "mp-sam" }
            assertEquals("Sam", row.senderName)
            assertEquals("sam-avatar", row.avatarHash)
            assertEquals("hi", row.body)
            assertEquals("sam", row.origin?.peerId)
            assertEquals("the board's own name for them survives on the origin", "Knit 1a2b", row.origin?.name)
            assertFalse(row.mine)
        }

    @Test
    fun aHeardPostIsShownWordForWord() =
        runTest {
            // Knit rewrites nothing it heard on somebody else's channel. A line that happens to open with a
            // contact's name was typed that way by whoever sent it, and guessing otherwise would edit a
            // stranger's words on their behalf.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam"))
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "Sam: hi",
                        id = "a",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 1,
                        originPeerId = "sam",
                    ),
                    msg(
                        senderId = "me",
                        body = "Sam: hi",
                        id = "b",
                        sentAt = 300,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 2,
                    ),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals("the contact's own line is content", "Sam: hi", rows.getValue("a").body)
            assertEquals("and so is a stranger's", "Sam: hi", rows.getValue("b").body)
        }

    @Test
    fun ourOwnPostIsAnOrdinaryOutgoingRowThatNeverTicksTwice() =
        runTest {
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "me", body = "hello mesh", id = "own", sentAt = 100, conversationId = Conversations.MESHTASTIC))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "own" }
            assertTrue(row.mine)
            assertNull(row.origin)
            assertFalse("nothing on the channel acks, so it never shows delivered", row.received)
        }

    @Test
    fun aHeardPostWithNoKnownNameFallsBackToItsNodeId() =
        runTest {
            // What every Meshtastic client shows for a node it has no NODEINFO for — so a stranger reads the
            // same here as there, rather than borrowing a name from somewhere.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        id = "mp2",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                    ),
                )
            advanceUntilIdle()

            assertEquals(
                "!1234abcd",
                vm.state.value.rows
                    .first { it.id == "mp2" }
                    .senderName,
            )
        }

    @Test
    fun theRoomIsTitledByTheLiveBoardThenByItsNewestPost() =
        runTest {
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        id = "mp3",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 1,
                        originChannel = "LongTurbo",
                    ),
                )
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.isBridged)
            assertFalse("nothing on the device can screen a file, and this channel is cleartext", state.canSendFile)
            assertFalse("never a verified badge — nothing here is verified", state.verified)
            assertNull("and no alias suffix: this id is not a peer", state.titleDiscriminator)
            assertEquals("with no board connected the newest post names the channel", "LongTurbo", state.title)

            // A connected board names it — whatever the user set slot 0 to, preset or their own word.
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "MediumFast", canPost = true)
            advanceUntilIdle()
            assertEquals("MediumFast", vm.state.value.title)

            loraFactsFlow.value = LoraFacts()
            messagesFlow.value = emptyList()
            advanceUntilIdle()
            assertEquals(context.getString(R.string.meshtastic_title), vm.state.value.title)
        }

    @Test
    fun theComposerGateFollowsTheRadio() =
        runTest {
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(PublicPostGate.NoRadio, vm.state.value.publicPostGate)

            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Down)
            advanceUntilIdle()
            assertEquals(PublicPostGate.RadioDown, vm.state.value.publicPostGate)

            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = false)
            advanceUntilIdle()
            assertEquals(PublicPostGate.ChannelUnusable, vm.state.value.publicPostGate)

            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            advanceUntilIdle()
            assertEquals(PublicPostGate.Open, vm.state.value.publicPostGate)
        }

    @Test
    fun aPostWithNoLiveRadioIsRefusedBeforeTheDisclosureAndKeepsTheDraft() =
        runTest {
            // Asking for the disclosure with no radio to post through would be a question about nothing.
            val vm = vm(Conversations.MESHTASTIC)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Down)
            advanceUntilIdle()

            vm.send("hello mesh")
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_mesh_post_not_connected), events)
            assertFalse(vm.showPublicConsent.value)
            assertTrue(mesh.sentPublicPosts.isEmpty())
            assertFalse("the guard is released, the draft is the user's", vm.isSending.value)
        }

    @Test
    fun aRefusedPostKeepsTheDraftAndSaysWhy() =
        runTest {
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            val events = mutableListOf<Int>()
            var cleared = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.clearInput.collect { cleared++ } }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            advanceUntilIdle()

            val expected =
                mapOf(
                    PublicPostRefusal.NOT_READY to R.string.chat_mesh_post_not_connected,
                    PublicPostRefusal.TOO_SOON to R.string.chat_mesh_post_too_soon,
                    PublicPostRefusal.TOO_LARGE to R.string.chat_mesh_post_too_large,
                    PublicPostRefusal.NO_AIR to R.string.chat_mesh_post_no_air,
                    PublicPostRefusal.KNIT_ON_PRIMARY to R.string.chat_mesh_post_channel_unusable,
                    PublicPostRefusal.NAK to R.string.chat_mesh_post_refused,
                )
            for ((reason, string) in expected) {
                mesh.publicPostOutcome = PublicPostOutcome.Refused(reason)
                vm.send("hello mesh")
                advanceUntilIdle()
                assertEquals(reason.name, string, events.last())
                assertFalse(vm.isSending.value)
            }
            assertEquals("the draft is never cleared on a refusal", 0, cleared)
            assertEquals(expected.size, mesh.sentPublicPosts.size)
        }

    @Test
    fun theBridgedComposerCapsTheDraftAndStillOwesTheDisclosure() =
        runTest {
            // The room's own two composer rules, both settled before a word is typed: the hard byte cap a
            // Meshtastic frame imposes, and the first-use sheet. No author name is among them any more
            // (ADR 2026-09.9469) — the words are the whole line, so the whole line is the budget.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            // The facts' default is a board that signs, so the cap is the signable one.
            assertEquals(PublicPostPolicy.MAX_SIGNED_TEXT_BYTES, vm.state.value.publicPostBudget)
            assertTrue("the disclosure has not been accepted yet", vm.state.value.needsPublicConsent)
        }

    @Test
    fun theBridgedComposerKeepsTheClientCapWhenTheRadioDoesNotSign() =
        runTest {
            // A pre-2.8 board signs nothing, so there is no cliff to stay under: the 200-byte client
            // convention is the cap, as it always was.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, signs = false)
            advanceUntilIdle()
            assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, vm.state.value.publicPostBudget)

            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, signs = true)
            advanceUntilIdle()
            assertEquals(PublicPostPolicy.MAX_SIGNED_TEXT_BYTES, vm.state.value.publicPostBudget)
        }

    @Test
    fun aSignedContactPostIsMarkedVerifiedInItsRow() =
        runTest {
            // The one state the room vouches for: the post's signature verified under the key the contact's
            // own profile names. The row says so; the screen draws the shield off it.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", avatarHash = "sam-avatar"))
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "hi",
                        id = "mp-signed",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = "Knit 1a2b",
                        originPeerId = "sam",
                        originSigned = MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                    ),
                )
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "mp-signed" }
            assertEquals("Sam", row.senderName)
            assertEquals(MeshSignature.CONTACT, row.origin?.signed)
            assertEquals(true, row.origin?.verified)
            assertEquals("sam", row.origin?.peerId)
        }

    @Test
    fun aMismatchedSignatureRowIsAStrangerWhateverTheNodeNumberClaims() =
        runTest {
            // Ingest attributed nobody (the signature failed the claimant's key), so the row is a stranger's
            // and says so — the board's name for the speaker, never the contact's.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", avatarHash = "sam-avatar"))
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "hi",
                        id = "mp-mismatch",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = "Knit 1a2b",
                        originPeerId = null,
                        originSigned = MessageEntity.ORIGIN_SIGNATURE_MISMATCH,
                    ),
                )
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "mp-mismatch" }
            assertEquals("Knit 1a2b", row.senderName)
            assertNull("no face for a stranger", row.avatarHash)
            assertEquals(MeshSignature.MISMATCH, row.origin?.signed)
            assertEquals(false, row.origin?.verified)
        }

    @Test
    fun aBoardSignedStrangerStaysAStranger() =
        runTest {
            // Our own board vouched for the number, which says the same radio keeps using it — not who
            // holds it. No contact, no shield; the provenance line gets a word.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "hi",
                        id = "mp-board",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = "Bob",
                        originSigned = MessageEntity.ORIGIN_SIGNED_BY_BOARD,
                    ),
                )
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .first { it.id == "mp-board" }
            assertEquals("Bob", row.senderName)
            assertNull(row.origin?.peerId)
            assertEquals(MeshSignature.BOARD, row.origin?.signed)
            assertEquals(false, row.origin?.verified)
        }

    @Test
    fun theFirstBridgedPostRaisesTheDisclosureAndSendsNothing() =
        runTest {
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true) // the gate runs before the disclosure
            advanceUntilIdle()

            vm.send("hello mesh")
            advanceUntilIdle()

            assertTrue(vm.showPublicConsent.value)
            assertTrue("nothing may reach the air before the disclosure is accepted", mesh.sentPublicPosts.isEmpty())
        }

    @Test
    fun acceptingTheDisclosureRecordsItWithoutSendingTheDraft() =
        runTest {
            // The sheet's button says "Post" about the room, not about these words. Sending here would put
            // them on a public band in the same tap as the decision to allow it.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            advanceUntilIdle()
            vm.send("hello mesh")
            advanceUntilIdle()

            vm.acceptPublicConsent()
            advanceUntilIdle()

            assertFalse(vm.showPublicConsent.value)
            coVerify(exactly = 1) { settings.acceptMeshtasticPostConsent() }
            assertTrue("accepting records the decision, it does not send the draft", mesh.sentPublicPosts.isEmpty())
        }

    @Test
    fun aConsentedBridgedPostTakesItsOwnOriginationPathNotSendChat() =
        runTest {
            // sendChat reads its destination off recipientId/group, so a room id handed to it would be minted
            // as a DM addressed to a peer that does not exist. Hence a separate entry point.
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            advanceUntilIdle()

            vm.send("hello mesh")
            advanceUntilIdle()

            assertFalse(vm.showPublicConsent.value)
            assertEquals(listOf("hello mesh"), mesh.sentPublicPosts)
            assertTrue("never sendChat, which would mint it as a DM to a peer that does not exist", mesh.sentChats.isEmpty())
        }

    @Test
    fun theBridgedRoomNeverSendsATypingCue() =
        runTest {
            // There is nobody on the far side to show one to, and `sendTyping` has no arm for this room —
            // an unguarded cue from here would arrive on every phone in the pocket as a *Nearby* one.
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            vm.onChatForeground()
            advanceUntilIdle()

            vm.onUserTyping("hi")
            advanceUntilIdle()

            assertTrue(mesh.sentTyping.isEmpty())
        }

    @Test
    fun aBridgedPostNeverShowsALoRaCongestionNotice() =
        runTest {
            // A spent window is answered per send, as a refusal the composer shows — never as a standing
            // notice about delayed delivery.
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, airtimeSpent = true)
            messagesFlow.value =
                listOf(msg(senderId = "sam", id = "mp4", sentAt = 100, conversationId = Conversations.MESHTASTIC, originNode = 1))
            advanceUntilIdle()

            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun groupRowsCarryDeliveredCountsExcludingSelf() =
        runTest {
            // The bubble's ✓✓ flips on the first member's ack, so the row carries the ratio the glyph
            // can't. The denominator excludes us — we never ack our own message (the details screen's
            // rule, kept identical so the two screens can't disagree).
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya", "theo"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", body = "mine", id = "g0", sentAt = 100, conversationId = GROUP),
                    msg(senderId = "sam", body = "theirs", id = "g1", sentAt = 200, conversationId = GROUP),
                )
            deliveredCountsFlow.value = mapOf("g0" to 2)
            advanceUntilIdle()

            val mine =
                vm.state.value.rows
                    .first { it.id == "g0" }
            assertEquals(2, mine.deliveredCount)
            assertEquals(3, mine.recipientTotal)
            // Someone else's message has no "who has it" answer to give.
            val theirs =
                vm.state.value.rows
                    .first { it.id == "g1" }
            assertEquals(0, theirs.deliveredCount)
            assertEquals(0, theirs.recipientTotal)
        }

    @Test
    fun aGroupHeaderCarriesTheOtherMembersFacesByNodeId() =
        runTest {
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", avatarHash = "sam-avatar"), peer("priya", name = "Priya"))
            // Roster order is sam-first; the header's cells are by node id, and never include us.
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya"))
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.isGroup)
            assertEquals(listOf(GroupFace("priya", "Priya", null), GroupFace("sam", "Sam", "sam-avatar")), state.groupFaces)
        }

    @Test
    fun groupRowsCarryTheAuthorsLocalVerification() =
        runTest {
            // A group header names the group, so the only place the reader learns which of several senders
            // they have checked a safety number with is the bubble itself. Ours never claims it.
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya"))
            peersFlow.value = listOf(peer("sam", name = "Sam", verified = true), peer("priya", name = "Priya"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", body = "mine", id = "g0", sentAt = 100, conversationId = GROUP),
                    msg(senderId = "sam", body = "theirs", id = "g1", sentAt = 200, conversationId = GROUP),
                    msg(senderId = "priya", body = "hers", id = "g2", sentAt = 300, conversationId = GROUP),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertTrue(rows.getValue("g1").senderVerified)
            assertFalse("an unverified member says nothing", rows.getValue("g2").senderVerified)
            assertFalse("our own bubble never vouches for us", rows.getValue("g0").senderVerified)
        }

    @Test
    fun roomAndDmRowsNeverCarryTheBadge() =
        runTest {
            // The room draws names for whoever is in range and a DM says it once in its header, so neither
            // repeats it per bubble — the flag is the group's alone.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", verified = true))
            messagesFlow.value = listOf(msg(senderId = "sam", body = "hi", id = "m0", sentAt = 100))
            advanceUntilIdle()
            assertFalse(
                vm.state.value.rows
                    .single()
                    .senderVerified,
            )

            stubDm("sam")
            val dm = vm("sam")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { dm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "sam", body = "hi", id = "d0", sentAt = 100, conversationId = "sam"))
            advanceUntilIdle()
            val row =
                dm.state.value.rows
                    .single()
            assertFalse("the DM header carries it instead", row.senderVerified)
            assertTrue(dm.state.value.verified)
        }

    @Test
    fun roomRowsCarryNoDeliveredCounts() =
        runTest {
            // The broadcast room has no roster, so there is no denominator and the tick keeps its plain
            // wording — deliveryLabel falls back whenever total is absent.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(msg(senderId = "me", body = "hi", id = "m0", sentAt = 100, conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .single()
            assertEquals(0, row.deliveredCount)
            assertEquals(0, row.recipientTotal)
        }

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

    /** Two senders who both call themselves Bob are told apart by their alias (ADR 058); a unique name is untouched. */
    @Test
    fun sameNamedSendersAreLabelledWithTheirAliasAndTheQuoteSnapshotStaysPlain() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "bob"), peer("carol", name = "Carol"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", body = "yo", id = "m1", sentAt = 100, conversationId = Conversations.NEARBY),
                    msg(senderId = "bob2", body = "also yo", id = "m2", sentAt = 200, conversationId = Conversations.NEARBY),
                    msg(senderId = "carol", body = "hi", id = "m3", sentAt = 300, conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals("Bob (${Alias.aliasFor("bob")})", rows.getValue("m1").senderName)
            assertEquals(Alias.aliasFor("bob"), rows.getValue("m1").senderDiscriminator)
            assertEquals("Bob", rows.getValue("m1").senderPlainName) // the reply-quote snapshot never carries the suffix
            assertEquals("bob (${Alias.aliasFor("bob2")})", rows.getValue("m2").senderName)
            assertEquals("Carol", rows.getValue("m3").senderName)
            assertNull(rows.getValue("m3").senderDiscriminator)
            assertEquals("Carol", rows.getValue("m3").senderPlainName)
            // The mention picker inserts the same label, and always knows the alias.
            val candidates =
                vm.state.value.mentionCandidates
                    .associateBy { it.nodeId }
            assertEquals("Bob (${Alias.aliasFor("bob")})", candidates.getValue("bob").displayName)
            assertEquals(Alias.aliasFor("bob"), candidates.getValue("bob").discriminator)
            assertEquals("Carol", candidates.getValue("carol").displayName)
            assertNull(candidates.getValue("carol").discriminator)
            assertEquals(Alias.aliasFor("carol"), candidates.getValue("carol").alias)
        }

    @Test
    fun aDmTitleCarriesTheDiscriminatorWhenAnotherPeerSharesTheName() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "Bob"))
            advanceUntilIdle()

            assertEquals("Bob (${Alias.aliasFor("bob")})", vm.state.value.title)
            assertEquals(Alias.aliasFor("bob"), vm.state.value.titleDiscriminator)

            peersFlow.value = listOf(peer("bob", name = "Bob"), peer("bob2", name = "Robert"))
            advanceUntilIdle()
            assertEquals("Bob", vm.state.value.title)
            assertNull(vm.state.value.titleDiscriminator)
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
    fun recentReactionsExposeTheNewestSix() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.recentReactions.collect {} }
            recentsFlow.value = (1..RecentReactions.KEPT).map { "e$it" }
            advanceUntilIdle()

            assertEquals((1..RecentReactions.SHOWN).map { "e$it" }, vm.recentReactions.value)
        }

    @Test
    fun reactingWithANewEmojiSendsItAndFrontsTheRecents() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            advanceUntilIdle()

            vm.react("m1", "🦄")
            advanceUntilIdle()

            assertEquals(listOf("m1" to "🦄"), mesh.sentReactions)
            coVerify(exactly = 1) { settings.recordReaction("🦄") }
        }

    @Test
    fun retractingYourOwnReactionSendsButLeavesTheRecentsAlone() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY))
            reactionsFlow.value = listOf(reaction("m1", "me", "👍"))
            advanceUntilIdle()

            vm.react("m1", "👍") // the chip you already own: a toggle-off, not a choice
            vm.react("m1", "❤️") // a replace: a fresh choice
            advanceUntilIdle()

            assertEquals(listOf("m1" to "👍", "m1" to "❤️"), mesh.sentReactions)
            coVerify(exactly = 0) { settings.recordReaction("👍") }
            coVerify(exactly = 1) { settings.recordReaction("❤️") }
        }

    @Test
    fun deletingAMessageDropsItsRowsAndGcsItsBlobWithoutTouchingTheMesh() =
        runTest {
            // A local delete, and only local: the row, its reactions and its per-recipient ticks go, the blob
            // goes if nothing else references it — resolved from the row *before* it is gone, since the hash
            // is only readable there — and nothing is sent. A row with no attachment asks the GC about null,
            // which it treats as a no-op.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1"),
                    msg(senderId = "me", id = "m2", conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()

            vm.deleteMessage("m1")
            vm.deleteMessage("m2")
            advanceUntilIdle()

            coVerify(exactly = 1) { messages.delete("m1") }
            coVerify(exactly = 1) { reactions.deleteForMessage("m1") }
            coVerify(exactly = 1) { receipts.deleteForMessage("m1") }
            coVerify(exactly = 1) { blobs.deleteIfUnreferenced("h1") }
            coVerify(exactly = 1) { messages.delete("m2") }
            coVerify(exactly = 1) { blobs.deleteIfUnreferenced(null) }
            assertEquals(listOf(R.string.chat_message_deleted, R.string.chat_message_deleted), events)
            assertTrue("nothing about a local delete reaches the mesh", mesh.sentChats.isEmpty() && mesh.sentReactions.isEmpty())
        }

    @Test
    fun blobSizesAreAskedForTheWindowsAttachmentsAndTheStagedOneOnly() =
        runTest {
            val ingested = AttachmentStore.Ingested(hash = "staged", mime = "image/jpeg")
            coEvery { attachments.ingest(any<Uri>()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.stagedAttachmentRelay.collect {} }
            advanceUntilIdle()
            // A thread with nothing to size asks for nothing — the repository answers that without a query.
            verify { blobs.observeSizes(emptySet()) }
            verify(exactly = 0) { blobs.observeSizes(match { it.isNotEmpty() }) }

            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1"),
                    msg(senderId = "bob", id = "m2", conversationId = Conversations.NEARBY),
                )
            advanceUntilIdle()
            verify { blobs.observeSizes(setOf("h1")) }

            vm.attach(Uri.parse("content://images/1"))
            advanceUntilIdle()
            // The composer's staged attachment rides the same subscription as the rows: one read, not two.
            verify { blobs.observeSizes(setOf("h1", "staged")) }
            verify(exactly = 0) { blobs.observeSizes(match { "h1" !in it && it.isNotEmpty() }) }
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

            sizesFlow.value = mapOf("h1" to 1_024)
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
    fun theStoredDraftIsHandedOverOnceAndOnlyThenAreEditsKept() =
        runTest {
            coEvery { drafts.load(Conversations.NEARBY) } returns "half a sentence"
            val vm = vm()

            // The composer reports its empty field the moment it composes — before the stored draft has been
            // taken. Persisting that would erase the very draft the screen is about to put back.
            vm.onDraftChanged("")
            advanceUntilIdle()
            verify(exactly = 0) { drafts.save(any(), any()) }

            assertEquals("half a sentence", vm.consumeRestoredDraft())
            // A rotation re-runs the screen's restore effect; a draft since cleared must not come back.
            assertEquals("", vm.consumeRestoredDraft())

            // The restore itself comes back through the composer's collector, indistinguishable from a
            // keystroke. It is not one: the row already holds this text, and saving it again would re-stamp
            // `updatedAt` and float "Draft: …" back over a message that landed after it.
            vm.onDraftChanged("half a sentence")
            verify(exactly = 0) { drafts.save(any(), any()) }

            vm.onDraftChanged("half a sentence more")
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, "half a sentence more") }
            // A recomposition (rotation, a screen popped off the chat) re-reports the unchanged text.
            vm.onDraftChanged("half a sentence more")
            verify(exactly = 1) { drafts.save(any(), any()) }
        }

    @Test
    fun aRestoredDraftIsNotATypingCue() =
        runTest {
            coEvery { drafts.load(Conversations.NEARBY) } returns "half a sentence"
            val vm = vm()
            vm.onChatForeground()
            assertEquals("half a sentence", vm.consumeRestoredDraft())

            // The screen's typing collector reports the restore the way it reports a typed character — on a
            // warm device the collectors are already running when the read lands. The peer must not see
            // "typing…" with nothing to follow.
            vm.onUserTyping("half a sentence")
            advanceUntilIdle()
            assertTrue(mesh.sentTyping.isEmpty())

            // The first report past the restored text is the user.
            vm.onUserTyping("half a sentence!")
            advanceUntilIdle()
            assertEquals(listOf(Conversations.NEARBY), mesh.sentTyping)
        }

    @Test
    fun withNoStoredDraftTheComposersEmptyReportIsNotASaveAndTheFirstKeystrokeIs() =
        runTest {
            val vm = vm() // the relaxed repository loads ""
            assertEquals("", vm.consumeRestoredDraft())

            // The composer's initial snapshot, now that the (empty) draft has been taken: nothing to keep.
            vm.onDraftChanged("")
            verify(exactly = 0) { drafts.save(any(), any()) }

            vm.onDraftChanged("h")
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, "h") }

            // After an accepted send the row is dropped directly; the field's own empty report that follows
            // matches what the row now holds, and the next keystroke starts a fresh draft.
            vm.send("h")
            advanceUntilIdle()
            verify { drafts.clear(Conversations.NEARBY) }
            vm.onDraftChanged("")
            verify(exactly = 1) { drafts.save(any(), any()) }
            vm.onDraftChanged("again")
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, "again") }
        }

    @Test
    fun anAcceptedSendDropsTheStoredDraftAndABlockedOneKeepsIt() =
        runTest {
            mesh.sendChatResult = false // moderator flags the text
            val vm = vm()

            vm.send("bad")
            advanceUntilIdle()
            verify(exactly = 0) { drafts.clear(any()) } // the user still has it to edit

            mesh.sendChatResult = true
            vm.onInputCleared()
            vm.send("fine")
            advanceUntilIdle()
            // Dropped here, not left to the cleared field's own report: a user who sends and immediately
            // leaves never gives us one.
            verify { drafts.clear(Conversations.NEARBY) }
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
    fun isSendingTracksTheSendLifecycleForTheSpinner() =
        runTest {
            val vm = vm()
            assertFalse("idle before any send", vm.isSending.value)

            vm.send("hi")
            advanceUntilIdle()
            // An accepted send holds the signal (like the double-submit guard it backs) until the screen
            // reports the field cleared, so the send-button spinner stays put across the
            // sendChat → clearInput → clearText hop rather than blinking off mid-send.
            assertTrue("send in flight → spinner shown", vm.isSending.value)

            vm.onInputCleared()
            assertFalse("field cleared → spinner cleared", vm.isSending.value)
        }

    @Test
    fun isSendingClearsWhenASendIsBlocked() =
        runTest {
            mesh.sendChatResult = false // moderator flags the text
            val vm = vm()

            vm.send("bad")
            advanceUntilIdle()
            assertFalse("a blocked send releases the spinner signal", vm.isSending.value)
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

    @Test
    fun capturingACleanPhotoStagesItForSending() =
        runTest {
            val jpeg = byteArrayOf(1, 2, 3)
            val ingested = AttachmentStore.Ingested(hash = "h3", mime = "image/jpeg")
            coEvery {
                attachments.ingest(jpeg, "image/jpeg")
            } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm()

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }

    @Test
    fun capturingAFlaggedPhotoInTheRoomBlocksItInsteadOfStaging() =
        runTest {
            val jpeg = byteArrayOf(4, 5, 6)
            val ingested = AttachmentStore.Ingested(hash = "h4", mime = "image/jpeg")
            coEvery {
                attachments.ingest(jpeg, "image/jpeg")
            } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertNull("flagged photo is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h4") }
        }

    @Test
    fun theBridgedRoomTakesNoAttachmentByAnyRoute() =
        runTest {
            // `sendPublicPost` takes a string, so a staged picture would sit in the composer and then vanish
            // at send — under the author's name, with nothing to say it never left. The screen offers no
            // picker and tells the keyboard it takes no images, so what actually reaches this is the share
            // sheet, which lists the room because sharing *text* into it is a fair thing to want.
            val uri = Uri.parse("content://images/9")
            val ingested = AttachmentStore.Ingested(hash = "h9", mime = "image/gif")
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm(Conversations.MESHTASTIC)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attach(uri)
            advanceUntilIdle()

            assertNull("nothing is staged where nothing can be sent", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h9") }
            assertTrue("and the refusal says why", events.contains(R.string.chat_mesh_text_only))
        }

    /** A failed capture has to say so: unlike a pick, the shot exists nowhere else to try again from. */
    @Test
    fun aFailedCaptureSurfacesAnErrorWhereAFailedPickStaysSilent() =
        runTest {
            val jpeg = byteArrayOf(7, 8, 9)
            val uri = Uri.parse("content://images/3")
            coEvery { attachments.ingest(jpeg, "image/jpeg") } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            coEvery { attachments.ingest(uri) } returns AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attach(uri)
            advanceUntilIdle()
            assertTrue("a failed pick stays silent", events.isEmpty())

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertTrue(events.contains(R.string.chat_image_capture_failed))
            assertNull(vm.pendingAttachment.value)
        }

    /**
     * The bug in knit/knit-next#31: a DM/group attachment's stored blob is `iv || ciphertext`, so exporting
     * it verbatim wrote ciphertext into the gallery under an image mime and still toasted success. The saved
     * bytes must be the same plaintext the bubble renders.
     */
    @Test
    fun savingAnEncryptedAttachmentExportsThePlaintext() =
        runTest {
            val plain = byteArrayOf(1, 2, 3, 4, 5)
            val sealed = AttachmentCrypto.seal(plain)
            coEvery { blobs.bytes("ct") } returns sealed.blob
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.saveAttachment("ct", b64(sealed.key), "image/webp")
            advanceUntilIdle()

            val exported = slot<ByteArray>()
            coVerify { gallerySaver.saveToPictures(capture(exported), "ct", "image/webp") }
            assertArrayEquals("saved the decrypted image, not the stored ciphertext", plain, exported.captured)
            assertTrue(events.contains(R.string.chat_image_saved))
        }

    /** A key-less blob (a Nearby-room attachment) is already plaintext and goes out untouched. */
    @Test
    fun savingAPlaintextAttachmentExportsTheStoredBytes() =
        runTest {
            val jpeg = byteArrayOf(9, 8, 7)
            coEvery { blobs.bytes("h") } returns jpeg
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()

            vm.saveAttachment("h", null, "image/jpeg")
            advanceUntilIdle()

            val exported = slot<ByteArray>()
            coVerify { gallerySaver.saveToPictures(capture(exported), "h", "image/jpeg") }
            assertArrayEquals(jpeg, exported.captured)
        }

    /** A key that doesn't open the blob must fail the save loudly rather than export the ciphertext. */
    @Test
    fun savingWithAKeyThatDoesNotOpenTheBlobFailsInsteadOfExportingCiphertext() =
        runTest {
            val sealed = AttachmentCrypto.seal(byteArrayOf(1, 2, 3, 4, 5))
            coEvery { blobs.bytes("ct") } returns sealed.blob
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            val wrongKey = ByteArray(32) // a valid AES-256 length, but not the key it was sealed under
            vm.saveAttachment("ct", b64(wrongKey), "image/webp")
            advanceUntilIdle()

            coVerify(exactly = 0) { gallerySaver.saveToPictures(any(), any(), any()) }
            assertTrue(events.contains(R.string.chat_image_save_failed))
        }

    /**
     * ADR 2026-09.7ad3: a received file the user saved is handed back to the screen to open, carrying the
     * decrypted bytes into the picked document on the way.
     */
    @Test
    fun savingAFileWritesThePlaintextAndAsksToOpenIt() =
        runTest {
            val plain = "%PDF-1.7".toByteArray()
            val sealed = AttachmentCrypto.seal(plain)
            coEvery { blobs.bytes("ct") } returns sealed.blob
            val dest = Uri.parse("content://docs/document/report.pdf")
            val written = ByteArrayOutputStream()
            shadowOf(context.contentResolver).registerOutputStream(dest, written)
            val vm = vm()
            // Subscribed before the act: the save resumes off withContext(IO) on the IO thread (Main is
            // unconfined), so its tryEmit can land before a late first() subscribes and be dropped.
            val opened = async(UnconfinedTestDispatcher(testScheduler)) { vm.savedFiles.first() }

            vm.saveAttachmentTo(PendingSave("ct", b64(sealed.key), "report.pdf", "application/pdf"), dest)
            val saved = opened.await()

            assertEquals(SavedFile(dest, "application/pdf"), saved)
            assertArrayEquals(plain, written.toByteArray())
            coVerify { blobs.rememberSavedCopy("ct", dest.toString(), any()) }
            assertTrue(
                "a read grant that outlives the process",
                context.contentResolver.persistedUriPermissions.any { it.uri == dest && it.isReadPermission },
            )
        }

    /** The second tap on a saved file opens the copy it was saved to and never asks again (ADR 2026-09.7ad3). */
    @Test
    fun aFileSavedBeforeOpensFromItsCopyWithoutAsking() =
        runTest {
            val copy = Uri.parse("content://docs/document/report.pdf")
            coEvery { blobs.savedCopy("ct") } returns copy.toString()
            Robolectric.buildContentProvider(PresentDocuments::class.java).create("docs")
            val vm = vm()
            val asked = mutableListOf<PendingSave>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.saveNeeded.collect { asked += it } }
            val opened = async(UnconfinedTestDispatcher(testScheduler)) { vm.savedFiles.first() }

            vm.openAttachment(PendingSave("ct", "k", "report.pdf", "application/pdf"))

            assertEquals(SavedFile(copy, "application/pdf"), opened.await())
            assertTrue(asked.isEmpty())
            coVerify(exactly = 0) { blobs.forgetSavedCopy(any()) }
        }

    /** A copy that was moved or deleted since is forgotten, and the tap goes back to the picker. */
    @Test
    fun aSavedCopyThatIsGoneIsForgottenAndAskedForAgain() =
        runTest {
            val gone = Uri.parse("content://docs/document/deleted.pdf") // no cursor: the provider has no such row
            coEvery { blobs.savedCopy("ct") } returns gone.toString()
            val vm = vm()
            val pending = PendingSave("ct", "k", "deleted.pdf", "application/pdf")
            val asked = async(UnconfinedTestDispatcher(testScheduler)) { vm.saveNeeded.first() }

            vm.openAttachment(pending)

            assertEquals(pending, asked.await())
            coVerify { blobs.forgetSavedCopy("ct") }
        }

    /** A file never saved goes straight to the picker. */
    @Test
    fun aFileNeverSavedAsksWhereToSaveIt() =
        runTest {
            coEvery { blobs.savedCopy("ct") } returns null
            val vm = vm()
            val asked = mutableListOf<PendingSave>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.saveNeeded.collect { asked += it } }
            val pending = PendingSave("ct", "k", "report.pdf", "application/pdf")

            vm.openAttachment(pending) // no suspension on this path: the ask lands before a late collector could subscribe

            assertEquals(listOf(pending), asked)
            coVerify(exactly = 0) { blobs.forgetSavedCopy(any()) }
        }

    /** A documents provider that still has every document it is asked about. */
    class PresentDocuments : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf("document_id")).apply { addRow(arrayOf<Any>(uri.lastPathSegment!!)) }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    /** An archive is saved but never opened: nothing on the device can look inside it (ADR 2026-09.7ad3). */
    @Test
    fun savingARiskyFileDoesNotAskToOpenIt() =
        runTest {
            coEvery { blobs.bytes("h") } returns byteArrayOf(0x50, 0x4B, 3, 4)
            val dest = Uri.parse("content://docs/document/stuff.zip")
            shadowOf(context.contentResolver).registerOutputStream(dest, ByteArrayOutputStream())
            val vm = vm()
            val opened = mutableListOf<SavedFile>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.savedFiles.collect { opened += it } }
            val saved = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveAttachmentTo(PendingSave("h", null, "stuff.zip", "application/zip"), dest)
            assertEquals(R.string.chat_file_saved, saved.await())

            assertTrue(opened.isEmpty())
            coVerify(exactly = 0) { blobs.rememberSavedCopy(any(), any(), any()) }
        }

    /**
     * ADR 035: the `blobs` row's mime describes the *ciphertext* bytes and is only whatever named the blob
     * when it landed (a fetcher default on the spool path). The message row's mime is the plaintext's own
     * type and wins; the blob row is the fallback for a row that names none.
     */
    @Test
    fun theMessageRowsMimeWinsOverTheBlobRows() =
        runTest {
            coEvery { blobs.bytes("h") } returns byteArrayOf(1)
            coEvery { blobs.mimeFor("h") } returns "image/jpeg"
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()

            vm.saveAttachment("h", null, "image/webp")
            advanceUntilIdle()

            coVerify { gallerySaver.saveToPictures(any(), "h", "image/webp") }

            vm.saveAttachment("h", null, null) // no row mime: fall back to what the blob calls itself
            advanceUntilIdle()

            coVerify { gallerySaver.saveToPictures(any(), "h", "image/jpeg") }
        }

    @Test
    fun aDmReadsLoraOnlyWhenOnlyTheBoardHearsThePeer() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraPlane.Live, vm.state.value.loraPlane)
            assertEquals(LoraReach.LoraOnly, vm.state.value.loraReach)
            assertEquals(LoraCarry.Dm, vm.state.value.loraCarry)

            // Bluetooth hears them too: the notice goes quiet, the draft still rides LoRa.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa, TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.Dm, vm.state.value.loraCarry)
        }

    @Test
    fun theRoomSaysWhenLoraAirtimeIsSpentAndSomeoneIsOnlyOnTheBoard() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.RoomSaturated, vm.state.value.loraReach)
            assertEquals(LoraCarry.Room, vm.state.value.loraCarry)

            // Bluetooth reaches her too, so the spent window delays nobody the room can name.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa, TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)

            // She goes back behind the board, then the window frees up: the room stops complaining but the
            // draft still rides LoRa.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            advanceUntilIdle()
            assertEquals(LoraReach.RoomSaturated, vm.state.value.loraReach)
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.Room, vm.state.value.loraCarry)
        }

    @Test
    fun aDmIgnoresAnotherPeerSittingBehindTheBoard() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
            // Bo is LoRa-only, which is what the *room* would speak about; this thread is Ana's, and
            // Bluetooth carries her — the room's existential rule must not leak into a DM.
            mesh.peerTransports.value =
                mapOf("ana" to setOf(TransportKind.Bluetooth), "bo" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun aGroupSaysItsMessagesDoNotTravelOverLoraWhileAMemberIsBehindTheBoard() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya"))
            // Priya is in the group and only the board hears her.
            mesh.peerTransports.value = mapOf("priya" to setOf(TransportKind.LoRa))
            advanceUntilIdle()

            assertEquals(LoraReach.GroupUnsupported, vm.state.value.loraReach)
            // No composer hint: nothing in a group rides the plane, so there is no budget to overrun.
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)

            // She comes back onto a phone radio and the notice retires itself.
            mesh.peerTransports.value = mapOf("priya" to setOf(TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun aGroupIgnoresLoraOnlyIdsThatAreNotOnItsRoster() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true)
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam"))
            // A LoRa-only stranger says nothing about whether *this group's* messages land; and our own
            // id is not somebody we can fail to deliver to, even were it ever to appear in the map.
            mesh.peerTransports.value =
                mapOf("theo" to setOf(TransportKind.LoRa), "me" to setOf(TransportKind.LoRa))
            advanceUntilIdle()

            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
        }

    @Test
    fun loraCarryFollowsThePlaneAndTheDmSwitch() =
        runTest {
            stubDm("ana")
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = false)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(LoraReach.LoraOnlyDmsOff, vm.state.value.loraReach)
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)

            loraFactsFlow.value = LoraFacts(LoraPlane.Down, dms = true)
            advanceUntilIdle()
            assertEquals(LoraPlane.Down, vm.state.value.loraPlane)
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)
            assertEquals(LoraCarry.None, vm.state.value.loraCarry)
        }

    @Test
    fun theRoomsRelayNoticeGoesQuietOnceDismissedAndStaysThatWay() =
        runTest {
            // Live plane, room open: the structural "never over the Internet" notice is up.
            relayFactsFlow.value = RelayFacts(enabled = true, configured = 1, active = 1, connected = 1)
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(RelayReach.Room, vm.state.value.relayReach)

            vm.dismissRelayNotice()
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.dismissRelayRoomNotice() }

            // The write is what makes it stick; the mock's flow is what the VM reads back.
            relayRoomNoticeDismissedFlow.value = true
            advanceUntilIdle()
            assertEquals(RelayReach.Silent, vm.state.value.relayReach)

            // Sticky across a relaunch: a fresh ViewModel over the same stored flag stays quiet.
            val relaunched = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { relaunched.state.collect {} }
            advanceUntilIdle()
            assertEquals(RelayReach.Silent, relaunched.state.value.relayReach)
        }

    @Test
    fun aMissingAttachmentsWaitLineFollowsTheConnectedRelays() =
        runTest {
            // Work item 50: a DM photo whose bytes are not here. While a connected relay covering the
            // thread carries photos the line says the Internet can bring it; when the only connected relay
            // is frames-only it says so; once the bytes land there is nothing to wait on.
            stubDm("ana")
            relayFactsFlow.value =
                RelayFacts(enabled = true, configured = 2, active = 2, connected = 2, coveredLabels = setOf("ana"), maxAttachBytes = 1)
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "ana", id = "m1", conversationId = "ana", attachmentHash = "h1"))
            advanceUntilIdle()
            assertEquals(
                AttachmentWait.Relay,
                vm.state.value.rows
                    .single()
                    .attachmentWait,
            )

            relayFactsFlow.value =
                RelayFacts(enabled = true, configured = 2, active = 2, connected = 1, coveredLabels = setOf("ana"), maxAttachBytes = null)
            advanceUntilIdle()
            assertEquals(
                AttachmentWait.RelayFramesOnly,
                vm.state.value.rows
                    .single()
                    .attachmentWait,
            )

            sizesFlow.value = mapOf("h1" to 1_024)
            advanceUntilIdle()
            assertEquals(
                AttachmentWait.Nearby,
                vm.state.value.rows
                    .single()
                    .attachmentWait,
            )
        }

    @Test
    fun aDismissedRoomNoticeDoesNotSilenceAnUncoveredDm() =
        runTest {
            // The flag is device-wide, so the one thing it must not do is hide the *other* notice — a
            // pending thread's line clears itself and carries different information.
            stubDm("ana")
            relayRoomNoticeDismissedFlow.value = true
            relayFactsFlow.value = RelayFacts(enabled = true, configured = 1, active = 1, connected = 1)
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(RelayReach.Pending, vm.state.value.relayReach)
        }

    private companion object {
        const val GROUP = "g-trailhead"
    }

    /**
     * The composer's file item (ADR 2026-09.qq2r). The room is the only thing that hides it: nothing on the
     * device can screen a file, and the room floods unencrypted to everyone in range.
     *
     * The recipient's `CAP_FILES` bit deliberately does **not** hide it. That bit arrives only in a profile
     * frame from a peer already running a build that has it, so gating visibility on it made the feature
     * invisible with nothing to explain why — on a device pair mid-rollout, the item simply never appeared.
     * It is enforced in [ChatViewModel.attachFile] instead, where it can say so.
     */
    @Test
    fun onlyTheRoomHidesTheFileItem() =
        runTest {
            val room = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { room.state.collect {} }
            peersFlow.value = listOf(peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES))
            advanceUntilIdle()
            assertFalse(room.state.value.canSendFile)

            // Offered toward a peer whose capabilities we have not learned yet — the case the old gate hid.
            stubDm("bob")
            val dm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { dm.state.collect {} }
            peersFlow.value = listOf(peer("bob", "Bob", capabilities = null))
            advanceUntilIdle()
            assertTrue(dm.state.value.canSendFile)
        }

    // --- direct file transfer (transfer/TransferManager) ---

    private fun transferRow(
        phase: TransferPhase,
        outgoing: Boolean = false,
    ) = TransferRecord(id = "t1", outgoing = outgoing, name = "clip.mp4", size = 2_000L, mime = "video/mp4", phase = phase)
        .toEntity(peerId = "bob", selfId = "me", sentAt = 5L)

    @Test
    fun aLargeFileIsOfferedOnlyToANearbyPeerCarryingTheCapability() =
        runTest {
            stubDm("bob")
            val uri = Uri.parse("content://docs/clip")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.CAP_E2E)
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.offerTransfer(uri)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_transfer_peer_too_old), events)

            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            vm.offerTransfer(uri)
            advanceUntilIdle()
            assertEquals(R.string.chat_transfer_peer_not_nearby, events.last())
            coVerify(exactly = 0) { transfers.offer(any(), any()) }

            mesh.neighbors.value = setOf(Peer("bob"))
            coEvery { transfers.offer("bob", uri.toString()) } returns OfferOutcome.Started("t1")
            vm.offerTransfer(uri)
            advanceUntilIdle()
            assertEquals("a started offer says nothing", 2, events.size)
            coVerify { transfers.offer("bob", uri.toString()) }

            coEvery { transfers.offer("bob", uri.toString()) } returns OfferOutcome.Refused(TransferRefusal.WifiOff)
            vm.offerTransfer(uri)
            advanceUntilIdle()
            assertEquals(R.string.chat_transfer_wifi_off, events.last())
        }

    @Test
    fun aLargeFileIsNeverOfferedFromTheRoomOrAGroup() =
        runTest {
            coEvery { groups.find(GROUP) } returns group(GROUP, members = listOf("me", "sam"))
            for (thread in listOf(Conversations.NEARBY, GROUP)) {
                val vm = vm(thread)
                val events = mutableListOf<Int>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
                vm.offerTransfer(Uri.parse("content://docs/clip"))
                advanceUntilIdle()
                assertEquals(thread, listOf(R.string.chat_transfer_needs_dm), events)
            }
            coVerify(exactly = 0) { transfers.offer(any(), any()) }
        }

    @Test
    fun aTransferRecordFoldsIntoACardWithLiveProgressAndReadsInterruptedWithoutIt() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            collectState(vm)
            messagesFlow.value = listOf(transferRow(TransferPhase.Transferring))
            transfersFlow.value =
                mapOf("t1" to TransferState("t1", "bob", false, "clip.mp4", 2_000L, "video/mp4", TransferPhase.Transferring, bytes = 500L))
            advanceUntilIdle()
            val live =
                checkNotNull(
                    vm.state.value.rows
                        .single()
                        .transfer,
                )
            assertEquals(TransferPhase.Transferring, live.phase)
            assertEquals(500L, live.bytes)
            assertFalse(live.interrupted)
            assertEquals(
                "a card row is a status-notice kind, not a bubble",
                MessageEntity.KIND_FILE_TRANSFER,
                vm.state.value.rows
                    .single()
                    .kind,
            )

            transfersFlow.value = emptyMap()
            advanceUntilIdle()
            assertTrue(
                "no live state behind a non-terminal record",
                checkNotNull(
                    vm.state.value.rows
                        .single()
                        .transfer,
                ).interrupted,
            )

            messagesFlow.value = listOf(transferRow(TransferPhase.Done))
            advanceUntilIdle()
            assertFalse(
                checkNotNull(
                    vm.state.value.rows
                        .single()
                        .transfer,
                ).interrupted,
            )
        }

    @Test
    fun answeringATransferGoesToTheManagerAndARefusalIsSaid() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            coEvery { transfers.accept("t1") } returns TransferRefusal.NoSpace
            transferConsentFlow.value = true

            vm.acceptTransfer("t1")
            vm.declineTransfer("t1")
            vm.cancelTransfer("t1")
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_transfer_no_space), events)
            coVerify { transfers.decline("t1") }
            coVerify { transfers.cancel("t1") }
        }

    /**
     * The disclosure is read once per device, on whichever side gets there first, and the tap it interrupted
     * finishes itself afterwards — the picker here, the accept in the case below.
     */
    @Test
    fun theFirstDirectTransferReadsTheDisclosureAndThenOpensThePicker() =
        runTest {
            stubDm("bob")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            mesh.neighbors.value = setOf(Peer("bob"))
            val vm = vm("bob")
            val pickers = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.transferPickerNeeded.collect { pickers += it } }

            vm.sendFileDirectly()
            advanceUntilIdle()
            assertEquals(TransferConsent(incoming = false), vm.showTransferConsent.value)
            assertTrue("nothing opens until it is read", pickers.isEmpty())

            vm.acceptTransferConsent()
            advanceUntilIdle()
            assertNull(vm.showTransferConsent.value)
            coVerify(exactly = 1) { settings.acceptDirectTransferConsent() }
            assertEquals(1, pickers.size)

            transferConsentFlow.value = true
            vm.sendFileDirectly()
            advanceUntilIdle()
            assertNull("read once, never again", vm.showTransferConsent.value)
            assertEquals(2, pickers.size)
        }

    @Test
    fun theDisclosureStandsInFrontOfAnAcceptAndCarriesItOnceRead() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")

            vm.acceptTransfer("t1")
            advanceUntilIdle()
            assertEquals(TransferConsent(incoming = true, transferId = "t1"), vm.showTransferConsent.value)
            coVerify(exactly = 0) { transfers.accept(any()) }

            vm.acceptTransferConsent()
            advanceUntilIdle()
            assertNull(vm.showTransferConsent.value)
            coVerify(exactly = 1) { transfers.accept("t1") }
        }

    @Test
    fun dismissingTheDisclosureRecordsNothingAndAnswersNothing() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")

            vm.acceptTransfer("t1")
            advanceUntilIdle()
            vm.dismissTransferConsent()
            advanceUntilIdle()

            assertNull(vm.showTransferConsent.value)
            coVerify(exactly = 0) { settings.acceptDirectTransferConsent() }
            coVerify(exactly = 0) { transfers.accept(any()) }
        }

    /** A thread that cannot take a file says so where the user tapped, rather than after a file is chosen. */
    @Test
    fun aThreadThatCannotTakeAFileIsRefusedBeforeTheDisclosure() =
        runTest {
            coEvery { groups.find(GROUP) } returns group(GROUP, members = listOf("me", "sam"))
            val vm = vm(GROUP)
            val events = mutableListOf<Int>()
            val pickers = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.transferPickerNeeded.collect { pickers += it } }

            vm.sendFileDirectly()
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_transfer_needs_dm), events)
            assertNull(vm.showTransferConsent.value)
            assertTrue(pickers.isEmpty())
        }

    /** One member on an old build is one person who cannot read the message, so the group send is refused. */
    @Test
    fun aGroupRefusesAFileUntilEveryOtherMemberCanReadOne() =
        runTest {
            val uri = Uri.parse("content://docs/report")
            coEvery { attachments.ingestFile(uri) } returns
                AttachmentStore.IngestResult.Success(
                    AttachmentStore.Ingested("h", "application/pdf", name = "r.pdf", sizeBytes = 10),
                    flagged = false,
                )
            coEvery { groups.find(GROUP) } returns group(GROUP, members = listOf("me", "sam", "priya"))
            coEvery { peers.find("sam") } returns peer("sam", "Sam", capabilities = Protocol.LOCAL_CAPABILITIES)
            coEvery { peers.find("priya") } returns peer("priya", "Priya", capabilities = Protocol.CAP_E2E)

            val vm = vm(GROUP)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            vm.attachFile(uri)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_file_peer_too_old), events)
            assertNull(vm.pendingAttachment.value)

            coEvery { peers.find("priya") } returns peer("priya", "Priya", capabilities = Protocol.LOCAL_CAPABILITIES)
            vm.attachFile(uri)
            advanceUntilIdle()
            assertEquals("our own missing bit must not count against the group", "r.pdf", vm.pendingAttachment.value?.name)
        }

    /**
     * The share sheet drains into the chat on its first composition, before the state combine has read a
     * peer row — so the gate has to be the ViewModel's, reading the repositories, not a check against the
     * rendered `canSendFile`. Without this the first shared file to a perfectly capable peer is refused.
     */
    @Test
    fun aSharedFileIsRefusedByTheRoomAndByAnOldPeerButNotByAnUnsettledState() =
        runTest {
            val uri = Uri.parse("content://docs/report")
            coEvery { attachments.ingestFile(uri) } returns
                AttachmentStore.IngestResult.Success(
                    AttachmentStore.Ingested("h", "application/pdf", name = "r.pdf", sizeBytes = 10),
                    flagged = false,
                )
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            coEvery { peers.find("old") } returns peer("old", "Old", capabilities = Protocol.CAP_E2E)

            val room = vm()
            val roomEvents = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { room.events.collect { roomEvents += it } }
            room.attachFile(uri)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_share_needs_chat), roomEvents)
            assertNull(room.pendingAttachment.value)

            stubDm("old")
            val stale = vm("old")
            val staleEvents = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stale.events.collect { staleEvents += it } }
            stale.attachFile(uri)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_file_peer_too_old), staleEvents)

            // Nobody has collected this one's state, so `canSendFile` is still its default false — and the
            // file is staged anyway, which is the whole point.
            stubDm("bob")
            val fresh = vm("bob")
            assertFalse(fresh.state.value.canSendFile)
            fresh.attachFile(uri)
            advanceUntilIdle()
            assertEquals("r.pdf", fresh.pendingAttachment.value?.name)
        }

    /**
     * A failed *pick* can stay silent — the picture is still in the picker (ADR 029) — but a file cannot.
     * An over-cap file is refused permanently and there is nothing to shrink, and a refused app package is a
     * decision rather than an accident; silence would read as the app doing nothing at all.
     */
    @Test
    fun eachFileRefusalSaysWhy() =
        runTest {
            val tooBig = Uri.parse("content://docs/big")
            val apk = Uri.parse("content://docs/app")
            coEvery { attachments.ingestFile(tooBig) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.TooLarge)
            coEvery { attachments.ingestFile(apk) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Installable)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attachFile(tooBig)
            advanceUntilIdle()
            vm.attachFile(apk)
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_file_too_large, R.string.chat_file_package_refused), events)
            assertNull(vm.pendingAttachment.value)
        }

    @Test
    fun anIngestedFileStagesWithItsNameAndSize() =
        runTest {
            val uri = Uri.parse("content://docs/report")
            val ingested =
                AttachmentStore.Ingested(
                    hash = "filehash",
                    mime = "application/pdf",
                    name = "report.pdf",
                    sizeBytes = 1_400_000,
                )
            coEvery { attachments.ingestFile(uri) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")

            vm.attachFile(uri)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }

    @Test
    fun aFileRowCarriesTheNameThatSelectsTheFileBubble() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", body = "", id = "f1", sentAt = 100, conversationId = "bob")
                        .copy(
                            attachmentHash = "filehash",
                            attachmentMime = "application/pdf",
                            attachmentName = "report.pdf",
                            attachmentSize = 1_400_000L,
                        ),
                )
            sizesFlow.value = mapOf("filehash" to 1_398_101)
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .single()
            assertEquals("report.pdf", row.attachmentName)
            assertEquals(1_400_000L, row.attachmentSize)
            assertEquals("the measured length supersedes the declared one once we hold the bytes", 1_398_101, row.attachmentBytes)
        }

    // ---- Link previews: the composer's card loop and the received card's row ----

    private val cardUrl = "https://example.com/a"
    private val cardBlob = LinkPreviewBlob(LinkPreviewBlob.VERSION, cardUrl, "Mesh networking", "How phones find each other")
    private val staged = AttachmentStore.Ingested("h-card", LinkPreviewBlob.MIME, link = cardBlob.toCard())

    private fun aCardIsAvailable() {
        linkPreviewsEnabledFlow.value = true
        coEvery { linkPreviews.fetchCard(cardUrl, any()) } returns LinkPreviewService.CardResult.Card(cardBlob)
        coEvery { attachments.ingestLinkPreview(cardBlob) } returns AttachmentStore.IngestResult.Success(staged, flagged = false)
    }

    @Test
    fun aLinkInTheDraftStagesItsCardOnceTheDraftRests() =
        runTest {
            aCardIsAvailable()
            val vm = vm()
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)
            assertFalse(vm.linkPreviewLoading.value)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = true) }
            // The card rides the send exactly like a photo: the whole Ingested, MIME and all.
            vm.send("look https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, mesh.sentChats.single().attachment)
            assertEquals(
                LinkPreviewBlob.MIME,
                mesh.sentChats
                    .single()
                    .attachment
                    ?.mime,
            )
        }

    @Test
    fun noCardIsFetchedWhileTheSettingIsOffOrThePhoneIsOffline() =
        runTest {
            aCardIsAvailable()
            linkPreviewsEnabledFlow.value = false
            val vm = vm()
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }

            linkPreviewsEnabledFlow.value = true
            onlineFlow.value = false
            vm.onDraftChanged("look https://example.com/a now")
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }

            // A route appearing re-arms the same draft.
            onlineFlow.value = true
            advanceUntilIdle()
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = true) }
            assertEquals(staged, vm.pendingAttachment.value)
        }

    @Test
    fun aStagedPhotoWinsOverALinkAndALinkLeavingTheDraftClearsItsCard() =
        runTest {
            aCardIsAvailable()
            val photo = AttachmentStore.Ingested("h-photo", "image/jpeg")
            coEvery { attachments.ingest(any<ByteArray>(), any()) } returns AttachmentStore.IngestResult.Success(photo, flagged = false)
            val vm = vm()
            vm.attachCaptured(byteArrayOf(1))
            advanceUntilIdle()
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }
            assertEquals(photo, vm.pendingAttachment.value)

            vm.clearAttachment()
            vm.onDraftChanged("look https://example.com/a !")
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)

            vm.onDraftChanged("no link any more")
            advanceUntilIdle()
            assertNull(vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h-card") }
        }

    @Test
    fun aDismissedCardStaysDismissedUntilTheDraftIsEmptiedAndAnEmptyLinkIsNotRetried() =
        runTest {
            aCardIsAvailable()
            val vm = vm()
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)
            vm.clearAttachment()
            vm.onDraftChanged("look https://example.com/a again")
            advanceUntilIdle()
            assertNull("the removed card does not come back while the draft lives", vm.pendingAttachment.value)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, any()) }

            vm.onDraftChanged("")
            vm.onDraftChanged("https://example.com/a")
            advanceUntilIdle()
            coVerify(exactly = 2) { linkPreviews.fetchCard(cardUrl, any()) }

            coEvery { linkPreviews.fetchCard("https://example.com/none", any()) } returns LinkPreviewService.CardResult.NoCard
            vm.clearAttachment()
            vm.onDraftChanged("")
            vm.onDraftChanged("see https://example.com/none")
            advanceUntilIdle()
            vm.onDraftChanged("see https://example.com/none please")
            advanceUntilIdle()
            coVerify(exactly = 1) { linkPreviews.fetchCard("https://example.com/none", any()) }
        }

    @Test
    fun aGroupCarriesACardOnlyWhenEveryOtherMemberCanRenderOne() =
        runTest {
            aCardIsAvailable()
            coEvery { groups.find(GROUP) } returns group(GROUP, listOf("me", "sam", "priya"))
            coEvery { peers.find("sam") } returns peer("sam", "Sam", capabilities = Protocol.LOCAL_CAPABILITIES)
            coEvery { peers.find("priya") } returns peer("priya", "Priya", capabilities = Protocol.CAP_FILES)
            val vm = vm(GROUP)
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }

            // The loop reacts to the draft, not to a peer's profile arriving: the next draft picks the bit up.
            coEvery { peers.find("priya") } returns peer("priya", "Priya", capabilities = Protocol.LOCAL_CAPABILITIES)
            vm.onDraftChanged("")
            vm.onDraftChanged("look https://example.com/a")
            advanceUntilIdle()
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = false) }
        }

    /** What the screen does on [ChatViewModel.clearInput]: empties the field (which the draft loop sees) and releases the guard. */
    private fun TestScope.screenClearsTheFieldOnSend(vm: ChatViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.clearInput.collect {
                vm.onDraftChanged("")
                vm.onInputCleared()
            }
        }
    }

    @Test
    fun aSendWaitsForTheCardStillBeingFetchedAndCarriesIt() =
        runTest {
            aCardIsAvailable()
            coEvery { linkPreviews.fetchCard(cardUrl, any()) } coAnswers {
                delay(2_000)
                LinkPreviewService.CardResult.Card(cardBlob)
            }
            val vm = vm()
            screenClearsTheFieldOnSend(vm)
            // The share-sheet shape: the link lands whole, and Send is the next tap — inside the fetch.
            vm.onDraftChanged("https://example.com/a")
            advanceTimeBy(1_000)
            assertTrue(vm.linkPreviewLoading.value)
            vm.send("https://example.com/a")
            runCurrent()
            assertTrue("nothing goes out while the card is on its way", mesh.sentChats.isEmpty())
            assertTrue(vm.isSending.value)
            advanceUntilIdle()
            assertEquals(staged, mesh.sentChats.single().attachment)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = true) }
        }

    @Test
    fun aSendBeforeTheDraftHasRestedFetchesTheCardItself() =
        runTest {
            aCardIsAvailable()
            val vm = vm()
            screenClearsTheFieldOnSend(vm)
            vm.onDraftChanged("https://example.com/a")
            vm.send("https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, mesh.sentChats.single().attachment)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = true) }
        }

    @Test
    fun aSlowCardDoesNotHoldTheSendPastItsBound() =
        runTest {
            aCardIsAvailable()
            coEvery { linkPreviews.fetchCard(cardUrl, any()) } coAnswers {
                delay(60_000)
                LinkPreviewService.CardResult.Card(cardBlob)
            }
            val vm = vm()
            screenClearsTheFieldOnSend(vm)
            vm.onDraftChanged("https://example.com/a")
            vm.send("https://example.com/a")
            advanceTimeBy(4_000)
            assertTrue(mesh.sentChats.isEmpty())
            advanceTimeBy(2_000)
            assertNull("past the bound the text goes alone", mesh.sentChats.single().attachment)
            advanceUntilIdle()
            assertNull("the abandoned fetch stages nothing", vm.pendingAttachment.value)
            assertFalse(vm.linkPreviewLoading.value)
        }

    @Test
    fun aSendDoesNotWaitForACardTheUserRemoved() =
        runTest {
            aCardIsAvailable()
            val vm = vm()
            screenClearsTheFieldOnSend(vm)
            vm.onDraftChanged("https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)
            vm.clearAttachment()
            vm.send("https://example.com/a")
            advanceUntilIdle()
            assertNull(mesh.sentChats.single().attachment)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, any()) }
        }

    @Test
    fun aThreadThatRidesLoraTakesACardLikeAPhoto() =
        runTest {
            aCardIsAvailable()
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("the room rides the board", LoraCarry.Room, vm.state.value.loraCarry)
            vm.onDraftChanged("https://example.com/a")
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)
        }

    @Test
    fun aReceivedCardReachesItsRowOnlyWhenTheBodyHoldsItsLink() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", body = "see https://example.com/a", attachmentHash = "hc")
                        .copy(attachmentMime = LinkPreviewBlob.MIME),
                )
            sizesFlow.value = mapOf("hc" to 100)
            advanceUntilIdle()
            assertNull(
                "no card until the container has decoded",
                vm.state.value.rows
                    .single()
                    .linkCard,
            )
            coVerify { linkCards.ensure("hc", null) }

            cardsFlow.value = mapOf("hc" to cardBlob.toCard())
            advanceUntilIdle()
            assertEquals(
                cardBlob.toCard(),
                vm.state.value.rows
                    .single()
                    .linkCard,
            )

            // A card for a link that is not in the body is never shown, however it got there.
            cardsFlow.value = mapOf("hc" to cardBlob.toCard().copy(url = "https://evil.example/x", host = "evil.example"))
            advanceUntilIdle()
            assertNull(
                vm.state.value.rows
                    .single()
                    .linkCard,
            )
        }

    // ---- Location sharing: the pin, the disclosure, the tile and the send ----

    private fun reading(
        accuracy: Float?,
        at: Long = 1_000L,
    ) = LocationFix(lat = 37.421998, lon = -122.084, accuracyM = accuracy, timeMs = 1_700_000_000_000L + at, elapsedRealtimeMs = at)

    @Test
    fun theFirstPinTapRaisesTheDisclosureAndAcceptingItAsksForTheGrant() =
        runTest {
            val vm = vm()
            var asked = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.locationPermissionNeeded.collect { asked++ } }

            vm.attachLocation()
            runCurrent()
            assertTrue(vm.showLocationConsent.value)
            assertEquals("nothing is asked for until the disclosure is read", 0, asked)
            assertEquals("and nothing listens", 0, locationSource.collectors)

            vm.acceptLocationConsent()
            runCurrent()
            coVerify(exactly = 1) { settings.acceptLocationShareConsent() }
            assertFalse(vm.showLocationConsent.value)
            assertEquals("accepting carries straight on to the grant", 1, asked)
            assertNull("the grant is the screen's to clear; nothing is staged yet", vm.stagedLocation.value)
        }

    @Test
    fun aDismissedDisclosureAsksAgainNextTimeAndAConsentedTapSkipsIt() =
        runTest {
            val vm = vm()
            var asked = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.locationPermissionNeeded.collect { asked++ } }

            vm.attachLocation()
            runCurrent()
            vm.dismissLocationConsent()
            assertFalse(vm.showLocationConsent.value)
            coVerify(exactly = 0) { settings.acceptLocationShareConsent() }
            assertEquals(0, asked)

            locationConsentFlow.value = true
            vm.attachLocation()
            runCurrent()
            assertFalse(vm.showLocationConsent.value)
            assertEquals(1, asked)
        }

    @Test
    fun theBridgedRoomRefusesThePin() =
        runTest {
            locationConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            val events = mutableListOf<Int>()
            var asked = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.locationPermissionNeeded.collect { asked++ } }

            vm.attachLocation()
            runCurrent()

            assertEquals(listOf(R.string.chat_mesh_text_only), events)
            assertEquals(0, asked)
            assertFalse(vm.showLocationConsent.value)
        }

    @Test
    fun startSeedsFromTheCachedReadingThenTightensAndStopsOnceGoodEnough() =
        runTest {
            locationSource.lastKnown = reading(accuracy = 30f, at = 500L)
            val vm = vm()

            vm.startLocation()
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Refining, vm.stagedLocation.value?.status)
            assertEquals(
                30f,
                vm.stagedLocation.value
                    ?.fix
                    ?.accuracyM,
            )
            assertEquals("the tile is the one listener", 1, locationSource.collectors)

            locationSource.readings.tryEmit(reading(accuracy = 15f, at = 1_500L))
            runCurrent()
            assertEquals(
                15f,
                vm.stagedLocation.value
                    ?.fix
                    ?.accuracyM,
            )
            assertEquals(1, locationSource.collectors)

            locationSource.readings.tryEmit(reading(accuracy = 5f, at = 2_500L))
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Ready, vm.stagedLocation.value?.status)
            assertEquals(
                5f,
                vm.stagedLocation.value
                    ?.fix
                    ?.accuracyM,
            )
            assertEquals("good enough: the listener is gone before the window closes", 0, locationSource.collectors)
            assertEquals("geo:37.421998,-122.084000;u=5", vm.stagedLocation.value?.token)
        }

    @Test
    fun theWindowFreezesWhatItHasOrFailsWithNothing() =
        runTest {
            val vm = vm()

            vm.startLocation()
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Acquiring, vm.stagedLocation.value?.status)
            advanceTimeBy(LocationFixPolicy.REFINE_WINDOW_MS + 1)
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Failed, vm.stagedLocation.value?.status)
            assertEquals(0, locationSource.collectors)

            // Retry from the failed tile: a fresh window, a reading, and the window closing on a Ready tile.
            vm.refreshLocation()
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Acquiring, vm.stagedLocation.value?.status)
            assertEquals(2, locationSource.subscriptions)
            locationSource.readings.tryEmit(reading(accuracy = 20f))
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Refining, vm.stagedLocation.value?.status)
            advanceTimeBy(LocationFixPolicy.REFINE_WINDOW_MS + 1)
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Ready, vm.stagedLocation.value?.status)
            assertEquals(
                20f,
                vm.stagedLocation.value
                    ?.fix
                    ?.accuracyM,
            )
            assertEquals(0, locationSource.collectors)
        }

    @Test
    fun noProviderAtAllFailsWithoutWaitingOutTheWindow() =
        runTest {
            locationSource.noProvider = true
            val vm = vm()
            vm.startLocation()
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.Failed, vm.stagedLocation.value?.status)
            assertEquals(0, locationSource.collectors)
        }

    @Test
    fun locationServicesOffIsItsOwnStateAndARevokedGrantIsRefusedWithoutListening() =
        runTest {
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            locationSource.enabled = false
            vm.startLocation()
            runCurrent()
            assertEquals(ChatViewModel.StagedLocation.Status.ServicesOff, vm.stagedLocation.value?.status)
            assertEquals(0, locationSource.subscriptions)

            vm.clearLocation()
            locationSource.enabled = true
            locationSource.precision = LocationPrecision.None
            vm.startLocation()
            runCurrent()
            assertEquals(listOf(R.string.chat_location_denied), events)
            assertNull(vm.stagedLocation.value)
            assertEquals(0, locationSource.subscriptions)
        }

    @Test
    fun anApproximateGrantIsStagedAndSaysSo() =
        runTest {
            locationSource.precision = LocationPrecision.Coarse
            val vm = vm()
            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 2_000f).copy(coarse = true))
            runCurrent()
            assertEquals(LocationPrecision.Coarse, vm.stagedLocation.value?.precision)
            assertEquals("geo:37.421998,-122.084000;u=2000", vm.stagedLocation.value?.token)
        }

    @Test
    fun clearingStopsListeningAndLeavingTheScreenFreezesUntilItComesBack() =
        runTest {
            val vm = vm()
            vm.onChatForeground()
            assertEquals("a chat with nothing staged never starts listening on its own", 0, locationSource.subscriptions)

            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 25f))
            runCurrent()
            assertEquals(1, locationSource.collectors)

            vm.onChatBackground()
            runCurrent()
            assertEquals("a backgrounded chat never keeps the radio on", 0, locationSource.collectors)
            assertEquals(ChatViewModel.StagedLocation.Status.Ready, vm.stagedLocation.value?.status)
            assertEquals(
                25f,
                vm.stagedLocation.value
                    ?.fix
                    ?.accuracyM,
            )

            vm.onChatForeground()
            runCurrent()
            assertEquals("coming back re-arms a fresh window on the tile that was cut short", 1, locationSource.collectors)
            assertEquals(2, locationSource.subscriptions)

            vm.clearLocation()
            runCurrent()
            assertNull(vm.stagedLocation.value)
            assertEquals(0, locationSource.collectors)
            vm.onChatBackground()
            vm.onChatForeground()
            runCurrent()
            assertEquals("a cleared tile does not come back with the screen", 2, locationSource.subscriptions)
        }

    @Test
    fun sendFoldsThePositionOntoTheBodyTextFirstAndClearsTheTile() =
        runTest {
            stubDm("bob")
            coEvery { groups.find("bob") } returns null
            val vm = vm("bob")
            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 12f))
            runCurrent()

            vm.send("See you at the gate")
            runCurrent()

            assertEquals("See you at the gate\ngeo:37.421998,-122.084000;u=12", mesh.sentChats.single().text)
            assertEquals("bob", mesh.sentChats.single().recipientId)
            assertNull("the position went with the message", vm.stagedLocation.value)
            assertEquals(0, locationSource.collectors)
        }

    @Test
    fun aPositionAloneIsAMessageAndALongDraftStillKeepsItsToken() =
        runTest {
            stubDm("bob")
            coEvery { groups.find("bob") } returns null
            val vm = vm("bob")
            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 12f))
            runCurrent()

            vm.send("")
            runCurrent()
            assertEquals("geo:37.421998,-122.084000;u=12", mesh.sentChats.single().text)

            vm.onInputCleared()
            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 12f))
            runCurrent()
            vm.send("a".repeat(5_000))
            runCurrent()
            val body = mesh.sentChats.last().text
            assertTrue(
                "the receiver clamps at TextLimits.MESSAGE, so the token has to fit under it",
                body.length <= app.getknit.knit.TextLimits.MESSAGE,
            )
            assertTrue(body.endsWith("\ngeo:37.421998,-122.084000;u=12"))
        }

    @Test
    fun sendingBeforeAReadingLandsRefusesAndKeepsTheDraftAndTheTile() =
        runTest {
            stubDm("bob")
            coEvery { groups.find("bob") } returns null
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            var cleared = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.clearInput.collect { cleared++ } }
            vm.startLocation()
            runCurrent()

            vm.send("on my way")
            runCurrent()

            assertEquals(listOf(R.string.chat_location_not_ready), events)
            assertTrue(mesh.sentChats.isEmpty())
            assertEquals(0, cleared)
            assertEquals(ChatViewModel.StagedLocation.Status.Acquiring, vm.stagedLocation.value?.status)
            assertFalse("the guard is released, the draft is the user's", vm.isSending.value)
        }

    @Test
    fun aRowWithAGeoLineCarriesItsPointAndAPlainRowDoesNot() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", sentAt = 1L, body = "here\ngeo:37.421998,-122.084000;u=12"),
                    msg(senderId = "bob", id = "m2", sentAt = 2L, body = "geo:91,0 is not a place"),
                )
            runCurrent()
            val rows = vm.state.value.rows
            assertEquals(GeoPoint(37.421998, -122.084, 12), rows[0].location)
            assertNull(rows[1].location)
        }
}
