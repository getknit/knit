package app.getknit.knit.data.message

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A chat message as stored on this device. [id] is the globally-unique wire id (also the dedup key
 * across the mesh). [recipientId] is null for broadcast-room messages and set for future 1:1 DMs.
 * [received] is the delivery-ack flag for messages this device sent (drives the ✓ tick).
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String? = null,
    val body: String,
    val sentAt: Long,
    val received: Boolean = false,
)
