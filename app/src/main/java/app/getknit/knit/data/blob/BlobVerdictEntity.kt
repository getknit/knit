package app.getknit.knit.data.blob

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cached on-device NSFW verdict for an image blob, keyed by the same SHA-256 content [hash] as
 * [BlobEntity]. Because the hash addresses the content, a verdict computed when an image is sent is
 * reused when the identical bytes are received (and vice-versa), so each distinct image is scanned at
 * most once. [flagged] true means the image is explicit and should be blurred behind a tap-to-reveal;
 * [score] is the classifier confidence in `[0, 1]`.
 */
@Entity(tableName = "blob_verdicts")
data class BlobVerdictEntity(
    @PrimaryKey val hash: String,
    val flagged: Boolean,
    val score: Float,
)

@Dao
interface BlobVerdictDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: BlobVerdictEntity)

    @Query("SELECT * FROM blob_verdicts WHERE hash = :hash")
    suspend fun find(hash: String): BlobVerdictEntity?

    /** Hashes of all blobs flagged as explicit — drives the chat's "blur this attachment?" state. */
    @Query("SELECT hash FROM blob_verdicts WHERE flagged = 1")
    fun observeFlaggedHashes(): Flow<List<String>>

    @Query("DELETE FROM blob_verdicts WHERE hash = :hash")
    suspend fun delete(hash: String)
}
