package app.getknit.knit.ui.chatlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.pausedUntilLabel
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun row(
        id: String,
        title: String,
        unread: Int = 0,
        isRoom: Boolean = false,
        status: DeliveryStatus? = null,
        plane: DeliveryPlane = DeliveryPlane.Unknown,
    ) = ConversationRow(
        id = id,
        title = title,
        avatarHash = null,
        isRoom = isRoom,
        isGroup = false,
        lastPreview = "hi",
        lastMessageAt = now - 60_000L,
        unreadCount = unread,
        lastStatus = status,
        lastDeliveredVia = plane,
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
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
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

    /**
     * The header's status line is the door to Your mesh: one node that still speaks the row's own description
     * (it is `clearAndSetSemantics` inside), now with the button role and the click. The overflow menu offers
     * the same screen, above Diagnostics.
     */
    @Test
    fun theStatusLineAndTheOverflowMenuOpenYourMesh() {
        var opened = 0
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state = ChatListUiState(neighborCount = 3, transportHealth = TransportHealth.Healthy),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = { opened++ },
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_your_mesh").assertContentDescriptionContains("3", substring = true)
        compose.onNodeWithTag("chatlist_your_mesh").performClick()
        assertEquals(1, opened)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Your mesh").performClick()
        assertEquals(2, opened)
    }

    @Test
    fun aRowWithADraftReadsAsDraftInsteadOfItsLastMessage() {
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("dm-1", "Ada").copy(draft = "half a sentence")),
                        ),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        // The row is one accessible node, so the preview line is read through its description — which is
        // where the "Draft:" prefix has to live, since italics say nothing to a screen reader.
        compose
            .onNodeWithTag("chat_row_dm-1")
            .assertContentDescriptionContains("Draft: half a sentence", substring = true)
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
                    onSearch = {},
                    onNewMessage = { newMessage++ },
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
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
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = { openedRequests++ },
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
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
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_requests").assertDoesNotExist()
    }

    @Test
    fun theGettingStartedHintRoutesToNearbyAndToAddContact() {
        var opened: String? = null
        var addContact = 0
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("nearby", "Nearby", isRoom = true)),
                            showGettingStarted = true,
                        ),
                    now = now,
                    onOpenConversation = { opened = it },
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = { addContact++ },
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_hint").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_hint_add_contact").performClick()
        assertEquals(1, addContact)
        compose.onNodeWithTag("chatlist_hint_nearby").performClick()
        assertEquals(Conversations.NEARBY, opened)
    }

    @Test
    fun theGettingStartedHintIsAbsentOnceThereIsSomethingToOpen() {
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state = ChatListUiState(conversations = listOf(row("nearby", "Nearby", isRoom = true))),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_hint").assertDoesNotExist()
    }

    @Test
    fun anOutboundRowSpeaksItsDeliveryTickAndOthersDoNot() {
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations =
                                listOf(
                                    row("dm-1", "Ada", status = DeliveryStatus.Sent),
                                    row(
                                        "dm-2",
                                        "Grace",
                                        status = DeliveryStatus.Delivered,
                                        plane = DeliveryPlane.Internet,
                                    ),
                                    row("dm-3", "Lena"),
                                ),
                        ),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        // The tick is icon-only, so the row's folded contentDescription is what carries it.
        compose.onNodeWithTag("chat_row_dm-1").assertContentDescriptionContains("Sent", substring = true)
        compose
            .onNodeWithTag("chat_row_dm-2")
            .assertContentDescriptionContains("Delivered over the Internet", substring = true)
        // An inbound (or empty) row has no tick: delivery isn't ours to report.
        val lena =
            compose
                .onNodeWithTag("chat_row_dm-3")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString()
        assertTrue(lena.contains("Lena"))
        assertFalse(lena.contains("Sent"))
    }

    /** The clone notice (ADR 2026-09.ypcc): shown on the flag, its two actions routed, absent otherwise. */
    @Test
    fun theCloneBannerOffersSignOutAndDismissOnlyWhileTheFlagIsUp() {
        var signOuts = 0
        var dismissals = 0
        var visible by mutableStateOf(true)
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state = ChatListUiState(conversations = listOf(row("nearby", "Nearby", isRoom = true)), cloneVisible = visible),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = { signOuts++ },
                    onDismissClone = { dismissals++ },
                    onResumeMesh = {},
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_clone_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_clone_banner_signout").performClick()
        assertEquals(1, signOuts)
        compose.onNodeWithTag("chatlist_clone_banner_dismiss").performClick()
        assertEquals(1, dismissals)

        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag("chatlist_clone_banner").assertDoesNotExist()
    }

    /** The paused banner: the deadline's clock time, Resume routed, absent once the deadline is gone. */
    @Test
    fun theMeshPausedBannerShowsTheDeadlineAndRoutesResume() {
        var resumes = 0
        val until = now + 15 * 60_000L
        var pausedUntil by mutableStateOf<Long?>(until)
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state = ChatListUiState(conversations = listOf(row("nearby", "Nearby", isRoom = true)), pausedUntil = pausedUntil),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = { resumes++ },
                    onStartMesh = {},
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_mesh_off_banner").assertIsDisplayed()
        val label = pausedUntilLabel(ApplicationProvider.getApplicationContext(), until, now)
        compose.onNodeWithText("Mesh paused until $label").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_mesh_off_banner_action").performClick()
        assertEquals(1, resumes)

        pausedUntil = null
        compose.waitForIdle()
        compose.onNodeWithTag("chatlist_mesh_off_banner").assertDoesNotExist()
    }

    /** The stopped form of the same banner: its own text, Start routed, and it outranks a pause. */
    @Test
    fun theMeshStoppedBannerShowsStartAndOutranksAPause() {
        var starts = 0
        var resumes = 0
        compose.setContent {
            KnitTheme {
                ChatListScreenContent(
                    state =
                        ChatListUiState(
                            conversations = listOf(row("nearby", "Nearby", isRoom = true)),
                            pausedUntil = now + 15 * 60_000L,
                            meshStopped = true,
                        ),
                    now = now,
                    onOpenConversation = {},
                    onSearch = {},
                    onNewMessage = {},
                    onOpenSettings = {},
                    onOpenYourMesh = {},
                    onOpenDiagnostics = {},
                    onOpenBlockedUsers = {},
                    onOpenMessageRequests = {},
                    onOpenDonate = {},
                    onOpenAddContact = {},
                    onShareApp = {},
                    onOpenRadioSettings = {},
                    onDismissRadioWarning = {},
                    onSignOut = {},
                    onDismissClone = {},
                    onResumeMesh = { resumes++ },
                    onStartMesh = { starts++ },
                    onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("chatlist_mesh_off_banner").assertIsDisplayed()
        compose.onNodeWithText("Mesh stopped").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_mesh_off_banner_action").performClick()
        assertEquals(1, starts)
        assertEquals(0, resumes)
    }
}
