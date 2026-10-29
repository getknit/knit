package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraFramePolicyTest {
    private fun env(
        type: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
        sentAt: Long = 0L,
    ) = RelayEnvelope(
        type = type,
        id = "id",
        senderId = "alice",
        sentAt = sentAt,
        recipientId = recipientId,
        group = group,
        payload = ByteArray(0),
    )

    private fun fanout(env: RelayEnvelope) = LoraFramePolicy.eligible(env)

    /**
     * The admit rule (ADR 2026-10.rp3s): the board airs only what this phone's user wrote. One predicate, so the deferred
     * "Forward over LoRa" is one clause in one place; and nothing is ours before our own id is known.
     */
    @Test
    fun onlyOurOwnFramesAirAndNothingBeforeOurIdIsKnown() {
        assertTrue(LoraFramePolicy.airs("alice", selfId = "alice"))
        assertFalse("a pocket-mate's frame never airs", LoraFramePolicy.airs("bob", selfId = "alice"))
        assertFalse("nothing is ours before our id is read", LoraFramePolicy.airs("alice", selfId = null))
    }

    @Test
    fun aBroadcastRoomChatFansOutButItsReactionAndAProfileDoNot() {
        assertTrue(fanout(env(FrameType.CHAT)))
        // ADR 2026-10.rp3s: a room reaction is never worth the air, and no key bootstrap rides this plane.
        assertFalse("a room reaction never airs", fanout(env(FrameType.REACTION)))
        assertFalse("a profile never airs", fanout(env(FrameType.PROFILE)))
        assertTrue(LoraFramePolicy.isBroadcastRoomChat(env(FrameType.CHAT)))
        assertFalse(LoraFramePolicy.isBroadcastRoomChat(env(FrameType.CHAT, recipientId = "bob")))
    }

    @Test
    fun dmFormChatFansOutButGroupFormDoesNot() {
        // ADR 039: a sealed DM-form chat rides the long-range plane, whatever it really is (a DM, or a sealed
        // receipt/reaction/ctl riding as one — the policy cannot and must not tell them apart).
        assertTrue("a DM chat rides LoRa", fanout(env(FrameType.CHAT, recipientId = "bob")))
        assertTrue(LoraFramePolicy.isDmForm(env(FrameType.CHAT, recipientId = "bob")))
        assertFalse(LoraFramePolicy.isDmForm(env(FrameType.CHAT)))
        assertFalse(
            "a group chat is not carried — the plane has no group conversation",
            fanout(env(FrameType.CHAT, group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice"))),
        )
        assertFalse(
            "a group reaction is private",
            fanout(env(FrameType.REACTION, group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice"))),
        )
    }

    @Test
    fun typingGroupMetaRequestsAndReceiptsNeverRide() {
        assertFalse(fanout(env(FrameType.TYPING)))
        assertFalse(fanout(env(FrameType.GROUP_UPDATE)))
        assertFalse(fanout(env(FrameType.GROUP_LEAVE)))
        assertFalse(fanout(env(FrameType.KEY_REQ)))
        assertFalse(fanout(env(FrameType.BLOB_REQ)))
        // The room ✓✓ (a cleartext receipt) left with the targeted path (ADR 2026-10.rp3s).
        assertFalse(fanout(env(FrameType.RECEIPT, recipientId = "bob")))
    }

    @Test
    fun aChatOlderThanTheWindowIsNotFreshButAProfileStampNeverAges() {
        assertTrue(LoraFramePolicy.isFresh(env(FrameType.CHAT, sentAt = 0L), now = LoraFramePolicy.FRESH_MS))
        assertFalse(LoraFramePolicy.isFresh(env(FrameType.CHAT, sentAt = 0L), now = LoraFramePolicy.FRESH_MS + 1))
        assertTrue(LoraFramePolicy.isFresh(env(FrameType.PROFILE, sentAt = 0L), now = Long.MAX_VALUE / 2))
    }
}
