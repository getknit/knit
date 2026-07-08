package app.getknit.knit.data.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// A data-access interface: one method per query, so the count naturally exceeds detekt's interface limit.
@Suppress("TooManyFunctions")
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

    /**
     * The [MessageEntity.recipientId] of the stored message [id], or null when it's a broadcast/group
     * message OR no such message is held. Lets [markReceived] reject a receipt whose sender isn't the
     * message's addressed DM recipient (a forged-ack guard); broadcast/group keep the best-effort tick.
     */
    @Query("SELECT recipientId FROM messages WHERE id = :id")
    suspend fun recipientOf(id: String): String?

    @Query("UPDATE messages SET received = 1 WHERE id = :id")
    suspend fun markReceived(id: String)

    /** How many messages in [conversationId] were authored by [me] — nonzero means the user has replied there. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderId = :me")
    suspend fun countMineIn(
        conversationId: String,
        me: String,
    ): Int

    /** Outgoing DMs to [recipientId] saved while their key was unknown, awaiting retransmit on key arrival. */
    @Query("SELECT * FROM messages WHERE recipientId = :recipientId AND pendingKey = 1")
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity>

    /** Clears the [MessageEntity.pendingKey] flag once a stuck DM has been sealed and flooded. */
    @Query("UPDATE messages SET pendingKey = 0 WHERE id = :id")
    suspend fun clearPending(id: String)

    /** Removes a single message locally (used by the long-press "Delete message" action). */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Removes every message in a thread (used when leaving a group, so the thread vanishes). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** How many messages still reference [hash] — guards deleting a shared, content-addressed blob. */
    @Query("SELECT COUNT(*) FROM messages WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /**
     * The (base64) per-attachment key stored with a message referencing the ciphertext [hash], if any —
     * used to decrypt a just-pulled E2E attachment blob so its plaintext can be screened. Null for a
     * plaintext (broadcast) attachment or when no such message is stored (e.g. we only relayed the blob).
     */
    @Query(
        "SELECT attachmentKey FROM messages " +
            "WHERE attachmentHash = :hash AND attachmentKey IS NOT NULL LIMIT 1",
    )
    suspend fun attachmentKeyForHash(hash: String): String?

    /** Attachment hashes referenced by stored messages whose bytes aren't in the `blobs` table yet. */
    @Query(
        "SELECT DISTINCT attachmentHash FROM messages " +
            "WHERE attachmentHash IS NOT NULL AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun hashesNeedingFetch(): List<String>

    /** Distinct conversations the local user ([me]) has authored a message in — the "threads I started" signal. */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE senderId = :me")
    suspend fun conversationsIAuthoredIn(me: String): List<String>

    /** Per-conversation row count + newest sentAt, for the retention sweep's cap / age / thread-count decisions. */
    @Query("SELECT conversationId, MAX(sentAt) AS lastSentAt, COUNT(*) AS count FROM messages GROUP BY conversationId")
    suspend fun conversationActivity(): List<ConversationActivity>

    /** Keeps only the newest [keep] messages (by sentAt) in [conversationId], deleting the rest. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND id NOT IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun deleteOldestInConversation(
        conversationId: String,
        keep: Int,
    )

    /** Deletes messages in [conversationId] older than [cutoff] (frame-global sentAt). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sentAt < :cutoff")
    suspend fun deleteOlderThan(
        conversationId: String,
        cutoff: Long,
    )
}

/** Room projection for [MessageDao.conversationActivity]: a thread's id, newest message time, and row count. */
data class ConversationActivity(
    val conversationId: String,
    val lastSentAt: Long,
    val count: Int,
)
