package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process-lifecycle coverage that only a black-box tool can drive: pressing Home, cycling through Recents,
 * and rotating the device, then asserting the app restores its screen and state. The in-process Compose
 * suite can't leave the app or reach the system UI, so this fills that gap. Uses the same seeded build; no
 * app-code changes.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleUiAutomatorTest : SeededUiAutomatorTest() {
    /**
     * A DM open with an unsent draft survives a Home → foreground excursion: the seeded thread re-renders
     * and the draft text is still in the input. Exercises the real Activity stop/restart path.
     */
    @Test
    fun homeAndReturn_restoresOpenChatAndDraft() {
        launch("chat/samr1v00")
        assertText(SEEDED_DM_MESSAGE)
        val input = requireTag("chat_input")
        input.click()
        input.text = DRAFT

        device.pressHome()
        bringToForeground()

        // The seeded thread is DB-backed, so it re-renders; the draft lives in the retained composition.
        assertText(SEEDED_DM_MESSAGE)
        assertEquals("draft lost across Home/return", DRAFT, requireTag("chat_input").text)
    }

    /**
     * Deep-linking straight to a chat (as a notification tap does) leaves no in-app back stack, so a system
     * Back exits the app rather than surfacing a chat list that was never on the stack. Tap-navigation Back
     * (chat → list) is covered by [SeededFlowsUiAutomatorTest.dmThread_opensSeededMessage_thenBackReturnsToList].
     */
    @Test
    fun back_fromDeepLinkedChat_leavesApp() {
        launch("chat/samr1v00")
        assertText(SEEDED_DM_MESSAGE)
        device.pressBack()
        assertTrue(
            "app should leave the foreground on Back from a deep-linked chat (no in-app back stack)",
            device.wait(Until.gone(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT_MS),
        )
    }

    /** Rotating to landscape and back keeps the open DM's content on screen (survives Activity recreation). */
    @Test
    fun rotation_preservesOpenChat() {
        launch("chat/samr1v00")
        assertText(SEEDED_DM_MESSAGE)

        device.setOrientationLeft()
        assertText(SEEDED_DM_MESSAGE)

        device.setOrientationNatural()
        assertText(SEEDED_DM_MESSAGE)
    }

    private companion object {
        // Newest seeded DM from Sam Rivera: "Oh and I found your water bottle 💧".
        const val SEEDED_DM_MESSAGE = "found your water bottle"
        const val DRAFT = "draft that should survive"
    }
}
