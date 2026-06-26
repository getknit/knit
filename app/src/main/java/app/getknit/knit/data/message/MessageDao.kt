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

    /** Attachment hashes referenced by stored messages whose bytes aren't in the `blobs` table yet. */
    @Query(
        "SELECT DISTINCT attachmentHash FROM messages " +
            "WHERE attachmentHash IS NOT NULL AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun hashesNeedingFetch(): List<String>
}
