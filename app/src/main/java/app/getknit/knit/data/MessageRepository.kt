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

    suspend fun markReceived(id: String) = dao.markReceived(id)

    suspend fun setAttachmentPath(hash: String, path: String) = dao.setAttachmentPath(hash, path)

    suspend fun hashesNeedingFetch(): List<String> = dao.hashesNeedingFetch()
}
