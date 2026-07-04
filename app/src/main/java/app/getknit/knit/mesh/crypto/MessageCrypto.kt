package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.WrappedKey
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration

/**
 * The end-to-end message cipher. Holds this device's **private** keysets (hybrid for decrypting wrapped
 * content keys, Ed25519 for signing) and performs the per-message seal/open. Pure logic — keys are
 * injected, no Android or persistence here — so it runs unchanged under JVM unit tests; the Android-only
 * key persistence lives in [app.getknit.knit.data.crypto.IdentityKeyStore].
 *
 * Scheme (static keys, no ratchet — see the E2E design): a fresh random content key encrypts the
 * [MessageContent] with AES-256-GCM into the [EncEnvelope], the content key is wrapped to each
 * recipient's hybrid key, and the AEAD AAD binds the message [header] (id|sender|sentAt|thread). The
 * envelope is *not* signed here — it travels inside an encrypted [app.getknit.knit.mesh.protocol.ChatContent]
 * payload, and the single frame signature over the whole [app.getknit.knit.mesh.protocol.RelayEnvelope]
 * (see `MeshManager.verifyInbound`) authenticates it, so a wrapped key or ciphertext can't be replayed
 * into another message.
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
     * Raw Ed25519 signature over [bytes] with this device's identity signing key. Used to sign the
     * canonical [app.getknit.knit.mesh.protocol.WireEnvelope.signed] blob (the one signature that now
     * authenticates every flooded frame type).
     */
    fun signRaw(bytes: ByteArray): ByteArray = signer.sign(bytes)

    /**
     * Encrypts [content] to [recipients] (nodeId → published bundle). Returns null if there are no
     * recipients with known keys (caller must not send a frame nobody can read). The returned envelope
     * is authenticated by the frame signature, not separately signed here.
     */
    fun seal(
        content: ByteArray,
        header: ByteArray,
        recipients: Map<String, PublicKeyBundle>,
    ): EncEnvelope? {
        if (recipients.isEmpty()) return null
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, content, header)
        val wrapped =
            recipients.entries.sortedBy { it.key }.map { (to, bundle) ->
                WrappedKey(to, bundle.hybridEncrypt().encrypt(key, header))
            }
        return EncEnvelope(nonce = iv, ct = ct, keys = wrapped)
    }

    /**
     * Unwraps our copy of the content key and decrypts. The sender's signature is verified earlier (over
     * the whole frame, in `MeshManager.verifyInbound`), so [open] no longer re-checks it — it only needs
     * our hybrid private key. Returns null on ANY failure (no wrapped key for us, decryption error) —
     * callers treat null as "drop this message" and must never let it abort relaying.
     */
    fun open(
        envelope: EncEnvelope,
        header: ByteArray,
        myNodeId: String,
    ): MessageContent? =
        runCatching {
            val wrapped = envelope.keys.firstOrNull { it.to == myNodeId } ?: return null
            val key = hybridDecrypt.decrypt(wrapped.wk, header)
            MessageContent.decode(AesGcm.decrypt(key, envelope.nonce, envelope.ct, header))
        }.getOrNull()

    companion object {
        /** The encrypted-AEAD header binding a message to its identity and thread. */
        fun header(
            id: String,
            senderId: String,
            sentAt: Long,
            thread: String,
        ): ByteArray = "$id|$senderId|$sentAt|$thread".toByteArray()

        /**
         * Verifies a raw Ed25519 [sig] over [bytes] against a peer's published [senderBundle]. Returns
         * false on ANY failure (missing or malformed signature, key mismatch) and never throws, so
         * inbound callers can drop-and-continue without aborting the relay.
         */
        fun verify(
            senderBundle: PublicKeyBundle,
            sig: ByteArray?,
            bytes: ByteArray,
        ): Boolean =
            runCatching {
                senderBundle.verifier().verify(requireNotNull(sig), bytes)
                true
            }.getOrDefault(false)
    }
}
