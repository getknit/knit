package app.getknit.knit.data

import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Single source of truth for chat messages. */
class MessageRepository(private val dao: MessageDao) {

    fun observeMessages(): Flow<List<MessageEntity>> = dao.observeAll()

    /** Messages in a single thread (the broadcast room or a 1:1 DM), oldest first. */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeForConversation(conversationId)

    suspend fun save(message: MessageEntity) = dao.upsert(message)

    suspend fun exists(id: String): Boolean = dao.exists(id)

    /** The DM recipient of message [id], or null for a broadcast/group message or one we don't hold. */
    suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    suspend fun markReceived(id: String) = dao.markReceived(id)

    /** Outgoing DMs to [recipientId] that are still awaiting the recipient's key before they can be sent. */
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity> =
        dao.pendingForRecipient(recipientId)

    /** Clears the pending-key flag once a stuck DM has finally been sealed and flooded. */
    suspend fun clearPending(id: String) = dao.clearPending(id)

    /** Deletes a single message from this device only. */
    suspend fun delete(id: String) = dao.deleteById(id)

    /** Deletes all messages in a thread from this device only (used when leaving a group). */
    suspend fun deleteByConversation(conversationId: String) = dao.deleteByConversation(conversationId)

    /** Number of messages still referencing [hash] (0 once an attachment's last message is gone). */
    suspend fun countByAttachmentHash(hash: String): Int = dao.countByAttachmentHash(hash)

    /** Base64 per-attachment key for an encrypted attachment by its ciphertext [hash], if stored. */
    suspend fun attachmentKeyForHash(hash: String): String? = dao.attachmentKeyForHash(hash)

    suspend fun hashesNeedingFetch(): List<String> = dao.hashesNeedingFetch()
}
