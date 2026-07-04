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

    /** Every carried frame id — the source for (re)building the [app.getknit.knit.mesh.StoreDigest] content digest. */
    @Query("SELECT id FROM forward_store")
    suspend fun allIds(): List<String>

    /** How many carried frames reference [hash] as their image blob — pins the blob against GC while any do. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /**
     * Content hashes referenced by a carried frame whose bytes aren't in the `blobs` table yet — the carrier's
     * side of the "still-missing blobs" set (mirrors [app.getknit.knit.data.message.MessageDao.hashesNeedingFetch]).
     * Re-requested on startup / neighbour-join so a carrier keeps trying to pull the image it's custodying.
     */
    @Query(
        "SELECT DISTINCT attachmentHash FROM forward_store " +
            "WHERE attachmentHash IS NOT NULL AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun attachmentHashesNeedingFetch(): List<String>

    /**
     * Every carried frame including expired-but-unswept rows, newest first — the `debug.STORE` bridge dump.
     * Mirrors what [allIds] (hence the content digest) covers, so a dump can show *why* the digest differs from
     * what a sync (which only exchanges [liveRows]) can reconcile.
     */
    @Query("SELECT * FROM forward_store ORDER BY receivedAt DESC")
    suspend fun allRows(): List<ForwardEntity>

    /** Ids of every non-expired carried frame — advertised on link-up so a peer replies with only what we lack. */
    @Query("SELECT id FROM forward_store WHERE expiresAt >= :now")
    suspend fun liveIds(now: Long): List<String>

    /** How many carried frames a single [senderId] accounts for — enforces the per-sender quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE senderId = :senderId")
    suspend fun countBySender(senderId: String): Int

    /** How many carried frames a single [groupId] accounts for — enforces the per-group quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE groupId = :groupId")
    suspend fun countByGroup(groupId: String): Int

    /**
     * How many carried broadcast-room *chat* frames are held — enforces the broadcast quota. Reactions,
     * receipts, and profiles share the null recipient/group shape, so the [ForwardEntity.type] discriminator
     * is required to count only broadcast chat and not starve those metadata frames on the broadcast quota.
     */
    @Query("SELECT COUNT(*) FROM forward_store WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL")
    suspend fun countBroadcast(): Int

    /**
     * Drops the [n] oldest rows (by [ForwardEntity.sentAt], id as tie-break) under global-cap pressure. Ordered
     * by the frame-global sentAt rather than local receivedAt/origin so every node evicts the *same* rows and
     * their content digests stay convergent — the old "relayed-before-our-own, oldest-received-first" order was
     * per-node, so nodes kept different sets and never converged.
     */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldest(n: Int)

    /** Evicts the [n] oldest-by-sentAt frames from [senderId]'s bucket (keeps its newest per the per-sender quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE senderId = :senderId ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBySender(
        senderId: String,
        n: Int,
    )

    /** Evicts the [n] oldest-by-sentAt frames from [groupId]'s bucket (keeps its newest per the per-group quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE groupId = :groupId ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestByGroup(
        groupId: String,
        n: Int,
    )

    /** Evicts the [n] oldest-by-sentAt broadcast-room chat frames (keeps the newest per the broadcast quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL " +
            "ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBroadcast(n: Int)
}
