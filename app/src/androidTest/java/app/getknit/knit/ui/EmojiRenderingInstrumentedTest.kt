package app.getknit.knit.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Highest-value device-quirk test: emoji rendering across API levels. `emojiOnlyCount` (ui/chat/EmojiText.kt)
 * runs on every message body and deliberately avoids the API-36-only `Character.isEmoji*` family. Opening the
 * seeded Nearby room composes several emoji-bearing bodies (running emojiOnlyCount on-device), then sending an
 * emoji-only message exercises the enlarged "jumbomoji" branch (emojiOnlyCount in 1..5) — the render path most
 * likely to trip an API-gated call on older hardware, invisible on one modern dev device.
 *
 * We assert the sent emoji echo rather than a seeded message: the echo is appended at the bottom the chat
 * scrolls to, so — unlike the newest seeded message, which carries an image attachment whose async height can
 * push its caption off-screen on some devices — it is reliably on-screen.
 */
@RunWith(AndroidJUnit4::class)
class EmojiRenderingInstrumentedTest : SeededUiTest() {
    @Test
    fun rendersEmojiOnlyMessageWithoutCrashing() {
        launch("chat/nearby")

        awaitTag("chat_input")
        compose.onNodeWithTag("chat_input").performTextInput(JUMBOMOJI)
        compose.onNodeWithTag("chat_send").performClick()
        // Generous: the send runs the on-device text moderator first (cold tflite load on a wiped process).
        awaitText(JUMBOMOJI, timeoutMs = 60_000)
    }

    private companion object {
        // U+1F389 U+1F973 U+1F525 (party popper, partying face, fire): three emoji -> the enlarged branch.
        const val JUMBOMOJI = "🎉🥳🔥"
    }
}
