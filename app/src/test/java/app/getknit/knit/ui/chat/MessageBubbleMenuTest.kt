package app.getknit.knit.ui.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The long-press menu's Translate (ADR 2026-10.t2h6), whose offer is answered after the menu opens. The menu is drawn
 * in the chat's own window by [MessageMenuHost] (ADR 2026-11.pk9u), apart from the bubble, in a list as on the screen:
 * an offer answered in the frame that layer first composes used to be lost, and Translate never showed on a phone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageBubbleMenuTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val row =
        ChatRow(
            id = "m1",
            body = "message 1",
            mine = false,
            senderName = "Bob",
            senderNodeId = "bob",
            avatarHash = null,
            sentAt = 1_700_000_000_001L,
            received = false,
        )

    private val translated = mutableListOf<String>()

    private fun bubble(offersTranslate: suspend (ChatRow) -> Boolean) {
        compose.setContent {
            KnitTheme {
                MessageMenuHost {
                    LazyColumn {
                        item {
                            MessageBubble(
                                row = row,
                                now = 1_700_000_000_000L,
                                showSenderName = false,
                                onImageClick = {},
                                onOpenProfile = {},
                                onReact = { _, _ -> },
                                onDelete = {},
                                onBlock = {},
                                onCopy = {},
                                onTranslate = { translated += it.id },
                                offersTranslate = offersTranslate,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithText("message 1").performTouchInput { longClick() }
        compose.onNodeWithTag("chat_message_menu").assertExists()
    }

    private fun translateShown() = compose.onAllNodesWithText("Translate").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anOfferAnsweredAsTheMenuOpensShowsTranslate() {
        // Answered without suspending, as the chat's own rule is on a phone: in the frame the layer first composes.
        bubble { true }
        openMenu()

        compose.waitUntil(AWAIT_MS) { translateShown() }
        compose.onNodeWithText("Translate").performClick()

        assertEquals(listOf("m1"), translated)
        compose.onNodeWithTag("chat_message_menu").assertDoesNotExist()
    }

    @Test
    fun anOfferAnsweredLaterStillShowsTranslate() {
        val answer = CompletableDeferred<Boolean>()
        val asked = AtomicInteger()
        bubble {
            asked.incrementAndGet()
            answer.await()
        }
        openMenu()
        compose.waitUntil(AWAIT_MS) { asked.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Translate").assertDoesNotExist()

        answer.complete(true)

        compose.waitUntil(AWAIT_MS) { translateShown() }
    }

    @Test
    fun aRefusedOfferShowsNoTranslate() {
        val answered = AtomicInteger()
        bubble {
            answered.incrementAndGet()
            false
        }
        openMenu()

        // The answer is in before the menu is read: otherwise its absence proves nothing.
        compose.waitUntil(AWAIT_MS) { answered.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Translate").assertDoesNotExist()
        compose.onNodeWithText("Reply").assertExists()
    }

    private companion object {
        const val AWAIT_MS = 5_000L
    }
}
