package app.getknit.knit.data.sticker

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * A sticker the user saved to the composer's sticker tray (ADR 2026-11.rk6d). A row exists while the sticker is saved,
 * and removing it deletes the row: saved stickers never leave the phone, so there is nothing for a tombstone to converge
 * with.
 *
 * Keyed by [plainHash], the SHA-256 of the sticker's plaintext, so one picture is saved once whichever chat it came
 * through. The row names the blob it was saved from — [blobHash] and, for a sealed attachment, its [blobKey] — and
 * never stores bytes of its own: a key-less copy of a sealed sticker would be served to anyone asking for its plain
 * hash, which is the confirmation oracle ADR 2026-10.mptm keeps closed. The row is what keeps those bytes from the
 * blob collection after the message that brought them is deleted ([app.getknit.knit.data.BlobRepository], whose
 * reference rules count it). [savedAt] is this phone's wall clock, newest first in the tray.
 *
 * In the encrypted database rather than the DataStore because it names blobs the database holds, and carried in a
 * backup with them, as pins are (`BackupTables.CARRIED`).
 */
@Entity(tableName = "stickers")
data class StickerEntity(
    @PrimaryKey val plainHash: String,
    val blobHash: String,
    val blobKey: String?,
    val mime: String,
    val animated: Boolean,
    val savedAt: Long,
)

/** A sticker as the tray shows and sends it: saved ([StickerDao.observeSaved]) or recently sent ([StickerDao.observeRecent]). */
data class Sticker(
    val plainHash: String,
    val blobHash: String,
    val blobKey: String?,
    val mime: String,
    val animated: Boolean,
)

@Dao
interface StickerDao {
    /** Saved stickers whose bytes this phone holds, newest saved first; a same-millisecond tie is broken by hash. */
    @Query(
        "SELECT plainHash, blobHash, blobKey, mime, animated FROM stickers " +
            "WHERE blobHash IN (SELECT hash FROM blobs) ORDER BY savedAt DESC, plainHash DESC",
    )
    fun observeSaved(): Flow<List<Sticker>>

    /**
     * The stickers [me] sent most recently, one per picture, newest first, at most [limit]. Read from the messages
     * themselves, so a sticker sent from the keyboard counts as one sent from the tray, and the bytes are held as long as
     * the messages are. A sealed row names its picture by `attachmentPlainHash`, a room post by its own hash. SQLite
     * takes the bare columns from the row `MAX(sentAt)` picked, so each picture is offered under its newest identity.
     */
    @Query(
        "SELECT plainHash, blobHash, blobKey, mime, animated FROM (" +
            "SELECT COALESCE(attachmentPlainHash, attachmentHash) AS plainHash, attachmentHash AS blobHash, " +
            "attachmentKey AS blobKey, attachmentMime AS mime, attachmentAnimated AS animated, MAX(sentAt) AS newest " +
            "FROM messages WHERE attachmentSticker = 1 AND senderId = :me AND attachmentHash IS NOT NULL " +
            "AND attachmentMime IS NOT NULL AND attachmentHash IN (SELECT hash FROM blobs) " +
            "GROUP BY COALESCE(attachmentPlainHash, attachmentHash)) ORDER BY newest DESC, plainHash DESC LIMIT :limit",
    )
    fun observeRecent(
        me: String,
        limit: Int,
    ): Flow<List<Sticker>>

    /** The saved plain hashes, for the message menu's Save / Remove choice. */
    @Query("SELECT plainHash FROM stickers")
    fun observeSavedHashes(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM stickers WHERE plainHash = :plainHash)")
    suspend fun isSaved(plainHash: String): Boolean

    @Query("SELECT COUNT(*) FROM stickers")
    suspend fun count(): Int

    @Query("SELECT blobHash FROM stickers WHERE plainHash = :plainHash")
    suspend fun blobHashOf(plainHash: String): String?

    /** How many saved stickers name [hash] as their bytes: one of the blob collection's references. */
    @Query("SELECT COUNT(*) FROM stickers WHERE blobHash = :hash")
    suspend fun countByBlobHash(hash: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sticker: StickerEntity)

    /** Removes a saved sticker. Returns the rows removed. */
    @Query("DELETE FROM stickers WHERE plainHash = :plainHash")
    suspend fun delete(plainHash: String): Int
}
