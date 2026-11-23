package app.getknit.knit.ui.chatlist

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.notifications.MuteFor
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A muted chat's row (ADR 2026-11.sp7k): what it says aloud, and the long-press menu's Mute / Unmute. Its own class
 * rather than more of [ChatListScreenContentTest], which is at detekt's size ceiling.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationListItemMuteTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val picks = mutableListOf<Pair<String, MuteFor>>()
    private val unmutes = mutableListOf<String>()
    private val deletes = mutableListOf<String>()

    private fun row(
        id: String = "g-crew",
        mutedUntil: Long? = null,
        isRoom: Boolean = false,
    ) = ConversationRow(
        id = id,
        title = "Crew",
        avatarHash = null,
        isRoom = isRoom,
        isGroup = !isRoom,
        lastPreview = "lunch?",
        lastMessageAt = now - 60_000L,
        unreadCount = 3,
        mutedUntil = mutedUntil,
    )

    private fun show(
        row: ConversationRow,
        canMute: Boolean = true,
    ) = compose.setContent {
        KnitTheme {
            ConversationListItem(
                row,
                now,
                onClick = {},
                onDelete = { deletes += it },
                canMute = canMute,
                onMute = { id, span -> picks += id to span },
                onUnmute = { unmutes += it },
            )
        }
    }

    private fun longPress(id: String) = compose.onNodeWithTag("chat_row_$id").performTouchInput { longClick() }

    private fun spoken(id: String): String =
        compose
            .onNodeWithTag("chat_row_$id")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString()

    private fun customActions(id: String): List<String> =
        compose
            .onNodeWithTag("chat_row_$id")
            .fetchSemanticsNode()
            .config
            .getOrElse(SemanticsActions.CustomActions) { emptyList() }
            .map { it.label }

    @Test
    fun aMutedRowSaysSoAndItsMenuUnmutesInOneTap() {
        show(row(mutedUntil = ChatMutes.ALWAYS))
        compose.onNodeWithTag("chat_row_g-crew").assertContentDescriptionContains("Crew, Muted, lunch?", substring = true)
        assertEquals(listOf("Unmute notifications", "Delete"), customActions("g-crew"))

        longPress("g-crew")
        compose.onNodeWithTag("chat_row_unmute").performClick()

        assertEquals(listOf("g-crew"), unmutes)
        assertTrue(picks.isEmpty())
    }

    @Test
    fun anUnmutedRowsMenuOffersTheSpansAndMutesForTheOnePicked() {
        show(row())
        assertFalse(spoken("g-crew").contains("Muted"))

        longPress("g-crew")
        compose.onNodeWithTag("chat_row_mute").performClick()
        compose.onNodeWithText("8 hours").assertExists()
        compose.onNodeWithTag("mute_for_week").performClick()

        assertEquals(listOf("g-crew" to MuteFor.WEEK), picks)
        compose.onNodeWithTag("mute_dialog").assertDoesNotExist()
        assertTrue(deletes.isEmpty())
    }

    @Test
    fun aCommonsRowHasAMenuForMuteAloneAndNoDelete() {
        val id = Conversations.COMMONS_PREFIX + "ab".repeat(32)
        show(row(id = id, isRoom = true))
        assertEquals(listOf("Mute notifications"), customActions(id))

        longPress(id)
        compose.onNodeWithTag("chat_row_mute").assertExists()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun aRowWithNoMutesWiredOffersNone() {
        show(row(), canMute = false)
        assertEquals(listOf("Delete"), customActions("g-crew"))
    }
}
