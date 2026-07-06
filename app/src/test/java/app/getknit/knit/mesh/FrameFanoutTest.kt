package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shouldFastFanout` decides which frames also ride the ~255 B coordination-plane fast path: the plaintext
 * broadcast room + the cleartext metadata frames, but never an E2E DM/group chat (wrapped keys won't fit)
 * nor the point-to-point/typing frames.
 */
class FrameFanoutTest {
    private fun env(
        type: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(
        type = type,
        id = "id",
        senderId = "s",
        recipientId = recipientId,
        group = group,
        payload = ByteArray(0),
    )

    @Test
    fun broadcastRoomChatFansOut() {
        assertTrue(shouldFastFanout(env(FrameType.CHAT)))
    }

    @Test
    fun dmChatDoesNotFanOut() {
        assertFalse(shouldFastFanout(env(FrameType.CHAT, recipientId = "bob")))
    }

    @Test
    fun groupChatDoesNotFanOut() {
        val group = GroupInfo(id = "g-1", members = listOf("a", "b"), createdBy = "a")
        assertFalse(shouldFastFanout(env(FrameType.CHAT, group = group)))
    }

    @Test
    fun cleartextMetadataFramesFanOut() {
        listOf(FrameType.REACTION, FrameType.RECEIPT, FrameType.PROFILE, FrameType.GROUP_UPDATE, FrameType.GROUP_LEAVE)
            .forEach { type -> assertTrue("$type should fast-fanout", shouldFastFanout(env(type))) }
    }

    @Test
    fun pointToPointRequestsAndTypingCuesDoNotFanOut() {
        listOf(FrameType.BLOB_REQ, FrameType.KEY_REQ, FrameType.TYPING)
            .forEach { type -> assertFalse("$type should not fast-fanout", shouldFastFanout(env(type))) }
    }
}
