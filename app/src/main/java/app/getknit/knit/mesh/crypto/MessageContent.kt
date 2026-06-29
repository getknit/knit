package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.Mention
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The plaintext payload of an encrypted DM/group message — everything that must stay private. It is
 * CBOR-serialized and AES-256-GCM-encrypted into [app.getknit.knit.mesh.protocol.EncEnvelope.ct]; the
 * cleartext [app.getknit.knit.mesh.protocol.RelayEnvelope] only keeps the routing metadata (id, sender,
 * recipientId/group) that relays need.
 *
 * [v] versions the *decrypted plaintext schema* — deliberately independent of
 * [app.getknit.knit.mesh.protocol.EncEnvelope.v] (the crypto scheme), so the two layers move
 * separately. It rides inside the authenticated ciphertext (it isn't on the wire). An unsupported
 * version is dropped on delivery (see `MeshManager.decrypt`).
 *
 * [attachmentKey] is the base64 AES key for the (separately encrypted, content-addressed by ciphertext
 * hash) image blob referenced by [attachmentHash]; null for text-only messages.
 */
@Serializable
data class MessageContent(
    val v: Int = VERSION,
    val body: String,
    val mentions: List<Mention> = emptyList(),
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(): ByteArray = cryptoCbor.encodeToByteArray(this)

    /** Whether this build understands this content schema version. */
    fun isSupported(): Boolean = v <= MAX_SUPPORTED

    companion object {
        /** Current plaintext-content schema version this build originates. */
        const val VERSION = 1

        /** Highest content schema version this build can read; a higher [v] is dropped on delivery. */
        const val MAX_SUPPORTED = 1

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): MessageContent? =
            runCatching { cryptoCbor.decodeFromByteArray<MessageContent>(bytes) }.getOrNull()
    }
}
