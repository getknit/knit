package app.getknit.knit.data.pin

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {
    /**
     * Pins on one thread's messages, newest pin first, each with its message — the pinned bar. The join is what
     * narrows the table to the thread, since a pin row names only its message; it also drops a pin whose message this
     * phone no longer holds. A same-millisecond tie is broken by message id so the bar's order is the same on every
     * emission.
     */
    @Query(
        "SELECT m.*, p.pinnedAt AS pinnedAt FROM pins p JOIN messages m ON m.id = p.messageId " +
            "WHERE m.conversationId = :conversationId ORDER BY p.pinnedAt DESC, p.messageId DESC",
    )
    fun observePinnedIn(conversationId: String): Flow<List<PinnedMessage>>

    /** The message ids of one thread's pins, oldest pin first — the order a full thread lets them go in. */
    @Query(
        "SELECT p.messageId FROM pins p JOIN messages m ON m.id = p.messageId " +
            "WHERE m.conversationId = :conversationId ORDER BY p.pinnedAt ASC, p.messageId ASC",
    )
    suspend fun pinIdsIn(conversationId: String): List<String>

    /** The newest pin stamp in one thread, or null when nothing there is pinned — the floor for the next pin's stamp. */
    @Query("SELECT MAX(p.pinnedAt) FROM pins p JOIN messages m ON m.id = p.messageId WHERE m.conversationId = :conversationId")
    suspend fun newestPinIn(conversationId: String): Long?

    /** Whether this message is pinned. */
    @Query("SELECT EXISTS(SELECT 1 FROM pins WHERE messageId = :messageId)")
    suspend fun isPinned(messageId: String): Boolean

    /** The thread a message sits in, or null when this phone does not hold it — a pin is only made on a held message. */
    @Query("SELECT conversationId FROM messages WHERE id = :messageId")
    suspend fun conversationOf(messageId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: PinEntity)

    /** Unpins a message, and drops a deleted message's pin (there is no FK cascade). Returns the rows removed. */
    @Query("DELETE FROM pins WHERE messageId = :messageId")
    suspend fun delete(messageId: String): Int

    /** Unpins every message [senderId] sent, in every thread — what blocking them does to their pins. */
    @Query("DELETE FROM pins WHERE messageId IN (SELECT id FROM messages WHERE senderId = :senderId)")
    suspend fun deleteFromSender(senderId: String)

    /**
     * Reclaims pin rows whose message is no longer stored (the table has no FK cascade), and pins on a message from
     * someone in [blocked], which blocking them should already have removed. No age floor, unlike the reactions' sweep:
     * a pin is only ever made on a message this phone holds, so none can be waiting for its message to arrive.
     */
    @Query(
        "DELETE FROM pins WHERE messageId NOT IN (SELECT id FROM messages) " +
            "OR messageId IN (SELECT id FROM messages WHERE senderId IN (:blocked))",
    )
    suspend fun deleteOrphans(blocked: Set<String>)
}
