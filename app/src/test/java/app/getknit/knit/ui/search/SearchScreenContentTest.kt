package app.getknit.knit.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Drives the stateless `SearchScreenContent`: the field, the three sections, their taps, and the empty states. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_700_000_000_000L
    private val opened = mutableListOf<String>()
    private val openedMessages = mutableListOf<Pair<String, String>>()
    private var cleared = 0
    private var backs = 0

    private val results =
        SearchUiState(
            forQuery = "sam",
            chats = listOf(ChatHit("samr1v00", "Sam Rivera", null, null, ConversationKind.DM)),
            people = listOf(PersonHit("samr1v00", "Sam Rivera", "quiet lantern", null)),
            messages =
                listOf(
                    MessageHit(
                        id = "m1",
                        conversationId = "dm-1",
                        conversationTitle = "Sam Rivera",
                        kind = ConversationKind.DM,
                        avatarHash = null,
                        sender = null,
                        snippet = "Oh and I found your water bottle",
                        hit = 20..24,
                        sentAt = now - 60_000L,
                    ),
                ),
        )

    private fun content(
        state: SearchUiState,
        query: String,
        autoFocus: Boolean = false,
        onQueryChange: (String) -> Unit = {},
        scoped: Boolean = false,
    ): @androidx.compose.runtime.Composable () -> Unit =
        {
            KnitTheme {
                SearchScreenContent(
                    state = state,
                    query = query,
                    now = now,
                    autoFocus = autoFocus,
                    onQueryChange = onQueryChange,
                    onClear = { cleared++ },
                    onOpenConversation = { opened += it },
                    onOpenMessage = { id, messageId -> openedMessages += id to messageId },
                    onBack = { backs++ },
                    scoped = scoped,
                )
            }
        }

    @Test
    fun typingReportsTheQueryAndShowsTheClearButton() {
        var query by mutableStateOf("")
        compose.setContent { content(SearchUiState(), query, onQueryChange = { query = it })() }

        compose.onNodeWithTag("search_clear").assertDoesNotExist()
        compose.onNodeWithTag("search_input").performTextInput("sam")

        assertEquals("sam", query)
        compose.onNodeWithTag("search_clear").assertIsDisplayed()
        compose.onNodeWithTag("search_clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun resultsRenderTheirSectionsAndTapsRouteTheirIds() {
        compose.setContent(content(results, "sam"))

        compose.onNodeWithTag("search_section_chats").assertIsDisplayed()
        compose.onNodeWithTag("search_section_people").assertIsDisplayed()
        compose.onNodeWithTag("search_section_messages").assertIsDisplayed()

        compose.onNodeWithTag("search_result_chat_samr1v00").performClick()
        compose.onNodeWithTag("search_result_person_samr1v00").performClick()
        compose.onNodeWithTag("search_result_message_m1").performClick()

        assertEquals(listOf("samr1v00", "samr1v00"), opened)
        assertEquals(listOf("dm-1" to "m1"), openedMessages)
    }

    @Test
    fun anEmptySectionHasNoHeading() {
        compose.setContent(content(results.copy(people = emptyList(), messages = emptyList()), "sam"))

        compose.onNodeWithTag("search_section_chats").assertIsDisplayed()
        compose.onNodeWithTag("search_section_people").assertDoesNotExist()
        compose.onNodeWithTag("search_section_messages").assertDoesNotExist()
    }

    @Test
    fun aRowIsOneSpokenNode() {
        compose.setContent(content(results, "sam"))

        compose.onNodeWithTag("search_result_message_m1").assertContentDescriptionContains("Sam Rivera", substring = true)
        compose.onNodeWithTag("search_result_message_m1").assertContentDescriptionContains("water bottle", substring = true)
        compose.onNodeWithTag("search_result_person_samr1v00").assertContentDescriptionContains("quiet lantern", substring = true)
    }

    @Test
    fun oneThreadsSearchShowsItsMessagesWithoutTheThread() {
        compose.setContent(content(results.copy(chats = emptyList(), people = emptyList()), "sam", scoped = true))

        compose.onNodeWithTag("search_section_messages").assertDoesNotExist()
        compose.onNodeWithTag("search_result_message_m1").assertContentDescriptionContains("water bottle", substring = true)
        compose
            .onNodeWithTag("search_result_message_m1")
            .assert(
                SemanticsMatcher("does not name the thread") { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.none { "Sam Rivera" in it } == true
                },
            )
        compose.onNodeWithTag("search_result_message_m1").performClick()
        assertEquals(listOf("dm-1" to "m1"), openedMessages)
    }

    @Test
    fun oneThreadsSearchSaysSoWhileIdle() {
        compose.setContent(content(SearchUiState(), "", scoped = true))

        compose.onNodeWithText(context.getString(R.string.search_in_chat_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_in_chat_idle)).assertIsDisplayed()
    }

    @Test
    fun theEmptyStatesFollowTheQuery() {
        compose.setContent(content(SearchUiState(), ""))
        compose.onNodeWithTag("search_idle").assertIsDisplayed()
    }

    @Test
    fun oneLetterAsksForMore() {
        compose.setContent(content(SearchUiState(forQuery = "s"), "s"))
        compose.onNodeWithTag("search_keep_typing").assertIsDisplayed()
    }

    @Test
    fun noResultsNamesTheQuery() {
        compose.setContent(content(SearchUiState(forQuery = "zzzz"), "zzzz"))
        compose.onNodeWithTag("search_empty").assertIsDisplayed()
        compose.onNodeWithText("No results for “zzzz”").assertIsDisplayed()
    }

    @Test
    fun anAnswerOnItsWayShowsNoEmptyState() {
        compose.setContent(content(SearchUiState(forQuery = "", isSearching = true), "zzzz"))
        compose.onNodeWithTag("search_empty").assertDoesNotExist()
        compose.onNodeWithTag("search_keep_typing").assertDoesNotExist()
        compose.onNodeWithTag("search_idle").assertDoesNotExist()
    }

    @Test
    fun theBackArrowGoesBack() {
        compose.setContent(content(SearchUiState(), ""))
        compose.onNodeWithTag("screen_search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun theFieldTakesFocusOnArrival() {
        compose.setContent(content(SearchUiState(), "", autoFocus = true))
        compose.waitForIdle()
        compose.onNodeWithTag("search_input").assertIsFocused()
    }
}
