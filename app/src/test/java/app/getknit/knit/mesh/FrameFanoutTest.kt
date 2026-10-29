package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fan-out predicates split every frame across the planes that have **no data path**:
 * [shouldFastFanout] (the room + cleartext metadata, fanned to every neighbor), [shouldFastSend] (sealed
 * DM-form chat, targeted at its addressee). The LoRa plane's long-range arm is gone (ADR 2026-10.x9fa): our own DMs
 * ride it compact, offered by their sender. The load-bearing invariant is that the two *coordination-plane* arms are
 * disjoint: a frame that
 * rode both would be fanned at every neighbor and unicast at one of them, doubling the ~255 B channel's
 * load for one frame.
 */
class FrameFanoutTest {
    private fun env(
        type: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
    ) = RelayEnvelope(type = type, id = "id", senderId = "alice", recipientId = recipientId, group = group, payload = ByteArray(0))

    private val group = GroupInfo(id = "g-x", members = listOf("alice", "bob"), createdBy = "alice")

    /** Every shape the predicates are asked about, so the disjointness sweep can't silently miss one. */
    private fun everyShape(): List<RelayEnvelope> =
        buildList {
            for (type in listOf(
                FrameType.CHAT,
                FrameType.REACTION,
                FrameType.RECEIPT,
                FrameType.PROFILE,
                FrameType.TYPING,
                FrameType.BLOB_REQ,
                FrameType.KEY_REQ,
            )) {
                add(env(type))
                add(env(type, recipientId = "bob"))
                add(env(type, group = group))
            }
        }

    @Test
    fun aDmFormChatRidesTheTargetedCoordinationArm() {
        val dm = env(FrameType.CHAT, recipientId = "bob")
        assertTrue(shouldFastSend(dm))
        assertFalse(shouldFastFanout(dm)) // never the fanned arm — it is addressed to exactly one node
    }

    @Test
    fun theRoomAndCleartextMetadataRideOnlyTheFannedArm() {
        for (e in listOf(env(FrameType.CHAT), env(FrameType.REACTION), env(FrameType.RECEIPT), env(FrameType.PROFILE))) {
            assertTrue(e.type, shouldFastFanout(e))
            assertFalse(e.type, shouldFastSend(e))
        }
    }

    @Test
    fun groupFormChatRidesNeitherCoordinationArm() {
        val g = env(FrameType.CHAT, group = group)
        assertFalse(shouldFastFanout(g))
        assertFalse(shouldFastSend(g)) // sealed to a sender-key chain, and past the channel's budget
    }

    @Test
    fun pointToPointRequestsRideNeither() {
        // TYPING already has its own explicit fastSend at the MeshManager call site and must not be
        // double-sent by the predicate; BLOB_REQ/KEY_REQ are single-hop and never fanned.
        for (e in listOf(env(FrameType.BLOB_REQ), env(FrameType.KEY_REQ), env(FrameType.TYPING, recipientId = "bob"))) {
            assertFalse(e.type, shouldFastFanout(e))
            assertFalse(e.type, shouldFastSend(e))
        }
    }

    @Test
    fun theTwoCoordinationPlaneArmsAreDisjoint() {
        val both = everyShape().filter { shouldFastFanout(it) && shouldFastSend(it) }
        assertEquals(emptyList<RelayEnvelope>(), both)
    }

    /** A DM-form frame always names the peer the targeted send needs; the call sites depend on it. */
    @Test
    fun everyFastSendFrameCarriesARecipient() {
        for (e in everyShape().filter { shouldFastSend(it) }) {
            assertTrue(e.type, e.recipientId != null)
        }
    }
}
