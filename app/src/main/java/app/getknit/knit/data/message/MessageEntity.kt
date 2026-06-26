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
)

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
