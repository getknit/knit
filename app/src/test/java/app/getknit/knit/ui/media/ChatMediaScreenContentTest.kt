package app.getknit.knit.ui.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.ui.chat.PendingSave
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stateless "Media, links and files" screen (ADR 2026-11.dgvf): its tabs and empty states, what a tap on each
 * kind of tile and row does, and the viewer's way back to the message.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS")
class ChatMediaScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val shownInChat = mutableListOf<String>()
    private val openedFiles = mutableListOf<PendingSave>()
    private val openedLinks = mutableListOf<String>()

    private val selectedTabs = mutableListOf<MediaTab>()

    /** Renders [initial]; a tab tap switches to that tab's state as the ViewModel would, empty and loaded. */
    private fun render(initial: ChatMediaUiState) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            KnitTheme {
                ChatMediaScreenContent(
                    state = state,
                    onSelectTab = { tab ->
                        selectedTabs += tab
                        state = initial.copy(tab = tab)
                    },
                    onShowInChat = { shownInChat += it },
                    onOpenFile = { openedFiles += it },
                    onOpenLink = { openedLinks += it },
                )
            }
        }
    }

    @Test
    fun eachTabSaysWhatItWouldListWhileTheThreadHasNone() {
        render(ChatMediaUiState(loaded = true))
        compose.onNodeWithTag("media_empty_media").assertIsDisplayed()
        compose.onNodeWithTag("media_tab_files").performClick()
        compose.onNodeWithTag("media_empty_files").assertIsDisplayed()
        compose.onNodeWithTag("media_tab_links").performClick()
        compose.onNodeWithTag("media_empty_links").assertIsDisplayed()
        // The open tab again asks for nothing: its read is already running.
        compose.onNodeWithTag("media_tab_links").performClick()
        assertEquals(listOf(MediaTab.Files, MediaTab.Links), selectedTabs)
    }

    @Test
    fun nothingIsClaimedEmptyBeforeTheFirstReadAnswers() {
        render(ChatMediaUiState(loaded = false))
        compose.onNodeWithTag("media_empty_media").assertDoesNotExist()
    }

    @Test
    fun aHeldPhotoOpensTheViewerWhoseShowInChatHandsTheMessageBack() {
        render(ChatMediaUiState(loaded = true, media = listOf(tile("m1"))))
        compose.onNodeWithTag("media_tile_m1").performClick()
        compose.onNodeWithContentDescription("Photo").assertIsDisplayed()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("viewer_show_in_chat").performClick()

        assertEquals(listOf("m1"), shownInChat)
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
    }

    @Test
    fun aTileNotOnThePhoneShowsItsMessageInTheChat() {
        render(ChatMediaUiState(loaded = true, media = listOf(tile("away", held = false))))
        compose.onNodeWithTag("media_tile_away").performClick()

        assertEquals(listOf("away"), shownInChat)
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
    }

    @Test
    fun aCoveredTileShowsOnTheFirstTapAndOpensOnTheSecond() {
        render(ChatMediaUiState(loaded = true, media = listOf(tile("x", covered = true))))
        val hidden = "Ana: hidden, may contain sensitive content"
        assertEquals(1, compose.onAllNodesWithContentDescription(hidden, substring = true).fetchSemanticsNodes().size)

        compose.onNodeWithTag("media_tile_x").performClick()
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
        assertEquals(
            "revealed, not opened",
            0,
            compose.onAllNodesWithContentDescription(hidden, substring = true).fetchSemanticsNodes().size,
        )

        compose.onNodeWithTag("media_tile_x").performClick()
        compose.onNodeWithContentDescription("Photo").assertIsDisplayed()
        assertTrue(shownInChat.isEmpty())
    }

    @Test
    fun aCoveredTileDrawsNothingOfItsPictureNotEvenAVideosPoster() {
        // A blur would not hide it before Android 12, so a covered tile draws no picture at all until it is shown.
        render(
            ChatMediaUiState(
                loaded = true,
                media =
                    listOf(
                        tile("coveredVideo", covered = true).copy(video = true, poster = POSTER, durationMs = 9_000L),
                        tile("coveredPhoto", covered = true),
                        tile("video").copy(video = true, poster = POSTER, durationMs = 9_000L),
                    ),
            ),
        )
        assertEquals("only the clean video's poster", 1, pictures())

        compose.onNodeWithTag("media_tile_coveredVideo").performClick()
        assertEquals("shown once tapped", 2, pictures())
    }

    @Test
    fun aHeldFileOpensAndAVoiceMessageOrAFileNotHereOpensTheChat() {
        render(
            ChatMediaUiState(
                tab = MediaTab.Files,
                loaded = true,
                files =
                    listOf(
                        file("doc", held = true),
                        file("voice", held = true, voice = true),
                        file("away", held = false),
                    ),
            ),
        )

        compose.onNodeWithTag("media_file_doc").performClick()
        assertEquals(listOf(PendingSave("h_doc", null, "doc.pdf", "application/pdf")), openedFiles)

        compose.onNodeWithTag("media_file_voice").performClick()
        compose.onNodeWithTag("media_file_away").performClick()
        compose.onNodeWithTag("media_file_doc_show").performClick()
        assertEquals(listOf("voice", "away", "doc"), shownInChat)
        assertEquals("only the held file opened", 1, openedFiles.size)
    }

    @Test
    fun aLinkOpensAndItsButtonShowsItsMessage() {
        val card = LinkCard("https://a.example/x", "a.example", "A page", null, hasImage = false)
        render(
            ChatMediaUiState(
                tab = MediaTab.Links,
                loaded = true,
                links =
                    listOf(
                        LinkItem(
                            "l1#0",
                            "l1",
                            "https://a.example/x",
                            card = card,
                            cardHash = "c",
                            mine = false,
                            senderName = "Ana",
                            sentAt = 0L,
                        ),
                    ),
            ),
        )

        compose.onNodeWithTag("media_link_l1#0").performClick()
        compose.onNodeWithTag("media_link_l1#0_show").performClick()

        assertEquals(listOf("https://a.example/x"), openedLinks)
        assertEquals(listOf("l1"), shownInChat)
    }

    @Test
    fun aBareLinkIsShownWithoutTheCharactersThatCouldReorderIt() {
        // U+202E RIGHT-TO-LEFT OVERRIDE would draw "moc.knab" as "bank.com"; what opens is still the link as sent.
        val url = "https://evil.example/‮moc.knab"
        render(
            ChatMediaUiState(
                tab = MediaTab.Links,
                loaded = true,
                links = listOf(LinkItem("l1#0", "l1", url, mine = false, senderName = "Ana", sentAt = 0L)),
            ),
        )

        compose.onNodeWithText("https://evil.example/moc.knab", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("media_link_l1#0").performClick()
        assertEquals(listOf(url), openedLinks)
    }

    private fun pictures() = compose.onAllNodesWithTag("media_tile_picture", useUnmergedTree = true).fetchSemanticsNodes().size

    private fun tile(
        id: String,
        held: Boolean = true,
        covered: Boolean = false,
    ) = MediaTile(
        messageId = id,
        hash = "h_$id",
        mime = "image/jpeg",
        key = null,
        video = false,
        held = held,
        covered = covered,
        mine = false,
        senderName = "Ana",
        sentAt = 0L,
    )

    private fun file(
        id: String,
        held: Boolean,
        voice: Boolean = false,
    ) = FileItem(
        messageId = id,
        hash = "h_$id",
        key = null,
        name = if (voice) null else "$id.pdf",
        mime = if (voice) "audio/aac" else "application/pdf",
        size = 1_000L,
        voice = voice,
        held = held,
        flagged = false,
        mine = false,
        senderName = "Ana",
        sentAt = 0L,
    )

    private companion object {
        val POSTER: String =
            java.util.Base64
                .getEncoder()
                .encodeToString(byteArrayOf(1, 2, 3, 4))
    }
}
