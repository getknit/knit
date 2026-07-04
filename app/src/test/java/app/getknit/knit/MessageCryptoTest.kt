package app.getknit.knit

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.WireCodec
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCryptoTest {
    /** A device identity: its cipher (private keys) plus the public bundle it would advertise. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
        val nodeId: String,
    )

    private fun party(nodeId: String): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig), nodeId)
    }

    private fun content(body: String) = MessageContent(body = body, mentions = listOf(Mention("bob00000", "Bob"))).encode()

    // --- seal / open (encrypt-only; authentication is the separate frame signature) ---

    @Test
    fun dmRoundTrips() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m1", alice.nodeId, 100L, bob.nodeId)

        val envelope = alice.crypto.seal(content("hi bob"), header, mapOf(bob.nodeId to bob.bundle))!!
        val opened = bob.crypto.open(envelope, header, bob.nodeId)

        assertEquals("hi bob", opened?.body)
        assertEquals("Bob", opened?.mentions?.firstOrNull()?.name)
    }

    @Test
    fun sealedEnvelopeSurvivesTheWireCodec() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m6", alice.nodeId, 1L, bob.nodeId)

        // End-to-end guard for the base64 → @ByteString re-type: seal, carry the envelope through the CBOR
        // payload codec exactly as a real `chat` frame does, then decrypt the *decoded* copy (not the
        // in-memory object). Proves nonce/ct/wk survive the wire encoding as raw bytes.
        val envelope = alice.crypto.seal(content("over the wire"), header, mapOf(bob.nodeId to bob.bundle))!!
        val payload = WireCodec.encodePayload(ChatContent(enc = envelope))
        val decodedEnc = WireCodec.decodePayload<ChatContent>(payload)?.enc
        assertNotNull(decodedEnc)
        assertEquals("over the wire", bob.crypto.open(decodedEnc!!, header, bob.nodeId)?.body)
    }

    @Test
    fun dmReplyRefSurvivesSealAndWire() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m8", alice.nodeId, 1L, bob.nodeId)

        // A reply's quoted author + snippet must stay INSIDE the ciphertext (never on the cleartext
        // ChatContent for a DM). Seal a MessageContent carrying a ReplyRef, carry the envelope through the
        // wire codec as a real chat frame, then decrypt the decoded copy and assert the quote survived.
        val reply = ReplyRef("m0", alice.nodeId, "Alice", "see you at 8", hasAttachment = true)
        val sealed =
            alice.crypto.seal(
                MessageContent(body = "on my way", replyTo = reply).encode(),
                header,
                mapOf(bob.nodeId to bob.bundle),
            )!!
        val decodedEnc = WireCodec.decodePayload<ChatContent>(WireCodec.encodePayload(ChatContent(enc = sealed)))?.enc
        assertNotNull(decodedEnc)
        assertEquals(reply, bob.crypto.open(decodedEnc!!, header, bob.nodeId)?.replyTo)
    }

    @Test
    fun groupMessageDecryptsForEveryMember() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val carol = party("carol000")
        val header = MessageCrypto.header("g1", alice.nodeId, 5L, "g-team")

        val envelope =
            alice.crypto.seal(
                content("hi team"),
                header,
                mapOf(bob.nodeId to bob.bundle, carol.nodeId to carol.bundle),
            )!!

        assertEquals("hi team", bob.crypto.open(envelope, header, bob.nodeId)?.body)
        assertEquals("hi team", carol.crypto.open(envelope, header, carol.nodeId)?.body)
    }

    @Test
    fun nonRecipientCannotDecrypt() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val eve = party("eve00000")
        val header = MessageCrypto.header("m2", alice.nodeId, 1L, bob.nodeId)

        val envelope = alice.crypto.seal(content("secret"), header, mapOf(bob.nodeId to bob.bundle))!!
        // Eve has no wrapped key for her node id at all.
        assertNull(eve.crypto.open(envelope, header, eve.nodeId))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m3", alice.nodeId, 1L, bob.nodeId)

        val envelope = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        // Flip the last ciphertext byte (EncEnvelope is a plain `class` now, so rebuild rather than copy);
        // the GCM tag then fails to verify and open() returns null.
        val corrupted = envelope.ct.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        val tampered = EncEnvelope(v = envelope.v, nonce = envelope.nonce, ct = corrupted, keys = envelope.keys)
        assertNull(bob.crypto.open(tampered, header, bob.nodeId))
    }

    @Test
    fun mismatchedHeaderIsRejected() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m5", alice.nodeId, 1L, bob.nodeId)
        val wrongHeader = MessageCrypto.header("m5", alice.nodeId, 2L, bob.nodeId) // different sentAt

        val envelope = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        // The header is the AEAD AAD, so unwrapping/decrypting under the wrong header fails.
        assertNull(bob.crypto.open(envelope, wrongHeader, bob.nodeId))
    }

    @Test
    fun emptyRecipientsSealsNothing() {
        val alice = party("alice000")
        val header = MessageCrypto.header("m7", alice.nodeId, 1L, "bob00000")
        assertNull(alice.crypto.seal(content("hi"), header, emptyMap()))
    }

    // --- the one frame signature authenticates without decryption (the carrier path) ---

    @Test
    fun carrierAuthenticatesFrameWithoutDecrypting() {
        // A carrier (not a recipient, holds no wrapped key) authenticates a DM by verifying the frame
        // signature over the signed routing-envelope bytes — exactly MeshManager.canCarry.
        val alice = party("alice000")
        val bob = party("bob00000")
        val carol = party("carol000") // the carrier
        val header = MessageCrypto.header("m1", alice.nodeId, 100L, bob.nodeId)
        val envelope = alice.crypto.seal(content("hi bob"), header, mapOf(bob.nodeId to bob.bundle))!!

        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "m1",
                senderId = alice.nodeId,
                sentAt = 100L,
                recipientId = bob.nodeId,
                payload = WireCodec.encodePayload(ChatContent(enc = envelope)),
            )
        val signed = WireCodec.encodeEnvelope(env)
        val sig = alice.crypto.signRaw(signed)

        assertTrue("carrier verifies authorship", MessageCrypto.verify(alice.bundle, sig, signed))
        assertNull("but cannot read it — no wrapped key", carol.crypto.open(envelope, header, carol.nodeId))
    }

    // --- generic raw sign / verify (frame-level signing) ---

    @Test
    fun genericSignAndVerifyRoundTrips() {
        val alice = party("alice000")
        val bytes = "frame-canonical-bytes".toByteArray()
        assertTrue(MessageCrypto.verify(alice.bundle, alice.crypto.signRaw(bytes), bytes))
    }

    @Test
    fun genericVerifyRejectsTamperedBytes() {
        val alice = party("alice000")
        val sig = alice.crypto.signRaw("frame-canonical-bytes".toByteArray())
        assertFalse(MessageCrypto.verify(alice.bundle, sig, "frame-canonical-bytez".toByteArray()))
    }

    @Test
    fun genericVerifyRejectsWrongKey() {
        val alice = party("alice000")
        val mallory = party("mallory0")
        val bytes = "frame-canonical-bytes".toByteArray()
        assertFalse(MessageCrypto.verify(mallory.bundle, alice.crypto.signRaw(bytes), bytes))
    }

    @Test
    fun genericVerifyRejectsNullSignature() {
        val alice = party("alice000")
        assertFalse(MessageCrypto.verify(alice.bundle, null, "frame-canonical-bytes".toByteArray()))
    }

    // --- bundle round-trip ---

    @Test
    fun publicKeyBundleEncodeDecodeRoundTrips() {
        val alice = party("alice000")
        val decoded = PublicKeyBundle.decode(alice.bundle.encoded)
        assertNotNull(decoded)
        assertEquals(alice.bundle, decoded)
    }

    @Test
    fun malformedBundleDecodesToNull() {
        assertNull(PublicKeyBundle.decode("not-a-bundle"))
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    }
}
