package app.getknit.knit.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-on-Robolectric spike (Phase 2): proves `createComposeRule` drives the stateless
 * `ChatListScreenContent` against the existing testTags on the JVM — no permissions, no MeshService, no
 * Koin. If this combination (Robolectric 4.16 / Compose BOM / SDK 36 native graphics) turns out flaky,
 * pin `@Config(sdk = [34])` here; if still flaky, move the Compose tests to instrumented androidTest.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun row(
        id: String,
        title: String,
        unread: Int = 0,
        isRoom: Boolean = false,
    ) = ConversationRow(
        id = id,
        title = title,
        avatarHash = null,
        isRoom = isRoom,
        isGroup = false,
        lastPreview = "hi",
        lastMessageAt = now - 60_000L,
        unreadCount = unread,
    )

    @Test
    fun rowsRenderAndTappingOneRoutesItsConversationId() {
        var opened: String? = null
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("nearby", "Nearby", isRoom = true), row("dm-1", "Ada", unread = 2)),
                            neighborCount = 3,
                            transportHealth = TransportHealth.Healthy,
                        ),
                    now = now,
                    onOpenConversation = { opened = it },
                    onNewMessage = {},
                    onOpenProfile = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onDeleteConversation = {},
                )
            }
        }

        // The row uses clearAndSetSemantics: its title is folded into the row's contentDescription.
        compose.onNodeWithContentDescription("Ada", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_row_dm-1").assertIsDisplayed()

        compose.onNodeWithTag("chat_row_dm-1").performClick()
        assertEquals("dm-1", opened)
    }

    @Test
    fun tappingTheFabOpensNewMessage() {
        var newMessage = 0
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state = ChatListUiState(conversations = listOf(row("nearby", "Nearby", isRoom = true))),
                    now = now,
                    onOpenConversation = {},
                    onNewMessage = { newMessage++ },
                    onOpenProfile = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_fab").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_fab").performClick()
        assertEquals(1, newMessage)
    }

    @Test
    fun requestsBadgeShowsCountAndTapOpensRequests() {
        var openedRequests = 0
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("nearby", "Nearby", isRoom = true)),
                            requestCount = 3,
                        ),
                    now = now,
                    onOpenConversation = {},
                    onNewMessage = {},
                    onOpenProfile = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = { openedRequests++ },
                    onOpenDonate = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_requests").performClick()
        assertEquals(1, openedRequests)
    }

    @Test
    fun requestsBadgeHiddenWhenNoPendingRequests() {
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("nearby", "Nearby", isRoom = true)),
                            requestCount = 0,
                        ),
                    now = now,
                    onOpenConversation = {},
                    onNewMessage = {},
                    onOpenProfile = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_requests").assertDoesNotExist()
    }
}
