package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the Message Requests inbox reached via the **in-app chat-list badge** — the sibling
 * of [MessageRequestNotificationUiAutomatorTest], which enters through the system notification shade — plus
 * the per-row Block confirm path. The debug `REQNOTIF` seam injects a synthetic unaccepted stranger DM; no
 * `POST_NOTIFICATIONS` grant is needed here because these enter through the UI, not the shade (on API 33 the
 * heads-up simply no-ops without the grant, so nothing overlays the badge).
 */
@RunWith(AndroidJUnit4::class)
class RequestsInboxUiAutomatorTest : SeededUiAutomatorTest() {
    /** Injecting a request lights the chat-list badge; tapping it opens the populated Requests inbox. */
    @Test
    fun requestBadge_opensInbox() {
        launch() // seeded chat list — no requests yet, so no badge
        injectRequest()
        // The badge appears only when a request is pending; it lights reactively once the row lands.
        requireTag("chatlist_requests").click()
        assertText(str(R.string.message_requests_title))
        assertTag("request_row_$STRANGER")
        assertText(STRANGER_NAME)
    }

    /** Blocking a request (row overflow → Block → confirm) removes it, leaving the inbox empty. */
    @Test
    fun request_blockPath_removesFromInbox() {
        launch()
        injectRequest()
        requireTag("chatlist_requests").click()
        requireTag("request_row_$STRANGER")

        // The row's overflow (the only MoreVert on this screen) → the "Block" item → the confirm dialog.
        requireDesc(str(R.string.chat_more_options)).click()
        requireText(str(R.string.message_requests_block)).click()
        requireText(str(R.string.message_requests_block_confirm_title)) // "Block this person?" dialog is up
        // Exact match: the confirm button "Block" is a substring of the title "Block this person?".
        requireExactText(str(R.string.message_requests_block)).click()

        // The request leaves the inbox, which is now empty.
        assertText(str(R.string.message_requests_empty))
    }

    /** Fires the debug seam that writes one synthetic unaccepted inbound DM (a "message request"). */
    private fun injectRequest() {
        device.executeShellCommand("am broadcast -p $PKG -a $REQNOTIF_ACTION --ei count 1")
    }

    private companion object {
        const val REQNOTIF_ACTION = "app.getknit.knit.debug.REQNOTIF"

        // Mirrors DebugBridgeReceiver.handleReqNotif's first synthetic stranger (nodeId + name).
        const val STRANGER = "strngr01"
        const val STRANGER_NAME = "Alex Stranger"
    }
}
