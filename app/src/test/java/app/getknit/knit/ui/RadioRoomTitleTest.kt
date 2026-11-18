package app.getknit.knit.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A radio room's title ([radioRoomTitle]): the network always leads, so a bare channel name ("Public") beside the room
 * glyph never reads as one of Knit's own channels, and the title starts the same with the board away.
 */
@RunWith(AndroidJUnit4::class)
class RadioRoomTitleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aKnownChannelFollowsTheNetworksName() {
        assertEquals("MeshCore (Public)", radioRoomTitle(context, R.string.meshcore_title, "Public"))
        assertEquals("Meshtastic (LongFast)", radioRoomTitle(context, R.string.meshtastic_title, "LongFast"))
        assertEquals("the board's own word for slot 0", "Meshtastic (Hikers)", radioRoomTitle(context, R.string.meshtastic_title, "Hikers"))
    }

    @Test
    fun withNoChannelKnownTheTitleIsTheNetworkAlone() {
        assertEquals("MeshCore", radioRoomTitle(context, R.string.meshcore_title, null))
        assertEquals("a blank name is no name", "Meshtastic", radioRoomTitle(context, R.string.meshtastic_title, "  "))
    }

    @Test
    fun aChannelNamedForTheNetworkIsNotRepeated() {
        assertEquals("MeshCore", radioRoomTitle(context, R.string.meshcore_title, "meshcore"))
        assertEquals(
            "surrounding spaces are not part of the name",
            "MeshCore (Public)",
            radioRoomTitle(context, R.string.meshcore_title, " Public "),
        )
    }

    @Test
    fun aBidiOverrideInTheChannelNameCannotReorderTheNetworkPrefix() {
        // A Meshtastic slot-0 name comes raw from the board's channel table, which a shared channel URL can set.
        assertEquals("Meshtastic (LongFast)", radioRoomTitle(context, R.string.meshtastic_title, "\u202ELongFast"))
        assertEquals(
            "one line, no controls",
            "Meshtastic (Long Fast)",
            radioRoomTitle(context, R.string.meshtastic_title, "Long\u0000\nFast"),
        )
        assertEquals("nothing printable is no name", "Meshtastic", radioRoomTitle(context, R.string.meshtastic_title, "\u202E\u2066"))
    }
}
