package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * The Meshtastic room's screen. Every assertion here is really one rule: a post heard on the paired radio's
 * channel must never look like a message from a Knit peer — a contact it is lined up with included — because
 * on that channel anyone can claim any name, and the UI is the only place that distinction is visible.
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
            peerId = null,
            hops = 2,
            snrDeci = -73,
            viaMqtt = false,
        )

    private fun row(
        id: String = "mp1",
        body: String = "anyone around?",
        origin: MeshOrigin? = this.origin,
        senderName: String? = null,
        avatarHash: String? = null,
    ) = ChatRow(
        id = id,
        body = body,
        mine = false,
        // Mirrors ChatViewModel: a heard author is named by the contact their board resolved to, else the
        // NodeDB name the board knew, else their `!hex` id.
        senderName = senderName ?: origin?.let { it.name ?: it.nodeLabel } ?: "Sam",
        senderNodeId = "sam",
        avatarHash = avatarHash,
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
        gate: PublicPostGate = PublicPostGate.Open,
        keyIsPublic: Boolean = true,
    ) = render(
        Conversations.MESHTASTIC,
        ChatUiState(
            rows = rows,
            isRoom = false,
            isBridged = true,
            canSendFile = false,
            publicPostBudget = PublicPostPolicy.MAX_ON_AIR_BYTES,
            publicPostGate = gate,
            publicChannelKeyIsPublic = keyIsPublic,
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
    fun theRoomSaysItIsUnencryptedAndUnverified() {
        // Both halves on the strip itself: the room is drawn like every other thread, so a reader who never
        // taps through would otherwise carry Knit's padlock into a channel that has none.
        render(listOf(row()))
        compose.onNodeWithTag("chat_mesh_notice").assertIsDisplayed()
        compose.onNodeWithText("Unencrypted radio channel — names not verified").assertIsDisplayed()

        compose.onNodeWithTag("chat_mesh_notice").performClick()
        compose.onNodeWithText("anyone can claim any name", substring = true).assertIsDisplayed()
        compose.onNodeWithText("encryption does not reach it", substring = true).assertIsDisplayed()
    }

    @Test
    fun aChannelTheUserKeyedThemselvesIsToldItIsNotEndToEnd() {
        // The board's slot 0 carries a key of the user's own, so calling their channel open would be a lie
        // in the other direction. What stays true is the part Knit can speak for: a shared channel key is
        // not end-to-end encryption, whoever holds it.
        render(listOf(row()), keyIsPublic = false)
        compose.onNodeWithText("Not end-to-end encrypted — names not verified").assertIsDisplayed()
        compose.onNodeWithText("Unencrypted radio channel", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Post to the radio channel, not end-to-end encrypted").assertIsDisplayed()
    }

    @Test
    fun aHeardPostNamesItsSpeakerAndHowItWasHeard() {
        render(listOf(row()))
        compose.onNodeWithText("Bob").assertIsDisplayed()
        // The bubble merges its children's semantics (it is one long-pressable target), so the line inside
        // it is only addressable by tag in the unmerged tree.
        compose.onNodeWithTag("chat_mesh_origin", useUnmergedTree = true).assertIsDisplayed()
        // The `!hex` id is shown because it is the only stable handle a heard author has, and the name beside
        // it is a claim. The hops and SNR are the reader's only handle on how far away that stranger is.
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · 2 hops away · SNR -7.3 dB")
    }

    @Test
    fun aSpeakerWithNoKnownNameShowsTheirIdExactlyOnce() {
        // The ordinary case for a stranger's first post: no NODEINFO for them has reached the board yet, so
        // the name line falls back to the id — and the provenance line must not then repeat it.
        render(listOf(row(origin = origin.copy(name = null))))
        compose.onNodeWithText("!1234abcd").assertIsDisplayed()
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("2 hops away · SNR -7.3 dB")
    }

    @Test
    fun aPostHeardDirectlySaysSoRatherThanCountingZeroHops() {
        render(listOf(row(origin = origin.copy(hops = 0, snrDeci = 62))))
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · heard directly · SNR 6.2 dB")
    }

    @Test
    fun anUnknownHopCountOrSnrIsAbsentRatherThanZero() {
        // Null is the firmware declining to say (no hop_start, no rxSnr) — which is not the same claim as
        // "heard directly at 0.0 dB", and must not be rendered as one.
        render(
            listOf(
                row(origin = origin.copy(hops = null, snrDeci = null)),
                // And with the id already on the name line there is nothing left to say, so no line at all.
                row(id = "mp2", origin = origin.copy(name = null, hops = null, snrDeci = null)),
            ),
        )
        compose.onAllNodesWithTag("chat_mesh_origin", useUnmergedTree = true).assertCountEquals(1)
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd")
    }

    @Test
    fun aPostOffAnInternetUplinkSaysSo() {
        // Last on the line on purpose: the hops and SNR describe the LoRa leg to this board, so the caveat
        // that the post entered the mesh from somewhere else is read after them, not instead of them.
        render(listOf(row(origin = origin.copy(viaMqtt = true))))
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · 2 hops away · SNR -7.3 dB · from the Internet")
    }

    @Test
    fun aBridgedAuthorIsNotTappable() {
        // The sharp edge: a name off an open channel is a claim, and there is no profile behind it.
        render(listOf(row()))
        compose.onNodeWithContentDescription("Bob, on the Meshtastic channel").assertHasNoClickAction()
        assertTrue("nothing may open a profile from here", profileOpened == null)
    }

    @Test
    fun aResolvedContactWearsTheirAvatarAndTapsThroughToTheCaveatFirst() {
        // Lined up with a contact by node number — a self-asserted profile field, not a signature — so the
        // bubble wears their name and face, and its avatar reaches the profile only past the caveat. The
        // separate content description is what stops the tap target announcing a verified identity.
        render(listOf(row(origin = origin.copy(name = "Knit 1a2b", peerId = "sam"), senderName = "Sam", avatarHash = "sam-avatar")))
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Sam, on the Meshtastic channel — identity not verified")
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Sam, on the Meshtastic channel").assertDoesNotExist()
        // The board's own name for the speaker moves down to the provenance line, beside the id.
        compose
            .onNodeWithTag("chat_mesh_origin", useUnmergedTree = true)
            .assertTextEquals("!1234abcd · Knit 1a2b · 2 hops away · SNR -7.3 dB")
    }

    @Test
    fun theTapOpensTheCaveatAndOnlyItsOwnButtonOpensTheProfile() {
        // The whole point of routing the tap through a dialog: dismissing it must leave the reader where a
        // straight-through tap would have taken them nowhere, and only a deliberate second tap opens the
        // contact — by peer id, since a heard post's sender column is this phone.
        render(listOf(row(origin = origin.copy(name = "Knit 1a2b", peerId = "sam"), senderName = "Sam", avatarHash = "sam-avatar")))
        compose.onNodeWithContentDescription("Sam, on the Meshtastic channel — identity not verified").performClick()
        compose.onNodeWithText("Might not be Sam").assertIsDisplayed()
        compose.onNodeWithText("no way to check that Sam wrote this", substring = true).assertIsDisplayed()
        assertTrue("the caveat alone must not open a profile", profileOpened == null)

        compose.onNodeWithText("Open profile").performClick()
        assertEquals("sam", profileOpened)
    }

    @Test
    fun withNoRadioTheComposerGivesWayToALineSayingSo() {
        render(listOf(row()), gate = PublicPostGate.NoRadio)
        compose.onNodeWithTag("chat_mesh_footer").assertIsDisplayed()
        compose.onNodeWithText("Pair a Meshtastic radio to post here", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_input").assertDoesNotExist()
    }

    @Test
    fun whileTheRadioIsDownTheComposerStaysAndItsHintSaysToConnect() {
        // A link that flaps on every Bluetooth reconnect must not take the keyboard away mid-sentence.
        render(listOf(row()), gate = PublicPostGate.RadioDown)
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect your radio to post").assertIsDisplayed()
        compose.onNodeWithTag("chat_mesh_footer").assertDoesNotExist()
    }

    @Test
    fun aKnitAuthorStaysTappable() {
        // The control for the case above: strip the origin and the same bubble is an ordinary Knit message
        // whose avatar opens its author's profile.
        renderRoom(listOf(row(origin = null)))
        compose.onNodeWithContentDescription("View Sam's profile").assertHasClickAction()
    }

    @Test
    fun theRoomOffersAComposerThatSaysWhereAPostGoesAndHowItTravels() {
        // The hint names the destination and the lack of encryption, never the author: nothing about the
        // user goes on the air with a post (ADR 2026-09.9469), so a hint promising otherwise would be the
        // wrong promise — and this is the last surface before the words leave.
        render(listOf(row()))
        // The visible hint is marked decorative so TalkBack does not read it twice; the field carries it as
        // its own accessibility label instead, which is the node that has to say the right thing.
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Post unencrypted to the radio channel").assertIsDisplayed()
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
        // The cap is hard rather than a warning: the transmit path trims a long post silently, so a sentence
        // cut in half would go out with nothing on the author's screen to say so. Refusing the byte keeps
        // that decision here.
        val budget = PublicPostPolicy.MAX_ON_AIR_BYTES
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
        val budget = PublicPostPolicy.MAX_ON_AIR_BYTES
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
        // The scope line is the one this sheet exists for: the name stays off the air, but the radio's own
        // name does not, and anyone holding the user's contact card reads that back as them. A sheet that
        // only reassured would be the dishonest version.
        render(listOf(row()), showConsent = true)
        compose.onNodeWithText("Who can see this").assertIsDisplayed()
        compose.onNodeWithText("What does not go out").assertIsDisplayed()
        compose.onNodeWithText("Your Knit name stays off the air", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("chat_mesh_consent_accept").performClick()
        assertTrue("accepting has to reach the ViewModel", consentAccepted)
    }
}
