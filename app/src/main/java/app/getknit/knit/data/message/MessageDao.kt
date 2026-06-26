package app.getknit.knit.data.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY sentAt ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("UPDATE messages SET received = 1 WHERE id = :id")
    suspend fun markReceived(id: String)

    /** Removes a single message locally (used by the long-press "Delete message" action). */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** How many messages still reference [hash] — guards deleting a shared, content-addressed blob. */
    @Query("SELECT COUNT(*) FROM messages WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /** Sets the local file path on every message referencing [hash] (an attachment just arrived). */
    @Query("UPDATE messages SET attachmentPath = :path WHERE attachmentHash = :hash")
    suspend fun setAttachmentPath(hash: String, path: String)

    /** Attachment hashes referenced by stored messages whose bytes we don't yet have locally. */
    @Query("SELECT DISTINCT attachmentHash FROM messages WHERE attachmentHash IS NOT NULL AND attachmentPath IS NULL")
    suspend fun hashesNeedingFetch(): List<String>
}
