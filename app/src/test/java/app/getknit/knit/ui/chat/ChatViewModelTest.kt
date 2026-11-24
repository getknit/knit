@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinOutcome
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.draft.Draft
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.pin.PinnedMessage
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.data.relay.FreeUploadOffer
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.data.settings.FakeChatMutes
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.legal.repoFile
import app.getknit.knit.linkpreview.LinkPreviewService
import app.getknit.knit.location.FakeLocationSource
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationFixPolicy
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.LoraRadioReached
import app.getknit.knit.mesh.MeshPostOrigin
import app.getknit.knit.mesh.MeshcoreReactionOutcome
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.PublicPostPolicy
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.mesh.spool.FreeKeyJournal
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketJournal
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ShippedWordList
import app.getknit.knit.notifications.MuteFor
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.redact.FaceBox
import app.getknit.knit.redact.FaceDetector
import app.getknit.knit.transfer.OfferOutcome
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferRefusal
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.reaction
import app.getknit.knit.ui.redact.FaceSearch
import app.getknit.knit.ui.redact.PhotoReview
import app.getknit.knit.ui.share.OutsideUris
import app.getknit.knit.ui.share.OwnFile
import app.getknit.knit.ui.voice.VoicePlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

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

    // Hash → holder → the plane its proof came over (`BlobRepository.observeHolders`).
    private val holdersFlow = MutableStateFlow(emptyMap<String, Map<String, Int>>())
    private val flaggedFlow = MutableStateFlow(emptyList<String>())
    private val filteringFlow = MutableStateFlow(true)
    private val downloadLimitFlow = MutableStateFlow<Long?>(1_000_000L)
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
    private val typingIndicatorsFlow = MutableStateFlow(true)
    private val readCountsFlow = MutableStateFlow(emptyMap<String, Int>())
    private val readReceiptsFlow = MutableStateFlow(true)
    private val coverFacesFlow = MutableStateFlow(false)
    private val publicConsentFlow = MutableStateFlow(false)

    // The MeshCore room's own consent, per-kind facts and board name (ADR 2026-11.gsqu's Stage 6 amendment).
    private val meshcoreConsentFlow = MutableStateFlow(false)
    private val loraRoomsFlow = MutableStateFlow<Map<BoardKind, LoraFacts>>(emptyMap())
    private val meshcoreBoardNameFlow = MutableStateFlow<String?>("Knit 1a2b")

    // The board's live name off its face, and whether a consent was given on a sheet that named a chosen one (ADR
    // 2026-11.f4d3). The board carries its default unless a test renames it.
    private val meshcoreNamingFlow = MutableStateFlow<MeshcoreNaming?>(meshcoreNaming("Knit 1a2b", default = true))
    private val meshcoreCustomNameConsentFlow = MutableStateFlow(false)

    // Whether the MeshCore consent was given with its position line, and whether the board shares where it is (Stage 7).
    private val meshcorePositionConsentFlow = MutableStateFlow(false)
    private val meshcoreSharesFlow = MutableStateFlow<Boolean?>(null)
    private val locationConsentFlow = MutableStateFlow(false)
    private val transferConsentFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // A relaxed mock would hand back a mocked Draft, which equals no report, so every echo would look like an edit.
        coEvery { drafts.load(any()) } returns Draft.EMPTY
        coEvery { identity.nodeId() } returns "me"
        stubThread(Conversations.NEARBY)
        every { reactions.observeReactionsIn(any(), "me") } returns reactionsFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.recentReactions } returns recentsFlow
        // Keyed by the window's hashes in production; the test map simply holds every hash a test names.
        every { blobs.observeSizes(any()) } returns sizesFlow
        // Folded into the blob state beside the sizes: a relaxed mock's silent flow would stall every row.
        every { blobs.observeHolders(any()) } returns holdersFlow
        every { imageScreening.observeFlaggedHashes() } returns flaggedFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        // Combined with the filter into the blob state: a relaxed mock's silent flow would stall every row.
        every { settings.bluetoothDownloadLimitBytes } returns downloadLimitFlow
        every { groups.observeGroup(Conversations.NEARBY) } returns groupFlow
        stubThread(GROUP)
        every { groups.observeGroup(GROUP) } returns groupFlow
        stubThread(Conversations.MESHTASTIC)
        every { groups.observeGroup(Conversations.MESHTASTIC) } returns groupFlow
        stubThread(Conversations.MESHCORE_PUBLIC)
        every { groups.observeGroup(Conversations.MESHCORE_PUBLIC) } returns groupFlow
        every { settings.meshcorePostConsented } returns meshcoreConsentFlow
        every { settings.meshcorePostConsentPosition } returns meshcorePositionConsentFlow
        every { settings.meshcorePostConsentCustomName } returns meshcoreCustomNameConsentFlow
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
        // Read counts and the setting ride the same arm (ADR 2026-11.z6jw): silent flows would stall it too.
        every { receipts.observeReadCounts(any(), any()) } returns readCountsFlow
        every { settings.readReceiptsEnabled } returns readReceiptsFlow
        // Same trap: the decoded-card map is an arm of the blob-state combine.
        every { linkCards.cards } returns cardsFlow
        every { linkPreviews.online } returns onlineFlow
        every { settings.linkPreviewsEnabled } returns linkPreviewsEnabledFlow
        // An arm of the mesh-status combine: a relaxed mock's silent flow would stall every state assertion.
        every { settings.typingIndicatorsEnabled } returns typingIndicatorsFlow
        // Read with first() at every attach: a relaxed mock's flow would end empty and throw there.
        every { settings.coverFacesEnabled } returns coverFacesFlow
        every { settings.locationShareConsented } returns locationConsentFlow
        every { settings.directTransferConsented } returns transferConsentFlow
        every { transfers.states } returns transfersFlow
        every { transfers.mayOffer() } returns true
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
        // The table's mention window over every stored row, as the DAO answers it (`MentionWindowQueriesTest` holds the two equal).
        every { messages.observeMentionWindowEdge(conversationId, any(), any(), any()) } answers {
            val me = secondArg<String>()
            val blocked = thirdArg<Collection<String>>().toSet()
            val size = arg<Int>(3)
            messagesFlow.map { all ->
                val rows = all.map { WindowRow(it.id, it.senderId, it.sentAt, MentionStore.decode(it.mentions).size) }
                mentionWindowEdge(rows, me, blocked, size)
            }
        }
    }

    // The call layer a case hands the chat (experimental); null, as in a build without calls, unless a case sets it.
    private var calls: CallManager? = null

    // Free tickets (C-7.6-34); null, as in a build without Knit Anywhere, unless a case sets it.
    private var freeUploads: FreeUploads? = null

    private fun vm(
        conversationId: String = Conversations.NEARBY,
        preferMeshCalls: Flow<Boolean> = flowOf(false),
        pins: PinRepository? = null,
        directLinksOn: Flow<Boolean> = flowOf(false),
        // Each kind's own facts: null, as the production default for a rig, reads [loraFactsFlow] as its kind's alone.
        loraRooms: Flow<Map<BoardKind, LoraFacts>>? = null,
        faces: FaceDetector? = null,
        mutes: ChatMutes? = null,
    ) = ChatViewModel(
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
        calls = calls,
        freeUploads = freeUploads,
        preferMeshCalls = preferMeshCalls,
        directLinksOn = directLinksOn,
        pins = pins,
        // This install's own rule, read off the merged manifest as the app's Koin graph reads it.
        outsideUris = OutsideUris.of(context),
        loraRooms = loraRooms,
        meshcoreBoardName = meshcoreBoardNameFlow,
        meshcoreNaming = meshcoreNamingFlow,
        meshcoreSharesPosition = meshcoreSharesFlow,
        faces = faces,
        mutes = mutes,
    )

    // --- Per-chat mute (ADR 2026-11.sp7k) --------------------------------------------------------------------------------

    @Test
    fun theOverflowsMuteWritesThisChatsDeadlineAndUnmuteClearsIt() =
        runTest {
            val mutes = FakeChatMutes()
            val vm = vm(conversationId = GROUP, mutes = mutes)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.mutedUntil.collect {} }
            assertTrue(vm.canMute)
            assertNull(vm.mutedUntil.value)

            val before = System.currentTimeMillis()
            vm.mute(MuteFor.EIGHT_HOURS)
            runCurrent()
            val until = mutes.state.value.getValue(GROUP)
            // The deadline is eight hours from the moment of the tap, on the wall clock the gate reads.
            assertTrue(until in (before + MUTE_8H)..(System.currentTimeMillis() + MUTE_8H))
            assertEquals(until, vm.mutedUntil.value)

            vm.unmute()
            runCurrent()
            assertNull(vm.mutedUntil.value)
            assertEquals(emptyMap<String, Long>(), mutes.state.value)
        }

    @Test
    fun aRigWithNoMutesOffersNoMute() =
        runTest {
            val vm = vm(conversationId = GROUP)
            assertFalse(vm.canMute)
            vm.mute(MuteFor.ALWAYS)
            assertNull(vm.mutedUntil.value)
        }

    // --- Pinned messages (#42, ADR 2026-10.2r7e) ---------------------------------------------------------------------

    @Test
    @Config(qualifiers = "en-rUS") // asserts "You", "Video", "Photo" and "Message hidden"
    fun pinnedMessagesAreNamedTheWayTheThreadNamesItsSendersAndABlockedSendersPinIsLeftOut() =
        runTest {
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(GROUP) } returns
                flowOf(
                    listOf(
                        PinnedMessage(msg(senderId = "me", id = "m5", body = "I'll bring water", conversationId = GROUP), 50),
                        PinnedMessage(msg(senderId = "sam", id = "m4", body = "Meet at the north gate", conversationId = GROUP), 40),
                        PinnedMessage(msg(senderId = "mallory", id = "m3", body = "spam", conversationId = GROUP), 30),
                        PinnedMessage(
                            msg(senderId = "sam", id = "m2", body = "", conversationId = GROUP, attachmentHash = "v")
                                .copy(attachmentMime = "video/mp4", videoDurationMs = 3_000),
                            20,
                        ),
                        PinnedMessage(
                            msg(senderId = "sam", id = "m1", body = "", conversationId = GROUP, attachmentHash = "p")
                                .copy(attachmentMime = "image/jpeg"),
                            10,
                        ),
                        PinnedMessage(
                            msg(
                                senderId = "sam",
                                id = "m0",
                                body = "rude words",
                                conversationId = GROUP,
                                moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
                            ),
                            5,
                        ),
                    ),
                )
            peersFlow.value = listOf(peer("sam", name = "Sam"))
            blockedFlow.value = setOf("mallory")
            val vm = vm(GROUP, pins = pins)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pinned.collect {} }
            advanceUntilIdle()

            val shown = vm.pinned.value
            assertEquals(listOf("m5", "m4", "m2", "m1", "m0"), shown.map { it.messageId })
            assertEquals(listOf("You", "Sam", "Sam", "Sam", "Sam"), shown.map { it.author })
            // The chat list's preview rule (messagePreview), so a video with no caption is not called a photo.
            assertEquals(
                listOf("I'll bring water", "Meet at the north gate", "🎬 Video", "📷 Photo", "Message hidden"),
                shown.map { it.preview },
            )
        }

    @Test
    fun aPinnedHeardPostIsNamedForItsSpeakerNotThisPhone() =
        runTest {
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(Conversations.MESHTASTIC) } returns
                flowOf(
                    listOf(
                        PinnedMessage(
                            msg(
                                senderId = "me",
                                id = "heard",
                                body = "net check",
                                conversationId = Conversations.MESHTASTIC,
                                originNode = 0x1234abcdL,
                                originName = "Base Camp",
                            ),
                            10,
                        ),
                    ),
                )
            val vm = vm(Conversations.MESHTASTIC, pins = pins)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pinned.collect {} }
            advanceUntilIdle()

            assertEquals(listOf("Base Camp"), vm.pinned.value.map { it.author })
        }

    @Test
    fun senderNameIsOneRuleForTheThreadAndTheBar() {
        val labels = mapOf("sam" to "Sam", "ana" to "Ana")
        val labelOf = { id: String -> labels[id] ?: id }

        fun origin(
            name: String?,
            peerId: String?,
        ) = MeshOrigin(nodeLabel = "!1234abcd", name = name, peerId = peerId, hops = null, snrDeci = null, viaMqtt = false)

        assertEquals("Ana", senderName(origin("Base Camp", peerId = "ana"), "me", "me", "You", labelOf))
        assertEquals("Base Camp", senderName(origin("Base Camp", peerId = null), "me", "me", "You", labelOf))
        assertEquals("!1234abcd", senderName(origin(null, peerId = null), "me", "me", "You", labelOf))
        assertEquals("Kim", senderName(null, "me", "me", selfName = "Kim", labelOf = labelOf))
        assertEquals("Sam", senderName(null, "sam", "me", "You", labelOf))
    }

    @Test
    fun pinAndUnpinPassTheWantedStateAndAFullChatSaysItsOldestWent() =
        runTest {
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(any()) } returns flowOf(emptyList())
            coEvery { pins.setPinned("m4", true, any()) } returns PinOutcome.PinnedReplacingOldest
            coEvery { pins.setPinned("m1", true, any()) } returns PinOutcome.Pinned
            coEvery { pins.setPinned("m1", false, any()) } returns PinOutcome.Unpinned
            val vm = vm(GROUP, pins = pins)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.setPinned("m1", pinned = true)
            vm.setPinned("m1", pinned = false)
            advanceUntilIdle()
            assertTrue("a plain pin or unpin says nothing", events.isEmpty())

            vm.setPinned("m4", pinned = true)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_pin_replaced_oldest), events)
            coVerify(exactly = 1) { pins.setPinned("m1", true, any()) }
            coVerify(exactly = 1) { pins.setPinned("m1", false, any()) }
        }

    @Test
    fun blockingASenderUnpinsWhatTheySent() =
        runTest {
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(any()) } returns flowOf(emptyList())
            val vm = vm(GROUP, pins = pins)

            vm.block("mallory")
            advanceUntilIdle()

            coVerifyOrder {
                settings.block("mallory", any())
                pins.unpinFrom("mallory")
            }
        }

    @Test
    fun deletingAMessageDropsItsPin() =
        runTest {
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(any()) } returns flowOf(emptyList())
            val vm = vm(GROUP, pins = pins)

            vm.deleteMessage("m1")
            advanceUntilIdle()

            coVerify(exactly = 1) { pins.deleteForMessage("m1") }
        }

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

    /** ADR 2026-11.z6jw: seen rows reach the mesh only in the foreground, only from a DM or a group, once each. */
    @Test
    fun seenRowsReachTheMeshOnceOnlyWhileTheChatIsOnScreen() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")

            vm.onRowsSeen(setOf("d1"))
            advanceUntilIdle()
            assertTrue("a chat in the background reads nothing", mesh.messagesRead.isEmpty())

            vm.onChatForeground()
            vm.onRowsSeen(setOf("d1", "d2"))
            vm.onRowsSeen(setOf("d2", "d3"))
            advanceUntilIdle()
            assertEquals(listOf(setOf("d1", "d2"), setOf("d3")), mesh.messagesRead.map { it.toSet() })
        }

    @Test
    fun theRoomNeverReportsARead() =
        runTest {
            val vm = vm(Conversations.NEARBY)
            vm.onChatForeground()

            vm.onRowsSeen(setOf("r1"))
            advanceUntilIdle()

            assertTrue(mesh.messagesRead.isEmpty())
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
    fun aGroupOffersEveryoneFromTheRosterAloneWithoutUsOrTheBlockedSortedByNodeId() =
        runTest {
            blockedFlow.value = setOf("mallory")
            groupFlow.value = group(GROUP, members = listOf("zed", "me", "mallory", "amy", "kit"))
            val vm = vm(GROUP)
            collectState(vm)
            // Dora has posted but is not on the roster: the picker's candidates take her, the expansion does not.
            messagesFlow.value = listOf(msg(senderId = "dora", body = "hello", sentAt = 1L))
            advanceUntilIdle()

            assertEquals(
                listOf(Mention("amy", "everyone"), Mention("kit", "everyone"), Mention("zed", "everyone")),
                vm.state.value.everyoneMentions,
            )
            assertTrue(
                "dora is still an ordinary candidate",
                "dora" in
                    vm.state.value.mentionCandidates
                        .map { it.nodeId },
            )
        }

    @Test
    fun aGroupWithNobodyElseToMentionOffersNoEveryoneRow() =
        runTest {
            blockedFlow.value = setOf("mallory")
            groupFlow.value = group(GROUP, members = listOf("me", "mallory"))
            val vm = vm(GROUP)
            collectState(vm)
            advanceUntilIdle()

            assertNull(vm.state.value.everyoneMentions)
        }

    @Test
    fun aDirectMessageAndTheNearbyRoomNeverOfferEveryone() =
        runTest {
            stubDm("bob")
            for (thread in listOf("bob", Conversations.NEARBY)) {
                val vm = vm(thread)
                collectState(vm)
                messagesFlow.value = listOf(msg(senderId = "bob", body = "hello", sentAt = 1L))
                advanceUntilIdle()

                assertNull(thread, vm.state.value.everyoneMentions)
            }
        }

    // ADR 2026-11.p6dk: a received row's bubble styles only the mentions whose name is the reader's label for that id.

    @Test
    fun aReceivedRowStylesOnlyTheMentionWhoseNameIsTheReadersLabelForItsId() =
        runTest {
            val vm = vm(Conversations.NEARBY)
            collectState(vm)
            peersFlow.value = listOf(peer("alice", name = "Alice"), peer("bob", name = "Bob"))
            val unvouched = listOf(Mention("carol", "Alice"))
            val real = listOf(Mention("carol", "Alice"), Mention("alice", "Alice"))
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", body = "@Alice look", sentAt = 1L, id = "unvouched")
                        .copy(mentions = MentionStore.encode(unvouched)),
                    msg(senderId = "bob", body = "@Alice look", sentAt = 2L, id = "real").copy(mentions = MentionStore.encode(real)),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals(emptyList<Mention>(), rows.getValue("unvouched").styledMentions)
            assertEquals(listOf(Mention("alice", "Alice")), rows.getValue("real").styledMentions)
            // Every entry stays on the row as received: translation still protects each one's span.
            assertEquals(unvouched, rows.getValue("unvouched").mentions)
            assertEquals(real, rows.getValue("real").mentions)
        }

    @Test
    fun ourOwnRowKeepsEveryMentionItCarries() =
        runTest {
            val vm = vm(Conversations.NEARBY)
            collectState(vm)
            peersFlow.value = listOf(peer("bob", name = "Bob"))
            val mentions = listOf(Mention("bob", "Robert"))
            messagesFlow.value =
                listOf(msg(senderId = "me", body = "@Robert hi", sentAt = 1L).copy(mentions = MentionStore.encode(mentions)))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .single()
            assertEquals(mentions, row.styledMentions)
            assertEquals(mentions, row.mentions)
        }

    @Test
    fun everyoneIsStyledForAGroupsMembersAndNotInTheNearbyRoom() =
        runTest {
            val mentions = listOf(Mention("kit", "everyone"), Mention("me", "everyone"))
            val row = msg(senderId = "amy", body = "@everyone standup", sentAt = 1L).copy(mentions = MentionStore.encode(mentions))
            groupFlow.value = group(GROUP, members = listOf("me", "amy", "kit"))
            val inGroup = vm(GROUP)
            collectState(inGroup)
            messagesFlow.value = listOf(row)
            advanceUntilIdle()
            val groupRow =
                inGroup.state.value.rows
                    .single()
            assertEquals(mentions, groupRow.styledMentions)

            groupFlow.value = null
            val inRoom = vm(Conversations.NEARBY)
            collectState(inRoom)
            advanceUntilIdle()
            val roomRow =
                inRoom.state.value.rows
                    .single()
            assertEquals(emptyList<Mention>(), roomRow.styledMentions)
            assertEquals(mentions, roomRow.mentions)
        }

    @Test
    fun aMentionOlderThanTheThreadsNewestWindowDrawsPlainButItsEveryoneStillCounts() =
        runTest {
            groupFlow.value = group(GROUP, members = listOf("me", "amy", "kit"))
            peersFlow.value = listOf(peer("kit", name = "Kit"))
            val vm = vm(GROUP)
            collectState(vm)
            val old = listOf(Mention("kit", "Kit"), Mention("kit", "everyone"))
            val one = MentionStore.encode(listOf(Mention("kit", "Kit")))
            // The oldest row, then a full window of newer rows carrying mentions: it falls outside.
            messagesFlow.value =
                listOf(msg(senderId = "amy", body = "@Kit @everyone", sentAt = 1L, id = "old").copy(mentions = MentionStore.encode(old))) +
                (1..MENTION_WINDOW).map { msg(senderId = "amy", body = "@Kit", sentAt = 1L + it, id = "n$it").copy(mentions = one) }
            advanceUntilIdle()
            // The screen loads the newest page only; the window is the table's, so page back until the oldest row shows.
            repeat(2) {
                vm.loadOlder()
                advanceUntilIdle()
            }

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals(listOf(Mention("kit", "everyone")), rows.getValue("old").styledMentions)
            assertEquals(old, rows.getValue("old").mentions)
            assertEquals(listOf(Mention("kit", "Kit")), rows.getValue("n1").styledMentions)
        }

    @Test
    fun blockingAFlooderBringsAnOlderHonestMentionBackIntoTheWindow() =
        runTest {
            peersFlow.value = listOf(peer("kit", name = "Kit"))
            val vm = vm(Conversations.NEARBY)
            collectState(vm)
            val one = MentionStore.encode(listOf(Mention("kit", "Kit")))
            messagesFlow.value =
                listOf(msg(senderId = "amy", body = "@Kit hi", sentAt = 1L, id = "honest").copy(mentions = one)) +
                (1..MENTION_WINDOW).map { msg(senderId = "mallory", body = "@Kit", sentAt = 1L + it, id = "f$it").copy(mentions = one) }
            advanceUntilIdle()
            repeat(2) {
                vm.loadOlder()
                advanceUntilIdle()
            }
            assertEquals(
                emptyList<Mention>(),
                vm.state.value.rows
                    .single { it.id == "honest" }
                    .styledMentions,
            )

            blockedFlow.value = setOf("mallory")
            advanceUntilIdle()
            assertEquals(
                listOf(Mention("kit", "Kit")),
                vm.state.value.rows
                    .single { it.id == "honest" }
                    .styledMentions,
            )
        }

    @Test
    fun aFullGroupsEveryoneSendsSevenEntriesAndDropsThemWhenTheTokenIsEditedAway() =
        runTest {
            val roster = (1..8).map { "n$it" }
            val full = group(GROUP, members = listOf("me") + roster.take(7))
            groupFlow.value = full
            coEvery { groups.find(GROUP) } returns full
            val vm = vm(GROUP)
            collectState(vm)
            advanceUntilIdle()
            val picked = checkNotNull(vm.state.value.everyoneMentions)
            assertEquals(7, picked.size)

            // The screen hands send() what is still in the field: here the token was edited out.
            vm.send("hello all", mentionsStillIn("hello all", picked))
            advanceUntilIdle()
            assertEquals(emptyList<Mention>(), mesh.sentChats.single().mentions)
            vm.onInputCleared()

            val text = "@everyone standup"
            vm.send(text, mentionsStillIn(text, picked))
            advanceUntilIdle()
            assertEquals(picked, mesh.sentChats.last().mentions)
            assertTrue(
                mesh.sentChats
                    .last()
                    .mentions
                    .all { it.name == "everyone" },
            )
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
            assertEquals("with no board connected the newest post names the channel", "Meshtastic (LongTurbo)", state.title)

            // A connected board names it — whatever the user set slot 0 to, preset or their own word — after the network.
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, primaryChannel = "MediumFast", canPost = true)
            advanceUntilIdle()
            assertEquals("Meshtastic (MediumFast)", vm.state.value.title)

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

    // --- The MeshCore room (ADR 2026-11.gsqu's Stage 6 amendment): a MeshCore board's Public channel. ---

    private val meshcoreLive =
        LoraFacts(plane = LoraPlane.Live, canPost = true, primaryChannel = "Public", publicPostBytes = 64, board = BoardKind.MESHCORE)

    private fun meshcoreHeard(
        id: String,
        name: String?,
        moderation: Int = MessageEntity.MODERATION_NONE,
    ) = msg(
        senderId = "me",
        id = id,
        sentAt = 100,
        body = "anyone around?",
        conversationId = Conversations.MESHCORE_PUBLIC,
        originNode = MeshPostOrigin.MESHCORE_NODE,
        originName = name,
        moderation = moderation,
        receivedVia = DeliveryPlane.LoRa.code,
    )

    @Test
    fun aMeshcoreHeardPostIsAStrangerNamedByItsPostNeverAContactYouOrAHexNode() =
        runTest {
            // Security B1: the sentinel `originNode` is never drawn; a name is the post's own claim, never resolved.
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam", avatarHash = "sam-avatar"))
            messagesFlow.value = listOf(meshcoreHeard("named", "Bob"), meshcoreHeard("unnamed", null), meshcoreHeard("sam", "Sam"))
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            val named = rows.getValue("named")
            assertEquals("Bob", named.senderName)
            assertFalse("not ours, whatever the sender column says", named.mine)
            assertNull(named.avatarHash)
            val origin = checkNotNull(named.origin)
            assertTrue(origin.meshcore)
            assertNull(origin.peerId)
            assertFalse(origin.nodeLabel.startsWith("!"))
            assertFalse(origin.nameClash)
            assertEquals(context.getString(R.string.chat_meshcore_unnamed), rows.getValue("unnamed").senderName)
            // A contact's name is a claim anyone can write: their name stays the post's, never their face or id.
            val sam = rows.getValue("sam")
            assertEquals("Sam", sam.senderName)
            assertNull("never a contact's face", sam.avatarHash)
            assertNull(sam.origin?.peerId)
            assertEquals("drawn with the marker", true, sam.origin?.nameClash)
            assertTrue(vm.state.value.isMeshcoreRoom)
            assertTrue(vm.state.value.isBridged)
        }

    @Test
    fun aMeshcoreNameThatReadsAsUsOrOurBoardCarriesTheMarker() =
        runTest {
            // Security N2: "You", the person's own name and their board's `Knit xxxx`, by NameKey.
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    meshcoreHeard("you", context.getString(R.string.chat_self_name)),
                    meshcoreHeard("alice", "ALICE"),
                    meshcoreHeard("board", "Knit 1a2b"),
                    meshcoreHeard("stranger", "Knit 9f9f"),
                )
            advanceUntilIdle()

            val clash =
                vm.state.value.rows
                    .associate { it.id to it.origin?.nameClash }
            assertEquals(mapOf("you" to true, "alice" to true, "board" to true, "stranger" to false), clash)
        }

    /**
     * The owner's chosen handle is ours too (ADR 2026-11.f4d3): a stranger posting under it, or under the board's `Knit xxxx`
     * default it no longer carries, gets the marker; the handle is compared as the screens draw it, cleaned.
     */
    @Test
    fun aMeshcoreNameThatReadsAsOurBoardsHandleOrItsDefaultCarriesTheMarker() =
        runTest {
            meshcoreNamingFlow.value = meshcoreNaming("Sam 🧶")
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    meshcoreHeard("handle", "sam 🧶"),
                    meshcoreHeard("default", "Knit 1a2b"),
                    meshcoreHeard("stranger", "Sam"),
                )
            advanceUntilIdle()

            val clash =
                vm.state.value.rows
                    .associate { it.id to it.origin?.nameClash }
            assertEquals(mapOf("handle" to true, "default" to true, "stranger" to false), clash)
        }

    /**
     * Security review round 2: the bubble's clash set holds every name the screens render for a person, not only the raw
     * stored one — a namesake's disambiguated label ("Alice (QuietOtter)"), the alias a blank name shows, and our own.
     */
    @Test
    fun aMeshcoreNameThatReadsAsARenderedLabelCarriesTheMarker() =
        runTest {
            nameFlow.value = ""
            peersFlow.value = listOf(peer("alice1", name = "Alice"), peer("alice2", name = "Alice"), peer("blank", name = ""))
            val directory = directoryOf(peersFlow.value)
            val namesake = directory.label("alice1").text
            val alias = directory.label("blank").name
            val ours = directory.label("me").name
            assertTrue("the fixture renders a discriminator", namesake != "Alice")
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    meshcoreHeard("namesake", namesake),
                    meshcoreHeard("alias", alias),
                    meshcoreHeard("ours", ours),
                    meshcoreHeard("stranger", "Knit 9f9f"),
                    meshcoreHeard("unnamed", null),
                )
            advanceUntilIdle()

            val clash =
                vm.state.value.rows
                    .associate { it.id to it.origin?.nameClash }
            assertEquals(mapOf("namesake" to true, "alias" to true, "ours" to true, "stranger" to false, "unnamed" to false), clash)
            assertEquals(
                context.getString(R.string.chat_meshcore_unnamed),
                vm.state.value.rows
                    .single { it.id == "unnamed" }
                    .senderName,
            )
        }

    @Test
    fun thePinnedBarMarksEveryMeshcoreAuthor() =
        runTest {
            // The pinned bar is one line: every MeshCore author is marked there, whatever it matches (security review round
            // 2, the maintainer's call, 2026-11-15); "Unnamed" stays plain.
            val pins = mockk<PinRepository>(relaxed = true)
            val names = listOf(context.getString(R.string.chat_self_name), "sam", "Knit 1a2b", "Bob", null)
            every { pins.observePinnedIn(Conversations.MESHCORE_PUBLIC) } returns
                flowOf(names.mapIndexed { i, name -> PinnedMessage(meshcoreHeard("p$i", name), 50L - i) })
            peersFlow.value = listOf(peer("sam", name = "Sam"))
            val vm = vm(Conversations.MESHCORE_PUBLIC, pins = pins)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pinned.collect {} }
            advanceUntilIdle()

            val claimed = names.filterNotNull().map { context.getString(R.string.chat_meshcore_name_claimed, it) }
            assertEquals(claimed + context.getString(R.string.chat_meshcore_unnamed), vm.pinned.value.map { it.author })
        }

    @Test
    fun aMeshcorePostTheFilterHidesNamesNobody() =
        runTest {
            // Its name was screened with its words (security N2), so a hidden post shows no name either.
            filteringFlow.value = true
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(meshcoreHeard("flagged", "Slurname", MessageEntity.MODERATION_TEXT_FLAGGED))
            advanceUntilIdle()

            val row =
                vm.state.value.rows
                    .single()
            assertTrue(row.moderationFlagged)
            assertEquals(context.getString(R.string.chat_meshcore_unnamed), row.senderName)
        }

    /**
     * The directory as `PeerRepository` emits it with the name screen wired (ADR 2026-11.y4pc): drawn through the shipped
     * screen while [filteringFlow] is on, as stored once it is off — the one toggle the words' filter also reads.
     */
    private fun screenNamesWhileFiltering() {
        val names = ShippedWordList.shownNames()
        every { peers.observeDirectory() } returns
            combine(peersFlow, filteringFlow) { p, on -> directoryOf(p, screen = names.takeIf { on }) }
    }

    /**
     * ADR 2026-11.y4pc on the Stage 6 room: a MeshCore author's name the name screen hides reads as "Unnamed" in the bubble,
     * and the post itself stays shown (the words' filter passed it). A known person holds the same name, so a name that
     * got past the screen would also carry the clash marker; with filtering off it shows as written, marker and all.
     */
    @Test
    fun aMeshcoreNameTheNameScreenHidesIsUnnamedInTheBubbleUntilFilteringIsOff() =
        runTest {
            screenNamesWhileFiltering()
            peersFlow.value = listOf(peer("namesake", name = ShippedWordList.FLAGGED_NAME))
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(meshcoreHeard("flagged", ShippedWordList.FLAGGED_NAME))
            advanceUntilIdle()

            val unnamed = context.getString(R.string.chat_meshcore_unnamed)
            val hidden =
                vm.state.value.rows
                    .single()
            assertFalse("only the name is hidden, never the post", hidden.moderationFlagged)
            assertEquals("anyone around?", hidden.body)
            val origin = checkNotNull(hidden.origin)
            assertTrue(origin.meshcore)
            assertNull("the screened name, not the claimed one: $origin", origin.name)
            assertEquals(unnamed, origin.nodeLabel)
            assertEquals(unnamed, hidden.senderName)
            assertFalse("no name, so nothing to clash: $origin", origin.nameClash)

            filteringFlow.value = false
            advanceUntilIdle()
            val shown =
                vm.state.value.rows
                    .single()
            assertEquals(ShippedWordList.FLAGGED_NAME, shown.origin?.name)
            assertEquals(ShippedWordList.FLAGGED_NAME, shown.origin?.nodeLabel)
            assertEquals(ShippedWordList.FLAGGED_NAME, shown.senderName)
            assertEquals("the namesake's marker", true, shown.origin?.nameClash)
        }

    /** The pinned bar's one line reads the bubble's origin ([aMeshcoreNameTheNameScreenHidesIsUnnamedInTheBubbleUntilFilteringIsOff]). */
    @Test
    fun thePinnedBarNamesAMeshcoreAuthorTheNameScreenHidesUnnamedUntilFilteringIsOff() =
        runTest {
            screenNamesWhileFiltering()
            val pins = mockk<PinRepository>(relaxed = true)
            every { pins.observePinnedIn(Conversations.MESHCORE_PUBLIC) } returns
                flowOf(listOf(PinnedMessage(meshcoreHeard("p", ShippedWordList.FLAGGED_NAME), 50L)))
            val vm = vm(Conversations.MESHCORE_PUBLIC, pins = pins)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pinned.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(context.getString(R.string.chat_meshcore_unnamed)), vm.pinned.value.map { it.author })

            filteringFlow.value = false
            advanceUntilIdle()
            assertEquals(
                listOf(context.getString(R.string.chat_meshcore_name_claimed, ShippedWordList.FLAGGED_NAME)),
                vm.pinned.value.map { it.author },
            )
        }

    /**
     * Main's half of ADR 2026-11.y4pc: a heard Meshtastic post's board name the name screen hides is never drawn, and the
     * bubble falls back to the `!hex` id every Meshtastic client shows for the node.
     */
    @Test
    fun aMeshtasticBoardNameTheNameScreenHidesFallsBackToTheNodeIdUntilFilteringIsOff() =
        runTest {
            screenNamesWhileFiltering()
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        body = "anyone around?",
                        id = "mp-flagged",
                        sentAt = 100,
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcd,
                        originName = ShippedWordList.FLAGGED_NAME,
                    ),
                )
            advanceUntilIdle()

            val hidden =
                vm.state.value.rows
                    .single()
            assertNull("the screened board name: ${hidden.origin}", hidden.origin?.name)
            assertEquals("!1234abcd", hidden.senderName)

            filteringFlow.value = false
            advanceUntilIdle()
            val shown =
                vm.state.value.rows
                    .single()
            assertEquals(ShippedWordList.FLAGGED_NAME, shown.origin?.name)
            assertEquals(ShippedWordList.FLAGGED_NAME, shown.senderName)
        }

    @Test
    fun theMeshcoreRoomIsGatedTitledAndBudgetedByItsOwnKind() =
        runTest {
            // The aggregate is a live Meshtastic board's: the MeshCore room reads its own kind's facts (lora N5).
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, primaryChannel = "LongFast")
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHTASTIC to loraFactsFlow.value)
            advanceUntilIdle()

            assertEquals("no MeshCore board bound: history, and a refusal", PublicPostGate.NoRadio, vm.state.value.publicPostGate)
            assertEquals(context.getString(R.string.meshcore_title), vm.state.value.title)
            assertEquals("the floor before any ledger: the cap under the longest name", 127, vm.state.value.publicPostBudget)

            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            assertEquals(PublicPostGate.Open, vm.state.value.publicPostGate)
            assertEquals("slot 0's own name, after the network's", "MeshCore (Public)", vm.state.value.title)
            assertEquals("the flood ceiling's own budget, never Meshtastic's signing cliff", 64, vm.state.value.publicPostBudget)

            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive.copy(canPost = false))
            advanceUntilIdle()
            assertEquals("not set up for Knit, or slot 0 empty", PublicPostGate.ChannelUnusable, vm.state.value.publicPostGate)
        }

    @Test
    fun theMeshtasticRoomNeverReadsABoundMeshcoreBoardsFacts() =
        runTest {
            // lora-reviewer's round-1 finding 3 on 6a: with a MeshCore board bound, the aggregate is MeshCore's, and the
            // Meshtastic room's composer must not open titled with MeshCore's slot 0.
            loraFactsFlow.value = meshcoreLive
            val vm = vm(Conversations.MESHTASTIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive, BoardKind.MESHTASTIC to LoraFacts())
            advanceUntilIdle()

            assertEquals(PublicPostGate.NoRadio, vm.state.value.publicPostGate)
            assertEquals(context.getString(R.string.meshtastic_title), vm.state.value.title)
            assertFalse(vm.state.value.isMeshcoreRoom)
        }

    /**
     * The MeshCore consent's position line (ADR 2026-11.gsqu's Stage 7 amendment): shown unless the board is known not to share
     * where it is, recorded with the consent, and asked again once a board that shares it was consented without the line.
     */
    @Test
    fun aMeshcoreBoardThatSharesWhereItIsAsksAgainUntilTheConsentNamedIt() =
        runTest {
            meshcoreConsentFlow.value = true
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            assertFalse("a board that shares nothing: the old consent holds", vm.state.value.needsPublicConsent)
            assertEquals(false, vm.state.value.meshcoreSharesPosition)

            meshcoreSharesFlow.value = true
            advanceUntilIdle()
            assertTrue("now it shares where it is: ask again", vm.state.value.needsPublicConsent)
            assertEquals(true, vm.state.value.meshcoreSharesPosition)

            vm.send("hello mesh")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)
            assertTrue(mesh.sentPublicPosts.isEmpty())
            vm.acceptPublicConsent(positionShown = true)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = true) }

            meshcorePositionConsentFlow.value = true
            advanceUntilIdle()
            assertFalse(vm.state.value.needsPublicConsent)
        }

    /**
     * The consent records what the accepted sheet showed, never the state at the tap (security-reviewer, Stage 7b): a sheet drawn
     * without the position line, accepted after the board turned out to share, is a consent without the line, and the room
     * asks again. And a board whose policy is not read yet asks again for a consent given without the line.
     */
    @Test
    fun aMeshcoreConsentRecordsTheSheetItWasGivenOnNotTheStateAtTheTap() =
        runTest {
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            vm.send("hello mesh")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)

            // The board's policy moves while the sheet drawn without the line is open, and the user accepts that sheet.
            meshcoreSharesFlow.value = true
            advanceUntilIdle()
            vm.acceptPublicConsent(positionShown = false)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = false) }
            meshcoreConsentFlow.value = true
            advanceUntilIdle()
            assertTrue("a board that shares, consented without the line: ask again", vm.state.value.needsPublicConsent)

            meshcoreSharesFlow.value = null
            advanceUntilIdle()
            assertTrue("not read yet: ask again, as the sheet shows the line", vm.state.value.needsPublicConsent)

            meshcoreSharesFlow.value = false
            advanceUntilIdle()
            assertFalse("known not to share: the consent holds", vm.state.value.needsPublicConsent)
        }

    @Test
    fun aMeshcoreConsentWithoutThePositionLineSaysSo() =
        runTest {
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            assertTrue(vm.state.value.needsPublicConsent)

            vm.send("hello mesh")
            advanceUntilIdle()
            vm.acceptPublicConsent(positionShown = false)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = false) }
        }

    @Test
    fun aMeshcorePostAsksItsOwnConsentPostsInItsOwnRoomAndWordsItsOwnRefusals() =
        runTest {
            // Consent is per kind (security N13): the Meshtastic one, accepted here, never posts through MeshCore.
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()

            vm.send("hello mesh")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)
            assertEquals("the sheet names the board", "Knit 1a2b", vm.state.value.meshcoreBoardName)
            assertTrue(mesh.sentPublicPosts.isEmpty())

            // The board's position policy is not read yet, so the sheet showed its position line, and the consent says so.
            vm.acceptPublicConsent(positionShown = true)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = true) }
            coVerify(exactly = 0) { settings.acceptMeshtasticPostConsent() }
            meshcoreConsentFlow.value = true
            meshcorePositionConsentFlow.value = true
            advanceUntilIdle()

            val expected =
                mapOf(
                    PublicPostRefusal.NOT_SET_UP to R.string.chat_meshcore_post_not_set_up,
                    PublicPostRefusal.NAME_CHANGED to R.string.chat_meshcore_post_name_changed,
                    PublicPostRefusal.NAME_UNPOSTABLE to R.string.chat_meshcore_post_name_unpostable,
                    PublicPostRefusal.NO_CHANNEL to R.string.chat_meshcore_post_no_channel,
                    PublicPostRefusal.ROOM_OFF to R.string.chat_meshcore_post_room_off,
                    PublicPostRefusal.TOO_SOON to R.string.chat_mesh_post_too_soon,
                )
            for ((reason, string) in expected) {
                mesh.publicPostOutcome = PublicPostOutcome.Refused(reason)
                vm.send("hello mesh")
                advanceUntilIdle()
                assertEquals(reason.name, string, events.last())
            }
            assertEquals(List(expected.size) { Conversations.MESHCORE_PUBLIC }, mesh.sentPublicPostRooms)
            assertEquals("each carries the name it was consented under", List(expected.size) { boardHandle }, mesh.sentPublicPostHandles)
        }

    // --- MeshCore One reactions in the MeshCore room (ADR 2026-11.gsqu's 2026-11-21 amendment) ---

    /** A heard MeshCore post carrying [tag], as the room stores one since v38. */
    private fun taggedHeard(
        id: String,
        name: String? = "Bob",
        tag: String? = "b45pc4ek",
        moderation: Int = MessageEntity.MODERATION_NONE,
    ) = meshcoreHeard(id, name, moderation).copy(meshcoreTag = tag)

    /** A MeshCore room chat with its board live and its consent given, collecting state and [events]. */
    private fun TestScope.meshcoreRoom(events: MutableList<Int>): ChatViewModel {
        meshcoreConsentFlow.value = true
        meshcoreSharesFlow.value = false
        val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
        return vm
    }

    @Test
    fun aMeshcoreRoomReactionGoesOutInMeshcoreOnesFormUnderTheConsentedName() =
        runTest {
            val events = mutableListOf<Int>()
            val vm = meshcoreRoom(events)
            messagesFlow.value = listOf(taggedHeard("heard"))
            advanceUntilIdle()

            vm.react("heard", "👍")
            advanceUntilIdle()

            assertEquals(listOf(Triple("heard", "👍", boardHandle)), mesh.sentMeshcoreReactions)
            assertTrue("never a Knit reaction", mesh.sentReactions.isEmpty())
            coVerify(exactly = 1) { settings.recordReaction("👍") }
            assertTrue(events.isEmpty())
        }

    @Test
    fun aMeshcoreRoomReactionTakesTheComposersGatesInItsOrder() =
        runTest {
            val events = mutableListOf<Int>()
            meshcoreNamingFlow.value = meshcoreNaming("Sam: hi", unpostable = true)
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            messagesFlow.value = listOf(taggedHeard("heard"))
            advanceUntilIdle()

            // Not connected comes first, whatever the name or the consent.
            vm.react("heard", "👍")
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_mesh_post_not_connected), events)

            // Connected: a name the plane won't post under, before any sheet.
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            vm.react("heard", "👍")
            advanceUntilIdle()
            assertEquals(R.string.chat_meshcore_post_name_unpostable, events.last())
            assertFalse(vm.showPublicConsent.value)

            // A postable name, no consent yet: the sheet, and the tap is made again after it.
            meshcoreNamingFlow.value = meshcoreNaming("Knit 1a2b", default = true)
            advanceUntilIdle()
            vm.react("heard", "👍")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)
            assertEquals("no further word", 2, events.size)

            assertTrue("nothing went out", mesh.sentMeshcoreReactions.isEmpty())
            coVerify(exactly = 0) { settings.recordReaction(any()) }
        }

    @Test
    fun takingOurOwnMeshcoreReactionOffNeedsNoRadioAndSaysItStaysOnTheChannel() =
        runTest {
            val events = mutableListOf<Int>()
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            messagesFlow.value = listOf(taggedHeard("heard"))
            reactionsFlow.value = listOf(ReactionEntity("heard", "me", "👍", 1))
            mesh.meshcoreReactionOutcome = MeshcoreReactionOutcome.RemovedHere
            advanceUntilIdle()
            assertTrue(
                "our chip is drawn",
                vm.state.value.rows
                    .single()
                    .reactions
                    .single()
                    .mine,
            )

            // No radio and no consent: a removal is this phone's alone.
            vm.react("heard", "👍")
            advanceUntilIdle()

            assertEquals(listOf("heard" to "👍"), mesh.sentMeshcoreReactions.map { it.first to it.second })
            assertEquals(listOf(R.string.chat_meshcore_reaction_removed_here), events)
            assertFalse(vm.showPublicConsent.value)
            coVerify(exactly = 0) { settings.recordReaction(any()) }
        }

    @Test
    fun eachMeshcoreReactionOutcomeIsWorded() =
        runTest {
            val events = mutableListOf<Int>()
            val vm = meshcoreRoom(events)
            messagesFlow.value = listOf(taggedHeard("heard"))
            advanceUntilIdle()

            val expected =
                listOf(
                    MeshcoreReactionOutcome.Unaddressable to R.string.chat_meshcore_reaction_unaddressable,
                    MeshcoreReactionOutcome.Blocked to R.string.moderation_text_blocked,
                    // A reaction counts as a post for the floor, and says so rather than "Not posted".
                    MeshcoreReactionOutcome.Refused(PublicPostRefusal.TOO_SOON) to R.string.chat_meshcore_reaction_too_soon,
                    // Every other refusal takes the composer's words.
                    MeshcoreReactionOutcome.Refused(PublicPostRefusal.NAME_CHANGED) to R.string.chat_meshcore_post_name_changed,
                    MeshcoreReactionOutcome.Refused(PublicPostRefusal.NO_AIR) to R.string.chat_mesh_post_no_air,
                )
            for ((outcome, string) in expected) {
                mesh.meshcoreReactionOutcome = outcome
                vm.react("heard", "🎉")
                advanceUntilIdle()
                assertEquals(outcome.toString(), string, events.last())
            }
            assertEquals(expected.size, events.size)
            coVerify(exactly = 0) { settings.recordReaction(any()) }
        }

    @Test
    fun onlyAPostMeshcoreOneCanNameOffersAReaction() =
        runTest {
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            val ownTagged =
                msg(senderId = "me", id = "own", sentAt = 100, conversationId = Conversations.MESHCORE_PUBLIC)
                    .copy(meshcoreTag = "0123abcd")
            messagesFlow.value =
                listOf(
                    taggedHeard("heard"),
                    ownTagged,
                    taggedHeard("untagged", tag = null),
                    taggedHeard("flagged", moderation = MessageEntity.MODERATION_TEXT_FLAGGED),
                    taggedHeard("bracketed", name = "a]b"),
                    taggedHeard("unnamed", name = null),
                )
            advanceUntilIdle()

            val reactable =
                vm.state.value.rows
                    .associate { it.id to it.meshcoreReactable }
            assertEquals(
                mapOf(
                    "heard" to true,
                    "own" to true,
                    "untagged" to false,
                    "flagged" to false,
                    "bracketed" to false,
                    "unnamed" to false,
                ),
                reactable,
            )

            // Our own post is named by the board's name: while the board has none, it can't be reacted to.
            meshcoreNamingFlow.value = null
            advanceUntilIdle()
            assertFalse(
                vm.state.value.rows
                    .single { it.id == "own" }
                    .meshcoreReactable,
            )
        }

    /** A quote of row [id] as the composer builds one: a bridged room's never leaves the phone. */
    private fun quoting(id: String) = ReplyRef(messageId = id, authorId = "", author = "", snippet = "")

    /**
     * A reply in the MeshCore room goes out in MeshCore's own form (ADR 2026-11.gsqu's 2026-11-19 amendment): the quoted
     * post's name in brackets ahead of the words, our board's name for one of ours, and a plain post where there is no name
     * to address. The prefix is the post's own bytes, so the room's cap counts it.
     */
    @Test
    fun aMeshcoreReplyGoesOutInMeshcoresOwnFormAndItsPrefixCountsAgainstTheCap() =
        runTest {
            meshcoreConsentFlow.value = true
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            messagesFlow.value =
                listOf(
                    meshcoreHeard("bob", "Bob"),
                    meshcoreHeard("unnamed", null),
                    msg(senderId = "me", id = "ours", sentAt = 50, body = "earlier", conversationId = Conversations.MESHCORE_PUBLIC),
                )
            advanceUntilIdle()

            for ((text, quoted) in listOf("on my way" to "bob", "still here" to "ours", "hi" to "unnamed", "gone" to "swept")) {
                vm.send(text, replyTo = quoting(quoted))
                advanceUntilIdle()
                vm.onInputCleared()
            }
            assertEquals(listOf("@[Bob] on my way", "@[Knit 1a2b] still here", "hi", "gone"), mesh.sentPublicPosts)

            // `meshcoreLive` leaves 64 B: "@[Bob] " takes 7, so 58 B of words no longer fit, and nothing is posted.
            vm.send("x".repeat(58), replyTo = quoting("bob"))
            advanceUntilIdle()
            assertEquals(R.string.chat_mesh_post_too_large, events.last())
            assertEquals(4, mesh.sentPublicPosts.size)
            vm.send("x".repeat(57), replyTo = quoting("bob"))
            advanceUntilIdle()
            assertEquals("@[Bob] " + "x".repeat(57), mesh.sentPublicPosts.last())
        }

    /**
     * The Meshtastic room has no reply form: a quote there leaves the words as they are. A pin, not coverage of one guard:
     * it holds by three (`postPublic`'s room check, `meshcoreHandle` read only in the MeshCore room, and a heard row's
     * name taken only from a MeshCore origin), and no row a view-model rig can build gets past the last two.
     */
    @Test
    fun aMeshtasticRoomPostCarriesNoReplyPrefix() =
        runTest {
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, primaryChannel = "LongFast")
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        id = "heard",
                        conversationId = Conversations.MESHTASTIC,
                        originNode = 0x1234abcdL,
                        originName = "Bob",
                    ),
                )
            advanceUntilIdle()

            vm.send("on my way", replyTo = quoting("heard"))
            advanceUntilIdle()

            assertEquals(listOf("on my way"), mesh.sentPublicPosts)
        }

    /**
     * A MeshCore room row draws only the mentions stored on it (the 2026-11-19 amendment, security review): the one its
     * delivery bound to us ([InboundPipelineTest] pins the binding), never an `@[name]` read off a stranger's text, a
     * contact's name included.
     */
    @Test
    fun onlyAMeshcoreMentionOfUsDrawsAsAMention() =
        runTest {
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("sam", name = "Sam"))
            messagesFlow.value =
                listOf(
                    meshcoreHeard("reply", "Bob").copy(
                        body = "@[Knit 1a2b] ask @[Sam] too",
                        mentions = MentionStore.encode(listOf(Mention("me", "[Knit 1a2b]"))),
                    ),
                    meshcoreHeard("plain", "Bob").copy(body = "@[Sam] are you there?"),
                )
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertEquals(listOf(Mention("me", "[Knit 1a2b]")), rows.getValue("reply").mentions)
            // The binding our receive code made is drawn, though "[Knit 1a2b]" is no label of ours (ADR 2026-11.p6dk).
            assertEquals(listOf(Mention("me", "[Knit 1a2b]")), rows.getValue("reply").styledMentions)
            assertEquals(
                "[@[Knit 1a2b]]",
                mentionSpans(rows.getValue("reply").body, rows.getValue("reply").styledMentions)
                    .map { rows.getValue("reply").body.substring(it.start, it.end) }
                    .toString(),
            )
            assertTrue("a contact's name in a stranger's text is no mention", rows.getValue("plain").mentions.isEmpty())
        }

    /**
     * Post consent follows the board's name (ADR 2026-11.f4d3): a consent given on a sheet that named the `Knit abcd` default
     * does not cover a name the owner chose, so the room asks again under that name. Accepting the sheet that showed it
     * records so, and the post then carries exactly that name down to the plane. A move between chosen names is not asked
     * again (the maintainer's accepted residual: the composer line names it), and back to the default never is.
     */
    @Test
    fun aMeshcoreConsentGivenUnderTheDefaultAsksAgainForAChosenName() =
        runTest {
            meshcoreConsentFlow.value = true
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            assertFalse("the default, consented: it holds", vm.state.value.needsPublicConsent)
            assertFalse(vm.state.value.meshcoreBoardCustom)

            meshcoreNamingFlow.value = meshcoreNaming("Sam 🧶")
            advanceUntilIdle()
            assertTrue("renamed in another app: ask again", vm.state.value.needsPublicConsent)
            assertEquals("the sheet names the handle", "Sam 🧶", vm.state.value.meshcoreBoardName)
            assertTrue(vm.state.value.meshcoreBoardCustom)

            vm.send("hello mesh")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)
            assertTrue(mesh.sentPublicPosts.isEmpty())
            vm.acceptPublicConsent(positionShown = false, customNameShown = true)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = false, withCustomName = true) }
            meshcoreCustomNameConsentFlow.value = true
            advanceUntilIdle()
            assertFalse("a sheet named a chosen name: the consent covers it", vm.state.value.needsPublicConsent)

            vm.send("hello mesh")
            advanceUntilIdle()
            vm.onInputCleared()
            meshcoreNamingFlow.value = meshcoreNaming("Alex")
            advanceUntilIdle()
            assertFalse("chosen to chosen: not asked again", vm.state.value.needsPublicConsent)
            vm.send("hello again")
            advanceUntilIdle()
            vm.onInputCleared()
            meshcoreNamingFlow.value = meshcoreNaming("Knit 1a2b", default = true)
            meshcoreCustomNameConsentFlow.value = false
            advanceUntilIdle()
            assertFalse("the default never asks again", vm.state.value.needsPublicConsent)

            assertEquals(listOf("hello mesh", "hello again"), mesh.sentPublicPosts)
            assertEquals(
                "each post carries the name its consent decision saw",
                listOf(meshcoreNaming("Sam 🧶").handle, meshcoreNaming("Alex").handle),
                mesh.sentPublicPostHandles,
            )
        }

    /** A sheet that named the default records no chosen name, whatever the board is called by the time of the tap. */
    @Test
    fun aMeshcoreConsentOnTheDefaultsSheetDoesNotCoverAChosenName() =
        runTest {
            meshcoreSharesFlow.value = false
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            vm.send("hello mesh")
            advanceUntilIdle()
            assertTrue(vm.showPublicConsent.value)

            meshcoreNamingFlow.value = meshcoreNaming("Sam 🧶")
            advanceUntilIdle()
            vm.acceptPublicConsent(positionShown = false, customNameShown = false)
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.acceptMeshcorePostConsent(withPosition = false, withCustomName = false) }
            meshcoreConsentFlow.value = true
            advanceUntilIdle()
            assertTrue("consented on the default's sheet, now a chosen name: ask again", vm.state.value.needsPublicConsent)
        }

    /** A name another app left unpostable is refused before any sheet, as the plane would refuse it (ADR 2026-11.f4d3). */
    @Test
    fun anUnpostableMeshcoreNameIsRefusedBeforeTheSheet() =
        runTest {
            meshcoreNamingFlow.value = meshcoreNaming("Sam: hi", unpostable = true)
            val vm = vm(Conversations.MESHCORE_PUBLIC, loraRooms = loraRoomsFlow)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            loraRoomsFlow.value = mapOf(BoardKind.MESHCORE to meshcoreLive)
            advanceUntilIdle()
            assertTrue(vm.state.value.meshcoreNameUnpostable)

            vm.send("hello mesh")
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_meshcore_post_name_unpostable), events)
            assertFalse("no sheet for a name that cannot post", vm.showPublicConsent.value)
            assertTrue(mesh.sentPublicPosts.isEmpty())
        }

    @Test
    fun ourOwnMeshcorePostThatEndedReadsAsNotSentOrUnconfirmedNeverSent() =
        runTest {
            // The plane's ends, marked on the row (`MessageRepository.markPublicPostEnded`): `STALE` is never sent, an end
            // at the write may have aired — never the same words.
            val vm = vm(Conversations.MESHCORE_PUBLIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

            fun own(
                id: String,
                pendingKey: Boolean = false,
                via: DeliveryPlane = DeliveryPlane.LoRa,
            ) = msg(
                senderId = "me",
                id = id,
                sentAt = 100,
                conversationId = Conversations.MESHCORE_PUBLIC,
                pendingKey = pendingKey,
                receivedVia = via.code,
            )
            messagesFlow.value = listOf(own("sent"), own("stale", pendingKey = true), own("write", via = DeliveryPlane.Unknown))
            advanceUntilIdle()

            val ended =
                vm.state.value.rows
                    .associate { it.id to it.postEnded }
            assertEquals(mapOf("sent" to null, "stale" to DeliveryStatus.NotSent, "write" to DeliveryStatus.Unconfirmed), ended)
            assertEquals(
                R.string.chat_status_post_not_sent,
                deliveryLabel(DeliveryStatus.NotSent, DeliveryPlane.Unknown, mine = true).resId,
            )
            assertEquals(
                R.string.chat_status_post_unconfirmed,
                deliveryLabel(DeliveryStatus.Unconfirmed, DeliveryPlane.Unknown, mine = true).resId,
            )
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
    fun aPostOverTheBudgetKeepsTheDraftRatherThanAiringPartOfIt() =
        runTest {
            // Shared text skips the composer's cap, and the board would air only what fits while the row kept it all.
            publicConsentFlow.value = true
            val vm = vm(Conversations.MESHTASTIC)
            val events = mutableListOf<Int>()
            var cleared = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.clearInput.collect { cleared++ } }
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true)
            advanceUntilIdle()
            val budget = requireNotNull(vm.state.value.publicPostBudget)

            vm.send("x".repeat(budget + 1))
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_mesh_post_too_large), events)
            assertTrue(mesh.sentPublicPosts.isEmpty())
            assertEquals("the draft stays the user's", 0, cleared)
            assertFalse(vm.isSending.value)

            vm.send("x".repeat(budget))
            advanceUntilIdle()

            assertEquals(1, mesh.sentPublicPosts.size)
            assertEquals(1, events.size)
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
            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, roomSignedUpTo = LoraAirtime.UNSIGNED)
            advanceUntilIdle()
            assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, vm.state.value.publicPostBudget)

            loraFactsFlow.value = LoraFacts(plane = LoraPlane.Live, canPost = true, roomSignedUpTo = PublicPostPolicy.MAX_SIGNED_TEXT_BYTES)
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
    fun aGroupThreadWhoseRowIsGoneNeverSendsAsADm() =
        runTest {
            // Deleted from the chat list, then opened by a stale shortcut: with no row the thread reads as a peer's,
            // and `sendChat` would file the text under the group id as a pending-key DM to a node that does not exist.
            coEvery { groups.find(GONE_GROUP) } returns null
            val vm = vm(GONE_GROUP)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            advanceUntilIdle()

            vm.send("anyone still here?")
            advanceUntilIdle()

            assertTrue(mesh.sentChats.isEmpty())
            assertEquals(listOf(R.string.chat_gone), events)
            assertFalse("the guard is released, the draft is the user's", vm.isSending.value)
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
    fun turningTypingIndicatorsOffHidesACueAlreadyShowing() =
        runTest {
            val vm = vm(Conversations.NEARBY)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            mesh.typing.value = mapOf(Conversations.NEARBY to setOf("sam"))
            advanceUntilIdle()
            assertEquals(
                listOf("sam"),
                vm.state.value.typingPeers
                    .map { it.nodeId },
            )

            typingIndicatorsFlow.value = false
            advanceUntilIdle()
            assertTrue(
                "hidden at once, before the cue's own hold runs out",
                vm.state.value.typingPeers
                    .isEmpty(),
            )
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

    /** ADR 2026-11.z6jw: a group send reads as read only once every other member has; the count rides meanwhile. */
    @Test
    fun groupRowsReadOnlyOnceEveryMemberHasRead() =
        runTest {
            val vm = vm(GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(GROUP, members = listOf("me", "sam", "priya", "theo"))
            messagesFlow.value =
                listOf(msg(senderId = "me", body = "mine", id = "g0", sentAt = 100, conversationId = GROUP, received = true))
            deliveredCountsFlow.value = mapOf("g0" to 3)
            readCountsFlow.value = mapOf("g0" to 2)
            advanceUntilIdle()

            val partly =
                vm.state.value.rows
                    .first { it.id == "g0" }
            assertEquals(2, partly.readCount)
            assertFalse(partly.read)

            readCountsFlow.value = mapOf("g0" to 3)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.rows
                    .first { it.id == "g0" }
                    .read,
            )

            // Reciprocal: with the setting off, nothing reads as read and no count rides.
            readReceiptsFlow.value = false
            advanceUntilIdle()
            val off =
                vm.state.value.rows
                    .first { it.id == "g0" }
            assertFalse(off.read)
            assertEquals(0, off.readCount)
        }

    /** A DM of ours reads as read once its peer has read it; their message never does. */
    @Test
    fun aDmRowReadsAsReadOnceItsPeerHas() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(
                        senderId = "me",
                        recipientId = "bob",
                        body = "mine",
                        id = "d0",
                        sentAt = 100,
                        conversationId = "bob",
                        received = true,
                    ),
                    msg(senderId = "bob", recipientId = "me", body = "theirs", id = "d1", sentAt = 200, conversationId = "bob"),
                )
            readCountsFlow.value = mapOf("d0" to 1, "d1" to 1)
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertTrue(rows.getValue("d0").read)
            assertFalse(rows.getValue("d1").read)
        }

    /** Our DM their LoRa radio acked, until its ✓✓ (ADR 040's 2026-10-31 amendment); theirs and a received one never. */
    @Test
    fun aDmRowCarriesItsRadioReachedUntilTheReceipt() =
        runTest {
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", recipientId = "bob", body = "on air", id = "d0", sentAt = 100, conversationId = "bob"),
                    msg(
                        senderId = "me",
                        recipientId = "bob",
                        body = "acked",
                        id = "d1",
                        sentAt = 150,
                        conversationId = "bob",
                        received = true,
                    ),
                    msg(senderId = "bob", recipientId = "me", body = "theirs", id = "d2", sentAt = 200, conversationId = "bob"),
                )
            mesh.loraRadioReached.value = listOf("d0", "d1", "d2").associateWith { LoraRadioReached(atMs = 1L, late = false) }
            advanceUntilIdle()

            val rows =
                vm.state.value.rows
                    .associateBy { it.id }
            assertTrue(rows.getValue("d0").radioReached)
            assertFalse("the ✓✓ wins", rows.getValue("d1").radioReached)
            assertFalse("never on theirs", rows.getValue("d2").radioReached)
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
    fun aReactionInTheMeshtasticRoomIsNeverSent() =
        runTest {
            // No tapback form there, and the manager's fallback is a cleartext flood over the radios.
            val vm = vm(conversationId = Conversations.MESHTASTIC)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = Conversations.MESHTASTIC))
            advanceUntilIdle()

            vm.react("m1", "🦄")
            advanceUntilIdle()

            assertTrue(mesh.sentReactions.isEmpty())
            assertTrue(mesh.sentCommonsReactions.isEmpty())
            coVerify(exactly = 0) { settings.recordReaction(any()) }
        }

    @Test
    fun aReactionInACommonsIsPostedToTheRoomAndNeverTakesTheMeshPath() =
        runTest {
            val room = Conversations.COMMONS_PREFIX + "ab".repeat(32)
            val vm = vm(conversationId = room)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = room))
            advanceUntilIdle()

            vm.react("m1", "🦄")
            advanceUntilIdle()

            assertEquals(listOf(Triple(room, "m1", "🦄")), mesh.sentCommonsReactions)
            assertTrue(mesh.sentReactions.isEmpty())
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
    fun aFileADirectLinkCarriedShowsTheLinkInPlaceOfTheRelay() =
        runTest {
            // ADR 2026-10.a5yj: the row's plane is the link's when a link proved the file held at the far end of this
            // row — the peer for ours, us for theirs — whatever plane the message itself crossed.
            stubDm(BOB)
            val relay = DeliveryPlane.Internet.code
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", id = "ours", conversationId = BOB, attachmentHash = "h1", received = true, receivedVia = relay),
                    msg(senderId = BOB, id = "theirs", conversationId = BOB, attachmentHash = "h2", receivedVia = relay),
                    msg(senderId = BOB, id = "relayed", conversationId = BOB, attachmentHash = "h3", receivedVia = relay),
                    msg(
                        senderId = "me",
                        id = "radioHeld",
                        conversationId = BOB,
                        attachmentHash = "h4",
                        received = true,
                        receivedVia = relay,
                    ),
                )
            val link = DeliveryPlane.DirectLink.code
            holdersFlow.value =
                mapOf(
                    "h1" to mapOf(BOB to link),
                    "h2" to mapOf("me" to link),
                    "h3" to mapOf(BOB to link),
                    "h4" to mapOf(BOB to DeliveryPlane.Nearby.code),
                )
            val vm = vm(conversationId = BOB)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            val planes =
                vm.state.value.rows
                    .associate { it.id to it.deliveredVia }
            assertEquals(DeliveryPlane.DirectLink, planes["ours"])
            assertEquals(DeliveryPlane.DirectLink, planes["theirs"])
            // Bob holding h3 says nothing of how it reached us: the relay that carried its message stands.
            assertEquals(DeliveryPlane.Internet, planes["relayed"])
            // A proof a radio gave is no link's: the row keeps its message's plane.
            assertEquals(DeliveryPlane.Internet, planes["radioHeld"])
        }

    @Test
    fun aDmFileOfOursLosesItsRelayMarkerOnceThePeerProvesItHoldsIt() =
        runTest {
            // ADR 2026-10.vy27: Bob proving he holds h1 settles it, whichever plane carried it, so "Not on relays" has
            // nothing left to say. Its neighbour keeps the marker: only Bob's proof counts, not ours or a carrier's.
            stubDm(BOB)
            relayFactsFlow.value =
                RelayFacts(
                    enabled = true,
                    configured = 1,
                    active = 1,
                    connected = 1,
                    coveredLabels = setOf(BOB),
                    maxAttachBytes = 1_000_000,
                    uploadRooms = emptyList(),
                )
            sizesFlow.value = mapOf("h1" to 2_000, "h2" to 2_000)
            messagesFlow.value =
                listOf(
                    msg(senderId = "me", id = "proved", conversationId = BOB, attachmentHash = "h1"),
                    msg(senderId = "me", id = "unproved", conversationId = BOB, attachmentHash = "h2"),
                )
            val vm = vm(conversationId = BOB)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            fun relayOf(id: String) =
                vm.state.value.rows
                    .single { it.id == id }
                    .attachmentRelay
            assertEquals(AttachmentRelay.Locked, relayOf("proved"))

            holdersFlow.value =
                mapOf(
                    "h1" to mapOf(BOB to DeliveryPlane.Nearby.code),
                    "h2" to mapOf("me" to DeliveryPlane.DirectLink.code, "carol" to DeliveryPlane.Nearby.code),
                )
            advanceUntilIdle()
            assertEquals(AttachmentRelay.Silent, relayOf("proved"))
            assertEquals(AttachmentRelay.Locked, relayOf("unproved"))
        }

    @Test
    fun aGroupFileKeepsItsRelayMarkerWhateverOneMemberHolds() =
        runTest {
            // One member's proof says nothing of the others', so a change that let a member's holding end a group
            // file's marker would fail here.
            groupFlow.value = group(GROUP, members = listOf("me", BOB, "carol"))
            relayFactsFlow.value =
                RelayFacts(
                    enabled = true,
                    configured = 1,
                    active = 1,
                    connected = 1,
                    coveredLabels = setOf(GROUP),
                    maxAttachBytes = 1_000_000,
                    uploadRooms = emptyList(),
                )
            sizesFlow.value = mapOf("h1" to 2_000)
            holdersFlow.value = mapOf("h1" to mapOf(BOB to DeliveryPlane.Nearby.code))
            messagesFlow.value = listOf(msg(senderId = "me", id = "m1", conversationId = GROUP, attachmentHash = "h1"))
            val vm = vm(conversationId = GROUP)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(
                AttachmentRelay.Locked,
                vm.state.value.rows
                    .single()
                    .attachmentRelay,
            )
        }

    @Test
    fun theRelayMarkerNamesADirectLinkOnlyInADmWhosePeerTakesLinksWhileOurSwitchIsOn() =
        runTest {
            // ADR 2026-10.a5yj: a link carries a DM file no relay takes, so the marker's dialog may say so, but only where
            // one can come: our switch on, and Bob's build taking links. His own switch is the dialog's "if".
            val switch = MutableStateFlow(true)
            stubDm(BOB)
            peersFlow.value = listOf(peer(BOB, "Bob", capabilities = Protocol.LOCAL_CAPABILITIES or Protocol.CAP_INET_LINK))
            val dm = vm(conversationId = BOB, directLinksOn = switch)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { dm.state.collect {} }
            advanceUntilIdle()
            assertTrue(dm.state.value.relayDirectLinkNote)

            switch.value = false
            advanceUntilIdle()
            assertFalse("our switch off: no link can come", dm.state.value.relayDirectLinkNote)

            switch.value = true
            peersFlow.value = listOf(peer(BOB, "Bob", capabilities = Protocol.LOCAL_CAPABILITIES))
            advanceUntilIdle()
            assertFalse("a build that takes no links is never offered one", dm.state.value.relayDirectLinkNote)

            // A group whose only other member takes links still says nothing: a change that read the members' bits for
            // a group would fail here.
            groupFlow.value = group(GROUP, members = listOf("me", BOB))
            peersFlow.value = listOf(peer(BOB, "Bob", capabilities = Protocol.LOCAL_CAPABILITIES or Protocol.CAP_INET_LINK))
            val inGroup = vm(conversationId = GROUP, directLinksOn = switch)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { inGroup.state.collect {} }
            advanceUntilIdle()
            assertFalse("a group's file takes no link to every member", inGroup.state.value.relayDirectLinkNote)
        }

    @Test
    fun blobSizesAreAskedForTheWindowsAttachmentsAndTheStagedOneOnly() =
        runTest {
            val ingested = AttachmentStore.Ingested(hash = "staged", mime = "image/jpeg")
            coEvery { attachments.ingest(any<Uri>(), any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
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
    fun arrivalsAreReadForTheWindowsAwaitedAttachmentsOnly() =
        runTest {
            // The progress ring's source (#115): the links are polled only while an attachment the window shows is
            // still on its way, and only those attachments' arrivals come back — not a held one's, not a link
            // card's (a card never draws progress), not one outside the window.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.arrivals.collect {} }
            advanceUntilIdle()
            assertEquals("nothing awaited: the links are not polled", 0, mesh.arrivals.subscriptionCount.value)

            val photo = ArrivingFile("h1", 84_000, 204_000)
            mesh.arrivals.value =
                mapOf(
                    "h1" to photo,
                    "held" to ArrivingFile("held", 1, 2),
                    "card" to ArrivingFile("card", 3, 4),
                    "elsewhere" to ArrivingFile("elsewhere", 5, 6),
                )
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1"),
                    msg(senderId = "bob", id = "m2", conversationId = Conversations.NEARBY, attachmentHash = "held"),
                    msg(senderId = "bob", id = "m3", conversationId = Conversations.NEARBY)
                        .copy(attachmentHash = "card", attachmentMime = LinkPreviewBlob.MIME),
                )
            sizesFlow.value = mapOf("held" to 2)
            advanceUntilIdle()
            assertEquals("one poll, for the one awaited", 1, mesh.arrivals.subscriptionCount.value)
            assertEquals(mapOf("h1" to photo), vm.arrivals.value)

            sizesFlow.value = mapOf("held" to 2, "h1" to 204_000)
            advanceUntilIdle()
            assertEquals("landed: nothing left to show", emptyMap<String, ArrivingFile>(), vm.arrivals.value)
            assertEquals("and the poll stops", 0, mesh.arrivals.subscriptionCount.value)
        }

    @Test
    fun aLargeReceivedAttachmentAwaitsTheTapUntilApprovedHeldOrUngated() =
        runTest {
            // ADR 2026-10.js2p: the card shows exactly where BluetoothFetchGate holds the fetch.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            val big = msg(senderId = "bob", id = "m1", conversationId = Conversations.NEARBY, attachmentHash = "h1")

            fun awaits() =
                vm.state.value.rows
                    .single()
                    .attachmentAwaitsTap

            messagesFlow.value = listOf(big.copy(attachmentSize = 2_000_000L))
            advanceUntilIdle()
            assertTrue("over the limit, not held", awaits())

            messagesFlow.value = listOf(big.copy(attachmentSize = 900_000L))
            advanceUntilIdle()
            assertFalse("under the limit", awaits())

            messagesFlow.value = listOf(big)
            advanceUntilIdle()
            assertFalse("no declared size", awaits())

            messagesFlow.value = listOf(big.copy(attachmentSize = 2_000_000L, attachmentFetchApproved = true))
            advanceUntilIdle()
            assertFalse("tapped", awaits())

            messagesFlow.value = listOf(big.copy(attachmentSize = 2_000_000L, senderId = "me"))
            advanceUntilIdle()
            assertFalse("our own", awaits())

            messagesFlow.value = listOf(big.copy(attachmentSize = 2_000_000L))
            downloadLimitFlow.value = null
            advanceUntilIdle()
            assertFalse("limit set to Never", awaits())

            downloadLimitFlow.value = 1_000_000L
            sizesFlow.value = mapOf("h1" to 2_000_000)
            advanceUntilIdle()
            assertFalse("held", awaits())
        }

    @Test
    fun aTapToDownloadReachesTheMesh() =
        runTest {
            vm().downloadAttachment("h1")
            advanceUntilIdle()
            assertEquals(listOf("h1"), mesh.downloadedAttachments)
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
    fun aReplyQuotingFlaggedWordsCollapsesLikeAFlaggedBodyAndNamesItsAuthorItself() =
        runTest {
            // ADR 2026-10.gpa3: a clean body quoting flagged words is hidden behind the same toggle, and the quote's
            // author is this reader's label for the quoted id, never the name the sender wrote.
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "bob", id = "r1", conversationId = Conversations.NEARBY, body = "lol")
                        .withReply(ReplyRef("o1", "ada", "Forged Name", "something vile"))
                        .copy(replyToFlagged = true),
                )
            advanceUntilIdle()
            val row =
                vm.state.value.rows
                    .single()
            assertTrue(row.moderationFlagged)
            assertEquals(QuoteState.Unverified, row.quote!!.state)
            assertFalse("the sender's string is never the label", row.quote.author == "Forged Name")

            filteringFlow.value = false
            advanceUntilIdle()
            assertFalse(
                vm.state.value.rows
                    .single()
                    .moderationFlagged,
            )
        }

    @Test
    fun aQuoteSaysHowFarItWasCheckedAndShowsNothingOfAMismatch() {
        val label: (String) -> String = { "label:$it" }

        fun reply(
            check: Int,
            authorId: String = "ada",
            from: String = "bob",
        ) = msg(senderId = from, id = "r", conversationId = Conversations.NEARBY)
            .withReply(ReplyRef("o1", authorId, "Forged", "words"))
            .copy(replyCheck = check)

        val verified = quoteViewFor(reply(MessageEntity.REPLY_VERIFIED), mine = false, me = "me", selfLabel = "You", labelOf = label)!!
        assertEquals(QuoteView("o1", "label:ada", "words", hasAttachment = false, state = QuoteState.Verified), verified)

        val unverified = quoteViewFor(reply(MessageEntity.REPLY_UNCHECKED), mine = false, me = "me", selfLabel = "You", labelOf = label)!!
        assertEquals(QuoteState.Unverified, unverified.state)

        val mismatch = quoteViewFor(reply(MessageEntity.REPLY_MISMATCH), mine = false, me = "me", selfLabel = "You", labelOf = label)!!
        assertEquals(QuoteView("o1", "", "", hasAttachment = false, state = QuoteState.Unavailable), mismatch)

        // We hold everything we sent, so an unmatched quote of ours was deleted, swept or forged.
        val selfQuote = quoteViewFor(reply(MessageEntity.REPLY_UNCHECKED, authorId = "me"), false, "me", "You", label)!!
        assertEquals(QuoteState.Unavailable, selfQuote.state)
        val selfVerified = quoteViewFor(reply(MessageEntity.REPLY_VERIFIED, authorId = "me"), false, "me", "You", label)!!
        assertEquals("You", selfVerified.author)

        // Our own replies are trusted: we quoted a row we held.
        val mine = quoteViewFor(reply(MessageEntity.REPLY_UNCHECKED, from = "me"), true, "me", "You", label)!!
        assertEquals(QuoteState.Verified, mine.state)

        assertNull(quoteViewFor(msg(senderId = "bob", id = "plain"), mine = false, me = "me", selfLabel = "You", labelOf = label))
    }

    @Test
    fun theStoredDraftIsHandedOverOnceAndOnlyThenAreEditsKept() =
        runTest {
            coEvery { drafts.load(Conversations.NEARBY) } returns Draft("half a sentence")
            val vm = vm()

            // The composer reports its empty field the moment it composes — before the stored draft has been
            // taken. Persisting that would erase the very draft the screen is about to put back.
            vm.onDraftChanged(Draft(""))
            advanceUntilIdle()
            verify(exactly = 0) { drafts.save(any(), any()) }

            assertEquals(Draft("half a sentence"), vm.consumeRestoredDraft())
            // A rotation re-runs the screen's restore effect; a draft since cleared must not come back.
            assertEquals(Draft.EMPTY, vm.consumeRestoredDraft())

            // The restore itself comes back through the composer's collector, indistinguishable from a
            // keystroke. It is not one: the row already holds this text, and saving it again would re-stamp
            // `updatedAt` and float "Draft: …" back over a message that landed after it.
            vm.onDraftChanged(Draft("half a sentence"))
            verify(exactly = 0) { drafts.save(any(), any()) }

            vm.onDraftChanged(Draft("half a sentence more"))
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, Draft("half a sentence more")) }
            // A recomposition (rotation, a screen popped off the chat) re-reports the unchanged text.
            vm.onDraftChanged(Draft("half a sentence more"))
            verify(exactly = 1) { drafts.save(any(), any()) }
        }

    @Test
    fun aDraftsFormattingComesBackWithItAndAChangeToTheFormattingAloneIsKept() =
        runTest {
            val bold = listOf(StyleSpan(0, 4, BodyStyle.BOLD))
            coEvery { drafts.load(Conversations.NEARBY) } returns Draft("half a sentence", bold)
            val vm = vm()

            assertEquals(Draft("half a sentence", bold), vm.consumeRestoredDraft())
            // The restore's echo carries the styles the row holds: no edit, so no write and no new stamp.
            vm.onDraftChanged(Draft("half a sentence", bold))
            verify(exactly = 0) { drafts.save(any(), any()) }

            // The same words with the bold taken off are a different draft.
            vm.onDraftChanged(Draft("half a sentence"))
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, Draft("half a sentence")) }
        }

    @Test
    fun aRestoredDraftIsNotATypingCue() =
        runTest {
            coEvery { drafts.load(Conversations.NEARBY) } returns Draft("half a sentence")
            val vm = vm()
            vm.onChatForeground()
            assertEquals(Draft("half a sentence"), vm.consumeRestoredDraft())

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
            val vm = vm() // setUp stubs the repository to load Draft.EMPTY
            assertEquals(Draft.EMPTY, vm.consumeRestoredDraft())

            // The composer's initial snapshot, now that the (empty) draft has been taken: nothing to keep.
            vm.onDraftChanged(Draft(""))
            verify(exactly = 0) { drafts.save(any(), any()) }

            vm.onDraftChanged(Draft("h"))
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, Draft("h")) }

            // After an accepted send the row is dropped directly; the field's own empty report that follows
            // matches what the row now holds, and the next keystroke starts a fresh draft.
            vm.send("h")
            advanceUntilIdle()
            verify { drafts.clear(Conversations.NEARBY) }
            vm.onDraftChanged(Draft(""))
            verify(exactly = 1) { drafts.save(any(), any()) }
            vm.onDraftChanged(Draft("again"))
            verify(exactly = 1) { drafts.save(Conversations.NEARBY, Draft("again")) }
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
    fun aDraftOverTheCapIsRefusedWholeNotCutShort() =
        runTest {
            val vm = vm()
            val events = mutableListOf<Int>()
            var cleared = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.clearInput.collect { cleared++ } }

            vm.send("x".repeat(TextLimits.MESSAGE + 1))
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_message_too_long_to_send), events)
            assertTrue(mesh.sentChats.isEmpty())
            assertEquals("the draft stays the user's", 0, cleared)
            assertFalse(vm.isSending.value)

            // The outer whitespace is trimmed before the send measures, so it cannot be what refuses a draft.
            val fits = "x".repeat(TextLimits.MESSAGE)
            vm.send("  $fits\n")
            advanceUntilIdle()

            assertEquals(fits, mesh.sentChats.single().text)
            assertEquals(1, events.size)
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
            coEvery { attachments.ingest(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attach(uri)
            advanceUntilIdle()

            assertNull("flagged image is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h1") }
            coVerify { attachments.ingest(uri, true) }
        }

    @Test
    fun aCommonsTakesAPictureUnscreenedAndSendsItThroughTheRoomsOwnPath() =
        runTest {
            val room = Conversations.commonsIdFor("ab".repeat(32))
            val uri = Uri.parse("content://images/2")
            val ingested = AttachmentStore.Ingested(hash = "h2", mime = "image/jpeg")
            coEvery { attachments.ingest(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            stubDm(room)
            val vm = vm(conversationId = room)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue("a commons takes files as a group does", vm.state.value.canSendFile)

            vm.attach(uri)
            advanceUntilIdle()
            assertEquals(ingested, vm.pendingAttachment.value)
            // Never screened on the way out: only the Nearby room's sender screens (ADR 2026-10.9rh6).
            coVerify { attachments.ingest(uri, false) }

            vm.send("look")
            advanceUntilIdle()
            assertEquals(listOf(room to "look"), mesh.sentCommons)
            assertEquals(listOf<AttachmentStore.Ingested?>(ingested), mesh.sentCommonsAttachments)
        }

    @Test
    fun aRoomItsRelayNoLongerRunsTakesNoPostAndNoReaction() =
        runTest {
            val room = Conversations.commonsIdFor("ab".repeat(32))
            relayFactsFlow.value = RelayFacts(staleRooms = setOf(room))
            stubDm(room)
            val vm = vm(conversationId = room)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            messagesFlow.value = listOf(msg(senderId = "bob", id = "m1", conversationId = room))
            advanceUntilIdle()
            assertTrue(vm.state.value.commonsStale)

            vm.send("anyone there?")
            vm.react("m1", "🦄")
            advanceUntilIdle()

            assertTrue(mesh.sentCommons.isEmpty())
            assertTrue(mesh.sentCommonsReactions.isEmpty())
            coVerify(exactly = 0) { settings.recordReaction(any()) }

            // The relay runs it again (the operator fixed a slip): the room takes posts once more.
            relayFactsFlow.value = RelayFacts()
            advanceUntilIdle()
            vm.send("back again")
            advanceUntilIdle()
            assertEquals(listOf(room to "back again"), mesh.sentCommons)
        }

    @Test
    fun aCommonsWhoseRelayTakesNoAttachmentsRefusesOneAndSaysWhenTheRoomIsGone() =
        runTest {
            val room = Conversations.commonsIdFor("ab".repeat(32))
            val uri = Uri.parse("content://images/3")
            coEvery { attachments.ingest(uri, any()) } returns
                AttachmentStore.IngestResult.Success(AttachmentStore.Ingested(hash = "h3", mime = "image/jpeg"), flagged = false)
            relayFactsFlow.value = RelayFacts(textOnlyRooms = setOf(room), staleRooms = setOf(room))
            stubDm(room)
            val vm = vm(conversationId = room)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(vm.state.value.commonsTextOnly)
            assertTrue(vm.state.value.commonsStale)
            assertFalse(vm.state.value.canSendFile)

            vm.attach(uri)
            advanceUntilIdle()

            assertNull(vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h3") }
        }

    @Test
    fun aPickedVideoIsEncodedThenStagedAndAFlaggedRoomVideoIsBlocked() =
        runTest {
            // ADR 2026-10.hmfe: a pick the store calls a video takes the video door, with its progress shown meanwhile.
            val uri = Uri.parse("content://media/video/7")
            val video = VideoInfo(30_000L, 480, 854, byteArrayOf(1))
            val ingested = AttachmentStore.Ingested(hash = "v7", mime = "video/mp4", name = "video.mp4", video = video)
            coEvery { attachments.isVideo(uri) } returns true
            coEvery { attachments.ingestVideo(uri, any(), any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm() // Nearby room

            vm.attach(uri)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
            assertNull("the encode tile is gone once it is staged", vm.videoEncoding.value)
            coVerify { attachments.ingestVideo(uri, true, any()) }
            coVerify(exactly = 0) { attachments.ingest(uri, any()) }

            vm.clearAttachment()
            coEvery { attachments.ingestVideo(uri, any(), any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            vm.attach(uri)
            advanceUntilIdle()
            assertNull("a flagged video is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("v7") }
        }

    @Test
    fun aVideoFromTheFileButtonIsEncodedAndOneTheEncoderCannotReadGoesAsAFile() =
        runTest {
            // ADR 2026-10.hmfe's amendment: sent as-is, a video was refused past 8 MB and went out poster-less, a file
            // bubble on the sender's side. It takes the video door whichever button picked it.
            val clip = Uri.parse("content://docs/clip")
            val odd = Uri.parse("content://docs/odd")
            val video = VideoInfo(30_000L, 480, 854, byteArrayOf(1))
            val encoded = AttachmentStore.Ingested(hash = "v8", mime = "video/mp4", name = "video.mp4", video = video)
            val asFile = AttachmentStore.Ingested(hash = "f8", mime = "video/x-msvideo", name = "odd.avi", sizeBytes = 10)
            coEvery { attachments.isVideo(any()) } returns true
            coEvery { attachments.ingestVideo(clip, any(), any()) } returns AttachmentStore.IngestResult.Success(encoded, flagged = false)
            coEvery { attachments.ingestVideo(odd, any(), any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            coEvery { attachments.ingestFile(odd, any()) } returns AttachmentStore.IngestResult.Success(asFile, flagged = false)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")

            vm.attachFile(clip)
            advanceUntilIdle()
            assertEquals(encoded, vm.pendingAttachment.value)
            coVerify(exactly = 0) { attachments.ingestFile(clip, any()) }

            vm.clearAttachment()
            vm.attachFile(odd)
            advanceUntilIdle()
            assertEquals("a video the encoder cannot read is still a file", asFile, vm.pendingAttachment.value)
        }

    @Test
    fun aVideoPastSixtyOneSecondsAsksBeforeAnyEncodeAndOnlyAYesEncodesIt() =
        runTest {
            // ADR 2026-10.hmfe's 2026-10-31 amendment: the cut was a toast after the encode the user had waited through.
            // The length is read off the container first, and nothing is encoded until "Send the first 61 seconds?"
            // is answered yes.
            val uri = Uri.parse("content://media/video/9")
            val video = VideoInfo(61_000L, 480, 854, byteArrayOf(1))
            val ingested = AttachmentStore.Ingested(hash = "v9", mime = "video/mp4", name = "video.mp4", video = video)
            val asFile = AttachmentStore.Ingested(hash = "f9", mime = "video/x-msvideo", name = "long.avi", sizeBytes = 10)
            coEvery { attachments.isVideo(uri) } returns true
            coEvery { attachments.videoDurationMs(uri) } returns 134_000L
            coEvery { attachments.ingestVideo(uri, any(), any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            coEvery { attachments.ingestFile(uri, any()) } returns AttachmentStore.IngestResult.Success(asFile, flagged = false)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attach(uri)
            advanceUntilIdle()
            assertEquals(ChatViewModel.LongVideo(uri, 134_000L, orAsFile = false), vm.longVideo.value)
            assertNull("no encode tile while the question is up", vm.videoEncoding.value)

            // No: nothing encoded, nothing staged, nothing said.
            vm.dismissLongVideo()
            advanceUntilIdle()
            assertNull(vm.longVideo.value)
            assertNull(vm.pendingAttachment.value)
            coVerify(exactly = 0) { attachments.ingestVideo(any(), any(), any()) }

            // Yes: that pick is encoded (the encode's own plan makes the cut) and staged, with no notice after it.
            vm.attach(uri)
            advanceUntilIdle()
            vm.sendLongVideo()
            advanceUntilIdle()
            assertNull(vm.longVideo.value)
            assertEquals(ingested, vm.pendingAttachment.value)
            coVerify(exactly = 1) { attachments.ingestVideo(uri, false, any()) }
            assertTrue("nothing said: the sender already agreed to the cut", events.isEmpty())

            // The file button's pick asks too, and its yes keeps the button: a video the encoder cannot read goes as a file.
            vm.clearAttachment()
            coEvery { attachments.ingestVideo(uri, any(), any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            vm.attachFile(uri)
            advanceUntilIdle()
            assertEquals(ChatViewModel.LongVideo(uri, 134_000L, orAsFile = true), vm.longVideo.value)
            vm.sendLongVideo()
            advanceUntilIdle()
            assertEquals(asFile, vm.pendingAttachment.value)
            coVerify(exactly = 1) { attachments.ingestFile(uri, any()) }
        }

    @Test
    fun attachingAnImageInADmNeverAsksForAScreenAndStagesIt() =
        runTest {
            // Only the Nearby room screens what is sent (ADR 2026-10.9rh6); a DM's receivers screen it.
            val uri = Uri.parse("content://images/5")
            val jpeg = byteArrayOf(5, 5, 5)
            val ingested = AttachmentStore.Ingested(hash = "h5", mime = "image/jpeg")
            coEvery { attachments.ingest(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            coEvery { attachments.ingest(jpeg, "image/jpeg", any()) } returns
                AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm("bob")

            vm.attach(uri)
            advanceUntilIdle()
            assertEquals(ingested, vm.pendingAttachment.value)
            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            coVerify { attachments.ingest(uri, false) }
            coVerify { attachments.ingest(jpeg, "image/jpeg", false) }
            coVerify(exactly = 0) { attachments.ingest(any<Uri>(), true) }
            coVerify(exactly = 0) { attachments.ingest(any<ByteArray>(), any(), true) }
            coVerify(exactly = 0) { blobs.deleteIfUnreferenced("h5") }
        }

    @Test
    fun attachingACleanImageStagesItForSending() =
        runTest {
            val uri = Uri.parse("content://images/2")
            val ingested = AttachmentStore.Ingested(hash = "h2", mime = "image/jpeg")
            coEvery { attachments.ingest(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
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
                attachments.ingest(jpeg, "image/jpeg", any())
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
                attachments.ingest(jpeg, "image/jpeg", any())
            } returns AttachmentStore.IngestResult.Success(ingested, flagged = true)
            val vm = vm() // Nearby room

            vm.attachCaptured(jpeg)
            advanceUntilIdle()

            assertNull("flagged photo is not staged in the public room", vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h4") }
            coVerify { attachments.ingest(jpeg, "image/jpeg", true) }
        }

    // --- Cover faces (ADR 2026-10.whkk) ------------------------------------------------------------------------------

    /** A detector that finds one face, wherever it is asked. */
    private val oneFace =
        object : FaceDetector {
            override suspend fun detect(bitmap: Bitmap): List<FaceBox> = listOf(FaceBox(10f, 10f, 40f, 50f, 0.9f))
        }

    /**
     * A real photo, NASA's crew portrait, so the review's decode has something to decode. A test that decodes it runs
     * on native graphics: the review asks the platform's ImageDecoder whether the photo is whole, and the legacy
     * shadow answers no to every real file.
     */
    private val crewJpeg by lazy { repoFile("app/src/androidTest/assets/redact/crew.jpg").readBytes() }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withCoverFacesOnACapturedPhotoIsReviewedAndStagedOnlyFromTheReviewWhenDone() =
        runTest {
            coverFacesFlow.value = true
            val ingested = AttachmentStore.Ingested(hash = "hc", mime = "image/jpeg")
            coEvery { attachments.ingest(any<ByteArray>(), any(), any(), any()) } returns
                AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm("bob", faces = oneFace)

            vm.attachCaptured(crewJpeg)
            val review = vm.pendingReview.first { it?.search == FaceSearch.Done }!!

            assertEquals(1, review.redaction.coveredCount)
            coVerify(exactly = 0) { attachments.ingest(any<ByteArray>(), any(), any(), any()) }
            assertNull(vm.pendingAttachment.value)

            vm.finishReview()

            // The encode runs off the main thread, so the staging is awaited, not stepped to.
            coVerify(
                timeout = AWAIT_MS,
            ) { attachments.ingest(match<ByteArray> { !it.contentEquals(crewJpeg) }, "image/jpeg", false, any()) }
            coVerify(exactly = 0) { attachments.ingest(crewJpeg, any(), any(), any()) }
            assertNull(vm.pendingReview.value)
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withCoverFacesOnAPhotoCutShortIsUnreadableAndNeverReviewed() =
        runTest {
            // Its first 30%: the plain decode would answer the top of it with blank below, and the review would send that.
            coverFacesFlow.value = true
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attachCaptured(crewJpeg.copyOf(crewJpeg.size * 3 / 10))

            assertEquals(R.string.redact_unreadable, said.await())
            assertNull(vm.pendingReview.value)
            coVerify(exactly = 0) { attachments.ingest(any<ByteArray>(), any(), any(), any()) }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aCancelledReviewStagesNothing() =
        runTest {
            coverFacesFlow.value = true
            val vm = vm("bob", faces = oneFace)
            vm.attachCaptured(crewJpeg)
            vm.pendingReview.first { it?.search == FaceSearch.Done }

            vm.cancelReview()
            // Read before the finish: Main is unconfined, so the cancel has landed, and no encode on another thread
            // can be the one that cleared it.
            assertNull(vm.pendingReview.value)
            vm.finishReview()
            advanceUntilIdle()

            assertNull(vm.pendingReview.value)
            coVerify(exactly = 0) { attachments.ingest(any<ByteArray>(), any(), any(), any()) }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withCoverFacesOnAPickTheStoreCannotTellApartIsReviewedAndOneThatWillNotDecodeIsNeverSent() =
        runTest {
            // NONE through the photo door: the fail-safe reviews it. Its provider is gone, so the decode throws: the
            // photo is dropped with a word, and nothing at all is staged.
            coverFacesFlow.value = true
            // A provider that serves no files: opening the pick throws, as one whose document has gone does.
            Robolectric.buildContentProvider(PresentDocuments::class.java).create("gone")
            val uri = Uri.parse("content://gone/photo")
            coEvery { attachments.isVideo(uri) } returns false
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.NONE
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attach(uri)

            assertEquals(R.string.redact_unreadable, said.await())
            coVerify(exactly = 0) { attachments.ingest(any<Uri>(), any(), any()) }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withCoverFacesOnAPhotoPickedAsAFileIsReviewedNotSentAsAFile() =
        runTest {
            // The file door hands a picture to the image door, so with Cover faces on it is reviewed: here its provider
            // has gone, so it is dropped with a word, and the file door's own ingest is never reached.
            coverFacesFlow.value = true
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.CAP_FILES)
            Robolectric.buildContentProvider(PresentDocuments::class.java).create("gone")
            val uri = Uri.parse("content://gone/photo.jpg")
            coEvery { attachments.isVideo(uri) } returns false
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.STILL
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attachFile(uri)

            assertEquals(R.string.redact_unreadable, said.await())
            coVerify(exactly = 0) { attachments.ingestFile(any(), any()) }
        }

    @Test
    fun aPhotoPickedAsAFileForAPeerThatTakesNoFilesIsRefusedBeforeItsReview() =
        runTest {
            coverFacesFlow.value = true
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.CAP_E2E)
            val uri = Uri.parse("content://docs/photo.jpg")
            coEvery { attachments.isVideo(uri) } returns false
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.STILL
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attachFile(uri)

            assertEquals(R.string.chat_file_peer_too_old, said.await())
            assertNull(vm.pendingReview.value)
            coVerify(exactly = 0) { attachments.ingestFile(any(), any()) }
        }

    @Test
    fun withCoverFacesOnAnAnimationGoesAsItIsWithANotice() =
        runTest {
            coverFacesFlow.value = true
            val uri = Uri.parse("content://images/animated")
            coEvery { attachments.isVideo(uri) } returns false
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.MOVING
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attach(uri)
            advanceUntilIdle()

            assertEquals(R.string.redact_motion_not_covered, said.await())
            coVerify { attachments.ingest(uri, false, any()) }
            assertNull(vm.pendingReview.value)
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withCoverFacesOnAPhotoOfferedOverADirectTransferGoesOnlyAsItsCoveredCopy() =
        runTest {
            coverFacesFlow.value = true
            stubDm("bob")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            mesh.neighbors.value = setOf(Peer("bob"))
            Robolectric.buildContentProvider(CrewPhoto::class.java).create("crew")
            val uri = Uri.parse("content://crew/IMG_0001.png")
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.STILL
            every { attachments.displayName(uri) } returns "IMG_0001.png"
            val offered = slot<String>()
            coEvery { transfers.offer("bob", any()) } returns OfferOutcome.Started("t1")
            val vm = vm("bob", faces = oneFace)

            vm.offerTransfer(uri)
            val review = vm.pendingReview.first { it?.search == FaceSearch.Done }!!

            assertEquals(PhotoReview.Purpose.Transfer("IMG_0001.png"), review.purpose)
            assertEquals(1, review.redaction.coveredCount)
            coVerify(exactly = 0) { transfers.offer(any(), any()) }

            vm.finishReview()

            // The encode and the copy's write run off the main thread, so the offer is awaited, not stepped to.
            // Captured by the verify, on this thread: a stub's capture runs after the call is recorded, on the offer's own.
            coVerify(timeout = AWAIT_MS) { transfers.offer("bob", capture(offered)) }
            val copy = File(checkNotNull(Uri.parse(offered.captured).path))
            assertEquals("IMG_0001.jpg", copy.name)
            val sent = copy.readBytes()
            assertEquals("image/jpeg", FileTypes.imageMimeOf(sent))
            assertFalse("the original went", sent.contentEquals(crewJpeg))
            coVerify(exactly = 0) { transfers.offer("bob", uri.toString()) }
            assertNull(vm.pendingReview.value)
        }

    @Test
    fun withCoverFacesOnAnAnimationOfferedOverADirectTransferGoesAsItIsWithANotice() =
        runTest {
            coverFacesFlow.value = true
            stubDm("bob")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            mesh.neighbors.value = setOf(Peer("bob"))
            val uri = Uri.parse("content://docs/animated.gif")
            coEvery { attachments.pictureOf(uri) } returns AttachmentStore.Picture.MOVING
            coEvery { transfers.offer("bob", uri.toString()) } returns OfferOutcome.Started("t1")
            val vm = vm("bob", faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.offerTransfer(uri)
            advanceUntilIdle()

            assertEquals(R.string.redact_motion_not_covered, said.await())
            coVerify { transfers.offer("bob", uri.toString()) }
            assertNull(vm.pendingReview.value)
        }

    @Test
    fun theMeshtasticRoomRefusesAPhotoBeforeItsReviewNotAfter() =
        runTest {
            coverFacesFlow.value = true
            val vm = vm(Conversations.MESHTASTIC, faces = oneFace)
            val said = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.attachCaptured(crewJpeg)

            assertEquals(R.string.chat_mesh_text_only, said.await())
            assertNull(vm.pendingReview.value)
        }

    @Test
    fun withCoverFacesOffACapturedPhotoIsStagedAsItWasWithNoReview() =
        runTest {
            val ingested = AttachmentStore.Ingested(hash = "ho", mime = "image/jpeg")
            coEvery { attachments.ingest(crewJpeg, "image/jpeg", any()) } returns
                AttachmentStore.IngestResult.Success(ingested, flagged = false)
            val vm = vm("bob", faces = oneFace)

            vm.attachCaptured(crewJpeg)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
            assertNull(vm.pendingReview.value)
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
            coEvery { attachments.ingest(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
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
            coEvery { attachments.ingest(jpeg, "image/jpeg", any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            coEvery { attachments.ingest(uri, any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
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
     * #268: a share Knit cannot read crashed the app; failing as Unreadable instead, it still has to say so, since the
     * sender left the other app for this composer and a share that stages nothing reads as Knit ignoring it. Without a
     * grant the provider will not say a video is one, so a shared video fails through the image door, and the type
     * the share declared names it.
     */
    @Test
    fun aShareKnitCannotReadSaysSoAndCallsASharedVideoAVideo() =
        runTest {
            val picture = Uri.parse("content://media/external/images/media/40")
            val video = Uri.parse("content://media/external/video/media/41")
            coEvery { attachments.ingest(any<Uri>(), any(), any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.Unreadable)
            val vm = vm()
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attachShared(picture, "image/jpeg")
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_image_capture_failed), events)

            vm.attachShared(video, "video/mp4")
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_image_capture_failed, R.string.chat_video_unreadable), events)
            coVerify { attachments.ingest(video, true, any()) }
            coVerify(exactly = 0) { attachments.ingestVideo(any(), any(), any()) }
            assertNull(vm.pendingAttachment.value)
        }

    /**
     * #284 (ADR 2026-11.vdx4): a share, a paste or a pick naming Knit's own files would be read with Knit's permissions
     * and staged in the chat. None is read, not even to ask whether it is a video, and each says what an unreadable
     * one says, so the sender learns nothing about why. A URI on someone else's provider still goes through.
     */
    @Test
    fun aUriNamingKnitsOwnFilesIsNeverReadAndSaysWhatAnUnreadableOneSays() =
        runTest {
            // The store is a mock, so nothing here reads a byte either way: the gate is proven by the store never being
            // asked (the coVerify checks below) and by what the composer says.
            val ownFile = Uri.fromFile(File(context.filesDir, "datastore/knit_settings.preferences_pb"))
            val ownLog = Uri.parse("content://0@${context.packageName}.fileprovider/fieldlog/knit-fieldlog.txt")
            val ownCrash = Uri.parse("content://${context.packageName}.fileprovider/crash/report.txt")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attachShared(ownFile, "image/jpeg")
            vm.attachShared(ownLog, "video/mp4")
            vm.attachFile(ownCrash)
            vm.attach(ownFile) // a paste or a keyboard insert: quiet, as every failed pick is
            advanceUntilIdle()

            assertEquals(
                listOf(R.string.chat_image_capture_failed, R.string.chat_video_unreadable, R.string.chat_transfer_unreadable),
                events,
            )
            assertNull(vm.pendingAttachment.value)
            coVerify(exactly = 0) { attachments.isVideo(any()) }
            coVerify(exactly = 0) { attachments.ingest(any<Uri>(), any(), any()) }
            coVerify(exactly = 0) { attachments.ingestVideo(any(), any(), any()) }
            coVerify(exactly = 0) { attachments.ingestFile(any(), any()) }

            // Someone else's provider is read as before.
            val picture = Uri.parse("content://com.android.providers.media.documents/document/image%3A40")
            val ingested = AttachmentStore.Ingested(hash = "p40", mime = "image/jpeg")
            coEvery { attachments.ingest(picture, any(), any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            vm.attachShared(picture, "image/jpeg")
            advanceUntilIdle()
            assertEquals(ingested, vm.pendingAttachment.value)
        }

    /**
     * #284 against the face review (ADR 2026-10.whkk): with Cover faces on, a URI naming Knit's own files is refused at
     * every door before the review could decode or show it, so the review never becomes a way to read Knit's files.
     */
    @Test
    fun withCoverFacesOnAUriNamingKnitsOwnFilesNeverReachesTheReview() =
        runTest {
            coverFacesFlow.value = true
            val ownFile = Uri.fromFile(File(context.filesDir, "datastore/knit_settings.preferences_pb"))
            val ownCrash = Uri.parse("content://${context.packageName}.fileprovider/crash/report.txt")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            mesh.neighbors.value = setOf(Peer("bob"))
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.attach(ownFile)
            vm.attachShared(ownCrash, "image/jpeg")
            vm.attachFile(ownCrash)
            vm.offerTransfer(ownFile)
            advanceUntilIdle()

            assertNull(vm.pendingReview.value)
            coVerify(exactly = 0) { attachments.pictureOf(any()) }
            coVerify(exactly = 0) { attachments.isVideo(any()) }
            coVerify(exactly = 0) { attachments.displayName(any()) }
            coVerify(exactly = 0) { transfers.offer(any(), any()) }
            assertEquals(
                listOf(R.string.chat_image_capture_failed, R.string.chat_transfer_unreadable, R.string.chat_transfer_unreadable),
                events,
            )
        }

    /** #284: the direct transfer reads its file minutes later, as Knit, so one naming Knit's own files is never offered. */
    @Test
    fun aDirectTransferOfKnitsOwnFilesIsNeverOffered() =
        runTest {
            stubDm("bob")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            mesh.neighbors.value = setOf(Peer("bob"))
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.offerTransfer(Uri.fromFile(File(context.filesDir, "datastore/knit_settings.preferences_pb")))
            vm.offerTransfer(Uri.parse("content://${context.packageName}.fileprovider/apk/knit.apk"))
            advanceUntilIdle()

            assertEquals(listOf(R.string.chat_transfer_unreadable, R.string.chat_transfer_unreadable), events)
            coVerify(exactly = 0) { transfers.offer(any(), any()) }
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

    /**
     * A save is what teaches an attachment received before the plaintext column existed its plaintext name, from the
     * bytes it already opened, so forwarding the saved copy reuses the identity it came under (ADR 2026-10.mptm).
     */
    @Test
    fun savingASealedAttachmentRecordsItsPlaintextNameAndAKeylessOneDoesNot() =
        runTest {
            val plain = byteArrayOf(1, 2, 3, 4, 5)
            val sealed = AttachmentCrypto.seal(plain)
            coEvery { blobs.bytes("ct") } returns sealed.blob
            coEvery { blobs.bytes("room") } returns plain
            coEvery { messages.attachmentKeyWantingPlain("ct") } returns b64(sealed.key)
            coEvery { gallerySaver.saveToPictures(any(), any(), any()) } returns true
            val vm = vm()

            vm.saveAttachment("ct", b64(sealed.key), "image/webp")
            vm.saveAttachment("room", null, "image/jpeg")
            advanceUntilIdle()

            coVerify(exactly = 1) { messages.setAttachmentPlainHash("ct", sha256Hex(plain)) }
            coVerify(exactly = 0) { messages.setAttachmentPlainHash("room", any()) }
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

    /** A provider serving the crew portrait at every URI, so a picked photo can be decoded for its review. */
    class CrewPhoto : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor =
            ParcelFileDescriptor.open(repoFile("app/src/androidTest/assets/redact/crew.jpg"), ParcelFileDescriptor.MODE_READ_ONLY)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String = "image/jpeg"

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

    /**
     * #34: a voice note saves as its own decrypted bytes, under the name [AttachmentExport.voiceFileName] gives it, and
     * the toast names the folder. A save teaches a sealed note its plaintext name, as a picture's does (ADR 2026-10.mptm).
     */
    @Test
    fun savingASealedVoiceNoteExportsItsPlaintextUnderItsOwnName() =
        runTest {
            val plain = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 1, 2, 3)
            val sealed = AttachmentCrypto.seal(plain)
            val sentAt = 1_795_442_712_000L
            coEvery { blobs.bytes("ct") } returns sealed.blob
            coEvery { messages.attachmentKeyWantingPlain("ct") } returns b64(sealed.key)
            coEvery { gallerySaver.saveVoice(any(), any(), any()) } returns GallerySaver.VoiceSave.Saved
            val vm = vm()
            // Subscribed before the act: the save decrypts on Dispatchers.Default, off the test scheduler.
            val toast = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveVoice("ct", b64(sealed.key), "audio/aac", sentAt)

            // Robolectric runs SDK 36, past Android 12: the note goes to Recordings, and the toast says so.
            assertEquals(R.string.chat_voice_saved_recordings, toast.await())
            val exported = slot<ByteArray>()
            val name = slot<String>()
            coVerify { gallerySaver.saveVoice(capture(exported), capture(name), "audio/aac") }
            assertArrayEquals("saved the decrypted note, not the stored ciphertext", plain, exported.captured)
            assertEquals(AttachmentExport.voiceFileName("ct", sentAt, ZoneId.systemDefault(), "aac"), name.captured)
            coVerify(exactly = 1) { messages.setAttachmentPlainHash("ct", sha256Hex(plain)) }
        }

    /** A key-less voice note's stored bytes are already its plaintext, and go out untouched. */
    @Test
    fun savingAKeylessVoiceNoteExportsTheStoredBytes() =
        runTest {
            val aac = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 9, 8, 7)
            coEvery { blobs.bytes("h") } returns aac
            coEvery { gallerySaver.saveVoice(any(), any(), any()) } returns GallerySaver.VoiceSave.Saved
            val vm = vm()
            val toast = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveVoice("h", null, "audio/aac", 0L)
            toast.await()

            val exported = slot<ByteArray>()
            coVerify { gallerySaver.saveVoice(capture(exported), any(), "audio/aac") }
            assertArrayEquals(aac, exported.captured)
            coVerify(exactly = 0) { messages.setAttachmentPlainHash(any(), any()) }
        }

    /** A key that doesn't open the note fails the save rather than exporting the ciphertext as audio. */
    @Test
    fun savingAVoiceNoteWithAKeyThatDoesNotOpenItFailsInsteadOfExportingCiphertext() =
        runTest {
            val sealed = AttachmentCrypto.seal(byteArrayOf(1, 2, 3, 4, 5))
            coEvery { blobs.bytes("ct") } returns sealed.blob
            val vm = vm()
            val toast = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveVoice("ct", b64(ByteArray(32)), "audio/aac", 0L)

            // The toast is the save's last step, so nothing it skipped can still be on its way.
            assertEquals(R.string.chat_voice_save_failed, toast.await())
            coVerify(exactly = 0) { gallerySaver.saveVoice(any(), any(), any()) }
        }

    /** #34: a note already in the folder says so, and the saver is what decided nothing more was written. */
    @Test
    fun savingAVoiceNoteAlreadySavedSaysSo() =
        runTest {
            coEvery { blobs.bytes("h") } returns byteArrayOf(1, 2, 3)
            coEvery { gallerySaver.saveVoice(any(), any(), any()) } returns GallerySaver.VoiceSave.AlreadySaved
            val vm = vm()
            val toast = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveVoice("h", null, "audio/aac", 0L)

            assertEquals(R.string.chat_voice_already_saved, toast.await())
        }

    /**
     * The row's MIME is the sender's: an audio type off the save's list (a playlist here, which a music player would
     * follow to the sender's server) is refused before anything is decrypted or written.
     */
    @Test
    fun aVoiceNoteUnderAnUnlistedAudioTypeIsNotSaved() =
        runTest {
            coEvery { blobs.bytes("h") } returns "http://example.invalid/x\n".toByteArray()
            val vm = vm()
            val toast = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }

            vm.saveVoice("h", null, "audio/x-mpegurl", 0L)

            assertEquals(R.string.chat_voice_save_failed, toast.await())
            coVerify(exactly = 0) { gallerySaver.saveVoice(any(), any(), any()) }
        }

    /** A picked audio file sent as a voice note keeps its own type; a row with no audio type saves as a recorded note. */
    @Test
    fun aVoiceNoteSavesUnderTheRowsAudioTypeElseARecordedNotes() =
        runTest {
            coEvery { blobs.bytes("h") } returns byteArrayOf(1, 2, 3)
            coEvery { gallerySaver.saveVoice(any(), any(), any()) } returns GallerySaver.VoiceSave.Saved
            val vm = vm()

            val first = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
            vm.saveVoice("h", null, "audio/ogg", 0L)
            first.await()
            coVerify { gallerySaver.saveVoice(any(), any(), "audio/ogg") }

            val second = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first() }
            vm.saveVoice("h", null, null, 0L)
            second.await()
            coVerify { gallerySaver.saveVoice(any(), any(), VoiceAudio.MIME) }
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
    fun theRoomSaysWhenTheBusyBandHoldsItsPostsAndClearsWhenTheBrakeLifts() =
        runTest {
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, bandBusy = true, airtimeSpent = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            // Braked and spent: the brake is the larger change, so it is the one the room names.
            assertEquals(LoraReach.RoomBandBusy, vm.state.value.loraReach)

            // The brake lifts with the window still spent: back to the spent notice.
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
            advanceUntilIdle()
            assertEquals(LoraReach.RoomSaturated, vm.state.value.loraReach)

            // Braked again, but Bluetooth reaches her too: nobody is waiting on the held posts.
            loraFactsFlow.value = LoraFacts(LoraPlane.Live, dms = true, bandBusy = true)
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa, TransportKind.Bluetooth))
            advanceUntilIdle()
            assertEquals(LoraReach.Silent, vm.state.value.loraReach)

            // Behind the board again, then the brake lifts with air to spare: the room goes quiet.
            mesh.peerTransports.value = mapOf("ana" to setOf(TransportKind.LoRa))
            advanceUntilIdle()
            assertEquals(LoraReach.RoomBandBusy, vm.state.value.loraReach)
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

    @Test
    fun aDmToAPeerWithoutForwardSecrecySaysSoInsteadOfPending() =
        runTest {
            stubDm("ana")
            peersFlow.value = listOf(peer("ana", "Ana", pubKey = "k", capabilities = 0L))
            relayFactsFlow.value = RelayFacts(enabled = true, configured = 1, active = 1, connected = 1)
            val vm = vm("ana")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(RelayReach.NoForwardSecrecy, vm.state.value.relayReach)

            // The peer updates: its next profile claims the ratchet, and the thread is merely pending again.
            peersFlow.value = listOf(peer("ana", "Ana", pubKey = "k", capabilities = Protocol.CAP_RATCHET))
            advanceUntilIdle()
            assertEquals(RelayReach.Pending, vm.state.value.relayReach)
        }

    private companion object {
        const val GROUP = "g-trailhead"
        val MUTE_8H = TimeUnit.HOURS.toMillis(8)

        /** A bound for work on real threads, sized for the one-vCPU runner (testing.md, flake shape 7). */
        const val AWAIT_MS = 10_000L
        const val GONE_GROUP = "g-deleted"
        const val BOB = "bob"
        const val PEER_CALL = "callpeer7u3xqz5w2kcmvbdyrh4a"
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
    fun aPhoneThatMayNotHostSaysSoBeforeThePicker() =
        runTest {
            // ADR 2026-10.w3dw: the sender hosts the group, and this phone never does.
            stubDm("bob")
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            mesh.neighbors.value = setOf(Peer("bob"))
            every { transfers.mayOffer() } returns false
            val vm = vm("bob")
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            vm.offerTransfer(Uri.parse("content://docs/clip"))
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_transfer_cannot_host), events)
            coVerify(exactly = 0) { transfers.offer(any(), any()) }
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

    /**
     * Retry is drawn only for a file the chat found it can still read, and a Retry that finds the file gone
     * after all says so and takes the button away.
     */
    @Test
    fun retryIsOfferedOnlyForAFileThatCanStillBeReadAndAGoneFileTakesItAway() =
        runTest {
            stubDm("bob")
            mesh.neighbors.value = setOf(Peer("bob"))
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)

            fun failed(
                id: String,
                source: String,
            ) = TransferRecord(
                id = id,
                outgoing = true,
                name = "clip.mp4",
                size = 2_000L,
                mime = "video/mp4",
                phase = TransferPhase.Failed,
                sourceUri = source,
            ).toEntity(peerId = "bob", selfId = "me", sentAt = if (id == "moved") 5L else 6L)
            coEvery { transfers.retryReadable(match { it.sourceUri == "content://docs/kept" }) } returns true
            coEvery { transfers.retryReadable(match { it.sourceUri == "content://docs/moved" }) } returns false
            val vm = vm("bob")
            collectState(vm)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            fun retryable(id: String) =
                checkNotNull(
                    vm.state.value.rows
                        .single { it.transfer?.id == id }
                        .transfer,
                ).retryable

            messagesFlow.value = listOf(failed("moved", "content://docs/moved"), failed("kept", "content://docs/kept"))
            advanceUntilIdle()
            assertFalse("a file that can no longer be read offers no Retry", retryable("moved"))
            assertTrue(retryable("kept"))
            coVerify(exactly = 1) { transfers.retryReadable(match { it.sourceUri == "content://docs/kept" }) }

            // Gone between the check and the tap: the refusal is said and the button goes.
            coEvery { transfers.retry("kept") } returns OfferOutcome.Refused(TransferRefusal.Unreadable)
            vm.retryTransfer("kept")
            advanceUntilIdle()
            assertEquals(R.string.chat_transfer_unreadable, events.last())
            assertFalse(retryable("kept"))
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
            coEvery { attachments.ingestFile(uri, any()) } returns
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
            coEvery { attachments.ingestFile(uri, any()) } returns
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
            coEvery { attachments.ingestFile(tooBig, any()) } returns
                AttachmentStore.IngestResult.Failed(AttachmentStore.IngestResult.Reason.TooLarge)
            coEvery { attachments.ingestFile(apk, any()) } returns
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
            coEvery { attachments.ingestFile(uri, any()) } returns AttachmentStore.IngestResult.Success(ingested, flagged = false)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            stubDm("bob")
            val vm = vm("bob")

            vm.attachFile(uri)
            advanceUntilIdle()

            assertEquals(ingested, vm.pendingAttachment.value)
        }

    /**
     * #287: a field log or a crash report from Diagnostics arrives as Knit's own bytes, never a URI, so nothing asks
     * OutsideUris and no provider is read (ADR 2026-11.vdx4). It meets a shared file's refusals, the room and an old
     * peer, from the repositories rather than the unsettled state, and then stages.
     */
    @Test
    fun aLogKnitWroteStagesFromItsBytesBehindTheFileRefusals() =
        runTest {
            val log = OwnFile("knit-fieldlog-2.10.2-20261102-1432.jsonl", "text/plain", "{}\n".toByteArray())
            val ingested = AttachmentStore.Ingested("loghash", "text/plain", name = log.name, sizeBytes = log.bytes.size)
            coEvery { attachments.ingestFileBytes(log.bytes, log.name, log.mime, any()) } returns
                AttachmentStore.IngestResult.Success(ingested, flagged = false)
            coEvery { peers.find("bob") } returns peer("bob", "Bob", capabilities = Protocol.LOCAL_CAPABILITIES)
            coEvery { peers.find("old") } returns peer("old", "Old", capabilities = Protocol.CAP_E2E)

            val room = vm()
            val roomEvents = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { room.events.collect { roomEvents += it } }
            room.attachOwnFile(log)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_share_needs_chat), roomEvents)
            assertNull(room.pendingAttachment.value)

            stubDm("old")
            val stale = vm("old")
            val staleEvents = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stale.events.collect { staleEvents += it } }
            stale.attachOwnFile(log)
            advanceUntilIdle()
            assertEquals(listOf(R.string.chat_file_peer_too_old), staleEvents)
            assertNull(stale.pendingAttachment.value)

            stubDm("bob")
            val fresh = vm("bob")
            fresh.attachOwnFile(log)
            advanceUntilIdle()
            assertEquals(ingested, fresh.pendingAttachment.value)
            coVerify(exactly = 1) { attachments.ingestFileBytes(log.bytes, log.name, log.mime, false) }
            coVerify(exactly = 0) { attachments.isVideo(any()) }
            coVerify(exactly = 0) { attachments.ingestFile(any(), any()) }
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
            vm.onDraftChanged(Draft("look https://example.com/a"))
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
            vm.onDraftChanged(Draft("look https://example.com/a"))
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }

            linkPreviewsEnabledFlow.value = true
            onlineFlow.value = false
            vm.onDraftChanged(Draft("look https://example.com/a now"))
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
            coEvery { attachments.ingest(any<ByteArray>(), any(), any()) } returns
                AttachmentStore.IngestResult.Success(photo, flagged = false)
            val vm = vm()
            vm.attachCaptured(byteArrayOf(1))
            advanceUntilIdle()
            vm.onDraftChanged(Draft("look https://example.com/a"))
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }
            assertEquals(photo, vm.pendingAttachment.value)

            vm.clearAttachment()
            vm.onDraftChanged(Draft("look https://example.com/a !"))
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)

            vm.onDraftChanged(Draft("no link any more"))
            advanceUntilIdle()
            assertNull(vm.pendingAttachment.value)
            coVerify { blobs.deleteIfUnreferenced("h-card") }
        }

    @Test
    fun aDismissedCardStaysDismissedUntilTheDraftIsEmptiedAndAnEmptyLinkIsNotRetried() =
        runTest {
            aCardIsAvailable()
            val vm = vm()
            vm.onDraftChanged(Draft("look https://example.com/a"))
            advanceUntilIdle()
            assertEquals(staged, vm.pendingAttachment.value)
            vm.clearAttachment()
            vm.onDraftChanged(Draft("look https://example.com/a again"))
            advanceUntilIdle()
            assertNull("the removed card does not come back while the draft lives", vm.pendingAttachment.value)
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, any()) }

            vm.onDraftChanged(Draft(""))
            vm.onDraftChanged(Draft("https://example.com/a"))
            advanceUntilIdle()
            coVerify(exactly = 2) { linkPreviews.fetchCard(cardUrl, any()) }

            coEvery { linkPreviews.fetchCard("https://example.com/none", any()) } returns LinkPreviewService.CardResult.NoCard
            vm.clearAttachment()
            vm.onDraftChanged(Draft(""))
            vm.onDraftChanged(Draft("see https://example.com/none"))
            advanceUntilIdle()
            vm.onDraftChanged(Draft("see https://example.com/none please"))
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
            vm.onDraftChanged(Draft("look https://example.com/a"))
            advanceUntilIdle()
            coVerify(exactly = 0) { linkPreviews.fetchCard(any(), any()) }

            // The loop reacts to the draft, not to a peer's profile arriving: the next draft picks the bit up.
            coEvery { peers.find("priya") } returns peer("priya", "Priya", capabilities = Protocol.LOCAL_CAPABILITIES)
            vm.onDraftChanged(Draft(""))
            vm.onDraftChanged(Draft("look https://example.com/a"))
            advanceUntilIdle()
            coVerify(exactly = 1) { linkPreviews.fetchCard(cardUrl, isRoom = false) }
        }

    /** What the screen does on [ChatViewModel.clearInput]: empties the field (which the draft loop sees) and releases the guard. */
    private fun TestScope.screenClearsTheFieldOnSend(vm: ChatViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.clearInput.collect {
                vm.onDraftChanged(Draft(""))
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
            vm.onDraftChanged(Draft("https://example.com/a"))
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
            vm.onDraftChanged(Draft("https://example.com/a"))
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
            vm.onDraftChanged(Draft("https://example.com/a"))
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
            vm.onDraftChanged(Draft("https://example.com/a"))
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
            vm.onDraftChanged(Draft("https://example.com/a"))
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
            vm.send("a".repeat(app.getknit.knit.TextLimits.MESSAGE))
            runCurrent()
            val body = mesh.sentChats.last().text
            assertTrue(
                "the receiver clamps at TextLimits.MESSAGE, so the token has to fit under it",
                body.length <= app.getknit.knit.TextLimits.MESSAGE,
            )
            assertTrue(body.endsWith("\ngeo:37.421998,-122.084000;u=12"))
        }

    @Test
    fun sendMovesTheFormattingPastTheTrimmedBlanksAndStopsItShortOfThePosition() =
        runTest {
            stubDm("bob")
            coEvery { groups.find("bob") } returns null
            val vm = vm("bob")
            vm.startLocation()
            locationSource.readings.tryEmit(reading(accuracy = 12f))
            runCurrent()

            // "See" bold, and "gate" italic with the span running on into the trailing blanks the send trims.
            val draft = "  See you at the gate  "
            vm.send(draft, styles = listOf(StyleSpan(2, 5, BodyStyle.BOLD), StyleSpan(17, 23, BodyStyle.ITALIC)))
            runCurrent()

            val sent = mesh.sentChats.single()
            assertEquals("See you at the gate\ngeo:37.421998,-122.084000;u=12", sent.text)
            assertEquals(
                "indexed against the body sent, and never over the position's line",
                listOf(StyleSpan(0, 3, BodyStyle.BOLD), StyleSpan(15, 19, BodyStyle.ITALIC)),
                sent.styles,
            )
        }

    @Test
    fun aDraftWithoutFormattingSendsNone() =
        runTest {
            stubDm("bob")
            coEvery { groups.find("bob") } returns null
            val vm = vm("bob")

            vm.send("plain words")
            runCurrent()

            assertEquals(emptyList<StyleSpan>(), mesh.sentChats.single().styles)
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

    @Test
    fun theCallPutFirstFollowsTheCallLayerOnlyWhileTheChatIsOnScreen() =
        runTest {
            val calls = mockk<CallManager>(relaxed = true).also { calls = it }
            every { calls.walkieEnabled } returns true
            var meshFirst = true
            coEvery { calls.prefersWalkie(PEER_CALL) } answers { meshFirst }
            val vm = vm(PEER_CALL)
            runCurrent()
            assertFalse("not asked off screen", vm.meshCallFirst.value)

            vm.onChatForeground()
            runCurrent()
            assertTrue(vm.meshCallFirst.value)
            // A live path appears: the next re-check puts the live call first again.
            meshFirst = false
            advanceTimeBy(5_001)
            assertFalse(vm.meshCallFirst.value)

            vm.onChatBackground()
            meshFirst = true
            advanceTimeBy(60_000)
            assertFalse("no re-check off screen", vm.meshCallFirst.value)
        }

    @Test
    fun preferringMeshCallsPutsTheMeshCallFirstWhateverTheRoute() =
        runTest {
            val calls = mockk<CallManager>(relaxed = true).also { calls = it }
            every { calls.walkieEnabled } returns true
            coEvery { calls.prefersWalkie(PEER_CALL) } returns false
            val preferred = MutableStateFlow(true)
            val vm = vm(PEER_CALL, preferMeshCalls = preferred)
            vm.onChatForeground()
            runCurrent()
            assertTrue("the route alone would put the live call first", vm.meshCallFirst.value)

            preferred.value = false
            runCurrent()
            assertFalse("back to the route's verdict", vm.meshCallFirst.value)
            vm.onChatBackground()
        }

    /**
     * A DM offers only the call kinds its peer carries the bits for (C-3.11-3), whether or not the person prefers mesh
     * calls: a peer on a build without calls shows no call button to press for a refusal.
     */
    @Test
    fun aDmOffersOnlyTheCallKindsThePeerTakes() =
        runTest {
            val calls = mockk<CallManager>(relaxed = true).also { calls = it }
            every { calls.liveEnabled } returns true
            every { calls.walkieEnabled } returns true
            coEvery { groups.find(PEER_CALL) } returns null
            stubDm(PEER_CALL)
            val preferred = MutableStateFlow(true)
            val vm = vm(PEER_CALL, preferMeshCalls = preferred)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

            peersFlow.value = listOf(peer(PEER_CALL, capabilities = Protocol.CAP_CALL or Protocol.CAP_WALKIE))
            advanceUntilIdle()
            assertTrue(vm.state.value.canCall)
            assertTrue(vm.state.value.canMeshCall)

            peersFlow.value = listOf(peer(PEER_CALL, capabilities = Protocol.CAP_CALL))
            advanceUntilIdle()
            assertTrue("the live call stands in", vm.state.value.canCall)
            assertFalse(vm.state.value.canMeshCall)

            peersFlow.value = listOf(peer(PEER_CALL, capabilities = 0L))
            advanceUntilIdle()
            assertFalse(vm.state.value.canCall)
            assertFalse(vm.state.value.canMeshCall)

            // The preference only orders the buttons; switched off, a peer with neither bit still gets none.
            preferred.value = false
            advanceUntilIdle()
            assertFalse(vm.state.value.canCall)
            assertFalse(vm.state.value.canMeshCall)

            peersFlow.value = listOf(peer(PEER_CALL, capabilities = Protocol.CAP_CALL))
            advanceUntilIdle()
            assertTrue(vm.state.value.canCall)
            assertFalse("a peer without the walkie", vm.state.value.canMeshCall)
        }

    @Test
    fun eachCallButtonPlacesItsOwnKindAndAMeshCallNeedsThePeersBit() =
        runTest {
            val calls = mockk<CallManager>(relaxed = true).also { calls = it }
            coEvery { calls.start(any(), any()) } returns null
            coEvery { groups.find(PEER_CALL) } returns null
            coEvery { peers.find(PEER_CALL) } returns peer(PEER_CALL, capabilities = Protocol.CAP_CALL)
            val vm = vm(PEER_CALL)
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

            vm.startCall()
            vm.startMeshCall()
            advanceUntilIdle()
            coVerify(exactly = 1) { calls.start(PEER_CALL, CallKind.LIVE) }
            coVerify(exactly = 0) { calls.start(PEER_CALL, CallKind.WALKIE) }
            assertEquals(listOf(R.string.chat_mesh_call_peer_too_old), events)

            coEvery { peers.find(PEER_CALL) } returns peer(PEER_CALL, capabilities = Protocol.CAP_CALL or Protocol.CAP_WALKIE)
            vm.startMeshCall()
            advanceUntilIdle()
            coVerify(exactly = 1) { calls.start(PEER_CALL, CallKind.WALKIE) }
        }

    /**
     * A build may take mesh calls alone (ADR 2026-10.swvp): a mesh call asks for the peer's CAP_WALKIE and nothing else,
     * a live one for CAP_CALL, and the chat offers each kind only where this build takes it.
     */
    @Test
    fun aMeshCallNeedsOnlyTheWalkieBitAndEachKindIsOfferedWhereTheBuildTakesIt() =
        runTest {
            val calls = mockk<CallManager>(relaxed = true).also { calls = it }
            every { calls.liveEnabled } returns false
            every { calls.walkieEnabled } returns true
            coEvery { calls.start(any(), any()) } returns null
            coEvery { groups.find(PEER_CALL) } returns null
            coEvery { peers.find(PEER_CALL) } returns peer(PEER_CALL, capabilities = Protocol.CAP_WALKIE)
            stubDm(PEER_CALL)
            val vm = vm(PEER_CALL)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            val events = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            peersFlow.value = listOf(peer(PEER_CALL, capabilities = Protocol.CAP_WALKIE))
            advanceUntilIdle()
            assertFalse(vm.state.value.canCall)
            assertTrue(vm.state.value.canMeshCall)

            vm.startMeshCall()
            advanceUntilIdle()
            coVerify(exactly = 1) { calls.start(PEER_CALL, CallKind.WALKIE) }
            assertEquals(emptyList<Int>(), events)

            vm.startCall()
            advanceUntilIdle()
            coVerify(exactly = 0) { calls.start(PEER_CALL, CallKind.LIVE) }
            assertEquals(listOf(R.string.chat_call_peer_too_old), events)

            // The same thread in a build that takes live calls offers one only once the peer carries CAP_CALL.
            every { calls.liveEnabled } returns true
            val live = vm(PEER_CALL)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { live.state.collect {} }
            advanceUntilIdle()
            assertFalse("a walkie-only peer", live.state.value.canCall)
            assertTrue(live.state.value.canMeshCall)

            peersFlow.value = listOf(peer(PEER_CALL, capabilities = Protocol.CAP_CALL or Protocol.CAP_WALKIE))
            advanceUntilIdle()
            assertTrue(live.state.value.canCall)
            assertTrue(live.state.value.canMeshCall)
        }

    @Test
    fun theFreeTicketsNoticeStaysUpOnceAFreeKeyIsHeldUntilItIsAcknowledged() =
        runTest {
            // C-7.6-34 amended: free tickets are on by default, so the account can be made and the upload go through
            // before the person reads the notice. A held free key keeps it up in a thread with nothing held back, and OK
            // ends it; with no key and nothing held back there is nothing to tell.
            val host = "lax.spool.getknit.app"
            val status =
                SpoolStatus(
                    "wss://$host/spool/v1",
                    true,
                    0,
                    null,
                    emptyList(),
                    maxAttachBytes = 16 shl 20,
                    ent = true,
                    entGates = SpoolTicket.FL_FILES,
                    uploads = SpoolUploads.Open,
                )
            val keys = MutableStateFlow(mapOf(host to HeldKey("https://anywhere.getknit.app", "kak1_x", free = true)))
            val consent = MutableStateFlow(emptyMap<String, Boolean>())
            val free = mockk<FreeKeyJournal>()
            every { free.freeIssuers } returns MutableStateFlow(mapOf(host to "https://anywhere.getknit.app"))
            every { free.freeConsent } returns consent
            coEvery { free.setFreeConsent(any(), any()) } answers {
                val allowed = secondArg<Boolean?>()
                consent.value = if (allowed == null) consent.value - firstArg<String>() else consent.value + (firstArg<String>() to allowed)
            }
            val keyJournal = mockk<TicketKeyJournal>()
            every { keyJournal.keys } returns keys
            every { keyJournal.keyStates } returns MutableStateFlow(emptyMap())
            val tickets = mockk<SpoolTicketJournal>()
            every { tickets.spoolTickets } returns MutableStateFlow(emptyMap())
            freeUploads = FreeUploads(MutableStateFlow(listOf(status)), free, keyJournal, tickets)
            stubDm("bob")
            val vm = vm("bob")
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.freeUploadOffer.collect {} }
            advanceUntilIdle()

            val offer = vm.freeUploadOffer.value
            assertEquals(FreeUploadOffer(host, "anywhere.getknit.app", freeKeyHeld = true), offer)

            keys.value = emptyMap()
            advanceUntilIdle()
            assertNull("no key and nothing held back: nothing to tell", vm.freeUploadOffer.value)

            keys.value = mapOf(host to HeldKey("https://anywhere.getknit.app", "kak1_x", free = true))
            advanceUntilIdle()
            assertEquals("the notice is back before OK", offer, vm.freeUploadOffer.value)
            vm.answerFreeUploads(offer!!, true)
            advanceUntilIdle()
            assertNull("OK acknowledged it", vm.freeUploadOffer.value)
            assertEquals(mapOf(host to true), consent.value)
        }
}

/** A MeshCore board's live name as its plane's face carries it (ADR 2026-11.f4d3). */
private fun meshcoreNaming(
    name: String,
    default: Boolean = false,
    unpostable: Boolean = false,
) = MeshcoreNaming(MeshcoreHandle(name.encodeToByteArray()), namedDefault = default, unpostable = unpostable)

/** The board's default name as the rig's face carries it. */
private val boardHandle = MeshcoreHandle("Knit 1a2b".encodeToByteArray())
