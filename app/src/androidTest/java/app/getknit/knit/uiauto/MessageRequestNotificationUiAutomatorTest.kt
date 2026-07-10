package app.getknit.knit.uiauto

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiObject2
import app.getknit.knit.R
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end message-request notification flow — the one thing an in-process Compose test can't reach,
 * because it lives in the **system notification shade**. Drives the real coalesced heads-up
 * ([app.getknit.knit.notifications.MessageNotifier.notifyMessageRequests]) → open the shade → tap →
 * deep-link into the Requests inbox → Accept.
 *
 * The radio-less demo build never runs `InboundPipeline` (the sole production caller) and seeds no
 * requests, so the debug `REQNOTIF` bridge action injects a synthetic unaccepted inbound DM and posts the
 * heads-up (see `DebugBridgeReceiver`). `POST_NOTIFICATIONS` is a runtime permission on API 33+ (granted
 * here via shell); on 29–32 it is install-time granted, so the guard skips it.
 */
@RunWith(AndroidJUnit4::class)
class MessageRequestNotificationUiAutomatorTest : SeededUiAutomatorTest() {
    @Test
    fun messageRequestHeadsUp_opensRequestsInbox_andAccepts() {
        // The notification posts correctly on an emulator (verified via `dumpsys notification`), but a headless
        // Gradle-managed / ATD emulator's SystemUI never surfaces its content to the accessibility tree, so the
        // shade can't be driven here. Skip (not fail) on an emulator; the FTL physical-device pass covers it.
        assumeFalse("notification shade content isn't reachable on a headless emulator; runs on FTL hardware", isEmulator())

        grantNotifications()
        launch() // seeded chat list

        // Fire the debug seam: one synthetic unaccepted inbound DM from a stranger + the coalesced heads-up.
        device.executeShellCommand("am broadcast -p $PKG -a $REQNOTIF_ACTION --ei count 1")

        // The notification posts from a background coroutine after the broadcast returns, and some OEM shades
        // (Samsung One UI) don't live-refresh a notification added after the shade was opened — so re-open the
        // shade on each attempt until the heads-up appears.
        val headsUp =
            requireNotNull(openShadeAndFind(str(R.string.notif_requests_title))) {
                "message-request heads-up not found in the notification shade"
            }

        // Tapping the heads-up deep-links to the Requests inbox (MainActivity EXTRA_ROUTE -> Routes.MESSAGE_REQUESTS).
        headsUp.click()

        assertText(str(R.string.message_requests_title))
        assertTag("request_row_$STRANGER_NODE_ID")
        assertText(STRANGER_NAME)

        // Accept it → the request leaves the inbox, which is now empty.
        requireTag("request_accept_$STRANGER_NODE_ID").click()
        assertText(str(R.string.message_requests_empty))
    }

    /** Grant POST_NOTIFICATIONS on API 33+ (denied by default there); a no-op on 29–32 (install-time granted). */
    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        }
    }

    /**
     * (Re)opens the notification shade until a view containing [text] appears, then returns it — tolerating
     * the async post and OEM shades that don't live-refresh a notification added after the shade was opened.
     * Returns null after [SHADE_ATTEMPTS] attempts.
     */
    private fun openShadeAndFind(text: String): UiObject2? {
        repeat(SHADE_ATTEMPTS) {
            device.openNotification()
            waitText(text, SHADE_POLL_MS)?.let { return it }
        }
        return null
    }

    private companion object {
        const val REQNOTIF_ACTION = "app.getknit.knit.debug.REQNOTIF"

        // Mirrors DebugBridgeReceiver.handleReqNotif's first synthetic stranger (nodeId + name).
        const val STRANGER_NODE_ID = "strngr01"
        const val STRANGER_NAME = "Alex Stranger"

        // Re-open the shade up to 10× at 2.5s each (~25s budget) to surface the async, possibly-late heads-up.
        const val SHADE_ATTEMPTS = 10
        const val SHADE_POLL_MS = 2_500L
    }
}
