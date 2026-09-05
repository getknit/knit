package app.getknit.knit.data.message

import app.getknit.knit.ui.msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Meshtastic room's title rule, shared by the thread header and the chat-list row. */
class MeshRoomNamingTest {
    private val rows =
        listOf(
            msg(senderId = "me", sentAt = 1, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = "LongFast"),
            msg(senderId = "me", sentAt = 2, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = "LongTurbo"),
            msg(senderId = "me", sentAt = 3, conversationId = Conversations.MESHTASTIC, originNode = 1, originChannel = ""),
        )

    @Test
    fun `the live board wins, then the newest post that named a channel, then nothing`() {
        assertEquals("MediumFast", meshRoomChannel("MediumFast", rows))
        assertEquals("a blank live name says nothing", "LongTurbo", meshRoomChannel("", rows))
        assertEquals("LongTurbo", meshRoomChannel(null, rows))
        assertNull(meshRoomChannel(null, emptyList()))
        assertNull(meshRoomChannel(null, listOf(rows.last())))
    }
}
