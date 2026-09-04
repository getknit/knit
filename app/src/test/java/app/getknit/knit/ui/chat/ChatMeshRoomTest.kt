package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The bridged Meshtastic room's screen. Every assertion here is really one rule: a post overheard on somebody
 * else's public channel must never look like a message from a Knit peer — because on that channel anyone can
 * claim any name, and the UI is the only place that distinction is visible.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMeshRoomTest {
    @get:Rule
    val compose = createComposeRule()

    private val origin =
        MeshOrigin(
            nodeLabel = "!1234abcd",
            name = "Bob",
            gateway = "Sam",
            hops = 2,
            snrDeci = -73,
            viaMqtt = false,
        )

    private fun row(
        id: String = "mp1",
        body: String = "anyone around?",
        origin: MeshOrigin? = this.origin,
    ) = ChatRow(
        id = id,
        body = body,
        mine = false,
        senderName = origin?.name ?: "Sam",
        senderNodeId = "sam",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
        origin = origin,
    )

    private var profileOpened: String? = null

    /** The Nearby room, for the control case: the same bubble where its author *is* a Knit peer. */
    private fun renderRoom(rows: List<ChatRow>) =
        render(Conversations.NEARBY, ChatUiState(rows = rows, isRoom = true, myNodeId = "me", title = "Nearby"))

    private fun render(rows: List<ChatRow>) =
        render(
            Conversations.MESHTASTIC,
            ChatUiState(
                rows = rows,
                isRoom = false,
                isBridged = true,
                canSend = false,
                canSendFile = false,
                myNodeId = "me",
                title = "LongFast",
            ),
        )

    private fun render(
        conversationId: String,
        state: ChatUiState,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = conversationId,
                    state = state,
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    replyingTo = null,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = { profileOpened = it },
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

    @Test
    fun theRoomSaysItIsPublicAndUnverified() {
        render(listOf(row()))
        compose.onNodeWithTag("chat_mesh_notice").assertIsDisplayed()
        compose.onNodeWithText("Public channel — names here are not verified").assertIsDisplayed()

        compose.onNodeWithTag("chat_mesh_notice").performClick()
        compose.onNodeWithText("anyone can claim any name", substring = true).assertIsDisplayed()
    }

    @Test
    fun aBridgedPostNamesItsSpeakerAndTheRadioThatCarriedIt() {
        render(listOf(row()))
        compose.onNodeWithText("Bob").assertIsDisplayed()
        // The bubble merges its children's semantics (it is one long-pressable target), so the line inside
        // it is only addressable by tag in the unmerged tree.
        compose.onNodeWithTag("chat_mesh_origin", useUnmergedTree = true).assertIsDisplayed()
        // The `!hex` id is always shown: it is the only stable handle a bridged author has, and the name
        // beside it is a claim. The gateway is named because it is the one authenticated party in the row.
        compose.onNodeWithText("!1234abcd", substring = true).assertIsDisplayed()
        compose.onNodeWithText("via Sam's radio", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPostOffAnInternetUplinkSaysSo() {
        render(listOf(row(origin = origin.copy(viaMqtt = true))))
        compose.onNodeWithText("from the Internet", substring = true).assertIsDisplayed()
    }

    @Test
    fun aBridgedAuthorIsNotTappable() {
        // The sharp edge. Routing this tap would land on the *gateway* — a real contact — and offer to
        // message somebody who never said any of this.
        render(listOf(row()))
        compose.onNodeWithContentDescription("Bob, on the Meshtastic public channel").assertHasNoClickAction()
        assertTrue("nothing may open a profile from here", profileOpened == null)
    }

    @Test
    fun aKnitAuthorStaysTappable() {
        // The control for the case above: strip the origin and the same bubble is an ordinary Knit message
        // whose avatar opens its author's profile.
        renderRoom(listOf(row(origin = null)))
        compose.onNodeWithContentDescription("View Sam's profile").assertHasClickAction()
    }

    @Test
    fun theRoomOffersNoComposer() {
        render(listOf(row()))
        compose.onNodeWithTag("chat_read_only").assertIsDisplayed()
        compose.onNodeWithText("Knit does not post to this channel").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").assertDoesNotExist()
    }
}
