package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** Unit tests for [FastFrameCodec] — the pure compact/fragment side-channel frame codec. */
class FastFrameCodecTest {
    private val rng = Random(42)

    private fun wire(
        signed: ByteArray = rng.nextBytes(200),
        sig: ByteArray = rng.nextBytes(FastFrameCodec.SIG_BYTES),
        ttl: Int = 8,
        hops: Int = 0,
        relay: Boolean = true,
    ) = WireEnvelope(ttl = ttl, hops = hops, relay = relay, sig = sig, signed = signed)

    /** A sealed v3 chat envelope: valid CBOR the transcoder models, whose body is ciphertext and will not deflate. */
    private fun incompressibleCbor(): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = NodeId.derive("alice"),
                sentAt = 1_755_700_000_000L,
                recipientId = NodeId.derive("bob"),
                payload =
                    WireCodec.encodePayload(
                        ChatContent(enc = EncEnvelope(v = 3, nonce = ByteArray(0), ct = rng.nextBytes(120), keys = emptyList())),
                    ),
            ),
        )

    /** A realistic CBOR `signed` blob (a receipt routing envelope) — compressible, unlike random bytes. */
    private fun cborSigned(): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = "AAAAAAAAAAAAAAAAAAAAAA",
                senderId = "abcdefghijklmnopqrstuvwxy2",
                sentAt = 1_755_700_000_000L,
                payload = WireCodec.encodePayload(ReceiptContent(ackId = "BBBBBBBBBBBBBBBBBBBBBB")),
            ),
        )

    // --- compact frames ---

    @Test
    fun compactRoundTripPreservesSigAndSignedByteExact() {
        val original = wire(ttl = 5, hops = 3, relay = false)
        val decoded = FastFrameCodec.decodeCompact(checkNotNull(FastFrameCodec.encodeCompact(original)))!!
        assertArrayEquals(original.sig, decoded.sig)
        assertArrayEquals(original.signed, decoded.signed)
        assertEquals(5, decoded.ttl)
        assertEquals(3, decoded.hops)
        assertFalse(decoded.relay)
    }

    @Test
    fun aRealSignatureStillVerifiesAfterCompactRoundTrip() {
        TinkInit.ensure()
        val hybrid =
            KeysetHandle.generateNew(
                KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW").toParameters(),
                RegistryConfiguration.get(),
            )
        val sigKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW").toParameters(), RegistryConfiguration.get())
        val crypto = MessageCrypto(hybrid, sigKeys)
        val signed = cborSigned()
        val original = WireEnvelope(sig = crypto.signRaw(signed), signed = signed)
        val decoded = FastFrameCodec.decodeCompact(checkNotNull(FastFrameCodec.encodeCompact(original)))!!
        assertTrue(
            "the never-re-encode-signed rule, executable",
            MessageCrypto.verify(PublicKeyBundle.fromPrivate(hybrid, sigKeys), decoded.sig, decoded.signed),
        )
    }

    @Test
    fun deflatedBodyInflatesWithPresetDictionary() {
        val original = wire(signed = cborSigned())
        val compact = checkNotNull(FastFrameCodec.encodeCompact(original))
        assertTrue("CBOR text deflates", (compact[1].toInt() and 0x02) != 0)
        assertTrue("deflated form is smaller", compact.size < FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES + original.signed.size)
        assertArrayEquals(original.signed, FastFrameCodec.decodeCompact(compact)!!.signed)
    }

    /**
     * **Load-bearing for the LoRa plane: senders depend on this tolerance (ADR 2026-09.mhs5).**
     *
     * A deflate stream carries its own end marker, so bytes appended after it are never read — which is what
     * lets `LoraFrameCodec` pad a packet past the Meshtastic 2.8 signature cliff and still be decoded by every
     * build already in the field. Nothing else pins that property, so tightening [FastFrameCodec] to reject
     * trailing input would read as a safe cleanup and would silently break padded senders against older
     * receivers. If this test ever has to change, the padding has to be retired first.
     */
    @Test
    fun aDeflatedFrameIgnoresTrailingBytesBecauseThePaddedLoraSenderDependsOnIt() {
        val original = wire(signed = cborSigned())
        for (form in listOf(
            checkNotNull(FastFrameCodec.encodeCompact(original)),
            checkNotNull(FastFrameCodec.encodeTranscoded(original)),
        )) {
            assertTrue("this form must be deflated for the pad to be legal", FastFrameCodec.deflated(form))
            for (pad in listOf(1, 9, 64)) {
                val padded = form + ByteArray(pad)
                val decoded = checkNotNull(FastFrameCodec.decodeCompact(padded)) { "tag ${form[0]} + $pad pad bytes" }
                assertArrayEquals("sig survives $pad pad bytes on tag ${form[0]}", original.sig, decoded.sig)
                assertArrayEquals("signed survives $pad pad bytes on tag ${form[0]}", original.signed, decoded.signed)
            }
        }
    }

    /**
     * [FastFrameCodec.deflatedForm] is how a stored frame becomes paddable at all: the frames with most to gain
     * (a transcoded sealed tick) have no compressible bytes left, so deflating them is a *cost*. It is a small,
     * fixed one — and that is what makes the trade against a 66-byte firmware signature worth taking.
     */
    @Test
    fun aStoredFrameCanBeReDeflatedForAFixedFiveBytesAndStillRoundTrips() {
        for (stored in listOf(
            checkNotNull(FastFrameCodec.encodeCompact(wire(signed = rng.nextBytes(120)))),
            checkNotNull(FastFrameCodec.encodeTranscoded(wire(signed = incompressibleCbor()))),
        )) {
            assertFalse("fixture must store", FastFrameCodec.deflated(stored))
            val forced = checkNotNull(FastFrameCodec.deflatedForm(stored)) { "tag ${stored[0]}" }
            assertTrue("it is now paddable", FastFrameCodec.deflated(forced))
            assertEquals("deflate framing over incompressible bytes costs exactly five bytes", stored.size + 5, forced.size)
            val original = checkNotNull(FastFrameCodec.decodeCompact(stored))
            val decoded = checkNotNull(FastFrameCodec.decodeCompact(forced + ByteArray(4)))
            assertArrayEquals("sig survives the re-encode and a pad", original.sig, decoded.sig)
            assertArrayEquals("and so does signed", original.signed, decoded.signed)
            assertEquals(stored[0], forced[0])
            assertNull("an already-deflated frame has nothing to convert", FastFrameCodec.deflatedForm(forced))
        }
    }

    /** The other half of the pad's gate: a stored body has no end marker, so the sender must never pad one. */
    @Test
    fun aStoredFrameIsNotReportedAsPaddable() {
        val stored = checkNotNull(FastFrameCodec.encodeCompact(wire(signed = rng.nextBytes(120))))
        assertFalse("random bytes store rather than deflate", FastFrameCodec.deflated(stored))
        assertFalse(
            "a fragment is never paddable — its flags belong to the frame inside it",
            FastFrameCodec.deflated(byteArrayOf(FastFrameCodec.TAG_FRAG, 0x02)),
        )
        assertFalse("and neither is something too short to have flags", FastFrameCodec.deflated(byteArrayOf(FastFrameCodec.TAG_COMPACT)))
    }

    @Test
    fun incompressibleBodyFallsBackToStored() {
        val original = wire(signed = rng.nextBytes(120)) // random — deflate can only expand it
        val compact = checkNotNull(FastFrameCodec.encodeCompact(original))
        assertEquals("stored flag (bit1 clear)", 0, compact[1].toInt() and 0x02)
        assertEquals(FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES + 120, compact.size)
    }

    @Test
    fun ttlHopsPackAndSaturate() {
        assertEquals(8, FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(ttl = 8, hops = 0))!!)!!.ttl)
        assertEquals(0, FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(ttl = 8, hops = 0))!!)!!.hops)
        val hostile = FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(ttl = 200, hops = 100))!!)!!
        assertEquals("hostile ttl saturates (can only tighten propagation)", 15, hostile.ttl)
        assertEquals(15, hostile.hops)
        val zero = FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(ttl = 0, hops = 0))!!)!!
        assertEquals(0, zero.ttl)
        assertEquals(0, zero.hops)
    }

    @Test
    fun relayRidesFlagBitZero() {
        assertTrue(FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(relay = true))!!)!!.relay)
        assertFalse(FastFrameCodec.decodeCompact(FastFrameCodec.encodeCompact(wire(relay = false))!!)!!.relay)
    }

    @Test
    fun reservedFlagBitsAreRejected() {
        for (bit in listOf(0x20, 0x40, 0x80)) {
            val compact = checkNotNull(FastFrameCodec.encodeCompact(wire()))
            compact[1] = (compact[1].toInt() or bit).toByte()
            assertNull("an unknown future variant (bit $bit) must drop, not mis-decode", FastFrameCodec.decodeCompact(compact))
        }
    }

    @Test
    fun unsignedFrameRidesFlagBit4WithNoSigField() {
        val original = wire(sig = ByteArray(0), relay = false, signed = cborSigned())
        val compact = checkNotNull(FastFrameCodec.encodeCompact(original)) { "the empty sig is the UNSIGNED form, not an odd size" }
        assertEquals(FastFrameCodec.TAG_COMPACT, compact[0])
        assertTrue("flags bit 4 set", (compact[1].toInt() and FastFrameCodec.FLAG_UNSIGNED) != 0)
        assertEquals("relay clear", 0, compact[1].toInt() and 0x01)
        assertEquals(
            "no sig field: exactly the header plus the body",
            FastFrameCodec.HEADER_BYTES + (compact.size - FastFrameCodec.HEADER_BYTES),
            compact.size,
        )
        assertEquals(
            "64 bytes lighter than the signed form",
            64,
            checkNotNull(FastFrameCodec.encodeCompact(wire(signed = original.signed, relay = false))).size - compact.size,
        )
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(compact))
        assertEquals(0, decoded.sig.size)
        assertFalse(decoded.relay)
        assertArrayEquals(original.signed, decoded.signed)
    }

    @Test
    fun anUnsignedFrameAsShortAsTheHeaderIsRejected() {
        // Tag + flags(UNSIGNED) + ttl/hops and nothing after: no body to reconstruct.
        assertNull(FastFrameCodec.decodeCompact(byteArrayOf(0x03, FastFrameCodec.FLAG_UNSIGNED.toByte(), 0x00)))
    }

    @Test
    fun unknownDictIdIsRejected() {
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire(signed = cborSigned())))
        assertTrue("fixture must be deflated", (compact[1].toInt() and 0x02) != 0)
        compact[1] = ((compact[1].toInt() and 0x03) or 0x02 or (2 shl 2)).toByte() // dictId 2
        assertNull(FastFrameCodec.decodeCompact(compact))
    }

    @Test
    fun truncatedCompactIsRejected() {
        assertNull(FastFrameCodec.decodeCompact(ByteArray(FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES) { 0x03 }))
        assertNull(FastFrameCodec.decodeCompact(byteArrayOf(0x03)))
        assertNull("wrong tag", FastFrameCodec.decodeCompact(ByteArray(80) { 0x01 }))
    }

    @Test
    fun garbageDeflateStreamIsRejectedNotThrown() {
        val original = wire(signed = cborSigned())
        val compact = checkNotNull(FastFrameCodec.encodeCompact(original))
        assertTrue((compact[1].toInt() and 0x02) != 0)
        for (i in FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES until compact.size) compact[i] = 0x7F
        assertNull(FastFrameCodec.decodeCompact(compact))
    }

    @Test
    fun oddSigSizeIsUnencodable() {
        assertNull(FastFrameCodec.encodeCompact(wire(sig = rng.nextBytes(63))))
        assertNull(FastFrameCodec.encodeCompact(wire(sig = rng.nextBytes(65))))
        assertNull(FastFrameCodec.encodeCompact(wire(sig = rng.nextBytes(1))))
    }

    @Test
    fun dictV1IsFrozen() {
        val sha = MessageDigest.getInstance("SHA-256").digest(FastFrameCodec.DICT_V1).joinToString("") { "%02x".format(it) }
        // Editing DICT_V1 breaks live decode against shipped builds: mint DICT_V2 + a new dictId instead.
        assertEquals(DICT_V1_SHA256, sha)
    }

    @Test
    fun compactHeaderLayoutIsPinned() {
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire(signed = rng.nextBytes(100), ttl = 8, hops = 2, relay = true)))
        assertEquals(0x03, compact[0].toInt())
        assertEquals("relay set, stored", 0x01, compact[1].toInt())
        assertEquals("ttl 8 / hops 2", 0x82.toByte(), compact[2])
        assertEquals(FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES + 100, compact.size)
    }

    // --- fragments ---

    @Test
    fun fragmentSplitsAtFixedChunksAndConcatenatesInOrder() {
        val compact = rng.nextBytes(600).also { it[0] = FastFrameCodec.TAG_COMPACT }
        val parts = checkNotNull(FastFrameCodec.fragment(compact, maxMessage = 255, fragId = 7))
        assertEquals(3, parts.size)
        assertEquals(255, parts[0].size)
        assertEquals(255, parts[1].size)
        assertEquals(FastFrameCodec.FRAG_HEADER_BYTES + 600 - 2 * 251, parts[2].size)
        val glued =
            parts
                .map { checkNotNull(FastFrameCodec.parseFragment(it)) }
                .sortedBy { it.part }
                .fold(ByteArray(0)) { acc, f -> acc + f.payload }
        assertArrayEquals(compact, glued)
    }

    @Test
    fun fragmentReturnsNullPastThreeParts() {
        val max = FastFrameCodec.MAX_PARTS * (255 - FastFrameCodec.FRAG_HEADER_BYTES)
        assertNotNull(FastFrameCodec.fragment(ByteArray(max), maxMessage = 255, fragId = 1))
        assertNull(FastFrameCodec.fragment(ByteArray(max + 1), maxMessage = 255, fragId = 1))
    }

    @Test
    fun fragHeaderLayoutIsPinned() {
        val parts = checkNotNull(FastFrameCodec.fragment(ByteArray(300), maxMessage = 255, fragId = 0xABCD))
        val second = parts[1]
        assertEquals(0x04, second[0].toInt())
        assertEquals(0xAB.toByte(), second[1])
        assertEquals(0xCD.toByte(), second[2])
        assertEquals("part 1 of 2", 0x12, second[3].toInt())
        val parsed = checkNotNull(FastFrameCodec.parseFragment(second))
        assertEquals(0xABCD, parsed.fragId)
        assertEquals(1, parsed.part)
        assertEquals(2, parsed.count)
    }

    @Test
    fun malformedFragmentsAreRejected() {
        fun frag(partCount: Int): ByteArray = byteArrayOf(FastFrameCodec.TAG_FRAG, 0, 1, partCount.toByte(), 0x55)
        assertNull("count 0", FastFrameCodec.parseFragment(frag(0x00)))
        assertNull("count 1", FastFrameCodec.parseFragment(frag(0x01)))
        assertNull("count 4", FastFrameCodec.parseFragment(frag(0x04)))
        assertNull("part >= count", FastFrameCodec.parseFragment(frag(0x22)))
        assertNull("empty slice", FastFrameCodec.parseFragment(byteArrayOf(FastFrameCodec.TAG_FRAG, 0, 1, 0x02)))
        assertNull("wrong tag", FastFrameCodec.parseFragment(byteArrayOf(0x03, 0, 1, 0x02, 0x55)))
    }

    // --- transcoded frames (ADR 060) ---

    @Test
    fun transcodedRoundTripPreservesSigAndSignedByteExactAndIsSmaller() {
        val original = wire(signed = cborSigned(), ttl = 6, hops = 1, relay = false)
        val transcoded = checkNotNull(FastFrameCodec.encodeTranscoded(original))
        assertEquals(FastFrameCodec.TAG_TRANSCODED, transcoded[0])
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(transcoded))
        assertArrayEquals(original.sig, decoded.sig)
        assertArrayEquals(original.signed, decoded.signed)
        assertEquals(6, decoded.ttl)
        assertEquals(1, decoded.hops)
        assertFalse(decoded.relay)
        assertTrue("0x05 beats 0x03 on a real receipt", transcoded.size < checkNotNull(FastFrameCodec.encodeCompact(original)).size)
    }

    @Test
    fun aRealSignatureStillVerifiesAfterTranscodedRoundTrip() {
        TinkInit.ensure()
        val hybrid =
            KeysetHandle.generateNew(
                KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW").toParameters(),
                RegistryConfiguration.get(),
            )
        val sigKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW").toParameters(), RegistryConfiguration.get())
        val crypto = MessageCrypto(hybrid, sigKeys)
        val signed = cborSigned()
        val original = WireEnvelope(sig = crypto.signRaw(signed), signed = signed)
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(checkNotNull(FastFrameCodec.encodeTranscoded(original))))
        assertTrue(
            "the receiver rebuilds the canonical bytes and the original signature verifies over them",
            MessageCrypto.verify(PublicKeyBundle.fromPrivate(hybrid, sigKeys), decoded.sig, decoded.signed),
        )
    }

    @Test
    fun transcodedDeflateCarriesNoDictionaryAndTheDictIdsAreTagSpecific() {
        val chatty =
            WireCodec.encodeEnvelope(
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "AAAAAAAAAAAAAAAAAAAAAA",
                    senderId = "abcdefghijklmnopqrstuvwxy2",
                    sentAt = 1_755_700_000_000L,
                    payload = WireCodec.encodePayload(ChatContent(body = "the quick brown fox jumps over the lazy dog. ".repeat(6))),
                ),
            )
        val transcoded = checkNotNull(FastFrameCodec.encodeTranscoded(wire(signed = chatty)))
        assertTrue("repetitive text deflates on 0x05 too", (transcoded[1].toInt() and 0x02) != 0)
        assertEquals("dictId 0 = no dictionary", 0, (transcoded[1].toInt() shr 2) and 0x03)
        assertArrayEquals(chatty, checkNotNull(FastFrameCodec.decodeCompact(transcoded)).signed)
        val withDictV1 = transcoded.copyOf().also { it[1] = (it[1].toInt() or (FastFrameCodec.DICT_ID_V1 shl 2)).toByte() }
        assertNull("DICT_V1 is not a 0x05 dictionary", FastFrameCodec.decodeCompact(withDictV1))
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire(signed = chatty)))
        assertTrue((compact[1].toInt() and 0x02) != 0)
        val withNone = compact.copyOf().also { it[1] = (it[1].toInt() and (0x03 shl 2).inv()).toByte() } // DEFLATED, dictId 0
        assertNull("dictId 0 is not a 0x03 dictionary", FastFrameCodec.decodeCompact(withNone))
    }

    @Test
    fun encodeBestPicksTheSmallerFormAndReportsARefusal() {
        val cbor = wire(signed = cborSigned())
        val best = checkNotNull(FastFrameCodec.encodeBest(cbor, transcode = true))
        assertTrue(best.transcoded)
        assertFalse(best.transcodeRefused)
        assertTrue(best.frame.size <= checkNotNull(FastFrameCodec.encodeCompact(cbor)).size)
        val notTranscoding = checkNotNull(FastFrameCodec.encodeBest(cbor, transcode = false))
        assertEquals("a peer without the bit gets 0x03", FastFrameCodec.TAG_COMPACT, notTranscoding.frame[0])
        assertFalse(notTranscoding.transcodeRefused)
        val opaque = wire(signed = rng.nextBytes(120)) // not CBOR at all: the transcoder refuses, 0x03 carries it
        val refused = checkNotNull(FastFrameCodec.encodeBest(opaque, transcode = true))
        assertEquals(FastFrameCodec.TAG_COMPACT, refused.frame[0])
        assertTrue(refused.transcodeRefused)
        assertNull(FastFrameCodec.encodeTranscoded(opaque))
        assertNull(
            "an odd sig is unrepresentable in either form",
            FastFrameCodec.encodeBest(wire(sig = rng.nextBytes(63)), transcode = true),
        )
    }

    @Test
    fun transcodedFramesShareTheHeaderRulesAndRejectABodyThatWillNotRebuild() {
        val original = wire(signed = cborSigned(), relay = true, ttl = 8, hops = 2)
        val transcoded = checkNotNull(FastFrameCodec.encodeTranscoded(original))
        assertEquals("relay rides bit 0", 0x01, transcoded[1].toInt() and 0x01)
        assertEquals("no reserved bits", 0, transcoded[1].toInt() and 0xE0)
        assertEquals("ttl 8 / hops 2", 0x82.toByte(), transcoded[2])
        for (bit in listOf(0x20, 0x40, 0x80)) {
            assertNull(
                "reserved bit $bit",
                FastFrameCodec.decodeCompact(transcoded.copyOf().also { it[1] = (it[1].toInt() or bit).toByte() }),
            )
        }
        val bodyAt = FastFrameCodec.HEADER_BYTES + FastFrameCodec.SIG_BYTES
        val corrupt = transcoded.copyOf().also { it[bodyAt + 1] = 0x17 } // the first label → one no scope has
        assertNull("a body the rebuild refuses is a drop, never a wrong frame", FastFrameCodec.decodeCompact(corrupt))
        val unsigned = wire(sig = ByteArray(0), relay = false, signed = cborSigned())
        val unsignedTranscoded = checkNotNull(FastFrameCodec.encodeTranscoded(unsigned))
        assertTrue((unsignedTranscoded[1].toInt() and FastFrameCodec.FLAG_UNSIGNED) != 0)
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(unsignedTranscoded))
        assertEquals(0, decoded.sig.size)
        assertArrayEquals(unsigned.signed, decoded.signed)
        assertTrue(FastFrameCodec.isFrameTag(FastFrameCodec.TAG_COMPACT))
        assertTrue(FastFrameCodec.isFrameTag(FastFrameCodec.TAG_TRANSCODED))
        assertFalse(FastFrameCodec.isFrameTag(FastFrameCodec.TAG_FRAG))
    }

    @Test
    fun aFragmentedTranscodedFrameReassemblesThroughTheSameDecoder() {
        val big =
            WireCodec.encodeEnvelope(
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "AAAAAAAAAAAAAAAAAAAAAA",
                    senderId = "abcdefghijklmnopqrstuvwxy2",
                    sentAt = 1_755_700_000_000L,
                    payload = WireCodec.encodePayload(ChatContent(body = String(CharArray(400) { ('!' + rng.nextInt(90)) }))),
                ),
            )
        val transcoded = checkNotNull(FastFrameCodec.encodeTranscoded(wire(signed = big)))
        assertTrue(transcoded.size > 255)
        val parts = checkNotNull(FastFrameCodec.fragment(transcoded, maxMessage = 255, fragId = 9))
        val glued =
            parts
                .map { checkNotNull(FastFrameCodec.parseFragment(it)) }
                .sortedBy { it.part }
                .fold(ByteArray(0)) { acc, f -> acc + f.payload }
        assertEquals(FastFrameCodec.TAG_TRANSCODED, glued[0])
        assertArrayEquals(big, checkNotNull(FastFrameCodec.decodeCompact(glued)).signed)
    }

    private companion object {
        /** SHA-256 of [FastFrameCodec.DICT_V1] — regenerate ONLY when deliberately minting a new dict version. */
        const val DICT_V1_SHA256 = "ecd2f0729fb1b345a682608ed4793ac3620539df5d9fa22959444c26f673ec6c"
    }
}
