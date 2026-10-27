package app.getknit.knit.data.pin

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {
    /**
     * Live pins on one thread's messages, newest pin first, each with its message — the pinned bar. Tombstones
     * (`pinnedBy IS NULL`) are excluded, and the join is what narrows the table to the thread, since a pin row names
     * only its message; it also drops a pin whose message this phone no longer holds. A same-millisecond tie is
     * broken by message id so the bar's order is the same on every emission.
     */
    @Query(
        "SELECT m.*, p.pinnedBy AS pinnedBy, p.updatedAt AS pinnedAt FROM pins p JOIN messages m ON m.id = p.messageId " +
            "WHERE m.conversationId = :conversationId AND p.pinnedBy IS NOT NULL ORDER BY p.updatedAt DESC, p.messageId DESC",
    )
    fun observePinnedIn(conversationId: String): Flow<List<PinnedMessage>>

    /** The message ids of one thread's live pins, oldest pin first — the order a full thread lets them go in. */
    @Query(
        "SELECT p.messageId FROM pins p JOIN messages m ON m.id = p.messageId " +
            "WHERE m.conversationId = :conversationId AND p.pinnedBy IS NOT NULL ORDER BY p.updatedAt ASC, p.messageId ASC",
    )
    suspend fun livePinIdsIn(conversationId: String): List<String>

    /** The stored row for this message (a tombstone included), or null if it was never pinned — [PinRepository.apply]'s read. */
    @Query("SELECT * FROM pins WHERE messageId = :messageId")
    suspend fun find(messageId: String): PinEntity?

    /** The thread a message sits in, or null when this phone does not hold it — a pin is only made on a held message. */
    @Query("SELECT conversationId FROM messages WHERE id = :messageId")
    suspend fun conversationOf(messageId: String): String?

    @Upsert
    suspend fun upsert(pin: PinEntity)

    /** Drops a message's pin row (there is no FK cascade) when the message is deleted. */
    @Query("DELETE FROM pins WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    /**
     * Reclaims pin rows whose message is no longer stored (the table has no FK cascade) and that are older than
     * [olderThan] (epoch ms) — [app.getknit.knit.data.reaction.ReactionDao.deleteOrphansOlderThan]'s shape, whose age
     * floor will spare a shared pin that arrives ahead of its message.
     */
    @Query("DELETE FROM pins WHERE updatedAt < :olderThan AND messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphansOlderThan(olderThan: Long)
}
