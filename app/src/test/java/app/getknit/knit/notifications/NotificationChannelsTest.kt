package app.getknit.knit.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Knit's sounds as the platform holds them (ADR 2026-10.xukf): every channel in the Messages group plays
 * `res/raw/knit.ogg` but Mentions, which plays `knit_mention.ogg`; the open-to-chat cue plays
 * `knit_open_to_chat.ogg`; the rest of the App group keeps the system default. Each through a URI that names the
 * resource. Read back from the platform's channels, never from [NotificationChannels]' own builder.
 */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)

    // The group ids as the platform stores them: frozen once shipped, so a literal here, not the private constants.
    private fun channelsIn(group: String) = manager.notificationChannels.filter { it.group == group }

    @Test
    fun everyMessageChannelButMentionsPlaysKnitsSound() {
        NotificationChannels.ensure(app)
        val channels = channelsIn("knit_grp_messages").filter { it.id != NotificationChannels.MENTIONS }
        assertTrue(channels.map { it.id }.containsAll(listOf(NotificationChannels.NEARBY, NotificationChannels.DMS)))
        for (channel in channels) {
            assertEquals(channel.id, NotificationChannels.messageSound(app), channel.sound)
        }
    }

    @Test
    fun mentionsAndTheOpenToChatCuePlayTheirOwnSounds() {
        NotificationChannels.ensure(app)
        assertEquals(
            "android.resource://${app.packageName}/raw/knit_mention",
            manager.getNotificationChannel(NotificationChannels.MENTIONS).sound.toString(),
        )
        assertEquals(
            "android.resource://${app.packageName}/raw/knit_open_to_chat",
            manager.getNotificationChannel(NotificationChannels.OPEN_TO_CHAT).sound.toString(),
        )
        for (sound in listOf(R.raw.knit_mention, R.raw.knit_open_to_chat)) {
            val head = app.resources.openRawResource(sound).use { it.readNBytes(4) }
            assertEquals("OggS", String(head, Charsets.US_ASCII))
        }
    }

    @Test
    fun theBumpCarriesOverAChannelTheUserTurnedDown() {
        // The ids the soundless channels shipped under, frozen: literals, not the private constants.
        manager.createNotificationChannel(NotificationChannel("knit_open_to_chat", "x", NotificationManager.IMPORTANCE_NONE))
        manager.createNotificationChannel(NotificationChannel("knit_msg_mentions", "x", NotificationManager.IMPORTANCE_LOW))
        NotificationChannels.ensure(app)
        assertNull(manager.getNotificationChannel("knit_open_to_chat"))
        assertNull(manager.getNotificationChannel("knit_msg_mentions"))
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(NotificationChannels.OPEN_TO_CHAT).importance,
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(NotificationChannels.MENTIONS).importance)
    }

    @Test
    fun theBumpCarriesOverTheUsersSoundVibrationLightsAndBadge() {
        val picked = Uri.parse("content://media/internal/audio/media/42")
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_mentions", "x", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the user chose None
                enableVibration(true) // Settings' Vibrate toggle: on, with no pattern
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel("knit_open_to_chat", "x", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(picked, null) // a sound the user picked
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 50, 100)
                enableLights(true)
                lightColor = 0xFF00FF00.toInt()
            },
        )
        NotificationChannels.ensure(app)
        val mentions = manager.getNotificationChannel(NotificationChannels.MENTIONS)
        assertNull(mentions.sound)
        assertTrue(mentions.shouldVibrate())
        assertFalse(mentions.canShowBadge())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, mentions.importance)
        val open = manager.getNotificationChannel(NotificationChannels.OPEN_TO_CHAT)
        assertEquals(picked, open.sound)
        assertTrue(open.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 100, 50, 100), open.vibrationPattern)
        assertTrue(open.shouldShowLights())
        assertEquals(0xFF00FF00.toInt(), open.lightColor)
    }

    @Test
    fun theBumpKeepsVibrationOffWhenAPatternIsStillStored() {
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_mentions", "x", NotificationManager.IMPORTANCE_HIGH).apply {
                vibrationPattern = longArrayOf(0, 100, 50, 100) // a pattern left stored...
                enableVibration(false) // ...then vibration switched off
            },
        )
        NotificationChannels.ensure(app)
        assertFalse(manager.getNotificationChannel(NotificationChannels.MENTIONS).shouldVibrate())
    }

    @Test
    fun theBumpReplacesASoundTheUserNeverChose() {
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_mentions", "x", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(NotificationChannels.messageSound(app), null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel("knit_open_to_chat", "x", NotificationManager.IMPORTANCE_DEFAULT),
        )
        NotificationChannels.ensure(app)
        assertEquals(NotificationChannels.mentionSound(app), manager.getNotificationChannel(NotificationChannels.MENTIONS).sound)
        assertEquals(
            NotificationChannels.openToChatSound(app),
            manager.getNotificationChannel(NotificationChannels.OPEN_TO_CHAT).sound,
        )
    }

    @Test
    fun everyReplacedChannelGivesWayToItsSuccessor() {
        // The ids the channels shipped under before the sound bump, frozen: literals, not the constants.
        val replaced =
            mapOf(
                "knit_msg_nearby" to NotificationChannels.NEARBY,
                "knit_msg_groups_v2" to NotificationChannels.GROUPS,
                "knit_msg_dms" to NotificationChannels.DMS,
                "knit_msg_mentions" to NotificationChannels.MENTIONS,
                "knit_msg_meshtastic" to NotificationChannels.MESHTASTIC,
                "knit_msg_requests_v2" to NotificationChannels.REQUESTS,
                "knit_open_to_chat" to NotificationChannels.OPEN_TO_CHAT,
            )
        for (old in replaced.keys) {
            manager.createNotificationChannel(NotificationChannel(old, "x", NotificationManager.IMPORTANCE_DEFAULT))
        }
        NotificationChannels.ensure(app)
        for ((old, new) in replaced) {
            assertNull(old, manager.getNotificationChannel(old))
            assertNotNull(new, manager.getNotificationChannel(new))
            assertTrue(new, new != old)
        }
    }

    @Test
    fun aConversationSetApartMovesWithItsParent() {
        val picked = Uri.parse("content://media/internal/audio/media/7")
        manager.createNotificationChannel(NotificationChannel("knit_msg_dms", "x", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("knit_msg_groups_v2", "x", NotificationManager.IMPORTANCE_HIGH))
        // Silenced in its own settings, sound left as it came: the stock default.
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_dms : ana", "Ana", NotificationManager.IMPORTANCE_LOW).apply {
                setConversationId("knit_msg_dms", "ana")
            },
        )
        // A sound of its own.
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_groups_v2 : climbing", "Climbing", NotificationManager.IMPORTANCE_HIGH).apply {
                setConversationId("knit_msg_groups_v2", "climbing")
                setSound(picked, null)
            },
        )
        NotificationChannels.ensure(app)

        assertNull(manager.getNotificationChannel("knit_msg_dms : ana"))
        assertNull(manager.getNotificationChannel("knit_msg_groups_v2 : climbing"))
        val ana = manager.notificationChannels.single { it.conversationId == "ana" }
        assertEquals(NotificationChannels.DMS, ana.parentChannelId)
        assertEquals("Ana", ana.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, ana.importance)
        assertEquals(NotificationChannels.messageSound(app), ana.sound)
        val climbing = manager.notificationChannels.single { it.conversationId == "climbing" }
        assertEquals(NotificationChannels.GROUPS, climbing.parentChannelId)
        assertEquals(picked, climbing.sound)
    }

    @Test
    fun aConversationWhoseParentThisBuildHidesGoesWithIt() {
        // The commons is registered only when BuildConfig.COMMONS; either way its old conversations must not linger.
        manager.createNotificationChannel(NotificationChannel("knit_msg_commons", "x", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(
            NotificationChannel("knit_msg_commons : home", "Home", NotificationManager.IMPORTANCE_LOW).apply {
                setConversationId("knit_msg_commons", "home")
            },
        )
        NotificationChannels.ensure(app)
        assertNull(manager.getNotificationChannel("knit_msg_commons"))
        assertNull(manager.getNotificationChannel("knit_msg_commons : home"))
        val moved = manager.notificationChannels.filter { it.conversationId == "home" }
        assertEquals(if (BuildConfig.COMMONS) listOf(NotificationChannels.COMMONS) else emptyList(), moved.map { it.parentChannelId })
    }

    @Test
    fun theBumpKeepsTheDefaultsWhenTheUserLeftThemAlone() {
        manager.createNotificationChannel(
            NotificationChannel("knit_open_to_chat", "x", NotificationManager.IMPORTANCE_DEFAULT),
        )
        NotificationChannels.ensure(app)
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(NotificationChannels.OPEN_TO_CHAT).importance,
        )
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(NotificationChannels.MENTIONS).importance)
    }

    @Test
    fun theSoundIsNamedByResourceNameAndResolvesToTheOgg() {
        NotificationChannels.ensure(app)
        val uri = NotificationChannels.messageSound(app)
        assertEquals("android.resource://${app.packageName}/raw/knit", uri.toString())
        val (type, name) = uri.pathSegments
        assertEquals(R.raw.knit, app.resources.getIdentifier(name, type, uri.authority))
        val head = app.resources.openRawResource(R.raw.knit).use { it.readNBytes(4) }
        assertEquals("OggS", String(head, Charsets.US_ASCII))
    }

    @Test
    fun theOtherAppChannelsKeepTheSystemDefault() {
        NotificationChannels.ensure(app)
        val channels = channelsIn("knit_grp_app").filter { it.id != NotificationChannels.OPEN_TO_CHAT }
        assertTrue(channels.map { it.id }.containsAll(listOf(NotificationChannels.STATUS, NotificationChannels.ALERTS)))
        for (channel in channels) {
            assertEquals(channel.id, Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        }
    }
}
