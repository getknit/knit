package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.WrappedKey
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The end-to-end message cipher. Holds this device's **private** keysets (hybrid for decrypting wrapped
 * content keys, Ed25519 for signing) and performs the per-message seal/open. Pure logic — keys are
 * injected, no Android or persistence here — so it runs unchanged under JVM unit tests; the Android-only
 * key persistence lives in [app.getknit.knit.data.crypto.IdentityKeyStore].
 *
 * Scheme (static keys, no ratchet — see the E2E design): a fresh random content key encrypts the
 * [MessageContent] with AES-256-GCM, the content key is wrapped to each recipient's hybrid key, and the
 * whole envelope is Ed25519-signed over a canonical blob that also binds the message [header]
 * (id|sender|sentAt|thread) — so a wrapped key or ciphertext can't be replayed into another message.
 */
class MessageCrypto(
    private val ownHybridPrivate: KeysetHandle,
    private val ownSigPrivate: KeysetHandle,
) {
    init {
        TinkInit.ensure()
    }

    private val signer: PublicKeySign by lazy {
        ownSigPrivate.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
    }
    private val hybridDecrypt: HybridDecrypt by lazy {
        ownHybridPrivate.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
    }

    /**
     * Ed25519-signs arbitrary [bytes] with this device's identity signing key, returning the base64
     * signature. Used for frame-level authentication of flooded frames (see
     * [app.getknit.knit.mesh.protocol.signedBytes]); the E2E [seal] path computes its own signature
     * over the envelope instead.
     */
    fun sign(bytes: ByteArray): String = b64(signer.sign(bytes))

    /** An encrypted, signed message ready to attach to a [app.getknit.knit.mesh.protocol.ChatFrame]. */
    data class Sealed(val envelope: EncEnvelope, val sig: String)

    /**
     * Encrypts [content] to [recipients] (nodeId → published bundle) and signs it. Returns null if there
     * are no recipients with known keys (caller must not send a frame nobody can read).
     */
    fun seal(content: ByteArray, header: ByteArray, recipients: Map<String, PublicKeyBundle>): Sealed? {
        if (recipients.isEmpty()) return null
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, content, header)
        val wrapped = recipients.entries.sortedBy { it.key }.map { (to, bundle) ->
            WrappedKey(to, b64(bundle.hybridEncrypt().encrypt(key, header)))
        }
        val envelope = EncEnvelope(nonce = b64(iv), ct = b64(ct), keys = wrapped)
        val sig = b64(signer.sign(signingBytes(header, envelope)))
        return Sealed(envelope, sig)
    }

    /**
     * Verifies [sig] against [senderBundle], unwraps our copy of the content key, and decrypts. Returns
     * null on ANY failure (bad/missing signature, no wrapped key for us, decryption error) — callers
     * treat null as "drop this message" and must never let it abort relaying.
     */
    fun open(
        envelope: EncEnvelope,
        sig: String?,
        header: ByteArray,
        myNodeId: String,
        senderBundle: PublicKeyBundle,
    ): MessageContent? = runCatching {
        requireNotNull(sig) { "missing signature" }
        senderBundle.verifier().verify(b64d(sig), signingBytes(header, envelope)) // throws on bad sig
        val wrapped = envelope.keys.firstOrNull { it.to == myNodeId } ?: return null
        val key = hybridDecrypt.decrypt(b64d(wrapped.wk), header)
        MessageContent.decode(AesGcm.decrypt(key, b64d(envelope.nonce), b64d(envelope.ct), header))
    }.getOrNull()

    /**
     * Verifies an encrypted message's envelope signature against the sender's published [senderBundle]
     * **without decrypting** — so a relay/carrier (which is not a recipient and holds no wrapped key)
     * can still authenticate a DM before storing it for store-and-forward. Mirrors the signature check
     * [open] performs for the recipient. Returns false on any failure (missing/bad signature) and never
     * throws, so an inbound carry decision can drop-and-continue without aborting the relay.
     */
    fun verifyEnvelope(
        senderBundle: PublicKeyBundle,
        sig: String?,
        header: ByteArray,
        envelope: EncEnvelope,
    ): Boolean = runCatching {
        senderBundle.verifier().verify(b64d(requireNotNull(sig)), signingBytes(header, envelope))
        true
    }.getOrDefault(false)

    /**
     * Canonical bytes signed/verified for an envelope. Length-prefixed and with the wrapped keys sorted
     * by recipient id, so both endpoints derive identical bytes regardless of wire ordering.
     */
    private fun signingBytes(header: ByteArray, envelope: EncEnvelope): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { sink ->
            sink.writeInt(envelope.v)
            sink.writeChunk(header)
            sink.writeChunk(b64d(envelope.nonce))
            sink.writeChunk(b64d(envelope.ct))
            val sorted = envelope.keys.sortedBy { it.to }
            sink.writeInt(sorted.size)
            sorted.forEach {
                sink.writeChunk(it.to.toByteArray())
                sink.writeChunk(b64d(it.wk))
            }
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.writeChunk(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        /** The signed/encrypted header binding a message to its identity and thread. */
        fun header(id: String, senderId: String, sentAt: Long, thread: String): ByteArray =
            "$id|$senderId|$sentAt|$thread".toByteArray()

        /**
         * Verifies a base64 Ed25519 [sig] over [bytes] against a peer's published [senderBundle].
         * Returns false on ANY failure (missing or malformed signature, key mismatch) and never
         * throws, so inbound callers can drop-and-continue without aborting the relay (mirrors how
         * [open] swallows verification failures).
         */
        fun verify(senderBundle: PublicKeyBundle, sig: String?, bytes: ByteArray): Boolean =
            runCatching {
                senderBundle.verifier().verify(b64d(requireNotNull(sig)), bytes)
                true
            }.getOrDefault(false)
    }
}
