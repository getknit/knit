package app.getknit.knit.ui.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.chatlist.ConversationRow
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless `ShareTargetScreenContent` (the share-sheet destination picker). It reuses the
 * chat-list `ConversationListItem`, so each row carries the `chat_row_<id>` testTag; tapping one must
 * forward its conversation id to `onPick`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareTargetScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun row(
        id: String,
        title: String,
        isRoom: Boolean = false,
    ) = ConversationRow(
        id = id,
        title = title,
        avatarHash = null,
        isRoom = isRoom,
        isGroup = false,
        lastPreview = "hi",
        lastMessageAt = now - 60_000L,
        unreadCount = 0,
    )

    @Test
    fun rowsRenderAndTappingOnePicksItsConversationId() {
        var picked: String? = null
        compose.setContent {
            KnitTheme {
                ShareTargetScreenContent(
                    conversations = listOf(row("nearby", "Nearby", isRoom = true), row("dm-1", "Ada")),
                    now = now,
                    onBack = {},
                    onPick = { picked = it },
                )
            }
        }

        compose.onNodeWithTag("chat_row_dm-1").assertIsDisplayed()
        compose.onNodeWithTag("chat_row_dm-1").performClick()
        assertEquals("dm-1", picked)
    }
}
