package app.getknit.knit.data.blob

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /** Hashes of all stored blobs (no BLOB read) — drives the chat's "attachment present yet?" state. */
    @Query("SELECT hash FROM blobs")
    fun observeHashes(): Flow<List<String>>

    /**
     * Blob hashes referenced by no message attachment and no peer avatar. The caller must still exclude
     * the device's own-avatar hash (it lives in DataStore, not a table) before deleting.
     */
    @Query(
        "SELECT hash FROM blobs WHERE " +
            "hash NOT IN (SELECT attachmentHash FROM messages WHERE attachmentHash IS NOT NULL) AND " +
            "hash NOT IN (SELECT avatarHash FROM peers WHERE avatarHash IS NOT NULL)",
    )
    suspend fun orphanHashes(): List<String>

    @Query("DELETE FROM blobs WHERE hash = :hash")
    suspend fun delete(hash: String)
}
