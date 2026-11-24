package app.getknit.knit.ui.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
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
import org.robolectric.annotation.GraphicsMode

/**
 * #34: the long-press menu's "Save voice message", offered on a voice note whose bytes are on the phone and on nothing
 * else. The note carries a line of text so the long-press lands on the bubble the way the other menu tests' does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceNoteSaveMenuTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private data class Saved(
        val hash: String,
        val key: String?,
        val mime: String?,
        val sentAt: Long,
    )

    private val saved = mutableListOf<Saved>()

    private fun voiceRow(ready: Boolean) =
        ChatRow(
            id = "m1",
            body = "listen to this",
            mine = false,
            senderName = "Bob",
            senderNodeId = "bob",
            avatarHash = null,
            sentAt = 1_700_000_000_001L,
            received = false,
            attachmentHash = "h1",
            attachmentMime = "audio/aac",
            attachmentKey = "k1",
            attachmentReady = ready,
            voiceDurationMs = if (ready) 7_400 else null,
        )

    private fun bubble(row: ChatRow) {
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
                                onSaveVoice = { hash, key, mime, sentAt -> saved += Saved(hash, key, mime, sentAt) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithText("listen to this").performTouchInput { longClick() }
        compose.onNodeWithTag("chat_message_menu").assertExists()
    }

    @Test
    fun aHeldVoiceNoteOffersSaveAndSavesTheRowsNote() {
        bubble(voiceRow(ready = true))
        openMenu()

        compose.onNodeWithText("Save voice message").performClick()

        assertEquals(listOf(Saved("h1", "k1", "audio/aac", 1_700_000_000_001L)), saved)
        compose.onNodeWithTag("chat_message_menu").assertDoesNotExist()
    }

    @Test
    fun aVoiceNoteStillOnItsWayOffersNoSave() {
        bubble(voiceRow(ready = false))
        openMenu()

        // The menu is drawn with its actions: Save's absence is read from a menu that is there.
        compose.onNodeWithText("Reply").assertExists()
        compose.onNodeWithText("Save voice message").assertDoesNotExist()
    }

    /** Held bytes alone are not enough: a file that is here is not a voice note, and offers no Save of one. */
    @Test
    fun aHeldAttachmentThatIsNotAudioOffersNoSave() {
        bubble(voiceRow(ready = true).copy(attachmentMime = "application/pdf", attachmentName = "report.pdf", voiceDurationMs = null))
        openMenu()

        compose.onNodeWithText("Reply").assertExists()
        compose.onNodeWithText("Save voice message").assertDoesNotExist()
    }
}
