package app.getknit.knit.ui.call

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The call ring as the platform holds it (ADR 2026-10.xukf): the ring channel plays `res/raw/knit_ring.ogg` through a
 * URI that names the resource, under a fresh id, and the channel that rang the phone's default ringtone is gone.
 */
@RunWith(AndroidJUnit4::class)
class CallNotificationsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Test
    fun theRingChannelPlaysKnitsRing() {
        CallNotifications.ensureChannels(app)
        val sound = manager.getNotificationChannel(CallNotifications.CHANNEL_RING).sound
        assertEquals("android.resource://${app.packageName}/raw/knit_ring", sound.toString())
        val (type, name) = sound.pathSegments
        assertEquals(R.raw.knit_ring, app.resources.getIdentifier(name, type, sound.authority))
        val head = app.resources.openRawResource(R.raw.knit_ring).use { it.readNBytes(4) }
        assertEquals("OggS", String(head, Charsets.US_ASCII))
    }

    /** The id the ring had before it was Knit's own; a channel's sound cannot change, so that channel must go. */
    @Test
    fun theDefaultRingtoneChannelIsDeleted() {
        manager.createNotificationChannel(NotificationChannel("knit_calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
        CallNotifications.ensureChannels(app)
        assertNull(manager.getNotificationChannel("knit_calls"))
        assertNotNull(manager.getNotificationChannel(CallNotifications.CHANNEL_RING))
    }
}
