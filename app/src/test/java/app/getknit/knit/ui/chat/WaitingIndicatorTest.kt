package app.getknit.knit.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A missing attachment's spinner settles to a still glyph after [SPINNER_PATIENCE_MS] (#72): an indeterminate
 * spinner is an infinite animation, and one left in view for good kept Compose drawing every frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaitingIndicatorTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private var hash by mutableStateOf("h1")

    private fun setBubble() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KnitTheme {
                FileAttachmentBubble(
                    name = "report.pdf",
                    mime = "application/pdf",
                    declaredSize = 1_400_000L,
                    heldBytes = null,
                    ready = false,
                    flagged = false,
                    onOpen = {},
                    onLongClick = {},
                    hash = hash,
                )
            }
        }
    }

    private fun assertStalled(stalled: Boolean) {
        compose.onAllNodesWithTag("chat_attachment_stalled", useUnmergedTree = true).assertCountEquals(if (stalled) 1 else 0)
    }

    @Test
    fun aMissingFileSpinsForHalfAMinuteThenSettles() {
        setBubble()
        assertStalled(false)

        compose.mainClock.advanceTimeBy(SPINNER_PATIENCE_MS - 1_000)
        assertStalled(false)

        compose.mainClock.advanceTimeBy(2_000)
        assertStalled(true)

        // The regression itself: with the spinner gone the screen can go idle at all.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertStalled(true)
    }

    @Test
    fun aDifferentAttachmentInTheSlotSpinsAfresh() {
        setBubble()
        compose.mainClock.advanceTimeBy(SPINNER_PATIENCE_MS + 1_000)
        assertStalled(true)

        hash = "h2"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertStalled(false)

        compose.mainClock.advanceTimeBy(SPINNER_PATIENCE_MS + 1_000)
        assertStalled(true)
    }
}
