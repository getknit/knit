package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.lora.PublicPostPolicy
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
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
        // Mirrors ChatViewModel: a bridged author is named by their NODEINFO name when the gateway's board
        // knew one, and by their `!hex` id when it did not.
        senderName = origin?.let { it.name ?: it.nodeLabel } ?: "Sam",
        senderNodeId = "sam",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
        origin = origin,
    )

    private var profileOpened: String? = null
    private var consentAccepted = false

    /** Held rather than made inline, so a test can seed a draft and read back what the field kept of it. */
    private val inputState = TextFieldState("")

    /** The Nearby room, for the control case: the same bubble where its author *is* a Knit peer. */
    private fun renderRoom(rows: List<ChatRow>) =
        render(Conversations.NEARBY, ChatUiState(rows = rows, isRoom = true, myNodeId = "me", title = "Nearby"))

    private fun render(
        rows: List<ChatRow>,
        showConsent: Boolean = false,
    ) = render(
        Conversations.MESHTASTIC,
        ChatUiState(
            rows = rows,
            isRoom = false,
            isBridged = true,
            canSendFile = false,
            publicPostName = "Alice",
            publicPostBudget = PublicPostPolicy.bodyBudget("Alice"),
            myNodeId = "me",
            title = "LongFast",
        ),
        showConsent = showConsent,
    )

    private fun render(
        conversationId: String,
        state: ChatUiState,
        showConsent: Boolean = false,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = conversationId,
                    state = state,
                    inputState = inputState,
                    pendingAttachment = null,
                    replyingTo = null,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = { profileOpened = it },
                    onOpenGroupDetails = {},
                    onSend = {},
                    showPublicConsent = showConsent,
                    onAcceptPublicConsent = { consentAccepted = true },
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
        // The `!hex` id is shown because it is the only stable handle a bridged author has, and the name
        // beside it is a claim. The gateway is named because it is the one authenticated party in the row.
        // The hops and SNR are the reader's only handle on how far away that unverifiable stranger is.
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · via Sam's radio · 2 hops away · SNR -7.3 dB")
    }

    @Test
    fun aSpeakerWithNoKnownNameShowsTheirIdExactlyOnce() {
        // The ordinary case for a stranger's first post: no NODEINFO for them has reached the board yet, so
        // the name line falls back to the id — and the provenance line must not then repeat it.
        render(listOf(row(origin = origin.copy(name = null))))
        compose.onNodeWithText("!1234abcd").assertIsDisplayed()
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("via Sam's radio · 2 hops away · SNR -7.3 dB")
    }

    @Test
    fun aPostHeardDirectlySaysSoRatherThanCountingZeroHops() {
        render(listOf(row(origin = origin.copy(hops = 0, snrDeci = 62))))
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · via Sam's radio · heard directly · SNR 6.2 dB")
    }

    @Test
    fun anUnknownHopCountOrSnrIsAbsentRatherThanZero() {
        // Null is the firmware declining to say (no hop_start, no rxSnr) — which is not the same claim as
        // "heard directly at 0.0 dB", and must not be rendered as one.
        render(listOf(row(origin = origin.copy(hops = null, snrDeci = null))))
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · via Sam's radio")
    }

    @Test
    fun aPostOffAnInternetUplinkSaysSo() {
        // Last on the line on purpose: the hops and SNR describe the LoRa leg to the gateway's board, so the
        // caveat that the post entered that mesh from somewhere else is read after them, not instead of them.
        render(listOf(row(origin = origin.copy(viaMqtt = true))))
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · via Sam's radio · 2 hops away · SNR -7.3 dB · from the Internet")
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
    fun theRoomOffersAComposerThatNamesTheAuthorItWillPutOnTheAir() {
        // Everywhere else on this band the user is "Knit abcd" (ADR 049). The hint is the whole visible
        // surface of that exception, so it has to name them before they type rather than after they send.
        render(listOf(row()))
        // The visible hint is marked decorative so TalkBack does not read it twice; the field carries it as
        // its own accessibility label instead, which is the node that has to say the right thing.
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Post as Alice").assertIsDisplayed()
    }

    @Test
    fun theRoomOffersNothingItCannotPutOnTheAir() {
        // A photo, a file or a voice note has no way onto a foreign mesh's text channel; offering one would
        // flood it inside Knit and silently never leave. The same flag takes the composer's content receiver
        // away, which is what turns the keyboard's GIF and sticker tabs into "images not supported here" —
        // not assertable from here, since a content receiver publishes no semantics, only MIME types to the
        // IME. `ChatViewModelTest` covers the refusal under it, for the routes that do not pass this screen.
        render(listOf(row()))
        compose.onNodeWithContentDescription("Attach photo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hold to record a voice message").assertDoesNotExist()
    }

    @Test
    fun aPostIsCappedAtWhatOneMeshtasticFrameCarries() {
        // The cap is hard rather than a warning: the transmit path trims a long post silently, and does it on
        // whichever phone in the pocket owns the board — so a sentence cut in half would go out under this
        // author's name with nothing on their screen to say so. Refusing the byte keeps that decision here.
        val budget = PublicPostPolicy.bodyBudget("Alice")
        render(listOf(row()))
        compose.onNodeWithTag("chat_input").performTextInput("x".repeat(budget))
        compose.onNodeWithTag("chat_input").performTextInput("y")
        assertEquals("the overflowing keystroke is refused, not trimmed in", budget, inputState.text.length)
        assertTrue("and it is refused whole", inputState.text.none { it == 'y' })
    }

    @Test
    fun theCapIsCountedInBytesBecauseTheFrameIs() {
        // The failure a character cap would miss: fifty emoji are two hundred bytes, and a field measuring
        // UTF-16 units would wave all of them through and let the radio cut the line instead.
        render(listOf(row()))
        compose.onNodeWithTag("chat_input").performTextInput("🙂".repeat(60))
        assertTrue("nothing over the byte budget was kept", inputState.text.isEmpty())
    }

    @Test
    fun theLastStretchOfTheBudgetIsCounted() {
        // Only the last stretch: a permanent counter over a field almost nobody fills is chrome, and a field
        // that stops accepting input with no warning at all reads as a bug. Both halves are shown because the
        // cap here is short and surprising — a bare remainder would never say what the room was.
        val budget = PublicPostPolicy.bodyBudget("Alice")
        render(listOf(row()))
        compose.onNodeWithTag("chat_public_post_length").assertDoesNotExist()

        compose.onNodeWithTag("chat_input").performTextInput("x".repeat(budget - 3))
        compose
            .onNodeWithTag("chat_public_post_length", useUnmergedTree = true)
            .assertTextEquals("${budget - 3}/$budget")

        compose.onNodeWithTag("chat_input").performTextInput("xxx")
        compose
            .onNodeWithTag("chat_public_post_length", useUnmergedTree = true)
            .assertTextEquals("$budget/$budget")
        // Read aloud as a fraction otherwise; the node carries its own spoken label.
        compose.onNodeWithContentDescription("Post length: $budget of $budget").assertIsDisplayed()
    }

    @Test
    fun theDisclosureSaysWhatLeavesTheDeviceAndWhatDoesNot() {
        // The scope line is the one this sheet exists for: a name that stays off the radio everywhere else
        // rides on the front of every post here. A sheet that only reassured would be the dishonest version.
        render(listOf(row()), showConsent = true)
        compose.onNodeWithText("Who can see this").assertIsDisplayed()
        compose.onNodeWithText("What does not go out").assertIsDisplayed()
        compose.onNodeWithText("Your Knit name goes on the front", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("chat_mesh_consent_accept").performClick()
        assertTrue("accepting has to reach the ViewModel", consentAccepted)
    }
}
