package app.getknit.knit.ui.call

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * When the "Allow full-screen calls" notification goes up. It used to be posted at every process start while the grant
 * was off, so a reboot re-asked however the person had answered; now only a call that has just rung without the grant
 * asks, and onboarding is the one place Knit asks unprompted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CallControllerFullScreenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val calls = MutableStateFlow<CallState?>(null)

    @Before
    fun grantNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun TestScope.start(canRingFullScreen: Boolean?) {
        CallController(
            context = app,
            calls = calls,
            missedCalls = emptyFlow(),
            scope = backgroundScope,
            faceOf = { CallFace("Ada", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) },
            alertMs = 30_000,
            canRingFullScreen = { canRingFullScreen },
        ).start()
        runCurrent()
    }

    private fun TestScope.ring() {
        calls.value = CallState(id = "call-1", peerId = "peer-1", outgoing = false, phase = CallPhase.Alerting, startedAt = 0)
        runCurrent()
    }

    private fun posted(tag: String) = notifications.getNotification(tag, CallNotifications.ID)

    /** `start()` posted the old ask synchronously, before launching anything, so reading right after it is not early. */
    @Test
    fun startingWithoutTheGrantAsksNothing() =
        runTest {
            start(canRingFullScreen = false)
            assertNull("start asked for full-screen calls", posted(CallNotifications.TAG_FULL_SCREEN))
        }

    @Test
    fun aCallThatRingsWithoutTheGrantAsksForIt() =
        runTest {
            start(canRingFullScreen = false)
            assertNull(posted(CallNotifications.TAG_FULL_SCREEN))
            ring()
            assertNotNull("the ring was not posted", posted(CallNotifications.TAG_RING))
            assertNotNull("a ring without the grant did not ask", posted(CallNotifications.TAG_FULL_SCREEN))
        }

    @Test
    fun aCallThatRingsWithTheGrantAsksNothing() =
        runTest {
            start(canRingFullScreen = true)
            ring()
            assertNotNull("the ring was not posted", posted(CallNotifications.TAG_RING))
            assertNull("a ring with the grant asked", posted(CallNotifications.TAG_FULL_SCREEN))
        }

    /** Below Android 14 every call may ring full screen: there is nothing to ask for. */
    @Test
    fun aCallThatRingsWhereThereIsNothingToAskAsksNothing() =
        runTest {
            start(canRingFullScreen = null)
            ring()
            assertNotNull("the ring was not posted", posted(CallNotifications.TAG_RING))
            assertNull("a ring with nothing to ask asked", posted(CallNotifications.TAG_FULL_SCREEN))
        }
}
