package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pinned bar under a chat's title and the long-press Pin / Unpin (#42): nothing while nothing is pinned, the
 * shown pin's author and preview, a tap that cycles through several, and an unpin from the bar or the bubble, each
 * asking for the state it wants rather than a toggle. Asserts English labels, so it pins the locale.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS")
class ChatPinnedBarTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<Pair<String, Boolean>>()

    private fun row(
        id: String,
        body: String,
    ) = ChatRow(
        id = id,
        body = body,
        mine = false,
        senderName = "Ana",
        senderNodeId = "ana",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
    )

    private fun render(
        pinned: List<PinnedView>,
        rows: List<ChatRow> = emptyList(),
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = "ana",
                    state = ChatUiState(rows = rows, isRoom = false, myNodeId = "me", title = "Ana"),
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
                    pinned = pinned,
                    onSetPinned = { id, pinned -> asked += id to pinned },
                )
            }
        }
    }

    private fun pin(
        id: String,
        author: String,
        preview: String,
    ) = PinnedView(messageId = id, author = author, preview = preview)

    @Test
    fun nothingPinnedDrawsNoBar() {
        render(pinned = emptyList())
        compose.onNodeWithTag("chat_pinned_bar").assertDoesNotExist()
    }

    @Test
    fun onePinShowsItsAuthorAndFirstLine() {
        render(pinned = listOf(pin("m1", "Bea", "Meet at the north gate")))

        compose.onNodeWithTag("chat_pinned_bar").assertIsDisplayed()
        compose.onNodeWithText("Pinned message").assertIsDisplayed()
        compose.onNodeWithText("Bea").assertIsDisplayed()
        compose.onNodeWithText("Meet at the north gate").assertIsDisplayed()
    }

    @Test
    fun tappingTheBarCyclesThroughSeveralPins() {
        render(pinned = listOf(pin("m3", "Ana", "newest"), pin("m2", "Ben", "middle"), pin("m1", "Cy", "oldest")))

        compose.onNodeWithText("Pinned message 1 of 3").assertIsDisplayed()
        compose.onNodeWithText("newest").performClick()
        compose.onNodeWithText("Pinned message 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("middle").performClick()
        compose.onNodeWithText("oldest").performClick()
        // Past the oldest it wraps round to the newest.
        compose.onNodeWithText("newest").assertIsDisplayed()
        assertEquals("a tap never unpins", emptyList<Pair<String, Boolean>>(), asked)
    }

    @Test
    fun theBarsButtonAsksToUnpinTheShownMessageEvenWhenTappedTwice() {
        render(pinned = listOf(pin("m2", "Ana", "newest"), pin("m1", "Ben", "oldest")))

        // The list has not caught up between the taps, as when Room is slower than the finger: both ask for "unpinned",
        // so the second is a no-op in the repository rather than a re-pin.
        compose.onNodeWithContentDescription("Unpin message").performClick()
        compose.onNodeWithContentDescription("Unpin message").performClick()

        assertEquals(listOf("m2" to false, "m2" to false), asked)
    }

    @Test
    fun longPressOffersPinOnAnUnpinnedMessage() {
        render(pinned = emptyList(), rows = listOf(row("m1", "where do we meet?")))

        compose.onNodeWithText("where do we meet?").performTouchInput { longClick() }
        compose.onNodeWithText("Pin message").performClick()

        assertEquals(listOf("m1" to true), asked)
    }

    @Test
    fun longPressOffersUnpinOnAPinnedMessage() {
        render(
            pinned = listOf(pin("m1", "Ana", "north gate")),
            rows = listOf(row("m1", "north gate at noon")),
        )

        compose.onNodeWithText("north gate at noon").performTouchInput { longClick() }
        compose.onNodeWithText("Unpin message").performClick()

        assertEquals(listOf("m1" to false), asked)
    }
}
