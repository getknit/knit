package app.getknit.knit.data.reaction

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {

    /** Live reactions for the UI — tombstones (retracted, emoji IS NULL) are excluded. */
    @Query("SELECT * FROM reactions WHERE emoji IS NOT NULL ORDER BY updatedAt ASC")
    fun observeAll(): Flow<List<ReactionEntity>>

    /** The stored last-writer-wins clock for this reactor on this message, or null if none yet. */
    @Query("SELECT updatedAt FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun updatedAtFor(messageId: String, reactorNodeId: String): Long?

    /** The reactor's current emoji on this message (null if none/retracted) — drives toggle logic. */
    @Query("SELECT emoji FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun emojiFor(messageId: String, reactorNodeId: String): String?

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
