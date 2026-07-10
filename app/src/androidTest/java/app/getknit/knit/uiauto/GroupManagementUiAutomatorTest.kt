package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.getknit.knit.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the group-management actions on the seeded "Trailhead Crew" group: renaming the
 * group round-trips through the DB to the chat-list row, and leaving deletes the thread and pops back to
 * the chat list. Both live behind the group-details overflow menu and a confirm dialog — separate popup
 * windows that don't inherit the NavHost's `testTagsAsResourceId`, so the menu items and dialog buttons are
 * driven by their (localized) text, and the rename field by its editable class.
 */
@RunWith(AndroidJUnit4::class)
class GroupManagementUiAutomatorTest : SeededUiAutomatorTest() {
    /** Rename the group; the new name persists to the group-details title and the chat-list row. */
    @Test
    fun group_rename_roundTrips() {
        openGroupDetails()
        openGroupOverflow()
        requireText(str(R.string.chat_group_rename)).click() // "Rename group" → rename dialog

        // The dialog's editable field is the only EditText on screen; replace the pre-filled current name.
        val field =
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), DEFAULT_TIMEOUT_MS)) {
                "rename dialog input field not found"
            }
        field.text = NEW_NAME
        // Exact match: the confirm button "Rename" is a substring of the dialog title "Rename group".
        requireExactText(str(R.string.chat_group_rename_action)).click()

        // Persisted: the details title updates, and Back to the chat list shows the renamed row.
        assertText(NEW_NAME)
        device.pressBack() // group details → group chat
        device.pressBack() // group chat → chat list
        assertDesc(NEW_NAME)
    }

    /** Leaving the group deletes the thread and returns to the chat list, where its row is gone. */
    @Test
    fun group_leave_removesRow() {
        openGroupDetails()
        openGroupOverflow()
        requireText(str(R.string.chat_group_leave)).click() // "Leave group" → confirm dialog
        // Exact match: the confirm button "Leave" is a substring of the dialog title "Leave group?".
        requireExactText(str(R.string.chat_group_leave_confirm_action)).click()

        // onLeft pops back to the chat list; the group row disappears once the tombstone persists.
        assertTag("chat_row_nearby")
        assertTrue(
            "the group row should disappear from the chat list after leaving",
            device.wait(Until.gone(By.descContains(GROUP_NAME)), DEFAULT_TIMEOUT_MS),
        )
    }

    /** Chat list → the "Trailhead Crew" group row → the top-bar group avatar → the group-details screen. */
    private fun openGroupDetails() {
        launch()
        requireDesc(GROUP_NAME).click()
        requireTag("chat_group_avatar").click()
        assertText(str(R.string.group_details_title)) // "Group info" — confirms we're on the details screen
    }

    /** Opens the group-details top-bar overflow (the MoreVert icon, addressed by its contentDescription). */
    private fun openGroupOverflow() {
        requireDesc(str(R.string.chat_more_options)).click()
    }

    private companion object {
        const val GROUP_NAME = "Trailhead Crew"
        const val NEW_NAME = "Summit Squad"
    }
}
