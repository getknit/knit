package app.getknit.knit.data.blob

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** One stored blob's identity and size, projected by [BlobDao.observeSizes] without reading the bytes. */
data class BlobSize(
    val hash: String,
    val size: Int,
)

@Dao
interface BlobDao {
    /** Stores [blob]; content-addressed, so a hash we already hold is silently ignored (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blob: BlobEntity)

    /** The stored bytes for [hash], or null if not held. The only query that reads the BLOB column. */
    @Query("SELECT bytes FROM blobs WHERE hash = :hash")
    suspend fun bytes(hash: String): ByteArray?

    @Query("SELECT mime FROM blobs WHERE hash = :hash")
    suspend fun mimeFor(hash: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM blobs WHERE hash = :hash)")
    suspend fun exists(hash: String): Boolean

    /**
     * Hash and byte length of the stored blobs among [hashes] — drives both the chat's "attachment present
     * yet?" state (key membership) and its "can this attachment cross a relay?" state (the size). A hash we
     * do not hold simply has no row.
     *
     * Keyed rather than the whole table on purpose: Room invalidates per table, so this re-runs on every
     * blob write anywhere in the app while a chat is open, and a table walk decrypted every leaf page of
     * the largest table in the database each time. Against the primary key it is one seek per asked hash.
     * No BLOB read either: like [carrierOnlyBlobBytes], `length(bytes)` reads the row's length varint rather
     * than decrypting the payload, so widening this from a bare hash list costs nothing per row.
     */
    @Query("SELECT hash, length(bytes) AS size FROM blobs WHERE hash IN (:hashes)")
    fun observeSizes(hashes: List<String>): Flow<List<BlobSize>>

    /**
     * Blob hashes referenced by no message attachment, no peer avatar, no group photo, and no carried
     * store-and-forward frame. The caller must still exclude the device's own-avatar hash (it lives in
     * DataStore, not a table) before deleting. The forward_store clause is what makes a carrier's custodied
     * image blob durable — held (not reclaimed as an orphan) for as long as the frame that references it is
     * carried, so it survives to a late joiner.
     */
    @Query(
        "SELECT hash FROM blobs WHERE " +
            "hash NOT IN (SELECT attachmentHash FROM messages WHERE attachmentHash IS NOT NULL) AND " +
            "hash NOT IN (SELECT avatarHash FROM peers WHERE avatarHash IS NOT NULL) AND " +
            "hash NOT IN (SELECT photoHash FROM groups WHERE photoHash IS NOT NULL) AND " +
            "hash NOT IN (SELECT attachmentHash FROM forward_store WHERE attachmentHash IS NOT NULL)",
    )
    suspend fun orphanHashes(): List<String>

    /**
     * Total bytes of blobs held *purely* for store-and-forward custody — referenced by a carried frame but by
     * no local `messages` row (our own sends and delivered messages are uncapped, kept via that message ref).
     * Bounds the altruistic carrier-only footprint; `length(bytes)` reads the row-header length varint, so it
     * does not decrypt the BLOB. Because these blobs are NOT folded into the content digest, this budget is a
     * purely *local* knob and need not match across nodes (unlike the frame quotas).
     */
    @Query(
        "SELECT COALESCE(SUM(length(bytes)), 0) FROM blobs WHERE " +
            "hash IN (SELECT attachmentHash FROM forward_store WHERE attachmentHash IS NOT NULL) AND " +
            "hash NOT IN (SELECT attachmentHash FROM messages WHERE attachmentHash IS NOT NULL)",
    )
    suspend fun carrierOnlyBlobBytes(): Long

    @Query("DELETE FROM blobs WHERE hash = :hash")
    suspend fun delete(hash: String)
}
