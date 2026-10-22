package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IOS-5 (ADR 2026-10.qjap): a correctly signed envelope whose `id` or `senderId` holds bytes that aren't UTF-8 is refused
 * where every plane decodes `signed`, as iOS refuses it — before verify, custody, relay or a digest can see it. Without
 * the check kotlinx would hand back a repaired id (U+FFFD) that names no frame the signer sent.
 */
class EnvelopeUtf8Test {
    private val hybrid by lazy {
        TinkInit.ensure()
        KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW").toParameters(),
            RegistryConfiguration.get(),
        )
    }
    private val sigKey by lazy {
        TinkInit.ensure()
        KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW").toParameters(), RegistryConfiguration.get())
    }
    private val crypto by lazy { MessageCrypto(hybrid, sigKey) }
    private val bundle by lazy { PublicKeyBundle.fromPrivate(hybrid, sigKey) }
    private val nodeId by lazy { NodeId.fromPublicKeyBundle(bundle.encoded) }

    /** [signed] with the first occurrence of [marker]'s last byte replaced by 0xFF, then signed for real. */
    private fun forged(
        env: RelayEnvelope,
        marker: String,
    ): WireEnvelope {
        val signed = WireCodec.encodeEnvelope(env)
        val needle = marker.encodeToByteArray()
        val at = (0..signed.size - needle.size).single { i -> needle.indices.all { signed[i + it] == needle[it] } }
        signed[at + needle.size - 1] = 0xFF.toByte()
        return WireEnvelope(sig = crypto.signRaw(signed), signed = signed)
    }

    private fun chat(
        id: String,
        senderId: String,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = senderId,
        sentAt = 1L,
        payload = WireCodec.encodePayload(ChatContent(body = "hi")),
    )

    @Test
    fun `the honest frame decodes and verifies`() {
        val env = chat("msg-QQ", nodeId)
        val signed = WireCodec.encodeEnvelope(env)
        val wire = WireEnvelope(sig = crypto.signRaw(signed), signed = signed)
        assertTrue(MessageCrypto.verify(bundle, wire.sig, wire.signed))
        assertEquals("msg-QQ", WireCodec.decodeEnvelope(wire.signed)?.id)
    }

    @Test
    fun `a signed frame with a bad byte in its id is refused`() {
        val wire = forged(chat("msg-QQ", nodeId), "msg-QQ")
        assertTrue("signed for real: only the text check stands in the way", MessageCrypto.verify(bundle, wire.sig, wire.signed))
        assertNull(WireCodec.decodeEnvelope(wire.signed))
        // The whole wire still parses, so the refusal is the signed layer's, the one every plane shares.
        assertNotNull(WireCodec.decodeWire(WireCodec.encodeWire(wire)))
    }

    @Test
    fun `a signed frame with a bad byte in its sender is refused`() {
        val wire = forged(chat("msg-1", "$nodeId-QQ"), "$nodeId-QQ")
        assertTrue(MessageCrypto.verify(bundle, wire.sig, wire.signed))
        assertNull(WireCodec.decodeEnvelope(wire.signed))
    }

    @Test
    fun `bad text inside the opaque payload leaves the envelope alone and fails the checked payload read`() {
        val payload = WireCodec.encodePayload(ChatContent(body = "hi-QQ"))
        payload[payload.size - 1] = 0xFF.toByte()
        val env = RelayEnvelope(type = FrameType.CHAT, id = "msg-2", senderId = nodeId, sentAt = 1L, payload = payload)
        val decoded = checkNotNull(WireCodec.decodeEnvelope(WireCodec.encodeEnvelope(env)))
        assertNotNull("kotlinx repairs it", WireCodec.decodePayload<ChatContent>(decoded.payload))
        assertNull("the checked read refuses it, as iOS does", WireCodec.decodeChecked<ChatContent>(decoded.payload))
    }
}
