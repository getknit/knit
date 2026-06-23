package app.getknit.knit.data

import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Single source of truth for chat messages. */
class MessageRepository(private val dao: MessageDao) {

    fun observeMessages(): Flow<List<MessageEntity>> = dao.observeAll()

    suspend fun save(message: MessageEntity) = dao.upsert(message)

    suspend fun exists(id: String): Boolean = dao.exists(id)

    suspend fun markReceived(id: String) = dao.markReceived(id)
}
