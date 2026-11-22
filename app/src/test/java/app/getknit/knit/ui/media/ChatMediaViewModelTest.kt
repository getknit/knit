package app.getknit.knit.ui.media

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ThreadMediaRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.ui.chat.AttachmentExport
import app.getknit.knit.ui.chat.ChatWindow
import app.getknit.knit.ui.chat.PendingSave
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.msg
import app.getknit.knit.ui.peer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * "Media, links and files" (ADR 2026-11.dgvf): that only the open tab is read, what each tab lists and covers, what
 * the screen asks the blobs table for, which link wears a card, and how far each tab's window grows. The SQL itself,
 * each tab's kinds included, is `MessageMediaDaoTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaViewModelTest {
    private val media = mockk<ThreadMediaRepository>()
    private val blobs = mockk<BlobRepository>()
    private val peers = mockk<PeerRepository>()
    private val settings = mockk<SettingsStore>()
    private val identity = mockk<IdentitySource>()
    private val linkCards = mockk<LinkCardStore>()
    private val export = mockk<AttachmentExport>()
    private val context = mockk<Context>()

    private val mediaRows = MutableStateFlow<List<MessageEntity>>(emptyList())

    // Without replay until a test emits: a tab whose read has not answered yet.
    private val fileRows = MutableSharedFlow<List<MessageEntity>>(replay = 1)
    private val linkRows = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val held = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val flagged = MutableStateFlow<List<String>>(emptyList())
    private val filtering = MutableStateFlow(true)
    private val blocked = MutableStateFlow<Set<String>>(emptySet())
    private val cards = MutableStateFlow<Map<String, LinkCard>>(emptyMap())

    /** Every hash set the screen asked the blobs table about, in order. */
    private val askedSizes = mutableListOf<Set<String>>()

    /** A size answer a test holds back for one hash set, released when it emits. */
    private val gatedSizes = mutableMapOf<Set<String>, MutableSharedFlow<Map<String, Int>>>()

    /** Every (tab, block list, limit) a read was opened with, in order. */
    private val reads = mutableListOf<Triple<MediaTab, Set<String>, Int>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { media.observeMedia(THREAD, any(), any()) } answers {
            reads += Triple(MediaTab.Media, secondArg(), thirdArg())
            mediaRows
        }
        every { media.observeFiles(THREAD, any(), any()) } answers {
            reads += Triple(MediaTab.Files, secondArg(), thirdArg())
            fileRows
        }
        every { media.observeLinkRows(THREAD, any(), any(), any()) } answers {
            reads += Triple(MediaTab.Links, secondArg(), arg(3))
            linkRows
        }
        every { blobs.observeSizes(any()) } answers {
            val asked = firstArg<Set<String>>()
            askedSizes += asked
            gatedSizes[asked] ?: held.map { sizes -> sizes.filterKeys { it in asked } }
        }
        every { peers.observeDirectory() } returns flowOf(directoryOf(listOf(peer("ana", name = "Ana"))))
        every { settings.blockedNodeIds } returns blocked
        every { settings.contentFilteringEnabled } returns filtering
        coEvery { identity.nodeId() } returns ME
        every { linkCards.cards } returns cards
        coEvery { linkCards.ensure(any(), any()) } just Runs
        every { context.getString(R.string.chat_self_name) } returns "You"
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.started(): ChatMediaViewModel {
        val vm =
            ChatMediaViewModel(
                conversationId = THREAD,
                media = media,
                blobs = blobs,
                peers = peers,
                settings = settings,
                identity = identity,
                flaggedHashes = flagged,
                linkCards = linkCards,
                export = export,
                context = context,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun onlyTheOpenTabIsReadAndASwitchStopsTheLastOnesRead() =
        runTest {
            val vm = started()
            vm.state.first { it.loaded }
            assertEquals(listOf(MediaTab.Media), reads.map { it.first })

            vm.selectTab(MediaTab.Files)
            mediaRows.subscriptionCount.first { it == 0 }
            assertEquals(listOf(MediaTab.Media, MediaTab.Files), reads.map { it.first })

            vm.selectTab(MediaTab.Links)
            linkRows.subscriptionCount.first { it == 1 }
            fileRows.subscriptionCount.first { it == 0 }
            assertEquals(0, mediaRows.subscriptionCount.value)
        }

    @Test
    fun aTabIsLoadingUntilItsOwnReadAnswersNeverShowingTheLastTabs() =
        runTest {
            mediaRows.value = listOf(row("photo", 1L, "P", mime = "image/jpeg"))
            val vm = started()
            vm.state.first { it.media.isNotEmpty() }

            vm.selectTab(MediaTab.Files)
            val switching = vm.state.first { it.tab == MediaTab.Files }
            assertFalse("no empty state claimed before the read answers", switching.loaded)
            assertTrue(switching.media.isEmpty() && switching.files.isEmpty())

            fileRows.emit(listOf(row("file", 1L, "F", mime = "application/pdf", name = "a.pdf")))
            assertEquals(
                listOf("file"),
                vm.state
                    .first { it.loaded }
                    .files
                    .map { it.messageId },
            )
        }

    @Test
    fun aTabIsLoadingUntilItsOwnSizesAnswerNeverReadingTheLastTabs() =
        runTest {
            val filesSizes = MutableSharedFlow<Map<String, Int>>(replay = 1)
            gatedSizes[setOf("F")] = filesSizes
            mediaRows.value = listOf(row("photo", 1L, "P", mime = "image/jpeg"))
            held.value = mapOf("P" to 10, "F" to 10)
            val vm = started()
            vm.state.first { it.media.isNotEmpty() }

            vm.selectTab(MediaTab.Files)
            fileRows.emit(listOf(row("file", 1L, "F", mime = "application/pdf", name = "a.pdf")))
            // The Files rows are in and their sizes are not: the Media tab's must not stand in, which would show the
            // file as not on the phone.
            assertFalse(vm.state.first { it.tab == MediaTab.Files }.loaded)

            filesSizes.emit(mapOf("F" to 10))
            assertTrue(
                vm.state
                    .first { it.loaded && it.tab == MediaTab.Files }
                    .files
                    .single()
                    .held,
            )
        }

    @Test
    fun theMediaTabTellsVideosFromPhotosAndTheFilesTabVoiceFromFiles() =
        runTest {
            mediaRows.value =
                listOf(
                    row("video", 2L, "W", mime = "video/mp4", name = "video.mp4").copy(videoDurationMs = 9_000L),
                    row("photo", 1L, "P", mime = "image/jpeg"),
                )
            val vm = started()
            val media = vm.state.first { it.loaded }.media
            assertEquals(listOf("video" to true, "photo" to false), media.map { it.messageId to it.video })
            assertEquals(9_000L, media.first().durationMs)

            vm.selectTab(MediaTab.Files)
            fileRows.emit(
                listOf(
                    row("voice", 2L, "V", mime = "audio/aac").copy(voiceDurationMs = 4_000),
                    row("file", 1L, "F", mime = "application/pdf", name = "map.pdf"),
                ),
            )
            val files = vm.state.first { it.loaded && it.tab == MediaTab.Files }.files
            assertEquals(listOf("voice" to true, "file" to false), files.map { it.messageId to it.voice })
            assertEquals(4_000L, files.first().voiceDurationMs)
        }

    @Test
    fun aFlaggedPictureIsCoveredOnlyWhileContentFilteringIsOn() =
        runTest {
            mediaRows.value =
                listOf(
                    row("poster", 3L, "W", mime = "video/mp4").copy(videoDurationMs = 9_000L, videoPosterFlagged = true),
                    row("flagged", 2L, "X", mime = "image/jpeg"),
                    row("clean", 1L, "P", mime = "image/jpeg"),
                )
            flagged.value = listOf("X")
            val vm = started()

            val on = vm.state.first { it.loaded }
            assertEquals(
                "the picture by its hash's verdict, the video by its poster's too",
                listOf("poster" to true, "flagged" to true, "clean" to false),
                on.media.map { it.messageId to it.covered },
            )

            filtering.value = false
            val off = vm.state.first { s -> s.loaded && s.media.none { it.covered } }
            assertEquals(3, off.media.size)
        }

    @Test
    fun aFlaggedFileWearsItsFlagOnlyWhileContentFilteringIsOn() =
        runTest {
            flagged.value = listOf("F")
            val vm = started()
            vm.selectTab(MediaTab.Files)
            fileRows.emit(
                listOf(
                    row("flagged", 2L, "F", mime = "application/zip", name = "a.zip"),
                    row("clean", 1L, "G", mime = "application/zip", name = "b.zip"),
                ),
            )

            val on = vm.state.first { it.loaded && it.tab == MediaTab.Files }.files
            assertEquals(listOf(true, false), on.map { it.flagged })

            filtering.value = false
            vm.state.first { s -> s.loaded && s.files.size == 2 && s.files.none { it.flagged } }
        }

    @Test
    fun theBlobsTableIsAskedForTheOpenTabsAttachmentsOrCardsOnly() =
        runTest {
            val vm = started()
            vm.state.first { it.loaded }
            assertEquals("a tab with nothing to size asks for nothing", emptySet<String>(), askedSizes.last())

            mediaRows.value = listOf(row("photo", 1L, "P", mime = "image/jpeg"))
            vm.state.first { it.media.isNotEmpty() }
            assertEquals(setOf("P"), askedSizes.last())

            linkRows.value =
                listOf(
                    row("carded", 3L, "C", mime = LinkPreviewBlob.MIME, body = "https://a.example"),
                    // A link with no card: nothing of it is in the blobs table.
                    row("bare", 2L, null, body = "https://b.example"),
                    // A photo captioned with a link: listed for its link, and its photo is the Media tab's to size.
                    row("captioned", 1L, "PX", mime = "image/jpeg", body = "https://c.example"),
                )
            vm.selectTab(MediaTab.Links)
            vm.state.first { it.links.size == 3 }
            assertEquals("the Links tab asks for its cards, and the Media tab's photo no longer", setOf("C"), askedSizes.last())
        }

    @Test
    fun aTileOrFileIsHeldOnlyWhenItsBytesAreOnThePhone() =
        runTest {
            mediaRows.value = listOf(row("photo", 1L, "P", mime = "image/jpeg"))
            held.value = mapOf("H" to 10, "V" to 10)
            val vm = started()
            assertFalse(
                vm.state
                    .first { it.loaded }
                    .media
                    .single()
                    .held,
            )

            vm.selectTab(MediaTab.Files)
            fileRows.emit(
                listOf(
                    row("voice", 4L, "V", mime = "audio/aac"),
                    row("away", 3L, "A", mime = "application/pdf", name = "away.pdf"),
                    row("here", 2L, "H", mime = "application/pdf", name = "here.pdf"),
                ),
            )
            val files = vm.state.first { it.loaded && it.tab == MediaTab.Files }.files

            assertEquals(listOf(true, false, true), files.map { it.held })
            assertEquals(PendingSave("H", null, "here.pdf", "application/pdf"), files[2].pending)
            assertNull("a file not here yet opens in the chat", files[1].pending)
            assertNull("a voice message plays in the chat", files[0].pending)
        }

    @Test
    fun aCardIsDecodedOnceItsBlobHasLandedWhileTheLinksTabIsOpen() =
        runTest {
            linkRows.value = listOf(row("carded", 1L, "C", mime = LinkPreviewBlob.MIME, body = "https://a.example"))
            val vm = started()
            vm.selectTab(MediaTab.Links)
            // The link is listed, so the Links tab's sizes have answered, without the card: nothing to decode yet.
            vm.state.first { it.links.isNotEmpty() }
            coVerify(exactly = 0) { linkCards.ensure(any(), any()) }

            held.value = mapOf("C" to 10)
            coVerify(exactly = 1) { linkCards.ensure("C", null) }
        }

    @Test
    fun aLinkWearsItsCardOnlyWhenTheBodyHoldsTheCardsLink() =
        runTest {
            cards.value =
                mapOf(
                    "C1" to LinkCard("https://a.example/x", "a.example", "A", null, hasImage = true),
                    "C2" to LinkCard("https://elsewhere.example", "elsewhere.example", "Not this one", null, hasImage = false),
                )
            linkRows.value =
                listOf(
                    row("mismatched", 2L, "C2", mime = LinkPreviewBlob.MIME, body = "https://b.example"),
                    row("two", 1L, "C1", mime = LinkPreviewBlob.MIME, body = "see http://a.example/x and www.b.example"),
                )
            val vm = started()
            vm.selectTab(MediaTab.Links)
            val links = vm.state.first { it.links.size == 3 }.links

            assertEquals(listOf("mismatched#0", "two#0", "two#1"), links.map { it.key })
            assertNull("a card fetched for another page is never drawn", links[0].card)
            assertEquals("A", links[1].card?.title)
            assertEquals("C1", links[1].cardHash)
            assertEquals("a bare www. link opens as https", "https://www.b.example", links[2].url)
            assertNull(links[2].card)
        }

    @Test
    fun aFlaggedCardIsDroppedForItsBareLinkWhileFilteringIsOn() =
        runTest {
            cards.value = mapOf("C1" to LinkCard("https://a.example", "a.example", "A", null, hasImage = true))
            linkRows.value = listOf(row("link", 1L, "C1", mime = LinkPreviewBlob.MIME, body = "https://a.example"))
            flagged.value = listOf("C1")
            val vm = started()
            vm.selectTab(MediaTab.Links)

            val on =
                vm.state
                    .first { it.links.isNotEmpty() }
                    .links
                    .single()
            assertEquals("https://a.example", on.url)
            assertNull(on.card)

            filtering.value = false
            vm.state.first { it.links.singleOrNull()?.card != null }
        }

    @Test
    fun ourOwnRowsAreYoursAndOthersWearTheirLabel() =
        runTest {
            mediaRows.value =
                listOf(
                    row("theirs", 2L, "T", mime = "image/jpeg", sender = "ana"),
                    row("ours", 1L, "O", mime = "image/jpeg", sender = ME),
                )
            val media = started().state.first { it.loaded }.media

            assertEquals(listOf("Ana" to false, "You" to true), media.map { it.senderName to it.mine })
        }

    @Test
    fun aBlockIsReadAgainWithTheBlockList() =
        runTest {
            val vm = started()
            vm.state.first { it.loaded }
            blocked.value = setOf("troll")
            vm.state.first { it.loaded }

            assertEquals(Triple(MediaTab.Media, setOf("troll"), ChatMediaViewModel.PAGE), reads.last())
        }

    @Test
    fun eachTabsWindowGrowsByAPageUpToTheCapAndIsKeptAcrossSwitches() =
        runTest {
            mediaRows.value = rows(ChatMediaViewModel.PAGE)
            val vm = started()
            assertTrue(vm.state.first { it.loaded }.more)

            vm.loadMore()
            assertEquals(2 * ChatMediaViewModel.PAGE, reads.last().third)

            vm.selectTab(MediaTab.Files)
            assertEquals(
                "the Files tab keeps its own window",
                Triple(MediaTab.Files, emptySet<String>(), ChatMediaViewModel.PAGE),
                reads.last(),
            )
            vm.selectTab(MediaTab.Media)
            assertEquals("and the Media tab's grown one is kept", 2 * ChatMediaViewModel.PAGE, reads.last().third)

            repeat(ChatWindow.MAX / ChatMediaViewModel.PAGE + 2) { vm.loadMore() }
            assertEquals("never past the bound the blob read's IN list rests on", ChatWindow.MAX, reads.last().third)

            mediaRows.value = rows(ChatWindow.MAX)
            assertFalse("a full window at the cap offers no more", vm.state.first { it.media.size == ChatWindow.MAX }.more)
        }

    @Test
    fun aWindowThatIsNotFullOffersNoMore() =
        runTest {
            mediaRows.value = rows(ChatMediaViewModel.PAGE - 1)
            assertFalse(started().state.first { it.loaded }.more)
        }

    private fun rows(count: Int) = List(count) { i -> row("r$i", (count - i).toLong(), "H$i", mime = "image/jpeg") }

    private fun row(
        id: String,
        sentAt: Long,
        hash: String?,
        mime: String? = null,
        name: String? = null,
        body: String = "",
        sender: String = "ana",
    ): MessageEntity =
        msg(senderId = sender, sentAt = sentAt, conversationId = THREAD, body = body, attachmentHash = hash, id = id)
            .copy(attachmentMime = mime, attachmentName = name)

    private companion object {
        const val THREAD = "thread"
        const val ME = "me"
    }
}
