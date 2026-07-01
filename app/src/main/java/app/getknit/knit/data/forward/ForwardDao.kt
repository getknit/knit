package app.getknit.knit.data.forward

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
@Suppress("TooManyFunctions") // a handful of small carry-store queries; splitting them would obscure, not clarify
interface ForwardDao {

    /** Stores [row]; keyed by frame id, so a frame we already carry is silently ignored (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ForwardEntity)

    /** Every non-expired carried frame, newest first — the push-on-contact source. */
    @Query("SELECT * FROM forward_store WHERE expiresAt >= :now ORDER BY receivedAt DESC")
    suspend fun liveRows(now: Long): List<ForwardEntity>

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

    /** How many carried frames a single [groupId] accounts for — enforces the per-group quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE groupId = :groupId")
    suspend fun countByGroup(groupId: String): Int

    /**
     * How many carried broadcast-room frames are held — enforces the broadcast quota. Broadcast rows are
     * the only ones with no recipient and no group (that pair uniquely identifies them), so no schema
     * discriminator is needed.
     */
    @Query("SELECT COUNT(*) FROM forward_store WHERE recipientId IS NULL AND groupId IS NULL")
    suspend fun countBroadcast(): Int

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
