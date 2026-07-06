package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Drives the stateless `ChatScreenContent` — the send/attach button-mode switch and the reply banner. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var sends = 0
    private var attaches = 0
    private var cancelledReply = 0

    private fun content(
        input: String,
        replyingTo: ReplyRef? = null,
    ): @androidx.compose.runtime.Composable () -> Unit =
        {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state = ChatUiState(isRoom = true, myNodeId = "me"),
                    inputState = TextFieldState(input),
                    pendingAttachment = null,
                    replyingTo = replyingTo,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = { sends++ },
                    onAttachClick = { attaches++ },
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = { cancelledReply++ },
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = {},
                )
            }
        }

    @Test
    fun sendButtonSendsWhenTheInputIsNotEmpty() {
        compose.setContent(content(input = "hello"))

        compose.onNodeWithTag("chat_send").performClick()

        assertEquals(1, sends)
        assertEquals(0, attaches)
    }

    @Test
    fun sendButtonBecomesAttachWhenTheInputIsEmpty() {
        compose.setContent(content(input = ""))

        compose.onNodeWithTag("chat_send").performClick()

        assertEquals(0, sends)
        assertEquals(1, attaches)
    }

    @Test
    fun replyBannerShowsAndCancels() {
        compose.setContent(
            content(
                input = "",
                replyingTo = ReplyRef(messageId = "m1", authorId = "bob", author = "Bob", snippet = "earlier", hasAttachment = false),
            ),
        )

        compose.onNodeWithTag("reply_preview").assertIsDisplayed()
        compose.onNodeWithTag("reply_cancel").performClick()
        assertEquals(1, cancelledReply)
    }
}
