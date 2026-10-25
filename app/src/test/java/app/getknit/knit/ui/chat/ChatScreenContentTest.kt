package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.draft.Draft
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless `ChatScreenContent` — the send/attach button-mode switch, the long-press that
 * opens the camera, and the reply banner.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass") // one screen's whole content contract over one shared content() harness, as ChatViewModelTest
class ChatScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private var sends = 0
    private var attaches = 0
    private var cameras = 0
    private var cancelledReply = 0
    private var files = 0
    private var clearedLocations = 0
    private var locationClicks = 0
    private var accepts = 0
    private var consentsAccepted = 0
    private val downloads = mutableListOf<String>()

    private fun content(
        input: String,
        // Held by a test that needs to read back what the field kept of its keystrokes.
        inputState: TextFieldState = TextFieldState(input),
        replyingTo: ReplyRef? = null,
        state: ChatUiState = ChatUiState(isRoom = true, myNodeId = "me"),
        pendingAttachment: AttachmentStore.Ingested? = null,
        linkPreviewLoading: Boolean = false,
        stagedLocation: ChatViewModel.StagedLocation? = null,
        onDraftChanged: (Draft) -> Unit = {},
        showTransferConsent: TransferConsent? = null,
        onLoadOlder: () -> Unit = {},
        // A search hit to open the thread on, and the reveal it asks for when the window has not reached it.
        jumpToMessageId: String? = null,
        onRevealMessage: (String) -> Unit = {},
        // Read inside the composition, so a test can swap the state under a running screen (the rows landing).
        liveState: (() -> ChatUiState)? = null,
    ): @androidx.compose.runtime.Composable () -> Unit =
        {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state = liveState?.invoke() ?: state,
                    inputState = inputState,
                    pendingAttachment = pendingAttachment,
                    stagedLocation = stagedLocation,
                    onClearLocation = { clearedLocations++ },
                    onLocationClick = { locationClicks++ },
                    linkPreviewLoading = linkPreviewLoading,
                    onDraftChanged = onDraftChanged,
                    replyingTo = replyingTo,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = { sends++ },
                    onAttachClick = { attaches++ },
                    onCameraClick = { cameras++ },
                    onFileClick = { files++ },
                    onAcceptTransfer = { accepts++ },
                    showTransferConsent = showTransferConsent,
                    onAcceptTransferConsent = { consentsAccepted++ },
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
                    onSaveAttachment = { _, _, _ -> },
                    onDownloadAttachment = { downloads += it },
                    onLoadOlder = onLoadOlder,
                    jumpToMessageId = jumpToMessageId,
                    onRevealMessage = onRevealMessage,
                )
            }
        }

    private fun transferRow(view: TransferView) =
        ChatRow(
            id = "xfer:${view.id}",
            body = "",
            mine = view.outgoing,
            senderName = "Bob",
            senderNodeId = "bob",
            kind = MessageEntity.KIND_FILE_TRANSFER,
            avatarHash = null,
            sentAt = 1_700_000_000_001L,
            received = true,
            transfer = view,
        )

    private fun transferView(
        phase: TransferPhase,
        id: String = "t1",
        name: String = "clip.mp4",
        mime: String? = "video/mp4",
        savedUri: String? = null,
    ) = TransferView(
        id,
        outgoing = false,
        name = name,
        size = 2_000L,
        mime = mime,
        phase = phase,
        bytes = 0L,
        savedUri = savedUri,
        reason = null,
        interrupted = false,
    )

    @Test
    fun anIncomingTransferOfferDrawsItsAnswersAndAcceptAnswers() {
        compose.setContent(
            content(input = "", state = ChatUiState(myNodeId = "me", rows = listOf(transferRow(transferView(TransferPhase.Offered))))),
        )

        compose.onNodeWithTag("transfer_decline").assertIsDisplayed()
        compose.onNodeWithTag("transfer_accept").performClick()
        assertEquals(1, accepts)
    }

    @Test
    fun aReceivedFileOffersOpenButAnAppPackageDoesNot() {
        val rows =
            listOf(
                transferRow(transferView(TransferPhase.Done, savedUri = "content://media/1")),
                transferRow(
                    transferView(
                        TransferPhase.Done,
                        id = "t2",
                        name = "Knit.apk",
                        mime = "application/vnd.android.package-archive",
                        savedUri = "content://media/2",
                    ),
                ),
            )
        compose.setContent(content(input = "", state = ChatUiState(myNodeId = "me", rows = rows)))

        compose.onAllNodesWithTag("transfer_card").assertCountEquals(2)
        compose.onAllNodesWithTag("transfer_open").assertCountEquals(1)
        compose.onAllNodesWithTag("transfer_accept").assertCountEquals(0)
    }

    /**
     * The disclosure reaches the screen, takes its receiving-end wording, and its button answers.
     *
     * Matched by text rather than by tag: a `ModalBottomSheet` composes in its own window, which is also why
     * `uiautomator` cannot see the tag either. The scroll to the button is as much the point as the click —
     * Robolectric's default screen is 320×470, shorter than this sheet's copy, which is exactly the phone on
     * which an unscrollable sheet would strand its own Continue button.
     */
    @Test
    fun theDirectTransferDisclosureTakesItsReceivingWordingAndItsButtonAnswers() {
        compose.setContent(
            content(input = "", showTransferConsent = TransferConsent(incoming = true, transferId = "t1")),
        )

        compose.onNodeWithText("Receive this file?").assertIsDisplayed()
        compose.onNodeWithText("How it works").assertIsDisplayed()
        // The sender's half of the sheet stays out of a receiver's copy.
        compose.onNodeWithText("Send a file directly?").assertDoesNotExist()
        compose
            .onNodeWithText("Knit does not scan files for harmful content", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithText("Continue").performScrollTo().performClick()
        assertEquals(1, consentsAccepted)
    }

    /** A thread of [count] rows, newest last, in the oldest-first shape the ViewModel emits. */
    private fun rows(count: Int) =
        (1..count).map { i ->
            ChatRow(
                id = "m$i",
                body = "message $i",
                mine = false,
                senderName = "Bob",
                senderNodeId = "bob",
                avatarHash = null,
                sentAt = 1_700_000_000_000L + i,
                received = false,
            )
        }

    @Test
    fun aThreadWithMoreHistorySaysSoAboveItsOldestMessage() {
        compose.setContent(
            content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(3), hasOlder = true)),
        )

        compose.onNodeWithText("Loading earlier messages…").assertExists()
    }

    @Test
    fun aFullyLoadedThreadSaysNothingAboveItsOldestMessage() {
        compose.setContent(
            content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(3), hasOlder = false)),
        )

        compose.onNodeWithText("Loading earlier messages…").assertDoesNotExist()
    }

    @Test
    fun scrollingBackToTheOldestLoadedMessageAsksForAnotherPage() {
        var pages = 0
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(60), hasOlder = true),
                onLoadOlder = { pages++ },
            ),
        )
        // The thread opens resting on the newest message, so nothing has been asked for yet.
        assertEquals(0, pages)

        // The list is reversed, so its last index is the oldest row — the visual top.
        compose.onNodeWithTag("chat_thread").performScrollToIndex(59)
        compose.waitForIdle()

        assertTrue("reaching the oldest loaded message reads more history", pages > 0)
    }

    /** A search hit inside the first window: revealed (a no-op there) and scrolled into view. */
    @Test
    fun aSearchHitInsideTheWindowIsScrolledIntoView() {
        val revealed = mutableListOf<String>()
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(60), hasOlder = true),
                jumpToMessageId = "m5",
                onRevealMessage = { revealed += it },
            ),
        )
        compose.waitForIdle()

        assertEquals(listOf("m5"), revealed)
        compose.onNodeWithText("message 5").assertIsDisplayed()
    }

    /** A hit older than the window: the reveal widens it, and the scroll waits for the rows to arrive. */
    @Test
    fun aSearchHitBeyondTheWindowWaitsForTheRowsToReachIt() {
        val revealed = mutableListOf<String>()
        var live by mutableStateOf(ChatUiState(isRoom = true, myNodeId = "me", rows = rows(3), hasOlder = true))
        compose.setContent(
            content(
                input = "",
                jumpToMessageId = "m40",
                onRevealMessage = { revealed += it },
                liveState = { live },
            ),
        )
        compose.waitForIdle()
        assertEquals(listOf("m40"), revealed)
        compose.onNodeWithText("message 40").assertDoesNotExist()

        live = live.copy(rows = rows(60))
        compose.waitForIdle()

        compose.onNodeWithText("message 40").assertIsDisplayed()
        assertEquals("revealed once, not again when the rows land", listOf("m40"), revealed)
    }

    /** The seed state has no rows and no history; a hit asked for then must outlive it. */
    @Test
    fun aSearchHitRequestedWhileLoadingSurvivesUntilTheRowsLand() {
        val revealed = mutableListOf<String>()
        var live by mutableStateOf(ChatUiState(isRoom = true, myNodeId = "me", isLoading = true))
        compose.setContent(
            content(input = "", jumpToMessageId = "m5", onRevealMessage = { revealed += it }, liveState = { live }),
        )
        compose.waitForIdle()

        live = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(60), hasOlder = false)
        compose.waitForIdle()

        assertEquals(listOf("m5"), revealed)
        compose.onNodeWithText("message 5").assertIsDisplayed()
    }

    /** A hit the fully loaded thread does not hold — retention trimmed it — opens the thread at its newest. */
    @Test
    fun aSearchHitAbsentFromAFullyLoadedThreadIsDropped() {
        val revealed = mutableListOf<String>()
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(3), hasOlder = false),
                jumpToMessageId = "m99",
                onRevealMessage = { revealed += it },
            ),
        )
        compose.waitForIdle()

        assertEquals(listOf("m99"), revealed)
        compose.onNodeWithText("message 3").assertIsDisplayed()
    }

    @Test
    fun sendButtonSendsWhenTheInputIsNotEmpty() {
        compose.setContent(content(input = "hello"))

        compose.onNodeWithTag("chat_send").performClick()

        assertEquals(1, sends)
        assertEquals(0, attaches)
    }

    /**
     * Enter breaks the line and never sends: the soft keyboard is asked for a plain Enter rather than a Send
     * action, and a hardware Enter lands in the draft. Ctrl+Enter is the keyboard's send, and it must not
     * leave a newline behind in the draft on its way out.
     */
    @Test
    fun enterStartsANewLineAndCtrlEnterSends() {
        val state = TextFieldState("hello")
        compose.setContent(content(input = "hello", inputState = state))

        compose.onNodeWithTag("chat_input").assert(hasImeAction(ImeAction.Default))
        compose.onNodeWithTag("chat_input").requestFocus()
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }

        assertEquals("hello\n", state.text.toString())
        assertEquals(0, sends)

        compose.onNodeWithTag("chat_input").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }

        assertEquals(1, sends)
        assertEquals("hello\n", state.text.toString())
    }

    @Test
    fun sendButtonBecomesAttachWhenTheInputIsEmpty() {
        compose.setContent(content(input = ""))

        compose.onNodeWithTag("chat_send").performClick()

        assertEquals(0, sends)
        assertEquals(1, attaches)
    }

    /** Long-press is the camera's only entry point, so it has to reach past the button's own click. */
    @Test
    fun longPressingTheAttachButtonOpensTheCamera() {
        compose.setContent(content(input = ""))

        compose.onNodeWithTag("chat_send").performTouchInput { longClick() }

        assertEquals(1, cameras)
        assertEquals(0, attaches)
        assertEquals(0, sends)
    }

    /**
     * In Send mode the same button must not open a camera — that would interrupt the send it looks like
     * it triggers. With no long-press handler the gesture still resolves to an ordinary click on
     * release, exactly as it did when this was a `FilledIconButton`.
     */
    @Test
    fun longPressingInSendModeSendsAndNeverOpensTheCamera() {
        compose.setContent(content(input = "hello"))

        compose.onNodeWithTag("chat_send").performTouchInput { longClick() }

        assertEquals(0, cameras)
        assertEquals(1, sends)
    }

    /**
     * The file picker is its own control in the field beside the mic, not a menu behind the trailing
     * button's long press. The first cut hid it behind that gesture and it was unfindable; worse, it was
     * also gated on the peer's advertised capability, so it was frequently not there at all.
     */
    @Test
    fun theAttachFileButtonIsOfferedOutsideTheRoomAndOpensThePicker() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = false, myNodeId = "me", canSendFile = true)))

        compose.onNodeWithTag("chat_attach_file").performClick()

        assertEquals(1, files)
        assertEquals(0, cameras)
        assertEquals(0, attaches)
    }

    /**
     * The floor the ATF suite (and Play's pre-launch report) enforces: a 48x48dp touch target. The two
     * inline buttons sit flush against each other with no spacer, so this size *is* the spacing — the gap
     * a reader sees between the paperclip and the mic is the 12dp inset a 24dp glyph needs inside a 48dp
     * target, not padding that could be trimmed. Shrink the box and the a11y suite fails.
     */
    @Test
    fun theAttachFileButtonKeepsAFullTouchTarget() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = false, myNodeId = "me", canSendFile = true)))

        val bounds = compose.onNodeWithTag("chat_attach_file").getUnclippedBoundsInRoot()
        assertTrue("width ${bounds.right - bounds.left} < 48dp", (bounds.right - bounds.left) >= 48.dp)
        assertTrue("height ${bounds.bottom - bounds.top} < 48dp", (bounds.bottom - bounds.top) >= 48.dp)
    }

    @Test
    fun theRoomOffersNoAttachFileButton() {
        compose.setContent(content(input = ""))

        compose.onNodeWithTag("chat_attach_file").assertDoesNotExist()
    }

    /** The long press keeps the camera it has always had — the file picker did not take that gesture. */
    @Test
    fun theAttachFileButtonDoesNotDisplaceTheCameraLongPress() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = false, myNodeId = "me", canSendFile = true)))

        compose.onNodeWithTag("chat_send").performTouchInput { longClick() }

        assertEquals(1, cameras)
        assertEquals(0, files)
    }

    /**
     * A staged file has nothing to thumbnail, and handing its bytes to the image loader drew a blank
     * square with only the ✕ on it — it read as broken rather than staged.
     */
    @Test
    fun aStagedFileShowsItsNameAndSizeRatherThanAnEmptyThumbnail() {
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = false, myNodeId = "me", canSendFile = true),
                pendingAttachment =
                    AttachmentStore.Ingested(
                        hash = "h",
                        mime = "application/pdf",
                        name = "quarterly-report.pdf",
                        sizeBytes = 1_400_000,
                    ),
            ),
        )

        compose.onNodeWithText("quarterly-report.pdf", useUnmergedTree = true).assertIsDisplayed()
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

    private val card =
        LinkCard(
            url = "https://example.com/a",
            host = "example.com",
            title = "Mesh networking",
            description = "How phones find each other",
            hasImage = false,
        )

    private fun cardRow(
        linkCard: LinkCard?,
        flagged: Boolean = false,
    ) = ChatRow(
        id = "m-card",
        body = "see https://example.com/a",
        mine = false,
        senderName = "Bob",
        senderNodeId = "bob",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
        attachmentHash = "h-card",
        attachmentMime = LinkPreviewBlob.MIME,
        attachmentReady = true,
        attachmentFlagged = flagged,
        linkCard = linkCard,
    )

    /** ADR 2026-10.js2p: a large attachment only Bluetooth could bring waits for the tap, which reaches the mesh. */
    @Test
    fun aLargeAttachmentAwaitingTheTapShowsItsSizeAndDownloadsOnTap() {
        val row =
            cardRow(linkCard = null).copy(
                attachmentHash = "h-big",
                attachmentMime = "application/pdf",
                attachmentName = "Survey map.pdf",
                attachmentSize = 4_200_000L,
                attachmentReady = false,
                attachmentAwaitsTap = true,
            )
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(row))))

        compose.onNodeWithTag("chat_attachment_tap_to_download").assertIsDisplayed().performClick()
        assertEquals(listOf("h-big"), downloads)
    }

    /** A phone without Wi-Fi Aware is never told the plane fetches the attachment on its own (the Moto G trial). */
    @Test
    fun theTapCardNamesWifiAwareOnlyWhereThePhoneHasIt() {
        var wifiAware by mutableStateOf(false)
        compose.setContent {
            TapToDownloadCard(label = "Photo", declaredSize = 2_000_000, onDownload = {}, onLongClick = {}, wifiAware = wifiAware)
        }
        val card = compose.onNodeWithTag("chat_attachment_tap_to_download")
        card
            .assert(hasContentDescription("Can take minutes over Bluetooth.", substring = true))
            .assert(!hasContentDescription("Wi-Fi Aware", substring = true))
        wifiAware = true
        card.assert(hasContentDescription("Wi-Fi Aware gets it on its own.", substring = true))
    }

    /** A group is the one thread that has to say it per bubble: the header names the group, not the person. */
    @Test
    fun aVerifiedAuthorWearsTheShieldBesideTheirNameInAGroup() {
        compose.setContent(
            content(
                input = "",
                state =
                    ChatUiState(
                        isRoom = false,
                        isGroup = true,
                        myNodeId = "me",
                        title = "Trailhead Crew",
                        rows = listOf(rows(1).single().copy(senderVerified = true)),
                    ),
            ),
        )

        compose.onNodeWithTag("chat_verified_shield", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("You verified Bob", useUnmergedTree = true).assertIsDisplayed()
    }

    /** The cluster branch must leave the tappable root — and its tag, which five device tests wait on — in place. */
    @Test
    fun aGroupHeaderDrawingItsMembersClusterKeepsItsTappableAvatar() {
        val faces = listOf("a", "b", "c").map { GroupFace(nodeId = it, name = it.uppercase(), avatarHash = null) }
        compose.setContent(
            content(
                input = "",
                state =
                    ChatUiState(
                        isRoom = false,
                        isGroup = true,
                        myNodeId = "me",
                        title = "Trailhead Crew",
                        memberCount = 4,
                        groupFaces = faces,
                        rows = rows(1),
                    ),
            ),
        )

        compose.onNodeWithTag("chat_group_avatar").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun anUnverifiedAuthorWearsNoShield() {
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = false, isGroup = true, myNodeId = "me", title = "Trailhead Crew", rows = rows(1)),
            ),
        )

        compose.onAllNodesWithTag("chat_verified_shield", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aDecodedCardDrawsAsOneLabelledNodeWithItsTitleAndHost() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(cardRow(card)))))
        compose.onNodeWithTag("chat_link_card").assertIsDisplayed()
        compose.onNodeWithTag("chat_link_card").assertContentDescriptionEquals("Link preview: Mesh networking, example.com")
        compose.onNodeWithTag("chat_link_card_hidden").assertDoesNotExist()
    }

    @Test
    fun aCardThatHasNotDecodedDrawsNeitherACardNorAPhotoSpinner() {
        compose.setContent(
            content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(cardRow(linkCard = null)))),
        )
        compose.onNodeWithTag("chat_link_card").assertDoesNotExist()
        compose.onNodeWithText("Photo appears once a device that has it is reachable").assertDoesNotExist()
        compose.onNodeWithText("see https://example.com/a", substring = true).assertIsDisplayed()
    }

    private fun quoteRow(
        quote: QuoteView,
        body: String = "on my way",
        flagged: Boolean = false,
    ) = ChatRow(
        id = "r1",
        body = body,
        mine = false,
        senderName = "Bob",
        senderNodeId = "bob",
        avatarHash = null,
        sentAt = 1_700_000_000_000L,
        received = false,
        moderationFlagged = flagged,
        quote = quote,
    )

    private fun showQuote(row: ChatRow) =
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(row))))

    @Test
    fun anUnverifiedQuoteSaysSoUnderItsWords() {
        showQuote(quoteRow(QuoteView("o1", "Ada", "Meet at the trailhead", hasAttachment = false, state = QuoteState.Unverified)))

        compose.onNodeWithText("Meet at the trailhead", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Unverified — original not on this phone", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aVerifiedQuoteCarriesNoCue() {
        showQuote(quoteRow(QuoteView("o1", "Ada", "Meet at the trailhead", hasAttachment = false, state = QuoteState.Verified)))

        compose.onNodeWithText("Ada", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Unverified — original not on this phone", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anUnavailableQuoteShowsNothingOfTheClaim() {
        showQuote(quoteRow(QuoteView("o1", "", "", hasAttachment = false, state = QuoteState.Unavailable)))

        compose.onNodeWithText("Quoted message unavailable", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aHiddenReplyWithNoTextCollapsesItsQuoteUntilTapped() {
        // Its body is blank, so the quote carries the hint; the tap reveals rather than jumping to the original.
        showQuote(
            quoteRow(
                QuoteView("o1", "Ada", "something vile", hasAttachment = false, state = QuoteState.Verified),
                body = "",
                flagged = true,
            ),
        )

        compose.onNodeWithText("Quoted message hidden — tap to reveal", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("something vile", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat_quote", useUnmergedTree = true).performClick()
        compose.onNodeWithText("something vile", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aHiddenReplyWithTextLeavesTheHintToItsBody() {
        showQuote(
            quoteRow(
                QuoteView("o1", "Ada", "something vile", hasAttachment = false, state = QuoteState.Verified),
                flagged = true,
            ),
        )

        compose.onNodeWithText("Quoted message hidden", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Message hidden — tap to reveal", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("on my way", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aFlaggedCardHidesWholeUntilTapped() {
        compose.setContent(
            content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(cardRow(card, flagged = true)))),
        )
        compose.onNodeWithTag("chat_link_card_hidden").assertIsDisplayed()
        compose.onNodeWithTag("chat_link_card").assertDoesNotExist()
        compose.onNodeWithTag("chat_link_card_hidden").performClick()
        compose.onNodeWithTag("chat_link_card").assertIsDisplayed()
    }

    @Test
    fun aStagedCardShowsItsTitleAndHostBesideTheClearBadge() {
        val staged = AttachmentStore.Ingested(hash = "h-card", mime = LinkPreviewBlob.MIME, link = card)
        compose.setContent(content(input = "see https://example.com/a", pendingAttachment = staged))
        compose.onNodeWithTag("chat_link_staged").assertIsDisplayed()
        compose
            .onNodeWithTag("chat_link_staged")
            .assertContentDescriptionEquals("Link preview: Mesh networking, example.com. Tap the cross to send without it")
    }

    @Test
    fun theLoadingLineShowsWhileNothingIsStagedAndTheDraftReachesTheCallback() {
        val drafts = ArrayList<Draft>()
        compose.setContent(content(input = "https://example.com/a", linkPreviewLoading = true, onDraftChanged = { drafts += it }))
        compose.onNodeWithTag("chat_link_preview_loading").assertIsDisplayed()
        assertEquals(listOf(Draft("https://example.com/a")), drafts)
    }

    @Test
    fun theLoadingLineYieldsToAStagedAttachment() {
        val staged = AttachmentStore.Ingested(hash = "h-card", mime = LinkPreviewBlob.MIME, link = card)
        compose.setContent(content(input = "x", linkPreviewLoading = true, pendingAttachment = staged))
        compose.onNodeWithTag("chat_link_preview_loading").assertDoesNotExist()
        compose.onNodeWithTag("chat_link_staged").assertIsDisplayed()
    }

    // ---- Location sharing: the card, the staged tile and the pin ----

    private val point = GeoPoint(37.421998, -122.084, 12)

    private fun locationRow(body: String = "See you at the gate\ngeo:37.421998,-122.084000;u=12") =
        ChatRow(
            id = "m-loc",
            body = body,
            mine = false,
            senderName = "Bob",
            senderNodeId = "bob",
            avatarHash = null,
            sentAt = 1_700_000_000_000L,
            received = false,
            location = point,
        )

    @Test
    fun aBodyWithAPositionDrawsTheCardInPlaceOfItsLine() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(locationRow()))))
        compose.onNodeWithTag("chat_location_card").assertIsDisplayed()
        compose.onNodeWithTag("chat_location_card").assertContentDescriptionEquals("Location: 37.421998, -122.084000, Accurate to 12 m")
        compose.onNodeWithTag("chat_location_copy").assertIsDisplayed()
        compose.onNodeWithText("See you at the gate").assertIsDisplayed()
        compose.onNodeWithText("geo:", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPositionAloneDrawsOnlyTheCard() {
        compose.setContent(
            content(
                input = "",
                state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(locationRow(body = "geo:37.421998,-122.084000;u=12"))),
            ),
        )
        compose.onNodeWithTag("chat_location_card").assertIsDisplayed()
        compose.onNodeWithText("geo:", substring = true).assertDoesNotExist()
    }

    @Test
    fun aFormattedBodyIsDrawnWithItsStylesAndAPositionLeavesThemOnTheWords() {
        // "gate" bold over a body that ends on a position: the card takes the token's line, the bold stays on its word.
        val row = locationRow().copy(styles = listOf(StyleSpan(15, 19, BodyStyle.BOLD)))
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = listOf(row))))

        val text =
            compose
                .onNodeWithText("See you at the gate", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("gate", text.text.substring(bold.start, bold.end))
    }

    @Test
    fun aPlainBodyDrawsNoCard() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me", rows = rows(1))))
        compose.onNodeWithTag("chat_location_card").assertDoesNotExist()
    }

    @Test
    fun aStagedPositionShowsItsTileTurnsTheButtonIntoSendAndClearsFromItsBadge() {
        val staged =
            ChatViewModel.StagedLocation(
                fix = LocationFix(37.421998, -122.084, 12f, timeMs = 1_700_000_000_000L, elapsedRealtimeMs = 0L),
                status = ChatViewModel.StagedLocation.Status.Ready,
                precision = LocationPrecision.Fine,
            )
        compose.setContent(content(input = "", state = ChatUiState(isRoom = false, myNodeId = "me"), stagedLocation = staged))
        compose.onNodeWithTag("chat_location_staged").assertIsDisplayed()
        // The tile is one node for TalkBack, so its texts are read off its description.
        compose
            .onNodeWithTag(
                "chat_location_staged",
            ).assert(hasContentDescription("Your location, 37.421998, -122.084000, Accurate to 12 m", substring = true))
        compose.onNodeWithTag("chat_location_staged").assert(hasContentDescription("Sent only to this chat", substring = true))
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        // The overflow drops its offer while a tile is staged: the tile's Refresh is the way to ask again.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("chat_send_location").assertDoesNotExist()
        compose.onNodeWithTag("chat_location_clear").performClick()
        assertEquals(1, clearedLocations)
    }

    @Test
    fun aFailedTileSaysSoAndOffersARetryAndTheRoomTileNamesEveryoneNearby() {
        val failed =
            ChatViewModel.StagedLocation(
                fix = null,
                status = ChatViewModel.StagedLocation.Status.Failed,
                precision = LocationPrecision.Fine,
            )
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me"), stagedLocation = failed))
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithTag("chat_location_staged").assert(hasContentDescription("Couldn't get a fix", substring = true))
        compose
            .onNodeWithTag(
                "chat_location_staged",
            ).assert(hasContentDescription("Everyone nearby will see your exact location", substring = true))
    }

    @Test
    fun sendLocationLivesInTheRoomsOverflowAndNowhereInTheField() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = true, myNodeId = "me")))
        compose.onNodeWithTag("chat_attach_location").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("chat_send_location").assertIsDisplayed()
        // The room's menu is that one item: nobody to block, nothing to configure.
        compose.onNodeWithTag("chat_send_large_file").assertDoesNotExist()
        compose.onNodeWithText("Block").assertDoesNotExist()
        compose.onNodeWithTag("chat_send_location").performClick()
        assertEquals(1, locationClicks)
    }

    @Test
    fun sendLocationLeadsADmsOverflowAndStaysOfferedWithADraft() {
        compose.setContent(content(input = "hello", state = ChatUiState(isRoom = false, myNodeId = "me")))
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("chat_send_location").assertIsDisplayed()
        compose.onNodeWithTag("chat_send_large_file").assertIsDisplayed()
    }

    @Test
    fun theBridgedRoomOffersNoMenuAndSoNoLocation() {
        compose.setContent(content(input = "", state = ChatUiState(isRoom = false, isBridged = true, myNodeId = "me")))
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        compose.onNodeWithTag("chat_send_location").assertDoesNotExist()
    }
}
