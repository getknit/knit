package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.frameIdOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal set of `docs/SPOOL_PROTOCOL.md` §7.8 (C-7.8-13, C-7.8-14): a `typing` cue, on a pair's own DM scope or a
 * group's, never stored. [ScopeFramesTest]'s §4.4 matrix for the lane a spool never stores and no ticket gates, both
 * directions, every wrong scope kind, and the opened wire.
 */
class ScopeSignalsTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbe"
    private val carol = "ccccccccccccccccccccccccce"
    private val mallory = "mmmmmmmmmmmmmmmmmmmmmmmmmm"
    private val root = ByteArray(32) { it.toByte() }

    private val groupId = "g-00112233445566778899aabb"
    private val founding = setOf(alice, bob, carol)

    private fun scope(secret: ByteArray = root) =
        Scope(
            id = ScopeCrypto.dmScopeId(secret, alice, bob),
            keys = ScopeCrypto.dmSealKeys(secret, alice, bob),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            peerId = bob,
        )

    private fun groupScope() =
        Scope(
            id = ScopeCrypto.groupScopeId(root, groupId, 1),
            keys = ScopeCrypto.groupSealKeys(root, groupId, 1),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            groupId = groupId,
            roster = founding,
        )

    private fun pairScope() =
        Scope(id = ByteArray(32) { 7 }, keys = scope().keys, bounds = ScopeRegistry.DEFAULT_BOUNDS, peerId = bob, pair = true)

    private val commonsSecret = ByteArray(32) { (it + 40).toByte() }

    private fun commonsScope(): Scope {
        val id = ScopeCrypto.commonsScopeId(commonsSecret)
        return Scope(
            id = id,
            keys = ScopeCrypto.commonsSealKeys(commonsSecret),
            bounds = ScopeRegistry.COMMONS_DEFAULT_BOUNDS,
            commonsId = "c-" + hex(id),
            spoolUrl = "wss://home.test/spool/v1",
        )
    }

    private fun talk(
        from: String,
        to: String,
    ) = RelayEnvelope(type = FrameType.TALK, id = "t1", senderId = from, recipientId = to, payload = ByteArray(24) { it.toByte() })

    private fun typing(
        from: String,
        to: String? = null,
        group: String? = null,
        id: String = frameIdOf("ty1"),
        payload: ByteArray = WireCodec.encodePayload(TypingContent(group)),
    ) = RelayEnvelope(type = FrameType.TYPING, id = id, senderId = from, sentAt = 7L, recipientId = to, payload = payload)

    @Test
    fun `the signal set takes a DM typing cue between the pair, in the direction named`() {
        assertTrue(ScopeFrames.sigEligible(typing(from = bob, to = alice), alice, scope(), inbound = true))
        assertTrue(ScopeFrames.sigEligible(typing(from = alice, to = bob), alice, scope(), inbound = false))
        // The wrong way round for the direction, our own cue coming back, a third party either way.
        assertFalse(ScopeFrames.sigEligible(typing(from = alice, to = bob), alice, scope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, to = alice), alice, scope(), inbound = false))
        assertFalse(ScopeFrames.sigEligible(typing(from = mallory, to = alice), alice, scope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, to = carol), alice, scope(), inbound = true))
        // A room cue (no recipient) on a DM scope names neither member as its recipient.
        assertFalse(ScopeFrames.sigEligible(typing(from = bob), alice, scope(), inbound = true))
    }

    @Test
    fun `the signal set takes a group typing cue from a member, for that group only`() {
        assertTrue(ScopeFrames.sigEligible(typing(from = bob, group = groupId), alice, groupScope(), inbound = true))
        assertTrue(ScopeFrames.sigEligible(typing(from = alice, group = groupId), alice, groupScope(), inbound = false))
        // Another group's cue, a non-member's, our own coming back, another member's going out, and one addressed to
        // somebody, which no group cue is.
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, group = "g-ffeeddccbbaa998877665544"), alice, groupScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = mallory, group = groupId), alice, groupScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = alice, group = groupId), alice, groupScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, group = groupId), alice, groupScope(), inbound = false))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, to = alice, group = groupId), alice, groupScope(), inbound = true))
        // A room cue has no group, so no group scope carries it.
        assertFalse(ScopeFrames.sigEligible(typing(from = bob), alice, groupScope(), inbound = true))
    }

    @Test
    fun `the signal set refuses every other type, the wrong scope kind and a payload that will not decode`() {
        val dmCueInGroup = typing(from = bob, to = alice)
        assertFalse("a DM cue never rides a group scope", ScopeFrames.sigEligible(dmCueInGroup, alice, groupScope(), inbound = true))
        assertFalse(
            "a group cue never rides a DM scope",
            ScopeFrames.sigEligible(typing(from = bob, to = alice, group = groupId), alice, scope(), inbound = true),
        )
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, to = alice), alice, pairScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob), alice, commonsScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = alice), alice, commonsScope(), inbound = false))
        // Every other type: a custodial chat, a cast's talk frame, a profile.
        assertFalse(ScopeFrames.sigEligible(dmFrame("c1", from = bob, to = alice).envelope, alice, scope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(talk(from = bob, to = alice), alice, scope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(profileFrame("p1", bob).envelope, alice, scope(), inbound = true))
        // The envelope's roster field is never a typing cue's, and a payload that will not decode is no cue at all.
        val rostered =
            RelayEnvelope(
                type = FrameType.TYPING,
                id = frameIdOf("ty2"),
                senderId = bob,
                group = GroupInfo(id = groupId, members = founding.toList(), createdBy = alice),
                payload = WireCodec.encodePayload(TypingContent(groupId)),
            )
        assertFalse(ScopeFrames.sigEligible(rostered, alice, groupScope(), inbound = true))
        assertFalse(ScopeFrames.sigEligible(typing(from = bob, to = alice, payload = byteArrayOf(0x42)), alice, scope(), inbound = true))
    }

    @Test
    fun `an opened sig is a delivery to this phone alone, its signature kept`() {
        val sig = ByteArray(ScopeCrypto.SIG_BYTES) { 3 }
        val signed = WireCodec.encodeEnvelope(typing(from = bob, group = groupId))
        val opened = ScopeFrames.openSig(groupScope(), alice, ScopeFrames.sealSig(groupScope(), sig, signed))!!
        assertEquals(0, opened.wire.ttl)
        assertEquals(0, opened.wire.hops)
        assertFalse(opened.wire.relay)
        assertArrayEquals(sig, opened.wire.sig)
        assertArrayEquals(signed, opened.wire.signed)
        assertEquals(FrameType.TYPING, opened.env.type)
    }

    @Test
    fun `a sig that fails its seal or its set opens to nothing`() {
        val sig = ByteArray(ScopeCrypto.SIG_BYTES) { 3 }
        val inbound = WireCodec.encodeEnvelope(typing(from = bob, to = alice))
        // Another scope's key, our own cue coming back, a talk frame sent as a sig, and a cast and a blob passed as sigs.
        assertNull(ScopeFrames.openSig(scope(), alice, ScopeFrames.sealSig(scope(secret = ByteArray(32) { 9 }), sig, inbound)))
        assertNull(
            ScopeFrames.openSig(
                scope(),
                alice,
                ScopeFrames.sealSig(scope(), sig, WireCodec.encodeEnvelope(typing(from = alice, to = bob))),
            ),
        )
        assertNull(ScopeFrames.openSig(scope(), alice, ScopeFrames.sealSig(scope(), sig, WireCodec.encodeEnvelope(talk(bob, alice)))))
        assertNull(ScopeFrames.openSig(scope(), alice, ScopeFrames.sealCast(scope(), sig, inbound)))
        assertNull(ScopeFrames.openSig(scope(), alice, ScopeFrames.seal(scope(), sig, inbound).blob))
        // And the other way: a sig never opens as a cast.
        assertNull(ScopeFrames.openCast(scope(), alice, ScopeFrames.sealSig(scope(), sig, WireCodec.encodeEnvelope(talk(bob, alice)))))
    }
}
