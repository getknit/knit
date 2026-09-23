package app.getknit.knit.data.blob

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * Where the user saved a received file, so the next tap on its bubble opens that copy instead of asking
 * again (ADR 2026-09.7ad3). Keyed by the attachment's blob [hash] — for a sealed attachment the ciphertext
 * hash, which is unique to the one message that carried it — and holding the document [uri] the storage
 * picker returned, which Knit keeps a persisted read grant on.
 *
 * In the encrypted database rather than the DataStore because a document URI usually spells out the file's
 * name, which is message content. Local to this phone: the URI names this phone's storage and the grant
 * behind it is this install's, so a backup never carries the table (`BackupTables.DEVICE_LOCAL`). The row
 * goes with its blob ([app.getknit.knit.data.BlobRepository]'s GC deletes both), and is dropped early when
 * the copy it names has been moved or deleted.
 */
@Entity(tableName = "saved_files")
data class SavedFileEntity(
    @PrimaryKey val hash: String,
    val uri: String,
    val savedAt: Long,
)

@Dao
interface SavedFileDao {
    /** The document the attachment [hash] was last saved to, or null if it never was. */
    @Query("SELECT uri FROM saved_files WHERE hash = :hash")
    suspend fun uriFor(hash: String): String?

    /** Records a save of [hash] to [uri], replacing the row in place when it was saved before. */
    @Query(
        "INSERT INTO saved_files (hash, uri, savedAt) VALUES (:hash, :uri, :savedAt) " +
            "ON CONFLICT(hash) DO UPDATE SET uri = excluded.uri, savedAt = excluded.savedAt",
    )
    suspend fun upsert(
        hash: String,
        uri: String,
        savedAt: Long,
    )

    @Query("DELETE FROM saved_files WHERE hash = :hash")
    suspend fun delete(hash: String)
}
