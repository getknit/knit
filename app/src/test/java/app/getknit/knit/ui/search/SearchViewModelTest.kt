@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceTimeBy are experimental kotlinx APIs

package app.getknit.knit.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.lora.LoraFacts
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
import kotlinx.coroutines.test.advanceTimeBy
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
 * Robolectric-hosted (titles and the "You" speaker come from real strings). The messages are a real in-memory
 * database ([InMemoryMessages]) so the FTS index and its triggers are the ones under test, not a stub.
 */
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var store: InMemoryMessages
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)

    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val acceptedFlow = MutableStateFlow(setOf("sam", "jose", "dani"))
    private val filteringFlow = MutableStateFlow(false)
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val peersFlow =
        MutableStateFlow(
            listOf(
                peer("sam", "Sam Rivera"),
                peer("jose", "José"),
                peer("dani", "Dani Cho"),
                peer("river", "River Salas"),
                peer("marlo", "Marlo K."),
            ),
        )
    private val loraFlow = MutableStateFlow(LoraFacts())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryMessages(mainDispatcher)
        coEvery { identity.nodeId() } returns "me"
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.acceptedConversations } returns acceptedFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { groups.observeGroups() } returns groupsFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
    }

    @After
    fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    private fun vm() = SearchViewModel(store.repo, peers, groups, settings, identity, loraFlow, context)

    private fun TestScope.start(vm: SearchViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    /** Types [query] and lets the debounce elapse. */
    private fun TestScope.search(
        vm: SearchViewModel,
        query: String,
    ) {
        vm.setQuery(query)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        advanceUntilIdle()
    }

    private suspend fun seedHistory() {
        store.add(
            msg("me", 10, "sam", body = "Great hiking with you today!", id = "s1"),
            msg("sam", 20, "sam", body = "Oh and I found your water bottle", id = "s2"),
            msg("jose", 30, "jose", body = "Hasta mañana", id = "j1"),
            msg("dani", 40, Conversations.NEARBY, body = "Water refill at the kiosk", id = "n1"),
        )
    }

    @Test
    fun aBlankQueryAnswersNothingAndIsNotSearching() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)
            advanceUntilIdle()

            assertTrue(vm.state.value.isEmpty)
            assertFalse(vm.state.value.isSearching)
        }

    @Test
    fun theAnswerFollowsTheDebounce() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)
            advanceUntilIdle()

            vm.setQuery("water")
            assertTrue("a keystroke reports a search on its way", vm.state.value.isSearching)
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )

            advanceTimeBy(2)
            advanceUntilIdle()
            assertEquals(
                setOf("s2", "n1"),
                vm.state.value.messages
                    .map { it.id }
                    .toSet(),
            )
            assertEquals("water", vm.state.value.forQuery)
            assertFalse(vm.state.value.isSearching)

            vm.clear()
            advanceUntilIdle()
            assertTrue("clearing is instant", vm.state.value.isEmpty)
            assertFalse(vm.state.value.isSearching)
        }

    @Test
    fun chatsMatchByTitleFoldingCaseAndAccents() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)

            search(vm, "jose")
            assertEquals(
                listOf("jose"),
                vm.state.value.chats
                    .map { it.id },
            )
            assertEquals(
                "José",
                vm.state.value.chats
                    .single()
                    .title,
            )
            assertEquals(
                ConversationKind.DM,
                vm.state.value.chats
                    .single()
                    .kind,
            )

            search(vm, "RIV")
            assertEquals(
                listOf("sam"),
                vm.state.value.chats
                    .map { it.id },
            )

            search(vm, "near")
            assertEquals(
                listOf(Conversations.NEARBY),
                vm.state.value.chats
                    .map { it.id },
            )
        }

    @Test
    fun anUnnamedGroupMatchesByItsGeneratedTitle() =
        runTest {
            seedHistory()
            groupsFlow.value = listOf(group("g-1", members = listOf("me", "sam", "dani"), createdAt = 5))
            // A known peer has spoken in it, so it is a chat rather than a stranger's request (ADR 009).
            store.add(msg("sam", 50, "g-1", body = "Carpool from the usual spot?", id = "g1"))
            val vm = vm()
            start(vm)

            search(vm, "dani")
            val chats = vm.state.value.chats
            assertEquals(setOf("g-1"), chats.map { it.id }.toSet())
            assertEquals("Sam Rivera & Dani Cho", chats.single().title)
        }

    @Test
    fun aGroupHitAndItsMessagesCarryTheSameFacesAsTheChatList() =
        runTest {
            seedHistory()
            groupsFlow.value = listOf(group("g-1", members = listOf("me", "sam", "dani"), createdAt = 5))
            store.add(msg("sam", 50, "g-1", body = "Carpool from the usual spot?", id = "g1"))
            val vm = vm()
            start(vm)

            search(vm, "carpool")
            val faces = listOf(GroupFace("dani", "Dani Cho", null), GroupFace("sam", "Sam Rivera", null))
            assertEquals(
                faces,
                vm.state.value.messages
                    .single()
                    .faces,
            )

            search(vm, "dani")
            assertEquals(
                faces,
                vm.state.value.chats
                    .single()
                    .faces,
            )
        }

    @Test
    fun peopleMatchByNameOrAliasAndFollowTheContactsRule() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)

            search(vm, "sam")
            val sam =
                vm.state.value.people
                    .single()
            assertEquals("sam", sam.nodeId)
            assertEquals("Sam Rivera", sam.name)
            assertTrue("the alias is carried for the row", sam.alias.isNotBlank())

            // The alias is a searchable name too — a precision surface (ADR 058).
            search(vm, sam.alias.split(' ').first())
            assertTrue(
                "found by the alias ${sam.alias}",
                vm.state.value.people
                    .any { it.nodeId == "sam" },
            )

            // A stranger whose DM is still a request is no contact, no chat, and nothing of theirs is searched.
            store.add(msg("river", 50, "river", body = "Salas here, saw you on the mesh", id = "r1"))
            search(vm, "salas")
            assertTrue(
                vm.state.value.people
                    .isEmpty(),
            )
            assertTrue(
                "a request thread is not a chat",
                vm.state.value.chats
                    .isEmpty(),
            )
            assertTrue(
                "nor are its messages searched",
                vm.state.value.messages
                    .isEmpty(),
            )
        }

    /**
     * #82, the seeded demo's shape: River's DM is a request and he is also in a stranger's group we never
     * answered, which is a request too, so neither makes him a person here — until a known peer speaks there.
     */
    @Test
    fun aStrangerInARequestGroupIsNoPersonUntilTheGroupIsAccepted() =
        runTest {
            seedHistory()
            groupsFlow.value = listOf(group("g-ridge", members = listOf("river", "me", "marlo"), createdBy = "river"))
            store.add(
                msg("river", 50, "river", body = "Salas here, saw you on the mesh", id = "r1"),
                msg("river", 60, "g-ridge", body = "Ridge run sign-ups are open", id = "gr1"),
            )
            val vm = vm()
            start(vm)

            search(vm, "salas")
            assertTrue(
                vm.state.value.people
                    .isEmpty(),
            )

            // Sam (an accepted contact) posts in the group: it is a chat now, and its members are people.
            store.add(msg("sam", 70, "g-ridge", body = "Count me in", id = "gr2"))
            search(vm, "salas")
            assertEquals(
                listOf("river"),
                vm.state.value.people
                    .map { it.nodeId },
            )
        }

    @Test
    fun aMessageHitCarriesItsThreadItsSpeakerAndTheMatch() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)

            search(vm, "water")
            val hits = vm.state.value.messages
            assertEquals("newest first", listOf("n1", "s2"), hits.map { it.id })

            val dm = hits.single { it.id == "s2" }
            assertEquals("Sam Rivera", dm.conversationTitle)
            assertNull("the DM's peer is already the title", dm.sender)
            assertEquals("water", dm.snippet.substring(dm.hit!!))

            val room = hits.single { it.id == "n1" }
            assertEquals("Nearby", room.conversationTitle)
            assertEquals("Dani Cho", room.sender)

            search(vm, "hiking")
            assertEquals(
                "You",
                vm.state.value.messages
                    .single()
                    .sender,
            )
        }

    @Test
    fun aBlockedPeerIsInNoSection() =
        runTest {
            seedHistory()
            store.add(msg("marlo", 60, "marlo", body = "water water water", id = "b1"))
            blockedFlow.value = setOf("marlo")
            acceptedFlow.value = acceptedFlow.value + "marlo"
            val vm = vm()
            start(vm)

            search(vm, "marlo")
            assertTrue(vm.state.value.isEmpty)
            search(vm, "water")
            assertFalse(
                vm.state.value.messages
                    .any { it.id == "b1" },
            )
        }

    @Test
    fun aLeftGroupIsNotSearched() =
        runTest {
            seedHistory()
            groupsFlow.value = listOf(group("g-1", members = listOf("me", "sam"), name = "Trailhead Crew", left = true))
            store.add(msg("sam", 70, "g-1", body = "Trailhead Crew assemble", id = "g1"))
            val vm = vm()
            start(vm)

            search(vm, "trailhead")
            assertTrue(vm.state.value.isEmpty)
        }

    @Test
    fun messagesNeedTwoCharactersAndEveryToken() =
        runTest {
            seedHistory()
            val vm = vm()
            start(vm)

            search(vm, "v")
            assertEquals(
                listOf("sam"),
                vm.state.value.chats
                    .map { it.id },
            )
            assertTrue(
                "one letter is not asked of the index",
                vm.state.value.messages
                    .isEmpty(),
            )

            search(vm, "water lantern")
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun flaggedTextIsLeftOutWhileFilteringIsOn() =
        runTest {
            store.add(
                msg("sam", 20, "sam", body = "water flagged", moderation = MessageEntity.MODERATION_TEXT_FLAGGED, id = "f1"),
                msg("sam", 21, "sam", body = "water plain", id = "f2"),
            )
            filteringFlow.value = true
            val vm = vm()
            start(vm)

            search(vm, "water")
            assertEquals(
                listOf("f2"),
                vm.state.value.messages
                    .map { it.id },
            )

            filteringFlow.value = false
            advanceUntilIdle()
            assertEquals(
                listOf("f2", "f1"),
                vm.state.value.messages
                    .map { it.id },
            )
        }
}
