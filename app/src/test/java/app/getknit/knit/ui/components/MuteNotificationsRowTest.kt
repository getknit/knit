package app.getknit.knit.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.notifications.MuteFor
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The details screens' Mute row (ADR 2026-11.sp7k): Mute opens the spans, Unmute is one tap and says when it ends. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS")
class MuteNotificationsRowTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    @Test
    fun muteOpensTheSpansAndThePickedOneMutesThenTheRowOffersUnmute() {
        var mutedUntil by mutableStateOf<Long?>(null)
        val picks = mutableListOf<MuteFor>()
        var unmutes = 0
        compose.setContent {
            KnitTheme {
                MuteNotificationsRow(
                    mutedUntil = mutedUntil,
                    now = now,
                    onMute = {
                        picks += it
                        mutedUntil = ChatMutes.ALWAYS
                    },
                    onUnmute = {
                        unmutes++
                        mutedUntil = null
                    },
                )
            }
        }
        compose.onNodeWithText("Mute notifications").assertIsDisplayed()
        compose.onNodeWithTag("mute_notifications_row").performClick()
        compose.onNodeWithTag("mute_dialog").assertIsDisplayed()
        compose.onNodeWithTag("mute_for_always").performClick()

        assertEquals(listOf(MuteFor.ALWAYS), picks)
        compose.onNodeWithTag("mute_dialog").assertDoesNotExist()
        compose.onNodeWithText("Unmute notifications").assertIsDisplayed()
        compose.onNodeWithText("Muted").assertIsDisplayed()

        compose.onNodeWithTag("mute_notifications_row").performClick()
        assertEquals(1, unmutes)
        compose.onNodeWithTag("mute_dialog").assertDoesNotExist()
        compose.onNodeWithText("Mute notifications").assertIsDisplayed()
    }

    @Test
    fun aTimedMuteSaysWhenItEnds() {
        compose.setContent {
            KnitTheme {
                MuteNotificationsRow(mutedUntil = now + 60 * 60_000L, now = now, onMute = {}, onUnmute = {})
            }
        }
        compose.onNodeWithText("Muted until", substring = true).assertIsDisplayed()
    }

    @Test
    fun cancelLeavesTheChatUnmuted() {
        val picks = mutableListOf<MuteFor>()
        compose.setContent {
            KnitTheme { MuteNotificationsRow(mutedUntil = null, now = now, onMute = { picks += it }, onUnmute = {}) }
        }
        compose.onNodeWithTag("mute_notifications_row").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(picks.isEmpty())
        compose.onNodeWithTag("mute_dialog").assertDoesNotExist()
    }
}
