package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's two relay indicators, and — as much as the assertions — the cases where they must stay
 * quiet. A marker that appears when it should not is worse than none: it teaches people to read a
 * working send as a broken one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRelayIndicatorTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        mine: Boolean = true,
        attachmentRelay: AttachmentRelay = AttachmentRelay.Silent,
        received: Boolean = false,
        deliveredVia: DeliveryPlane = DeliveryPlane.Unknown,
        attachmentReady: Boolean = true,
        attachmentWait: AttachmentWait = AttachmentWait.Nearby,
    ) = ChatRow(
        id = "m1",
        body = "look at this",
        mine = mine,
        senderName = if (mine) "You" else "Ana",
        senderNodeId = if (mine) "me" else "ana",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = received,
        deliveredVia = deliveredVia,
        attachmentHash = "h1",
        attachmentReady = attachmentReady,
        attachmentRelay = attachmentRelay,
        attachmentWait = attachmentWait,
    )

    private fun render(
        rows: List<ChatRow> = emptyList(),
        reach: RelayReach = RelayReach.Silent,
        staged: AttachmentRelay = AttachmentRelay.Silent,
        onDismissRelayNotice: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state =
                        ChatUiState(
                            rows = rows,
                            isRoom = true,
                            myNodeId = "me",
                            title = "Ana",
                            relayReach = reach,
                        ),
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    stagedAttachmentRelay = staged,
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
                    onDismissRelayNotice = onDismissRelayNotice,
                )
            }
        }
    }

    @Test
    fun aCoveredThreadShowsNoNotice() {
        render(reach = RelayReach.Covered)
        compose.onNodeWithTag("chat_relay_notice").assertDoesNotExist()
    }

    @Test
    fun aSilentReachShowsNoNotice() {
        // Plane off, or relays unreachable: nothing true to say, so nothing said.
        render(reach = RelayReach.Silent)
        compose.onNodeWithTag("chat_relay_notice").assertDoesNotExist()
    }

    @Test
    fun theRoomSaysItStaysLocal() {
        render(reach = RelayReach.Room)
        compose.onNodeWithTag("chat_relay_notice").assertIsDisplayed()
        compose.onNodeWithText("Nearby is never sent over the Internet").assertIsDisplayed()
    }

    @Test
    fun anUnscopedThreadSaysItIsNotCoveredYet() {
        render(reach = RelayReach.Pending)
        compose.onNodeWithText("Not covered by relays yet").assertIsDisplayed()
    }

    @Test
    fun aPeerWithoutForwardSecrecyIsNamedInTheExplanation() {
        render(reach = RelayReach.NoForwardSecrecy)
        compose.onNodeWithText("This conversation can't use relays").assertIsDisplayed()
        compose.onNodeWithTag("chat_relay_notice_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("chat_relay_notice").performClick()
        compose.onNodeWithText("Relays need forward secrecy").assertIsDisplayed()
        compose.onNodeWithText("Ana's version of Knit", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheNoticeExplainsWhy() {
        render(reach = RelayReach.Room)
        compose.onNodeWithTag("chat_relay_notice").performClick()
        compose.onNodeWithText("Nearby stays local").assertIsDisplayed()
    }

    @Test
    fun theRoomsNoticeCanBeClosed() {
        var dismissed = 0
        render(reach = RelayReach.Room, onDismissRelayNotice = { dismissed++ })
        compose.onNodeWithTag("chat_relay_notice_dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun closingTheNoticeDoesNotAlsoOpenTheExplanation() {
        // The close button sits beside the row's clickable, not inside it: a dismiss that also fired the
        // row would flash the dialog it is closing the notice to get rid of.
        render(reach = RelayReach.Room)
        compose.onNodeWithTag("chat_relay_notice_dismiss").performClick()
        compose.onNodeWithText("Nearby stays local").assertDoesNotExist()
    }

    @Test
    fun anUncoveredThreadsNoticeOffersNoCloseButton() {
        // Pending clears itself once a scope lands, so there is nothing to dismiss — and the flag behind
        // the button is device-wide, so offering it here would hide the room's notice from another screen.
        render(reach = RelayReach.Pending)
        compose.onNodeWithTag("chat_relay_notice").assertIsDisplayed()
        compose.onNodeWithTag("chat_relay_notice_dismiss").assertDoesNotExist()
    }

    @Test
    fun anOversizeAttachmentIsMarkedNearbyOnly() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithTag("chat_relay_marker").assertIsDisplayed()
        compose.onNodeWithText("Nearby only").assertIsDisplayed()
    }

    @Test
    fun aMissingPhotoSaysTheInternetCanAlsoBringIt() {
        render(rows = listOf(row(mine = false, attachmentReady = false, attachmentWait = AttachmentWait.Relay)))
        compose.onNodeWithText(LOADING_PHOTO).assertIsDisplayed()
        compose.onNodeWithText("Can also arrive over the Internet through your relays").assertIsDisplayed()
    }

    @Test
    fun aMissingPhotoSaysWhenTheConnectedRelaysCarryMessagesOnly() {
        // Work item 50: the photo relay is down and the frames-only one is up. The header says the plane is
        // live, so without this line the reader waits on an Internet that is not bringing the picture.
        render(rows = listOf(row(mine = false, attachmentReady = false, attachmentWait = AttachmentWait.RelayFramesOnly)))
        compose.onNodeWithText(LOADING_PHOTO).assertIsDisplayed()
        compose.onNodeWithText("Your connected relays carry messages only").assertIsDisplayed()
        compose.onNodeWithText("Can also arrive over the Internet through your relays").assertDoesNotExist()
    }

    @Test
    fun aMissingPhotoOffThePlaneKeepsItsOneLine() {
        render(rows = listOf(row(mine = false, attachmentReady = false, attachmentWait = AttachmentWait.Nearby)))
        compose.onNodeWithText(LOADING_PHOTO).assertIsDisplayed()
        compose.onNodeWithTag("chat_attachment_wait").assertDoesNotExist()
    }

    @Test
    fun aPhotoThatIsHereDrawsNoWaitLine() {
        // The line is about bytes that are missing; a stale wait value on a loaded image must not show.
        render(rows = listOf(row(mine = false, attachmentReady = true, attachmentWait = AttachmentWait.RelayFramesOnly)))
        compose.onNodeWithText(LOADING_PHOTO).assertDoesNotExist()
        compose.onNodeWithTag("chat_attachment_wait").assertDoesNotExist()
    }

    @Test
    fun aRelayableAttachmentIsNotMarked() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Relayable)))
        compose.onNodeWithTag("chat_relay_marker").assertDoesNotExist()
    }

    @Test
    fun aSilentAttachmentIsNotMarked() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Silent)))
        compose.onNodeWithTag("chat_relay_marker").assertDoesNotExist()
    }

    @Test
    fun theMarkerExplainsTheSizeCauseAndNamesTheFallback() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithTag("chat_relay_marker").performClick()
        compose.onNodeWithText("Too large for your relays").assertIsDisplayed()
        // The point of the explanation is that the attachment still arrives, so the fallback must be in it.
        // Worded "attachment", not "photo": the reach rule is a size comparison and is blind to format, so
        // the same marker fronts an oversize voice note.
        compose
            .onNodeWithText(
                "This attachment is bigger than any of your relays will hold, so it is not being uploaded. " +
                    "It still arrives when you and Ana are in Wi-Fi or Bluetooth range of each other.",
            ).assertIsDisplayed()
    }

    @Test
    fun theMarkerDistinguishesAFramesOnlyRelayFromAnOversizePhoto() {
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.Unsupported)))
        compose.onNodeWithTag("chat_relay_marker").performClick()
        compose.onNodeWithText("Your relays carry messages only").assertIsDisplayed()
    }

    @Test
    fun theDeliveryTickIsUntouchedByAnUnrelayableAttachment() {
        // Reach and delivery are separate facts: a nearby-only photo is still "Sent".
        render(rows = listOf(row(attachmentRelay = AttachmentRelay.TooLarge)))
        compose.onNodeWithContentDescription("Sent").assertIsDisplayed()
    }

    @Test
    fun anInternetDeliveredMessageShowsTheGlobeBesideTheTick() {
        render(rows = listOf(row(received = true, deliveredVia = DeliveryPlane.Internet)))
        compose.onNodeWithTag("chat_tick_relay", useUnmergedTree = true).assertIsDisplayed()
        // One announcement, not two: the globe is decorative and the tick carries the whole fact.
        compose.onNodeWithContentDescription("Delivered over the Internet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delivered").assertDoesNotExist()
    }

    @Test
    fun aNearbyDeliveredMessageKeepsThePlainTick() {
        render(rows = listOf(row(received = true, deliveredVia = DeliveryPlane.Nearby)))
        compose.onNodeWithTag("chat_tick_relay", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Delivered").assertIsDisplayed()
    }

    @Test
    fun anUndeliveredMessageNeverShowsTheGlobe() {
        // The plane is only known once a receipt lands, so a single ✓ can never carry it — a stale flag
        // on an un-acked row must not paint one either.
        render(rows = listOf(row(received = false, deliveredVia = DeliveryPlane.Internet)))
        compose.onNodeWithTag("chat_tick_relay", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Sent").assertIsDisplayed()
    }

    @Test
    fun anIncomingMessageThatCameOffARelayShowsTheGlobe() {
        // No tick — delivery isn't ours to report — but the same globe, and here it must announce
        // itself: there is no tick beside it to carry the fact.
        render(rows = listOf(row(mine = false, deliveredVia = DeliveryPlane.Internet)))
        compose.onNodeWithTag("chat_arrived_relay", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Arrived over the Internet").assertIsDisplayed()
    }

    @Test
    fun anIncomingMessageThatCameOverARadioShowsNothing() {
        render(rows = listOf(row(mine = false, deliveredVia = DeliveryPlane.Nearby)))
        compose.onNodeWithTag("chat_arrived_relay", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLoraDeliveredMessageShowsTheRadioMarkBesideTheTick() {
        render(rows = listOf(row(received = true, deliveredVia = DeliveryPlane.LoRa)))
        compose.onNodeWithTag("chat_tick_lora", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_tick_relay", useUnmergedTree = true).assertDoesNotExist()
        // Same one-announcement rule as the globe: the mark is decorative and the tick carries the fact.
        compose.onNodeWithContentDescription("Delivered over LoRa").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delivered").assertDoesNotExist()
    }

    @Test
    fun anUndeliveredMessageNeverShowsTheRadioMark() {
        render(rows = listOf(row(received = false, deliveredVia = DeliveryPlane.LoRa)))
        compose.onNodeWithTag("chat_tick_lora", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Sent").assertIsDisplayed()
    }

    @Test
    fun anIncomingMessageThatCameOverLoraShowsTheRadioMark() {
        render(rows = listOf(row(mine = false, deliveredVia = DeliveryPlane.LoRa)))
        compose.onNodeWithTag("chat_arrived_lora", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_arrived_relay", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Arrived over LoRa").assertIsDisplayed()
    }

    @Test
    fun anIncomingMessageShowsNoTickAtAll() {
        // Ticks are for our own sends: a received message says how it arrived, never that it was
        // "delivered" — that claim belongs to the sender's phone.
        render(rows = listOf(row(mine = false, received = true, deliveredVia = DeliveryPlane.Internet)))
        compose.onNodeWithTag("chat_tick_relay", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Delivered over the Internet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delivered").assertDoesNotExist()
    }
}

private const val LOADING_PHOTO = "Photo appears once a device that has it is reachable"
