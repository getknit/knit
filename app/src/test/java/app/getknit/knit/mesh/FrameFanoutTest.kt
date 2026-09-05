package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two fan-out predicates split every frame between the coordination plane ([shouldFastFanout]: the room +
 * cleartext metadata) and the long-range plane ([shouldLongRangeFanout]: sealed DM-form chat, ADR 039), and
 * never both — a frame that rode both would reach the LoRa child twice.
 */
class FrameFanoutTest {
    private fun env(
        type: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(type = type, id = "id", senderId = "alice", recipientId = recipientId, group = group, payload = ByteArray(0))

    private val group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice")

    @Test
    fun aDmFormChatRidesOnlyTheLongRangePlane() {
        val dm = env(FrameType.CHAT, recipientId = "bob")
        assertTrue(shouldLongRangeFanout(dm))
        assertFalse(shouldFastFanout(dm))
    }

    @Test
    fun theRoomAndCleartextMetadataRideOnlyTheCoordinationPlane() {
        for (e in listOf(env(FrameType.CHAT), env(FrameType.REACTION), env(FrameType.RECEIPT), env(FrameType.PROFILE))) {
            assertTrue(e.type, shouldFastFanout(e))
            assertFalse(e.type, shouldLongRangeFanout(e))
        }
    }

    @Test
    fun groupFormChatRidesNeither() {
        val g = env(FrameType.CHAT, group = group)
        assertFalse(shouldFastFanout(g))
        assertFalse(shouldLongRangeFanout(g))
    }

    @Test
    fun pointToPointRequestsRideNeither() {
        for (e in listOf(env(FrameType.BLOB_REQ), env(FrameType.KEY_REQ), env(FrameType.TYPING, recipientId = "bob"))) {
            assertFalse(e.type, shouldFastFanout(e))
            assertFalse(e.type, shouldLongRangeFanout(e))
        }
    }
}
