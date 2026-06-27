package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.Mention
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The plaintext payload of an encrypted DM/group message — everything that must stay private. It is
 * CBOR-serialized and AES-256-GCM-encrypted into [app.getknit.knit.mesh.protocol.EncEnvelope.ct]; the
 * cleartext [app.getknit.knit.mesh.protocol.ChatFrame] only keeps the routing metadata (id, sender,
 * recipientId/group) that relays need.
 *
 * [attachmentKey] is the base64 AES key for the (separately encrypted, content-addressed by ciphertext
 * hash) image blob referenced by [attachmentHash]; null for text-only messages.
 */
@Serializable
data class MessageContent(
    val body: String,
    val mentions: List<Mention> = emptyList(),
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(): ByteArray = cryptoCbor.encodeToByteArray(this)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): MessageContent? =
            runCatching { cryptoCbor.decodeFromByteArray<MessageContent>(bytes) }.getOrNull()
    }
}
