package app.getknit.knit

import app.getknit.knit.mesh.PendingInbound
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PendingInbound]'s park/replay buffer on the JVM. It's a pure data structure (no transport,
 * unlike [KeyExchangeTest]), so the parking/release/TTL/cap logic is asserted directly with an injected
 * clock. End-to-end "the frame reaches the user once its key arrives" is verified on devices, since the
 * replay step (`MeshManager.onDeliver`) needs the Android inbound pipeline.
 */
class PendingInboundTest {

    @Test
    fun heldFrameIsReleasedForItsSender() {
        var clock = 0L
        val buffer = PendingInbound(now = { clock })
        buffer.hold(wireFor("m1", "alice"), envFor("m1", "alice"), fromNodeId = "n")

        val released = buffer.release("alice")

        assertEquals(1, released.size)
        assertEquals("m1", released.first().env.id)
        assertEquals("n", released.first().fromNodeId)
        // Once released it's gone — a second release yields nothing.
        assertTrue(buffer.release("alice").isEmpty())
    }

    @Test
    fun releaseReturnsOnlyTheMatchingSenderOldestFirst() {
        var clock = 0L
        val buffer = PendingInbound(now = { clock })
        buffer.hold(wireFor("a1", "alice"), envFor("a1", "alice"), "n"); clock += 1
        buffer.hold(wireFor("b1", "bob"), envFor("b1", "bob"), "n"); clock += 1
        buffer.hold(wireFor("a2", "alice"), envFor("a2", "alice"), "n")

        assertEquals(listOf("a1", "a2"), buffer.release("alice").map { it.env.id })
        // Bob's frame is untouched by Alice's release.
        assertEquals(listOf("b1"), buffer.release("bob").map { it.env.id })
    }

    @Test
    fun duplicateFrameIdIsParkedOnce() {
        var clock = 0L
        val buffer = PendingInbound(now = { clock })
        buffer.hold(wireFor("m1", "alice"), envFor("m1", "alice"), "n")
        buffer.hold(wireFor("m1", "alice"), envFor("m1", "alice"), "n")

        assertEquals(1, buffer.release("alice").size)
    }

    @Test
    fun perSenderCapRejectsBeyondTheLimit() {
        var clock = 0L
        val buffer = PendingInbound(now = { clock }, maxPerSender = 2)
        repeat(5) { i ->
            buffer.hold(wireFor("m$i", "alice"), envFor("m$i", "alice"), "n")
            clock += 1
        }

        assertEquals(listOf("m0", "m1"), buffer.release("alice").map { it.env.id })
    }

    @Test
    fun globalCapEvictsOldestFirst() {
        var clock = 0L
        // Distinct senders so the per-sender cap doesn't fire; the global cap of 2 is the bound.
        val buffer = PendingInbound(now = { clock }, maxFrames = 2)
        buffer.hold(wireFor("m0", "s0"), envFor("m0", "s0"), "n"); clock += 1
        buffer.hold(wireFor("m1", "s1"), envFor("m1", "s1"), "n"); clock += 1
        buffer.hold(wireFor("m2", "s2"), envFor("m2", "s2"), "n") // evicts the oldest, m0

        assertTrue("oldest frame evicted under the global cap", buffer.release("s0").isEmpty())
        assertEquals(listOf("m1"), buffer.release("s1").map { it.env.id })
        assertEquals(listOf("m2"), buffer.release("s2").map { it.env.id })
    }

    @Test
    fun sweepExpiredDropsFramesPastTtlButKeepsFresh() {
        var clock = 0L
        val buffer = PendingInbound(now = { clock }, holdTtlMs = 100)
        buffer.hold(wireFor("old", "alice"), envFor("old", "alice"), "n")
        clock = 80
        buffer.hold(wireFor("new", "alice"), envFor("new", "alice"), "n")

        clock = 150 // "old" parked at 0 is now past the 100ms TTL; "new" parked at 80 is not
        assertEquals(1, buffer.sweepExpired())
        assertEquals(listOf("new"), buffer.release("alice").map { it.env.id })
    }

    private fun envFor(id: String, senderId: String): RelayEnvelope =
        RelayEnvelope(type = FrameType.CHAT, id = id, senderId = senderId, payload = ByteArray(0))

    private fun wireFor(id: String, senderId: String): WireEnvelope =
        WireEnvelope(sig = byteArrayOf(1), signed = WireCodec.encodeEnvelope(envFor(id, senderId)))
}
