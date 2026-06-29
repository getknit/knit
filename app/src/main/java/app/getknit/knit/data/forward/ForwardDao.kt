package app.getknit.knit.data.forward

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ForwardDao {

    /** Stores [row]; keyed by frame id, so a frame we already carry is silently ignored (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ForwardEntity)

    /** Canonical bytes of every non-expired carried frame, newest first — the push-on-contact source. */
    @Query("SELECT bytes FROM forward_store WHERE expiresAt >= :now ORDER BY receivedAt DESC")
    suspend fun liveBytes(now: Long): List<ByteArray>

    @Query("SELECT EXISTS(SELECT 1 FROM forward_store WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** The carried DM's cleartext recipient, or null if not held — gates the recipient-authenticated purge. */
    @Query("SELECT recipientId FROM forward_store WHERE id = :id")
    suspend fun recipientOf(id: String): String?

    @Query("DELETE FROM forward_store WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM forward_store WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM forward_store")
    suspend fun count(): Int

    /** How many carried frames a single [senderId] accounts for — enforces the per-sender quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE senderId = :senderId")
    suspend fun countBySender(senderId: String): Int

    /**
     * Drops the [n] lowest-priority rows under cap pressure: relayed frames (origin 0) before our own
     * (origin 1), oldest first within each. Keeps this device's own outbox alive longest.
     */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store ORDER BY origin ASC, receivedAt ASC LIMIT :n)",
    )
    suspend fun evictOldest(n: Int)
}
