package app.getknit.knit.mesh.lora

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LoraFrameCodecTest {
    private fun frame(body: String): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "id",
                senderId = "alice",
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        return WireEnvelope(relay = true, sig = ByteArray(64) { it.toByte() }, signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun aSmallFrameIsOneMessageAtTheDefaultCap() {
        val parts = LoraFrameCodec.encode(frame("hi"), fragId = 1)
        assertNotNull(parts)
        assertTrue("a short frame is a single packet", parts!!.size == 1)
    }

    @Test
    fun everyPacketRespectsTheGivenPayloadCap() {
        // A frame that fits one packet at 233 must split when the board's MTU only allows ~120-byte payloads.
        val wire = frame("The quick brown fox jumps over the lazy dog near the north gate at ten past.")
        val atMtu255 = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = 120)
        assertNotNull("still representable, just fragmented", atMtu255)
        assertTrue("every packet fits the cap", atMtu255!!.all { it.size <= 120 })
        assertTrue("it actually fragmented", atMtu255.size > 1)
    }

    /** ADR 060: the transcoded form is what puts a signed v3 tick in one packet at the MTU-255 boards' cap. */
    @Test
    fun theTranscodedFormFlipsASignedTickToOnePacketAtTheEsp32Cap() {
        val rng = Random(1)
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = NodeId.derive("alice"),
                sentAt = 1_755_700_000_000L,
                recipientId = NodeId.derive("bob"),
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_DM_V3,
                                    nonce = ByteArray(0),
                                    ct = rng.nextBytes(37),
                                    keys = emptyList(),
                                    r = RatchetHeader(se = 1, ek = rng.nextBytes(32), pe = 0, n = 2),
                                ),
                        ),
                    ),
            )
        val tick = WireEnvelope(relay = true, sig = rng.nextBytes(64), signed = WireCodec.encodeEnvelope(env))
        val cap = 255 - 27
        assertEquals("0x03 needs two packets", 2, LoraFrameCodec.encode(tick, fragId = 1, maxPayload = cap)!!.size)
        val best = checkNotNull(LoraFrameCodec.encodeBest(tick, fragId = 1, maxPayload = cap, transcode = true))
        assertTrue(best.transcoded)
        assertFalse(best.transcodeRefused)
        assertEquals("0x05 is one packet", 1, best.parts.size)
        assertEquals(FastFrameCodec.TAG_TRANSCODED, best.parts.single()[0])
    }

    // --- ADR 2026-09.mhs5: padding past the Meshtastic 2.8 signature cliff ---

    /** A board on 2.8, so the real governor prices the cliff exactly as the transport does. */
    private fun airtime28() =
        LoraAirtime().apply {
            onRadioConfig(
                LoraRadioConfig(
                    usePreset = true,
                    modemPreset = ModemPreset.LONG_FAST,
                    region = LoraRegion.US,
                    hopLimit = 3,
                    overrideDutyCycle = false,
                    channelNum = 0,
                ),
            )
            onBoard(SigningFacts("2.8.0.7239fe8"))
        }

    /** A cost model that always wants [target], for pinning the mechanics without a preset in the way. */
    private class FixedCost(
        private val target: Int,
    ) : PacketCost {
        override fun timeOnAirMs(payloadBytes: Int): Long = payloadBytes.toLong()

        override fun padTo(
            payloadBytes: Int,
            cap: Int,
        ): Int = minOf(target, cap)
    }

    /** A sealed v3 tick: the one-packet frame ADR 060 exists for, and the frame with most to gain here. */
    private fun sealedTick(signed: Boolean): WireEnvelope {
        val rng = Random(3)
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = NodeId.derive("alice"),
                sentAt = 1_755_700_000_000L,
                recipientId = NodeId.derive("bob"),
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_DM_V3,
                                    nonce = ByteArray(0),
                                    ct = rng.nextBytes(37),
                                    keys = emptyList(),
                                    r = RatchetHeader(se = 1, ek = rng.nextBytes(32), pe = 0, n = 2),
                                ),
                        ),
                    ),
            )
        return WireEnvelope(relay = signed, sig = if (signed) rng.nextBytes(64) else ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun aDeflatedFrameUnderTheCliffIsGrownPastItAndStillDecodes() {
        val air = airtime28()
        val cap = MeshtasticProto.MAX_PAYLOAD
        // Repetitive text, so the body deflates on its own — trailing bytes are already ignored on decode.
        val wire = frame("the quick brown fox jumps over the lazy dog. ".repeat(3))
        val bare = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap))
        val size = bare.parts.single().size
        assertTrue("fixture must land under the cliff to test anything, was $size B", size <= MeshtasticProto.MAX_SIGNED_PAYLOAD)
        assertEquals("nothing grows without a board to price against", 0, bare.grewBy)

        val padded = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap, cost = air))
        assertEquals("still one packet", 1, padded.parts.size)
        assertEquals(MeshtasticProto.MAX_SIGNED_PAYLOAD + 1, padded.parts.single().size)
        assertEquals(padded.parts.single().size - size, padded.grewBy)
        assertTrue("and the whole point: it is cheaper on the air", air.timeOnAirMs(padded.parts.single().size) < air.timeOnAirMs(size))

        val decoded = checkNotNull(FastFrameCodec.decodeCompact(padded.parts.single()))
        assertArrayEquals("sig crosses the pad byte-exact", wire.sig, decoded.sig)
        assertArrayEquals("and so does signed", wire.signed, decoded.signed)
    }

    /**
     * The frame with most to gain cannot be padded as it encodes: a transcoded sealed tick has no compressible
     * bytes left, so its body **stores**, and a receiver would read a pad as part of `signed`. Deflating it
     * anyway costs 5 bytes of framing and makes the pad legal — which is worth it, and priced rather than
     * assumed.
     */
    @Test
    fun aStoredFrameUnderTheCliffIsReDeflatedSoThatItCanBePaddedAtAll() {
        val air = airtime28()
        val cap = MeshtasticProto.MAX_PAYLOAD
        run {
            // The unsigned form (ADR 059's sealed tick): 157 B transcoded, the smallest frame on the plane and
            // the one the firmware would most like to sign. Its signed sibling is 221 B — already past the
            // cliff, and covered by the next test.
            val wire = sealedTick(signed = false)
            val bare = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap, transcode = true))
            val size = bare.parts.single().size
            assertTrue("fixture must store, not deflate", !FastFrameCodec.deflated(bare.parts.single()))
            assertTrue("...and land under the cliff, was $size B", size <= MeshtasticProto.MAX_SIGNED_PAYLOAD)

            val priced = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap, transcode = true, cost = air))
            val packet = priced.parts.single()
            assertTrue("it had to become deflated to be paddable", FastFrameCodec.deflated(packet))
            assertEquals("and it lands one byte past the cliff", MeshtasticProto.MAX_SIGNED_PAYLOAD + 1, packet.size)
            assertEquals(packet.size - size, priced.grewBy)
            assertTrue("cheaper on the air than the signature it dodges", air.timeOnAirMs(packet.size) < air.timeOnAirMs(size))
            assertEquals("still the transcoded form", FastFrameCodec.TAG_TRANSCODED, packet[0])

            val decoded = checkNotNull(FastFrameCodec.decodeCompact(packet))
            assertArrayEquals(wire.sig, decoded.sig)
            assertArrayEquals(wire.signed, decoded.signed)
        }
    }

    @Test
    fun aFrameAlreadyPastTheCliffIsLeftExactlyAsItEncoded() {
        val air = airtime28()
        val cap = MeshtasticProto.MAX_PAYLOAD
        // Incompressible and large: stored, over the cliff, so the firmware signs nothing and there is no
        // trade to make. Re-deflating it would be five bytes spent for nothing.
        val wire = WireEnvelope(relay = true, sig = ByteArray(64) { it.toByte() }, signed = Random(7).nextBytes(140))
        val bare = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap))
        val priced = checkNotNull(LoraFrameCodec.encodeBest(wire, fragId = 1, maxPayload = cap, cost = air))
        assertTrue("fixture must be past the cliff", bare.parts.single().size > MeshtasticProto.MAX_SIGNED_PAYLOAD)
        assertEquals(0, priced.grewBy)
        assertArrayEquals(bare.parts.single(), priced.parts.single())
    }

    @Test
    fun paddingGrowsOnlyTheLastFragmentAndNeverTheFragmentCount() {
        val cap = 120
        val wire = frame("the quick brown fox jumps over the lazy dog. ".repeat(30))
        val bare = checkNotNull(LoraFrameCodec.encode(wire, fragId = 7, maxPayload = cap))
        assertTrue("fixture must fragment", bare.size > 1)
        // An explicit target rather than the governor's: the cliff is unreachable at a 120-byte cap, and what
        // this pins is the mechanics — the tail is the only packet that may move.
        val padded = checkNotNull(LoraFrameCodec.encode(wire, fragId = 7, maxPayload = cap, cost = FixedCost(cap)))
        assertEquals("the fragment count cannot change", bare.size, padded.size)
        assertTrue("every packet still fits the cap", padded.all { it.size <= cap })
        for (i in 0 until bare.size - 1) assertArrayEquals("part $i is untouched", bare[i], padded[i])
        assertTrue("the tail grew", padded.last().size > bare.last().size)

        // And the far side still rebuilds the frame: the pad arrives as trailing bytes of the reassembly.
        val reassembled = padded.map { checkNotNull(FastFrameCodec.parseFragment(it)).payload }.reduce { a, b -> a + b }
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(reassembled))
        assertArrayEquals(wire.sig, decoded.sig)
        assertArrayEquals(wire.signed, decoded.signed)
    }

    @Test
    fun theCapIsClampedToTheProtocolLimit() {
        // Asking for more than 233 must not produce a packet the board would reject.
        val wire = frame("x".repeat(400))
        val huge = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = 10_000)
        val normal = LoraFrameCodec.encode(wire, fragId = 1, maxPayload = MeshtasticProto.MAX_PAYLOAD)
        assertTrue("an over-large cap is clamped to 233", huge?.all { it.size <= MeshtasticProto.MAX_PAYLOAD } == true)
        assertTrue("...and matches the protocol-limit encoding", huge?.size == normal?.size)
    }
}
