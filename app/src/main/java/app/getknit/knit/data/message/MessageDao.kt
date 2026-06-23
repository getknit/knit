package app.getknit.knit.data.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY sentAt ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("UPDATE messages SET received = 1 WHERE id = :id")
    suspend fun markReceived(id: String)

    /** Sets the local file path on every message referencing [hash] (an attachment just arrived). */
    @Query("UPDATE messages SET attachmentPath = :path WHERE attachmentHash = :hash")
    suspend fun setAttachmentPath(hash: String, path: String)

    /** Attachment hashes referenced by stored messages whose bytes we don't yet have locally. */
    @Query("SELECT DISTINCT attachmentHash FROM messages WHERE attachmentHash IS NOT NULL AND attachmentPath IS NULL")
    suspend fun hashesNeedingFetch(): List<String>
}
