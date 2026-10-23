package app.getknit.knit.notifications

import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Knit's message sound as the platform holds it (ADR 2026-10.xukf): every channel in the Messages group plays
 * `res/raw/knit.ogg` through a URI that names the resource, and the App group's channels keep the system default.
 * Read back from the platform's channels, never from [NotificationChannels]' own builder.
 */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)

    // The group ids as the platform stores them: frozen once shipped, so a literal here, not the private constants.
    private fun channelsIn(group: String) = manager.notificationChannels.filter { it.group == group }

    @Before
    fun setUp() = NotificationChannels.ensure(app)

    @Test
    fun everyMessageChannelPlaysKnitsSound() {
        val channels = channelsIn("knit_grp_messages")
        assertTrue(channels.map { it.id }.containsAll(listOf(NotificationChannels.NEARBY, NotificationChannels.DMS)))
        for (channel in channels) {
            assertEquals(channel.id, NotificationChannels.messageSound(app), channel.sound)
        }
    }

    @Test
    fun theSoundIsNamedByResourceNameAndResolvesToTheOgg() {
        val uri = NotificationChannels.messageSound(app)
        assertEquals("android.resource://${app.packageName}/raw/knit", uri.toString())
        val (type, name) = uri.pathSegments
        assertEquals(R.raw.knit, app.resources.getIdentifier(name, type, uri.authority))
        val head = app.resources.openRawResource(R.raw.knit).use { it.readNBytes(4) }
        assertEquals("OggS", String(head, Charsets.US_ASCII))
    }

    @Test
    fun theAppChannelsKeepTheSystemDefault() {
        val channels = channelsIn("knit_grp_app")
        assertTrue(channels.map { it.id }.containsAll(listOf(NotificationChannels.STATUS, NotificationChannels.ALERTS)))
        for (channel in channels) {
            assertEquals(channel.id, Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        }
    }
}
