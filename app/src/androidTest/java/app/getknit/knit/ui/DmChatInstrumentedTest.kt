package app.getknit.knit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Opening a seeded 1:1 DM renders its (plaintext-in-DB) history and the composer. */
@RunWith(AndroidJUnit4::class)
class DmChatInstrumentedTest : SeededUiTest() {
    @Test
    fun rendersSeededDmHistory() {
        launch("chat/samr1v00")

        // The DM thread with Sam Rivera; seeded rows are stored plaintext, so no key exchange is needed.
        awaitText("found your water bottle")
        awaitTag("chat_input")
    }
}
