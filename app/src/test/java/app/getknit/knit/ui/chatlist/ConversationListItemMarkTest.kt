package app.getknit.knit.ui.chatlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * What a row's preview mark says aloud. Its own class rather than more of [ChatListScreenContentTest], which is at
 * detekt's size ceiling.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationListItemMarkTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    /**
     * A translated line's mark is icon-only and the line reads like the message itself, so the row's summary says it
     * is a translation (ADR 2026-10.t2h6); a draft speaking for the row takes the mark's words with the line.
     */
    @Test
    fun aTranslatedPreviewSaysSoUntilADraftSpeaksForTheRow() {
        var draft by mutableStateOf<String?>(null)
        compose.setContent {
            KnitTheme {
                ConversationListItem(
                    ConversationRow(
                        id = "dm-1",
                        title = "Ada",
                        avatarHash = null,
                        isRoom = false,
                        isGroup = false,
                        lastPreview = "see you at six?",
                        previewMark = PreviewMark.Translated("es"),
                        lastMessageAt = now - 60_000L,
                        unreadCount = 0,
                        draft = draft,
                    ),
                    now,
                    onClick = {},
                )
            }
        }
        compose
            .onNodeWithTag("chat_row_dm-1")
            .assertContentDescriptionContains("Ada, Translated from Spanish, see you at six?", substring = true)

        draft = "on my"
        compose.waitForIdle()
        val spoken =
            compose
                .onNodeWithTag("chat_row_dm-1")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString()
        assertTrue(spoken.contains("on my"))
        assertFalse(spoken.contains("Translated"))
    }
}
