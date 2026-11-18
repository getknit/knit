package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The way back to the newest message for a reader scrolled up into history. The thread is bottom-anchored
 * (`reverseLayout`), so index 0 is the newest row and "scrolled up" is a growing first visible index;
 * the button latches open past [ChatWindow.AWAY_FROM_NEWEST] and closes at the same `<= 1` gate the typing
 * reveal uses. A new message is followed from as far up as the previous newest's index, which a date header
 * opening a new day pushes past 1. Robolectric's 320x470 screen fits about five rows, so index 20 of a
 * sixty-row thread is well past the threshold and well off the composed window.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScrollToNewestTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        i: Int,
        sentAt: Long = 1_700_000_000_000L + i,
    ) = ChatRow(
        id = "m$i",
        body = "message $i",
        mine = false,
        senderName = "Bob",
        senderNodeId = "bob",
        avatarHash = null,
        sentAt = sentAt,
        received = false,
    )

    /** A thread of [count] rows, newest last, in the oldest-first shape the ViewModel emits. */
    private fun rows(count: Int) = (1..count).map { row(it) }

    /** The thread with somebody else's next message, sent a day after the rest: the first of a new day. */
    private fun ChatUiState.withTomorrowsMessage() =
        copy(rows = rows + row(rows.size + 1, sentAt = rows.last().sentAt + 24 * 60 * 60 * 1000L))

    /** Read inside the composition, so a test can swap the state under a running screen. */
    private fun render(state: () -> ChatUiState) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state = state(),
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    replyingTo = null,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = {},
                    onAttachClick = {},
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = {},
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = { _, _, _ -> },
                )
            }
        }
    }

    private fun sixtyRows() = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(60))

    /** The button is a way back; a thread opened on its newest message has nowhere to go back to. */
    @Test
    fun absentWhileTheThreadRestsOnItsNewestMessage() {
        render { sixtyRows() }
        compose.waitForIdle()

        compose.onNodeWithText("message 60").assertIsDisplayed()
        compose.onNodeWithTag("chat_scroll_to_bottom").assertDoesNotExist()
    }

    @Test
    fun scrollingUpIntoHistoryOffersIt() {
        render { sixtyRows() }

        // Reversed list: index 20 is message 40, well past ChatWindow.AWAY_FROM_NEWEST.
        compose.onNodeWithTag("chat_thread").performScrollToIndex(20)
        compose.waitForIdle()

        compose.onNodeWithTag("chat_scroll_to_bottom").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun tappingItReturnsToTheNewestMessageAndHidesIt() {
        render { sixtyRows() }
        compose.onNodeWithTag("chat_thread").performScrollToIndex(20)
        compose.waitForIdle()
        // Twenty rows down and off the lazy list's composed window: absent, not merely off-screen.
        compose.onNodeWithText("message 60").assertDoesNotExist()

        compose.onNodeWithTag("chat_scroll_to_bottom").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("message 60").assertIsDisplayed()
        compose.onNodeWithTag("chat_scroll_to_bottom").assertDoesNotExist()
    }

    /**
     * A reader resting on the newest message follows the next one down, even when it opens a new day: its date header
     * lands between it and the old newest, which then sits two items up rather than one.
     */
    @Test
    fun aMessageOpeningANewDayIsFollowedByAReaderAtTheBottom() {
        var live by mutableStateOf(sixtyRows())
        render { live }
        compose.waitForIdle()
        compose.onNodeWithText("message 60").assertIsDisplayed()

        live = live.withTomorrowsMessage()
        compose.waitForIdle()

        compose.onNodeWithText("message 61").assertIsDisplayed()
    }

    /** ...while a reader up in history is left where they are, the way back still offered. */
    @Test
    fun aMessageOpeningANewDayLeavesAReaderInHistoryWhereTheyAre() {
        var live by mutableStateOf(sixtyRows())
        render { live }
        compose.onNodeWithTag("chat_thread").performScrollToIndex(20)
        compose.waitForIdle()

        live = live.withTomorrowsMessage()
        compose.waitForIdle()

        compose.onNodeWithText("message 61").assertDoesNotExist()
        compose.onNodeWithTag("chat_scroll_to_bottom").assertIsDisplayed()
    }

    /** The button belongs to the loaded thread: it leaves with it and never floats over the skeleton. */
    @Test
    fun leavesWithTheThreadWhileItReloads() {
        var live by mutableStateOf(sixtyRows())
        render { live }
        compose.onNodeWithTag("chat_thread").performScrollToIndex(20)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_scroll_to_bottom").assertIsDisplayed()

        live = live.copy(isLoading = true)
        compose.waitForIdle()

        compose.onNodeWithTag("chat_scroll_to_bottom").assertDoesNotExist()
    }
}
