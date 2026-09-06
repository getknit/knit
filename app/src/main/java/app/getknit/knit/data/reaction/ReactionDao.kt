package app.getknit.knit.data.reaction

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    /** Live reactions for the UI — tombstones (retracted, emoji IS NULL) are excluded. */
    @Query("SELECT * FROM reactions WHERE emoji IS NOT NULL ORDER BY updatedAt ASC")
    fun observeAll(): Flow<List<ReactionEntity>>

    /**
     * Live reactions on one thread's messages, oldest first — what a chat screen actually needs. Same
     * tombstone filter as [observeAll]; the join is what narrows it, since a reaction row names only its
     * message. A chat screen used to collect [observeAll] and then group the whole table by message id on
     * every emission, so a reaction in some other conversation rebuilt this one's rows.
     */
    @Query(
        "SELECT r.* FROM reactions r JOIN messages m ON m.id = r.messageId " +
            "WHERE m.conversationId = :conversationId AND r.emoji IS NOT NULL ORDER BY r.updatedAt ASC",
    )
    fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>>

    /**
     * Live reactions on one message, oldest first — the per-reactor rows the message-details screen
     * lists by name. Same tombstone filter as [observeAll] (a retraction is `emoji IS NULL`, not a
     * deleted row), narrowed by the existing `messageId` index.
     */
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND emoji IS NOT NULL ORDER BY updatedAt ASC")
    fun observeForMessage(messageId: String): Flow<List<ReactionEntity>>

    /** The stored last-writer-wins clock for this reactor on this message, or null if none yet. */
    @Query("SELECT updatedAt FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun updatedAtFor(
        messageId: String,
        reactorNodeId: String,
    ): Long?

    /** The reactor's current emoji on this message (null if none/retracted) — drives toggle logic. */
    @Query("SELECT emoji FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun emojiFor(
        messageId: String,
        reactorNodeId: String,
    ): String?

    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    /** Drops every reaction for a message (there is no FK cascade) when the message is deleted. */
    @Query("DELETE FROM reactions WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    /**
     * Reclaims reaction rows whose target message is no longer stored (the table has no FK cascade) and
     * that are older than [olderThan] (epoch ms). The age floor spares a reaction that legitimately
     * arrived before its message via out-of-order mesh delivery — see [ReactionEntity].
     */
    @Query(
        "DELETE FROM reactions WHERE updatedAt < :olderThan AND " +
            "messageId NOT IN (SELECT id FROM messages)",
    )
    suspend fun deleteOrphansOlderThan(olderThan: Long)
}
