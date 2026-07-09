package app.getknit.knit.ui.requests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-on-Robolectric: drives the stateless [MessageRequestsScreenContent] on the JVM (mirrors
 * `ChatListScreenContentTest`). Covers row rendering, the Accept action, and the empty state.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageRequestsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rowsRenderAndAcceptRoutesTheConversationId() {
        var accepted: String? = null
        compose.setContent {
            KnitTheme {
                MessageRequestsScreenContent(
                    requests =
                        listOf(
                            RequestRow(
                                conversationId = "stranger-1",
                                title = "Stranger",
                                avatarHash = null,
                                isGroup = false,
                                lastPreview = "hi there",
                                lastMessageAt = 0L,
                            ),
                        ),
                    onAccept = { accepted = it },
                    onBlock = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Stranger").assertIsDisplayed()
        compose.onNodeWithText("Accept").performClick()
        assertEquals("stranger-1", accepted)
    }

    @Test
    fun tappingDmAvatarOpensSenderProfile() {
        var opened: String? = null
        compose.setContent {
            KnitTheme {
                MessageRequestsScreenContent(
                    requests =
                        listOf(
                            RequestRow(
                                conversationId = "stranger-1",
                                title = "Stranger",
                                avatarHash = null,
                                isGroup = false,
                                lastPreview = "hi there",
                                lastMessageAt = 0L,
                            ),
                        ),
                    onAccept = {},
                    onBlock = {},
                    onDelete = {},
                    onOpenProfile = { opened = it },
                    onBack = {},
                )
            }
        }

        // The DM avatar carries the "View <name>'s profile" accessible name; tapping it routes the
        // conversationId (the peer's node id) to onOpenProfile.
        compose.onNodeWithContentDescription("View Stranger's profile").performClick()
        assertEquals("stranger-1", opened)
    }

    @Test
    fun emptyStateShownWhenNoRequests() {
        compose.setContent {
            KnitTheme {
                MessageRequestsScreenContent(
                    requests = emptyList(),
                    onAccept = {},
                    onBlock = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("No message requests").assertIsDisplayed()
    }
}
