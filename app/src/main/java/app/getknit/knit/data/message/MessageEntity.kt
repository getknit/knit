package app.getknit.knit.data.message

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.getknit.knit.mesh.protocol.Mention
import kotlinx.serialization.json.Json

/**
 * A chat message as stored on this device. [id] is the globally-unique wire id (also the dedup key
 * across the mesh). [recipientId] is null for broadcast-room messages and set for 1:1 DMs.
 * [conversationId] groups messages into a thread ([Conversations.NEARBY] for the room, the other
 * party's node id for a DM) and is indexed for the per-thread chat queries. [received] is the
 * delivery-ack flag for messages this device sent (drives the ✓/✓✓ tick).
 *
 * [mentions] is a JSON-encoded `List<Mention>` ("[]" when none); kept as a string so Room stays a
 * plain TEXT column and (de)serialization lives with the [Mention] type via [MentionStore].
 *
 * [attachmentHash]/[attachmentMime] reference an out-of-band image blob fetched by content hash; the
 * bytes live in the encrypted `blobs` table (see [app.getknit.knit.data.blob.BlobEntity]), keyed by
 * [attachmentHash], and are null until the blob has been pulled from the mesh.
 *
 * [attachmentKey] is the base64 AES key for an end-to-end-encrypted attachment: in a DM/group the blob
 * bytes are ciphertext (content-addressed by their ciphertext hash), so the UI must decrypt them with
 * this key before decoding. Null for plaintext (broadcast-room) attachments and text-only messages.
 *
 * [moderation] records an on-device content-moderation verdict for the [body] ([MODERATION_NONE] or
 * [MODERATION_TEXT_FLAGGED]). A flagged inbound message is still stored, but the UI collapses it behind
 * a tap-to-reveal rather than dropping it (so a false positive never loses content).
 *
 * [pendingKey] marks an outgoing DM that was saved locally but could not yet be sealed/flooded because
 * the recipient's public key wasn't known (distinct from [received], which can't tell "never sent" from
 * "sent, awaiting ack"). It stays true until the recipient's profile arrives and `MeshManager` re-seals
 * and floods it (see `flushPendingFor`). Always false for received messages and broadcast/group sends.
 *
 * [kind] discriminates an ordinary chat message ([KIND_NORMAL]) from a locally-generated status notice
 * ([KIND_MEMBER_LEFT], rendered as a centered "X left the chat" line rather than a bubble). A status row
 * has an empty [body] and its [senderId] is the subject of the event (the member who left).
 */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String? = null,
    val conversationId: String = Conversations.NEARBY,
    val body: String,
    val sentAt: Long,
    val received: Boolean = false,
    val mentions: String = "[]",
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
    val moderation: Int = MODERATION_NONE,
    val pendingKey: Boolean = false,
    val kind: Int = KIND_NORMAL,
) {
    companion object {
        /** [moderation]: text passed (or was not checked). */
        const val MODERATION_NONE = 0

        /** [moderation]: the body was flagged as abusive by the on-device text moderator. */
        const val MODERATION_TEXT_FLAGGED = 1

        /** [kind]: an ordinary chat message, shown as a sender bubble. */
        const val KIND_NORMAL = 0

        /** [kind]: a "member left the group" status notice, shown as a centered line. */
        const val KIND_MEMBER_LEFT = 1
    }
}

/**
 * Encodes/decodes the [MessageEntity.mentions] JSON column. Its own [Json] instance (WireCodec's is
 * private); a malformed/legacy value decodes to an empty list rather than crashing rendering.
 */
object MentionStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(mentions: List<Mention>): String = json.encodeToString(mentions)

    fun decode(stored: String): List<Mention> =
        runCatching { json.decodeFromString<List<Mention>>(stored) }.getOrDefault(emptyList())
}
