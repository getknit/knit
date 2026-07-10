package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of conversation creation from the Contacts picker (`launch("contacts")`): a single
 * pick opens a 1:1 DM, a multi pick creates a group. Drives the real `contact_<nodeId>` selection rows and
 * the `contacts_fab` confirm button, which the picker only shows once at least one peer is selected.
 */
@RunWith(AndroidJUnit4::class)
class ContactsFlowUiAutomatorTest : SeededUiAutomatorTest() {
    /** Selecting one peer (Theo — a seeded contact with no seeded DM) and confirming opens a fresh DM. */
    @Test
    fun contacts_singlePick_opensDm() {
        launch("contacts")
        requireTag("contact_$THEO").click()
        requireTag("contacts_fab").click()
        // A DM thread opens: the real message input is present and the top bar names the peer.
        assertTag("chat_input")
        assertText(THEO_NAME)
    }

    /** Selecting two peers and confirming creates a group chat — the group top-bar avatar proves it's a group. */
    @Test
    fun contacts_multiPick_createsGroup() {
        launch("contacts")
        requireTag("contact_$SAM").click()
        requireTag("contact_$DANI").click()
        requireTag("contacts_fab").click()
        // A group chat opens: the input plus the group top-bar avatar (a DM would show a peer avatar, no tag).
        assertTag("chat_input")
        assertTag("chat_group_avatar")
    }

    private companion object {
        // The picker lists only *established* contacts (accepted-DM ∪ group co-member ∪ verified), so it must
        // be one of Sam/Dani/Priya/Theo — the Nearby-only strangers (Jonas/Lena) never appear. Theo is a
        // group co-member with no 1:1 DM, so a single pick opens a fresh DM. Sam + Dani are both online (sorted
        // to the top, on-screen) and form a new group distinct from the seeded "Trailhead Crew".
        const val THEO = "theob123"
        const val THEO_NAME = "Theo Blake"
        const val SAM = "samr1v00"
        const val DANI = "danich01"
    }
}
