package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTypeTest {
    private val replayable =
        listOf(FrameType.CHAT, FrameType.REACTION, FrameType.RECEIPT, FrameType.GROUP_UPDATE, FrameType.GROUP_LEAVE)

    @Test
    fun `isReplayable is exactly the locally-delivered family`() {
        replayable.forEach { assertTrue(it, FrameType.isReplayable(it)) }
        listOf(FrameType.PROFILE, FrameType.BLOB_REQ, FrameType.KEY_REQ, FrameType.TYPING)
            .forEach { assertFalse(it, FrameType.isReplayable(it)) }
    }

    @Test
    fun `isCustodial is the replayable family plus profile`() {
        (replayable + FrameType.PROFILE).forEach { assertTrue(it, FrameType.isCustodial(it)) }
        listOf(FrameType.BLOB_REQ, FrameType.KEY_REQ, FrameType.TYPING)
            .forEach { assertFalse(it, FrameType.isCustodial(it)) }
    }

    @Test
    fun `an unknown future type is neither replayable nor custodial`() {
        assertFalse(FrameType.isReplayable("something-new"))
        assertFalse(FrameType.isCustodial("something-new"))
    }

    @Test
    fun `isStorable mirrors isCustodial for the envelope type`() {
        assertTrue(envelope(FrameType.CHAT).isStorable())
        assertTrue(envelope(FrameType.PROFILE).isStorable())
        assertFalse(envelope(FrameType.TYPING).isStorable())
        assertFalse(envelope("something-new").isStorable())
    }

    @Test
    fun `relayed caps ttl to the local default and increments hops`() {
        val relayed = wire(ttl = DEFAULT_TTL + 12, hops = 2).relayed()
        assertEquals(DEFAULT_TTL, relayed.ttl)
        assertEquals(3, relayed.hops)
    }

    @Test
    fun `relayed leaves a below-default ttl untouched`() {
        val relayed = wire(ttl = 3, hops = 0).relayed()
        assertEquals(3, relayed.ttl)
        assertEquals(1, relayed.hops)
    }

    @Test
    fun `relayed preserves the relay flag and forwards sig and signed by reference`() {
        val original = wire(ttl = 5, hops = 0, relay = false)
        val relayed = original.relayed()
        assertFalse(relayed.relay)
        // The signed bytes and signature must pass through byte-for-byte — never re-encoded.
        assertSame(original.sig, relayed.sig)
        assertSame(original.signed, relayed.signed)
    }

    private fun wire(
        ttl: Int,
        hops: Int,
        relay: Boolean = true,
    ) = WireEnvelope(
        ttl = ttl,
        hops = hops,
        relay = relay,
        sig = byteArrayOf(1, 2, 3),
        signed = byteArrayOf(4, 5, 6),
    )

    private fun envelope(type: String) = RelayEnvelope(type = type, id = "id", senderId = "sender", payload = ByteArray(0))
}
