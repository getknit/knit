package app.getknit.knit.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The Nearby broadcast room opens and echoes a locally-sent message with the radios inert. */
@RunWith(AndroidJUnit4::class)
class NearbyChatInstrumentedTest : SeededUiTest() {
    @Test
    fun opensAndEchoesASend() {
        launch("chat/nearby")

        // The room composed (the composer is present). We assert the send -> local echo rather than a
        // seeded message: the newest seeded broadcast message carries an image attachment whose async height
        // throws off the auto-scroll on some devices (observed on the Samsung a10/b0q), leaving its caption
        // intermittently off-screen where a LazyColumn won't compose it. A freshly-sent text message is
        // appended at the bottom the chat scrolls to, so it is reliably on-screen.
        awaitTag("chat_input")

        // DemoTransport.send is a no-op, but MeshManager persists the local row, so the outgoing bubble must
        // render. The echo await is generous: sendChat runs the on-device text moderator first
        // (isTextFlagged), whose tflite model cold-loads on a fresh, data-wiped process — slowest on the a10.
        compose.onNodeWithTag("chat_input").performTextInput(SMOKE_MESSAGE)
        compose.onNodeWithTag("chat_send").performClick()
        awaitText(SMOKE_MESSAGE, timeoutMs = 60_000)
    }

    private companion object {
        // Plainly benign, so the outgoing text moderator never flags it (which would drop the send).
        const val SMOKE_MESSAGE = "hello from the trail"
    }
}
