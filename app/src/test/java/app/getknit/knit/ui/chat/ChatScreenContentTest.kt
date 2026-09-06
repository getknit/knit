package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.ReplyRef
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
class ChatScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var sends = 0
    private var attaches = 0
    private var cameras = 0
    private var cancelledReply = 0
    private var files = 0

    private fun content(
        input: String,
        replyingTo: ReplyRef? = null,
        state: ChatUiState = ChatUiState(isRoom = true, myNodeId = "me"),
        pendingAttachment: AttachmentStore.Ingested? = null,
        linkPreviewLoading: Boolean = false,
        onDraftChanged: (String) -> Unit = {},
        onLoadOlder: () -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit =
        {
            KnitTheme {
                ChatScreenContent(
                    conversationId = Conversations.NEARBY,
                    state = state,
                    inputState = TextFieldState(input),
                    pendingAttachment = pendingAttachment,
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
                    onLoadOlder = onLoadOlder,
                )
            }
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
        val drafts = ArrayList<String>()
        compose.setContent(content(input = "https://example.com/a", linkPreviewLoading = true, onDraftChanged = { drafts += it }))
        compose.onNodeWithTag("chat_link_preview_loading").assertIsDisplayed()
        assertEquals(listOf("https://example.com/a"), drafts)
    }

    @Test
    fun theLoadingLineYieldsToAStagedAttachment() {
        val staged = AttachmentStore.Ingested(hash = "h-card", mime = LinkPreviewBlob.MIME, link = card)
        compose.setContent(content(input = "x", linkPreviewLoading = true, pendingAttachment = staged))
        compose.onNodeWithTag("chat_link_preview_loading").assertDoesNotExist()
        compose.onNodeWithTag("chat_link_staged").assertIsDisplayed()
    }
}
