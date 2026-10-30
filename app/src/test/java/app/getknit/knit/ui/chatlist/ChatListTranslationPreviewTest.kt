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
import app.getknit.knit.data.message.MessageEntity
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A translation the reader made, kept on its message's row (ADR 2026-10.t2h6), previews in the body's place with the
 * translation's mark, as the bubble shows it. Over a real in-memory database, so the write the translator makes
 * reaches the row through the list's own query; its own class rather than more of [ChatListViewModelTest], which is
 * at detekt's size ceiling. The Translation switch is handed in, so the rule runs in a build without translation too.
 */
@RunWith(AndroidJUnit4::class)
class ChatListTranslationPreviewTest {
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
    private val filteringFlow = MutableStateFlow(true)
    private val translationOn = MutableStateFlow(true)

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
        every { settings.contentFilteringEnabled } returns filteringFlow
        // A relaxed mock's Flow never emits, and one silent arm stalls the whole combine.
        every { settings.cloneSeenAt } returns MutableStateFlow(0L)
        every { settings.cloneDismissedAt } returns MutableStateFlow(0L)
        every { settings.meshPausedUntil } returns MutableStateFlow(null)
        every { settings.meshEnabled } returns MutableStateFlow(true)
        every { drafts.all } returns MutableStateFlow(emptyMap<String, DraftEntity>())
        every { transfers.states } returns MutableStateFlow(emptyMap<String, TransferState>())
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.started(): ChatListViewModel {
        val vm =
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
                translationOn = translationOn,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private fun ChatListViewModel.row(id: String) = state.value.conversations.first { it.id == id }

    /** Bob's Spanish line heads his DM; [translate] then writes the reader's translation onto it, as the translator does. */
    private suspend fun seedDm(moderation: Int = MessageEntity.MODERATION_NONE) =
        store.set(
            msg(
                senderId = "bob",
                sentAt = 100,
                conversationId = "bob",
                body = "¿nos vemos a las seis?",
                moderation = moderation,
                id = HEAD,
            ),
        )

    private suspend fun translate(flagged: Boolean = false) =
        store.db.messageDao().setTranslation(HEAD, "see you at six?", from = "es", to = "en", flagged = flagged, guessed = false)

    @Test
    fun aTranslatedHeadPreviewsItsTranslationWithTheMark() =
        runTest {
            val vm = started()
            seedDm()
            advanceUntilIdle()
            assertEquals("¿nos vemos a las seis?", vm.row("bob").lastPreview)
            assertNull(vm.row("bob").previewMark)

            translate()
            advanceUntilIdle()
            assertEquals("see you at six?", vm.row("bob").lastPreview)
            assertEquals(PreviewMark.Translated("es"), vm.row("bob").previewMark)
        }

    @Test
    fun aGroupLineKeepsItsSpeakerAheadOfTheTranslation() =
        runTest {
            val vm = started()
            val hikers = group(groupId = "g-1", members = listOf(ME, "bob"), createdAt = 10)
            groupsFlow.value = listOf(hikers)
            acceptedFlow.value = setOf("g-1")
            store.set(msg(senderId = "bob", sentAt = 100, conversationId = "g-1", body = "¿nos vemos a las seis?", id = HEAD))
            translate()
            advanceUntilIdle()

            assertEquals("Bob: see you at six?", vm.row("g-1").lastPreview)
            assertEquals(PreviewMark.Translated("es"), vm.row("g-1").previewMark)
        }

    @Test
    fun withTranslationOffTheRowShowsTheOriginal() =
        runTest {
            val vm = started()
            seedDm()
            translate()
            advanceUntilIdle()
            assertEquals("see you at six?", vm.row("bob").lastPreview)

            translationOn.value = false
            advanceUntilIdle()
            assertEquals("¿nos vemos a las seis?", vm.row("bob").lastPreview)
            assertNull(vm.row("bob").previewMark)
        }

    @Test
    fun aFlaggedTranslationReadsAsHiddenOnlyWhileFiltering() =
        runTest {
            val vm = started()
            seedDm()
            translate(flagged = true)
            advanceUntilIdle()
            assertEquals("Message hidden", vm.row("bob").lastPreview)
            assertNull("a hidden line wears no mark", vm.row("bob").previewMark)

            filteringFlow.value = false
            advanceUntilIdle()
            assertEquals("see you at six?", vm.row("bob").lastPreview)
            assertEquals(PreviewMark.Translated("es"), vm.row("bob").previewMark)
        }

    @Test
    fun aHiddenBodyStaysHiddenWhateverItsTranslation() =
        runTest {
            val vm = started()
            seedDm(moderation = MessageEntity.MODERATION_TEXT_FLAGGED)
            translate()
            advanceUntilIdle()

            assertEquals("Message hidden", vm.row("bob").lastPreview)
            assertNull(vm.row("bob").previewMark)

            // The body hides only while filtering: with it off, the translation shows as any other does.
            filteringFlow.value = false
            advanceUntilIdle()
            assertEquals("see you at six?", vm.row("bob").lastPreview)
            assertEquals(PreviewMark.Translated("es"), vm.row("bob").previewMark)
        }

    @Test
    fun aLineOfTheRowsOwnWearsNoTranslationMark() =
        runTest {
            val vm = started()
            store.set(
                msg(senderId = ME, sentAt = 50, conversationId = "bob", recipientId = "bob", body = "lunch?", id = LUNCH),
                msg(senderId = "bob", sentAt = 100, conversationId = "bob", body = "¿nos vemos a las seis?", id = HEAD),
            )
            translate()
            // Bob reacts to our message after his translated one: the reaction speaks for the row, not the translation.
            reactions.apply(ReactionEntity(LUNCH, "bob", "👍", 200))
            advanceUntilIdle()

            assertEquals("Reacted 👍 to “lunch?”", vm.row("bob").lastPreview)
            assertNull(vm.row("bob").previewMark)
        }

    private companion object {
        const val ME = "me"
        const val HEAD = "bob-head"
        const val LUNCH = "me-lunch"
    }
}
