package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the chat-list **overflow menu** and the screens behind it. The menu is a real
 * `DropdownMenu` popup window, and its destinations (Add contact / Diagnostics / Donate, and Blocked users behind
 * Settings' own overflow) have no in-process Compose test today — this is their only black-box coverage. Each is opened through the menu
 * and asserted by its own stable screen `testTag`; Back returns to the seeded chat list.
 *
 * "Install offline" (`share_app_menu`) opens a system share chooser rather than navigating in-app
 * (`ChatListScreen.onShareApp` → APK-merge chooser), so it's only asserted present in the menu, not driven.
 */
@RunWith(AndroidJUnit4::class)
class OverflowNavigationUiAutomatorTest : SeededUiAutomatorTest() {
    /** The overflow menu is a real popup that lists every destination, including the share entry. */
    @Test
    fun overflowMenu_listsAllDestinations() {
        launch()
        openOverflow()
        assertText(str(R.string.settings_title))
        assertText(str(R.string.add_contact_title))
        assertText(str(R.string.diagnostics_title))
        assertText(str(R.string.donate_title))
        assertText(str(R.string.share_app_menu))
    }

    @Test
    fun overflow_opensSettings() = openThenBack(R.string.settings_title, destTag = "screen_settings")

    @Test
    fun overflow_opensAddContact() = openThenBack(R.string.add_contact_title, destTag = "screen_add_contact")

    @Test
    fun overflow_opensDiagnostics() = openThenBack(R.string.diagnostics_title, destTag = "screen_diagnostics")

    /** Blocked users lives in Settings' overflow: chat list → Settings → its menu → the list, and Back to Settings. */
    @Test
    fun settingsOverflow_opensBlockedUsers() {
        launch()
        openOverflow()
        requireText(str(R.string.settings_title)).click()
        assertTag("screen_settings")
        openOverflow()
        requireText(str(R.string.blocked_users_title)).click()
        assertTag("screen_blocked_users")
        device.pressBack()
        assertTag("screen_settings")
    }

    @Test
    fun overflow_opensDonate() = openThenBack(R.string.donate_title, destTag = "screen_donate")

    /**
     * Launches the seeded chat list, opens the overflow menu, taps the item labelled [itemRes], asserts the
     * destination screen ([destTag]) renders, then Back returns to the chat list (the `chat_row_nearby` row).
     */
    private fun openThenBack(
        itemRes: Int,
        destTag: String,
    ) {
        launch()
        openOverflow()
        requireText(str(itemRes)).click()
        assertTag(destTag)
        device.pressBack()
        assertTag("chat_row_nearby")
    }

    /** Opens the top-bar overflow (the MoreVert icon, addressed by its contentDescription; chat list and Settings share it). */
    private fun openOverflow() {
        requireDesc(str(R.string.chat_more_options)).click()
    }
}
