package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the core seeded flows, driven through the real app process (no in-process Compose
 * hooks). Asserts the deterministic "hiking" seed by resource-id / text / contentDescription, exercises
 * tap-navigation between screens, and sends a DM through the real input field. No app-code changes — this
 * is the UIAutomator twin of the Compose `ChatList`/`DmChat`/`GroupDetails`/`NearbyChat` suites.
 *
 * Chat-list rows use `clearAndSetSemantics`, folding their title/preview into the row's contentDescription
 * and exposing a stable `chat_row_<id>` resource-id — so a DM row is matched by tag and the group row
 * (no fixed route) by its title in the contentDescription.
 */
@RunWith(AndroidJUnit4::class)
class SeededFlowsUiAutomatorTest : SeededUiAutomatorTest() {
    /** The seeded chat list renders the Nearby room, the Sam DM row, and the "Trailhead Crew" group row. */
    @Test
    fun chatList_showsSeededRows() {
        launch()
        assertTag("chat_row_nearby")
        assertTag("chat_row_samr1v00")
        // The group has no deep-link route; its title lives in the row contentDescription.
        assertDesc("Trailhead Crew")
    }

    /** Tapping the Sam DM row opens the thread (a seeded message shows); Back returns to the chat list. */
    @Test
    fun dmThread_opensSeededMessage_thenBackReturnsToList() {
        launch()
        requireTag("chat_row_samr1v00").click()
        // Newest seeded DM from Sam: "Oh and I found your water bottle 💧".
        assertText("found your water bottle")
        device.pressBack()
        assertTag("chat_row_nearby")
    }

    /** Chat list → "Trailhead Crew" group → top-bar avatar → group details, which lists Sam as a member. */
    @Test
    fun groupDetails_reachableFromChatList() {
        launch()
        requireDesc("Trailhead Crew").click()
        // In the group chat, the top-bar avatar opens the details screen.
        requireTag("chat_group_avatar").click()
        assertTag("group_member_samr1v00")
    }

    /** Typing into the real input field and tapping send echoes the message locally (no-op transport). */
    @Test
    fun dmSend_echoesLocally() {
        launch("chat/samr1v00")
        val input = requireTag("chat_input")
        input.click()
        input.text = SENT_BODY
        requireTag("chat_send").click()
        // First text send cold-loads the on-device tflite moderator, so the echo can lag ~a minute.
        assertText(SENT_BODY, SEND_ECHO_TIMEOUT_MS)
    }

    private companion object {
        const val SENT_BODY = "hello from uiautomator"
    }
}
