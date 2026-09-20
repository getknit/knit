package app.getknit.knit.ui.signout

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.mesh.MeshService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The pre-wipe checklist of "Sign out here" (ADR 2026-09.ypcc): the mesh service is stopped through the
 * plain door and every notification is gone before the platform clears the app's data. The wipe itself is
 * `ActivityManager.clearApplicationUserData`, which Robolectric answers with false and a kept process —
 * the same call a real phone acts on.
 */
@RunWith(RobolectricTestRunner::class)
class SignOutTest {
    @Test
    fun stopsTheMeshAndClearsNotificationsBeforeTheWipe() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.notify(7, Notification.Builder(app, "threads").setContentTitle("a thread").build())
        assertEquals(1, shadowOf(notifications).size())

        SignOut.here(app)

        assertEquals(ComponentName(app, MeshService::class.java), shadowOf(app).nextStoppedService.component)
        assertEquals("nothing named after the old identity survives", 0, shadowOf(notifications).size())
    }
}
