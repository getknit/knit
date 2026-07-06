package app.getknit.knit.data.forward

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * An `(id, expiresAt)` projection of a live carried frame — the rebuild source for the
 * [app.getknit.knit.mesh.StoreDigest] content digest, which needs the expiry alongside the id to drive its
 * lazy TTL-boundary fold (work item #8).
 */
data class ForwardIdExpiry(
    val id: String,
    val expiresAt: Long,
)

/**
 * Carry-store queries. **Every quota/eviction/digest query is live-filtered (`expiresAt >= :now`)**: an
 * expired-but-unswept row is per-node state (sweep ticks phase independently), so any bounding rule that
 * reads it would evict different *live* rows on different nodes and de-converge the content digest — the
 * convergent-custody rule from AGENTS.md. Expired rows are invisible to everything observable and exist only
 * until [deleteExpired] (the periodic sweep, now pure storage GC) reclaims them; the physical table is thus
 * bounded by the caps plus at most one sweep period of lapsed residue.
 */
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

    /** Live carried frames — the global-cap count (an expired-unswept row must not push a live one out). */
    @Query("SELECT COUNT(*) FROM forward_store WHERE expiresAt >= :now")
    suspend fun count(now: Long): Int

    /** Every carried frame id, expired residue included — diagnostics/tests only (the digest folds live ids). */
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
     * Deliberately wider than what the digest folds (live rows only), so a dump can show the benign lapsed
     * residue awaiting the sweep alongside the live set the cue plane actually advertises.
     */
    @Query("SELECT * FROM forward_store ORDER BY receivedAt DESC")
    suspend fun allRows(): List<ForwardEntity>

    /** Ids of every non-expired carried frame — advertised on link-up so a peer replies with only what we lack. */
    @Query("SELECT id FROM forward_store WHERE expiresAt >= :now")
    suspend fun liveIds(now: Long): List<String>

    /** `(id, expiresAt)` of every non-expired carried frame — the digest rebuild source (init, sweep, eviction). */
    @Query("SELECT id, expiresAt FROM forward_store WHERE expiresAt >= :now")
    suspend fun liveIdExpiries(now: Long): List<ForwardIdExpiry>

    /** How many live carried frames a single [senderId] accounts for — enforces the per-sender quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE senderId = :senderId AND expiresAt >= :now")
    suspend fun countBySender(
        senderId: String,
        now: Long,
    ): Int

    /** How many live carried frames a single [groupId] accounts for — enforces the per-group quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE groupId = :groupId AND expiresAt >= :now")
    suspend fun countByGroup(
        groupId: String,
        now: Long,
    ): Int

    /**
     * How many live carried broadcast-room *chat* frames are held — enforces the broadcast quota. Reactions,
     * receipts, and profiles share the null recipient/group shape, so the [ForwardEntity.type] discriminator
     * is required to count only broadcast chat and not starve those metadata frames on the broadcast quota.
     */
    @Query(
        "SELECT COUNT(*) FROM forward_store " +
            "WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL AND expiresAt >= :now",
    )
    suspend fun countBroadcast(now: Long): Int

    /**
     * Drops the [n] oldest **live** rows (by [ForwardEntity.sentAt], id as tie-break) under global-cap pressure.
     * Ordered by the frame-global sentAt rather than local receivedAt/origin so every node evicts the *same*
     * rows and their content digests stay convergent. Live-filtered because buckets mix TTL classes: an
     * expired-unswept short-TTL row can be *newer by sentAt* than a live long-TTL row, so counting/evicting
     * over all rows would push a live frame out on the unswept node only — de-converging the live sets the
     * digest now folds (work item #8).
     */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE expiresAt >= :now ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldest(
        n: Int,
        now: Long,
    )

    /** Evicts the [n] oldest-by-sentAt **live** frames from [senderId]'s bucket (keeps its newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE senderId = :senderId AND expiresAt >= :now " +
            "ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBySender(
        senderId: String,
        n: Int,
        now: Long,
    )

    /** Evicts the [n] oldest-by-sentAt **live** frames from [groupId]'s bucket (keeps its newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE groupId = :groupId AND expiresAt >= :now " +
            "ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestByGroup(
        groupId: String,
        n: Int,
        now: Long,
    )

    /** Evicts the [n] oldest-by-sentAt **live** broadcast-room chat frames (keeps the newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL " +
            "AND expiresAt >= :now ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBroadcast(
        n: Int,
        now: Long,
    )
}
