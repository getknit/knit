package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.bluetooth.SideCarousel
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [FrameKey] — the dedup key the Bluetooth plane's carriers share. */
class FrameKeyTest {
    private val env = RelayEnvelope(type = FrameType.CHAT, id = "id-1", senderId = "alice", payload = ByteArray(0))

    private fun wire(sig: ByteArray) = WireEnvelope(sig = sig, signed = byteArrayOf(1, 2, 3))

    @Test
    fun aSignedFrameKeysOnItsSignaturePrefix() {
        val sig = ByteArray(64) { it.toByte() }
        assertEquals("0001020304050607", FrameKey.of(wire(sig), env))
        assertEquals("0001020304050607", FrameKey.ofSigned(wire(sig)))
    }

    @Test
    fun aReSealKeepsItsIdButNotItsKey() {
        // A re-seal carries a fresh signature under the same id — the id would suppress it; the key must not.
        val a = FrameKey.of(wire(ByteArray(64) { 1 }), env)
        val b = FrameKey.of(wire(ByteArray(64) { 2 }), env)
        assertNotEquals(a, b)
    }

    @Test
    fun anUnsignedFrameKeysOnItsIdAndNeedsTheEnvelope() {
        assertEquals("u:id-1", FrameKey.of(wire(ByteArray(0)), env))
        assertNull(FrameKey.ofSigned(wire(ByteArray(0))))
    }

    @Test
    fun theSideChannelUsesTheSameKey() {
        val w = wire(ByteArray(64) { 7 })
        assertEquals(FrameKey.of(w, env), SideCarousel.frameKey(w, env))
    }
}
