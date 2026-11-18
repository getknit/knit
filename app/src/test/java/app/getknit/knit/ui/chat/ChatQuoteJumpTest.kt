package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A jump to a message — a tapped quote, a pinned message, a search hit — lands on that message, typing indicator or
 * not (#53). Under `reverseLayout` the indicator is the list's first item, drawn beneath the newest row, so a row's
 * place in the reversed rows is one short of its place in the list while somebody types. The thread here puts a row
 * taller than Robolectric's 320x470 screen on each side of the original, so a scroll one item off in either direction
 * rests a tall row on the bottom edge and leaves the original off screen, where its flash is never seen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatQuoteJumpTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        at: Int,
        id: String,
        body: String,
        quote: QuoteView? = null,
        daysAgo: Int = 0,
    ) = ChatRow(
        id = id,
        body = body,
        mine = false,
        senderName = "Bob",
        senderNodeId = "bob",
        avatarHash = null,
        sentAt = 1_700_000_000_000L + at - daysAgo * DAY_MS,
        received = false,
        quote = quote,
    )

    private fun tall(label: String) = (1..60).joinToString("\n") { "$label line $it" }

    /**
     * Oldest first, as the ViewModel emits: some history, a tall row, the original, another tall row, and the reply
     * quoting the original. A tall row on each side of the original makes a jump that lands one item short (#53) or
     * one too far rest that row on the bottom edge, with the original off screen above or below it. The tall row
     * beneath the original also keeps it off screen until something scrolls to it, which the tap tests assert.
     *
     * With [acrossDays] the original is two days old, the newer tall row yesterday's and the reply today's, so a date
     * header sits under each of the two newer rows' days: a jump that counted rows rather than list items would land
     * two short, on the newer tall row, with the original off screen above it.
     */
    private fun thread(
        typing: Boolean,
        acrossDays: Boolean = false,
    ) = ChatUiState(
        isRoom = true,
        myNodeId = "me",
        rows =
            (1..10).map { row(it, "m$it", "history $it", daysAgo = if (acrossDays) 2 else 0) } +
                row(11, "older-tall", tall("older"), daysAgo = if (acrossDays) 2 else 0) +
                row(12, "original", "the quoted message", daysAgo = if (acrossDays) 2 else 0) +
                row(13, "newer-tall", tall("newer"), daysAgo = if (acrossDays) 1 else 0) +
                row(
                    14,
                    "reply",
                    "a reply",
                    QuoteView("original", "Bob", "its snippet", hasAttachment = false, state = QuoteState.Verified),
                ),
        typingPeers = if (typing) listOf(TypingPeer(nodeId = "carol", name = "Carol", avatarHash = null)) else emptyList(),
    )

    private fun render(
        state: ChatUiState,
        jumpToMessageId: String? = null,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state = state,
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
                    jumpToMessageId = jumpToMessageId,
                )
            }
        }
    }

    /**
     * The original wholly inside the thread. Displayed alone is too loose: the list's content padding draws the edge of
     * the item past the bottom one, so a jump one item too far still leaves a sliver of the original on screen.
     */
    private fun assertTheOriginalIsInView() {
        val thread = compose.onNodeWithTag("chat_thread").getUnclippedBoundsInRoot()
        val original = compose.onNodeWithText("the quoted message").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$original not inside $thread", original.top >= thread.top && original.bottom <= thread.bottom)
    }

    private fun tapTheQuoteAndExpectTheOriginal(
        typing: Boolean,
        acrossDays: Boolean = false,
    ) {
        render(thread(typing, acrossDays))
        compose.waitForIdle()
        // The newer tall row sits between the reply and the original, so the original starts off screen.
        compose.onNodeWithText("the quoted message").assertDoesNotExist()

        compose.onNodeWithTag("chat_quote", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertTheOriginalIsInView()
    }

    @Test
    fun aTappedQuoteLandsOnItsOriginal() = tapTheQuoteAndExpectTheOriginal(typing = false)

    @Test
    fun aTappedQuoteLandsOnItsOriginalWhileSomebodyTypes() = tapTheQuoteAndExpectTheOriginal(typing = true)

    @Test
    fun aTappedQuoteLandsOnItsOriginalAcrossDays() = tapTheQuoteAndExpectTheOriginal(typing = false, acrossDays = true)

    @Test
    fun aTappedQuoteLandsOnItsOriginalAcrossDaysWhileSomebodyTypes() = tapTheQuoteAndExpectTheOriginal(typing = true, acrossDays = true)

    /** A search hit rides the `pendingQuoteTarget` path, the one a quote older than the window takes too. */
    @Test
    fun aSearchHitLandsOnItsMessageWhileSomebodyTypes() {
        render(thread(typing = true), jumpToMessageId = "original")
        compose.waitForIdle()

        assertTheOriginalIsInView()
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
