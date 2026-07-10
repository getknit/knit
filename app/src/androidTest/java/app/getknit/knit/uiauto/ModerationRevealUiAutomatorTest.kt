package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the received-flagged message collapse: a message the on-device text moderator
 * flagged renders behind "Message hidden — tap to reveal", and tapping the bubble reveals the real body
 * (`row.moderationFlagged && !revealed` in `ui/chat/ChatScreen.kt`). The radio-less demo build never
 * receives a real flagged message, so the debug `FLAGMSG` seam injects one — as the newest Nearby row, with
 * a known body — that this test then reveals.
 */
@RunWith(AndroidJUnit4::class)
class ModerationRevealUiAutomatorTest : SeededUiAutomatorTest() {
    @Test
    fun flaggedInbound_collapsed_tapReveals() {
        // Inject a flagged inbound message into the Nearby room with a known body. The body is a single token
        // (no spaces): UiDevice.executeShellCommand tokenizes on whitespace and does NOT honour quoting, so a
        // spaced value would be truncated to its first word.
        device.executeShellCommand(
            "am broadcast -p $PKG -a $FLAGMSG_ACTION --es conv nearby --es text $FLAGGED_BODY",
        )
        launch("chat/nearby")

        // Collapsed: the placeholder shows (waits for the async row to land) and the body is not yet visible.
        val collapsed = requireText(str(R.string.moderation_text_hidden))
        assertNull("flagged body should be hidden before reveal", waitText(FLAGGED_BODY, SHORT_TIMEOUT_MS))

        // Tap the collapsed bubble → the real body appears.
        collapsed.click()
        assertText(FLAGGED_BODY)
    }

    private companion object {
        const val FLAGMSG_ACTION = "app.getknit.knit.debug.FLAGMSG"

        // Single token on purpose (see executeShellCommand note above); distinctive so the reveal is unambiguous.
        const val FLAGGED_BODY = "flagged-reveal-marker"

        // Long enough to be confident the body isn't rendered (the collapsed placeholder is already up),
        // short enough not to stall the test.
        const val SHORT_TIMEOUT_MS = 2_000L
    }
}
