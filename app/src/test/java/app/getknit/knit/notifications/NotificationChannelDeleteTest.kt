package app.getknit.knit.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.call.CallNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The clean-up of channels a bump left behind ([NotificationChannels.delete]) on a platform that refuses the delete the
 * way a phone did on Play Console (v2.12.0): `deleteNotificationChannel` came back as a NullPointerException from
 * the system's foreground-service check while `SttDownloadService` was starting, and the service crashed. Here every
 * delete throws that exception.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [NotificationChannelDeleteTest.RefusedDeletes::class])
class NotificationChannelDeleteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val asked get() = Shadow.extract<RefusedDeletes>(manager).asked

    // The ids as phones from older versions still hold them: literals, since the constants are private and frozen.
    private fun leftOver(vararg ids: String) =
        ids.forEach { manager.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT)) }

    @Test
    fun aRefusedDeleteNeitherThrowsNorKeepsTheNewChannelsFromBeingMade() {
        leftOver("knit_messages", "knit_msg_nearby", "knit_calls")

        NotificationChannels.ensure(app)
        CallNotifications.ensureChannels(app)

        assertEquals(listOf("knit_messages", "knit_msg_nearby", "knit_calls"), asked)
        assertNotNull(manager.getNotificationChannel(NotificationChannels.NEARBY))
        assertNotNull(manager.getNotificationChannel(NotificationChannels.STATUS))
        assertNotNull(manager.getNotificationChannel(CallNotifications.CHANNEL_RING))
    }

    @Test
    fun noDeleteIsAskedOfAChannelThePhoneNoLongerHolds() {
        NotificationChannels.ensure(app)
        CallNotifications.ensureChannels(app)

        assertEquals(emptyList<String>(), asked)
    }

    /** Throws on every delete, as `INotificationManager.deleteNotificationChannel` did, and records what was asked. */
    @Implements(NotificationManager::class)
    class RefusedDeletes : ShadowNotificationManager() {
        val asked = mutableListOf<String>()

        @Implementation
        override fun deleteNotificationChannel(channelId: String) {
            asked += channelId
            throw NullPointerException(
                "Attempt to invoke virtual method 'java.lang.String android.app.Notification.getChannelId()' on a null " +
                    "object reference",
            )
        }
    }
}
