package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of Remove contact (ADR 2026-09.adgd, issue #23) on three seeded people who are contacts
 * in three different ways: Dani (verified, a DM we wrote in, no shared group) is removed outright; Sam (a DM
 * we wrote in, plus the Trailhead Crew we posted in) loses the DM but stays a contact through the group; and
 * Priya (the group alone) gets the explanation, with nothing to remove. The overflow menu and the confirm are
 * popup windows that don't inherit the NavHost's `testTagsAsResourceId`, so both are driven by their text.
 */
@RunWith(AndroidJUnit4::class)
class ContactRemovalUiAutomatorTest : SeededUiAutomatorTest() {
    /** Dani's DM is deleted, the profile closes past it to the chat list, and the picker no longer lists her. */
    @Test
    fun profile_removeContact_deletesTheDmAndLeavesThePicker() {
        openProfileFromDm(DANI, DANI_NAME)
        openOverflow()
        requireText(str(R.string.profile_details_remove_contact)).click()
        requireText(str(R.string.profile_details_remove_verified)) // the confirm says the check goes too
        // Exact match: the confirm button "Remove" is a substring of the title and the menu item.
        requireExactText(str(R.string.profile_details_remove_action)).click()

        // The profile sat on Dani's DM, which is gone, so it pops past both to the chat list.
        assertTag("chat_row_nearby")
        assertTagGone("chat_row_$DANI")
        openPicker()
        assertTag("contact_$SAM")
        assertTagGone("contact_$DANI")
    }

    /** Sam's confirm names the group that keeps him, and after the removal the picker still lists him. */
    @Test
    fun profile_removeGroupCoMember_namesTheGroupAndKeepsThemAContact() {
        openProfileFromDm(SAM, SAM_NAME)
        openOverflow()
        requireText(str(R.string.profile_details_remove_contact)).click()
        requireText(GROUP_NAME)
        requireExactText(str(R.string.profile_details_remove_action)).click()

        assertTag("chat_row_nearby")
        assertTagGone("chat_row_$SAM")
        openPicker()
        assertTag("contact_$SAM")
    }

    /** Priya is a contact only through the group: the dialog explains, offers OK alone, and nothing changes. */
    @Test
    fun profile_groupOnlyContact_explainsAndChangesNothing() {
        launch("profileDetails/$PRIYA")
        assertTag("screen_profile_details")
        openOverflow()
        requireText(str(R.string.profile_details_remove_contact)).click()
        requireText(GROUP_NAME)
        requireExactText(str(android.R.string.ok)).click()

        assertTag("screen_profile_details")
        openOverflow()
        requireText(str(R.string.profile_details_remove_contact)) // still offered: she is still a contact
    }

    /**
     * Chat list → [nodeId]'s DM row → the header avatar → their profile. The row tap can race the async seed
     * still reflowing rows, so a cold start is retried until the DM actually opens.
     */
    private fun openProfileFromDm(
        nodeId: String,
        name: String,
    ) {
        val viewProfile = str(R.string.chat_view_profile).format(name)
        repeat(OPEN_ATTEMPTS) {
            launch()
            requireTag("chat_row_nearby") // the seeded list is populated before we tap (seed is async)
            requireTag("chat_row_$nodeId").click()
            waitDesc(viewProfile, OPEN_POLL_MS)?.let { avatar ->
                avatar.click()
                assertTag("screen_profile_details")
                return
            }
        }
        error("$name's profile was unreachable from the chat list after $OPEN_ATTEMPTS attempts")
    }

    private fun openOverflow() {
        requireDesc(str(R.string.chat_more_options)).click()
    }

    /** The chat list's new-message FAB, which opens the contacts picker. */
    private fun openPicker() {
        requireDesc(str(R.string.contacts_new_message)).click()
    }

    private companion object {
        // The seeded ids and hiking-scenario names, spelled out so this reads as the black-box check it is.
        const val SAM = "samr1v00"
        const val SAM_NAME = "Sam Rivera"
        const val DANI = "danich01"
        const val DANI_NAME = "Dani Cho"
        const val PRIYA = "priyan07"
        const val GROUP_NAME = "Trailhead Crew"

        const val OPEN_ATTEMPTS = 3
        const val OPEN_POLL_MS = 12_000L
    }
}
