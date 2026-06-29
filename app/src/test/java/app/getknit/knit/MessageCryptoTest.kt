package app.getknit.knit

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.Mention
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
    private class Party(val crypto: MessageCrypto, val bundle: PublicKeyBundle, val nodeId: String)

    private fun party(nodeId: String): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig), nodeId)
    }

    private fun content(body: String) =
        MessageContent(body = body, mentions = listOf(Mention("bob00000", "Bob"))).encode()

    @Test
    fun dmRoundTrips() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m1", alice.nodeId, 100L, bob.nodeId)

        val sealed = alice.crypto.seal(content("hi bob"), header, mapOf(bob.nodeId to bob.bundle))!!
        val opened = bob.crypto.open(sealed.envelope, sealed.sig, header, bob.nodeId, alice.bundle)

        assertEquals("hi bob", opened?.body)
        assertEquals("Bob", opened?.mentions?.firstOrNull()?.name)
    }

    @Test
    fun groupMessageDecryptsForEveryMember() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val carol = party("carol000")
        val header = MessageCrypto.header("g1", alice.nodeId, 5L, "g-team")

        val sealed = alice.crypto.seal(
            content("hi team"),
            header,
            mapOf(bob.nodeId to bob.bundle, carol.nodeId to carol.bundle),
        )!!

        assertEquals("hi team", bob.crypto.open(sealed.envelope, sealed.sig, header, bob.nodeId, alice.bundle)?.body)
        assertEquals("hi team", carol.crypto.open(sealed.envelope, sealed.sig, header, carol.nodeId, alice.bundle)?.body)
    }

    @Test
    fun nonRecipientCannotDecrypt() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val eve = party("eve00000")
        val header = MessageCrypto.header("m2", alice.nodeId, 1L, bob.nodeId)

        val sealed = alice.crypto.seal(content("secret"), header, mapOf(bob.nodeId to bob.bundle))!!
        // Eve has no wrapped key for her node id at all.
        assertNull(eve.crypto.open(sealed.envelope, sealed.sig, header, eve.nodeId, alice.bundle))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m3", alice.nodeId, 1L, bob.nodeId)

        val sealed = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        val tampered = sealed.envelope.copy(ct = sealed.envelope.ct.dropLast(4) + "AAAA")
        assertNull(bob.crypto.open(tampered, sealed.sig, header, bob.nodeId, alice.bundle))
    }

    @Test
    fun wrongSenderBundleFailsSignatureCheck() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val mallory = party("mallory0")
        val header = MessageCrypto.header("m4", alice.nodeId, 1L, bob.nodeId)

        val sealed = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        // Verifying against the wrong identity's signing key must fail.
        assertNull(bob.crypto.open(sealed.envelope, sealed.sig, header, bob.nodeId, mallory.bundle))
    }

    @Test
    fun mismatchedHeaderIsRejected() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m5", alice.nodeId, 1L, bob.nodeId)
        val wrongHeader = MessageCrypto.header("m5", alice.nodeId, 2L, bob.nodeId) // different sentAt

        val sealed = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        assertNull(bob.crypto.open(sealed.envelope, sealed.sig, wrongHeader, bob.nodeId, alice.bundle))
    }

    @Test
    fun missingSignatureIsRejected() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val header = MessageCrypto.header("m6", alice.nodeId, 1L, bob.nodeId)

        val sealed = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!
        assertNull(bob.crypto.open(sealed.envelope, null, header, bob.nodeId, alice.bundle))
    }

    @Test
    fun emptyRecipientsSealsNothing() {
        val alice = party("alice000")
        val header = MessageCrypto.header("m7", alice.nodeId, 1L, "bob00000")
        assertNull(alice.crypto.seal(content("hi"), header, emptyMap()))
    }

    @Test
    fun genericSignAndVerifyRoundTrips() {
        val alice = party("alice000")
        val bytes = "frame-canonical-bytes".toByteArray()
        assertTrue(MessageCrypto.verify(alice.bundle, alice.crypto.sign(bytes), bytes))
    }

    @Test
    fun genericVerifyRejectsTamperedBytes() {
        val alice = party("alice000")
        val sig = alice.crypto.sign("frame-canonical-bytes".toByteArray())
        assertFalse(MessageCrypto.verify(alice.bundle, sig, "frame-canonical-bytez".toByteArray()))
    }

    @Test
    fun genericVerifyRejectsWrongKey() {
        val alice = party("alice000")
        val mallory = party("mallory0")
        val bytes = "frame-canonical-bytes".toByteArray()
        assertFalse(MessageCrypto.verify(mallory.bundle, alice.crypto.sign(bytes), bytes))
    }

    @Test
    fun genericVerifyRejectsNullSignature() {
        val alice = party("alice000")
        assertFalse(MessageCrypto.verify(alice.bundle, null, "frame-canonical-bytes".toByteArray()))
    }

    @Test
    fun carrierVerifiesEnvelopeWithoutDecrypting() {
        // A relay/carrier (not a recipient, holds no wrapped key) authenticates a DM before carrying it.
        val alice = party("alice000")
        val bob = party("bob00000")
        val carol = party("carol000") // the carrier
        val header = MessageCrypto.header("m1", alice.nodeId, 100L, bob.nodeId)
        val sealed = alice.crypto.seal(content("hi bob"), header, mapOf(bob.nodeId to bob.bundle))!!

        assertTrue(carol.crypto.verifyEnvelope(alice.bundle, sealed.sig, header, sealed.envelope))
    }

    @Test
    fun carrierVerifiesGroupEnvelopeWithGroupThreadHeader() {
        // A group message's header thread is the group id (not a recipientId), so a carrier must build
        // the verify header from the group id to authenticate it — this guards MeshManager.canCarry.
        val alice = party("alice000")
        val bob = party("bob00000")
        val carol = party("carol000")
        val dave = party("dave0000") // the carrier — not a member, holds no wrapped key
        val header = MessageCrypto.header("g1", alice.nodeId, 5L, "g-team")
        val sealed = alice.crypto.seal(
            content("hi team"),
            header,
            mapOf(bob.nodeId to bob.bundle, carol.nodeId to carol.bundle),
        )!!

        assertTrue(dave.crypto.verifyEnvelope(alice.bundle, sealed.sig, header, sealed.envelope))
        // The old DM-style header (recipientId.orEmpty() == "" for a group) builds the wrong thread and fails.
        val wrongHeader = MessageCrypto.header("g1", alice.nodeId, 5L, "")
        assertFalse(dave.crypto.verifyEnvelope(alice.bundle, sealed.sig, wrongHeader, sealed.envelope))
    }

    @Test
    fun verifyEnvelopeRejectsTamperedEnvelopeWrongSenderAndMissingSig() {
        val alice = party("alice000")
        val bob = party("bob00000")
        val mallory = party("mallory0")
        val carol = party("carol000")
        val header = MessageCrypto.header("m2", alice.nodeId, 1L, bob.nodeId)
        val sealed = alice.crypto.seal(content("hi"), header, mapOf(bob.nodeId to bob.bundle))!!

        val tampered = sealed.envelope.copy(ct = sealed.envelope.ct.dropLast(4) + "AAAA")
        assertFalse(carol.crypto.verifyEnvelope(alice.bundle, sealed.sig, header, tampered))
        assertFalse(carol.crypto.verifyEnvelope(mallory.bundle, sealed.sig, header, sealed.envelope))
        assertFalse(carol.crypto.verifyEnvelope(alice.bundle, null, header, sealed.envelope))
    }

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
