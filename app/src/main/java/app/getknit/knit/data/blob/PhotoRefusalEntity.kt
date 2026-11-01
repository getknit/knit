package app.getknit.knit.data.blob

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * A blob refused as a profile photo over the photo ceiling (`FileIntake.MAX_AVATAR_BYTES`, #234), kept beside its
 * screening verdict so the refusal outlives a restart (ADR 2026-11.4ckq): the database re-arm wants every advertised
 * photo whose bytes are not held, and without it a restart would stream the photo again, up to the ceiling, from every
 * holder, every session.
 *
 * Two kinds, as the session keeps them (`PartialBlobs`): with an empty [holder], a held copy was found [size] bytes long,
 * which refuses the hash for everyone (`BlobExchange.refuseAsPhoto`); with a [holder], that neighbour's stream passed the
 * ceiling or its size answer did ([size] 0), which is evidence about that holder alone and passes only it over
 * (`PartialBlobs.markHolder`) — a neighbour's claim must never refuse a contact's photo. Seeded into the session at
 * each mesh start. Kept while a peer advertises the hash; it goes with the hash's last advertiser
 * (`BlobRepository.deleteIfUnreferenced`, the start-up sweep). A table of its own, not a `blob_verdicts` column: a
 * verdict row is what tells screening a hash was already scanned, and a refusal is not a scan.
 */
@Entity(tableName = "photo_refusals", primaryKeys = ["hash", "holder"])
data class PhotoRefusalEntity(
    val hash: String,
    val holder: String = "",
    val size: Long,
)

@Dao
interface PhotoRefusalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(refusal: PhotoRefusalEntity)

    /** The hashes a held copy refused: refused for everyone. */
    @Query("SELECT hash FROM photo_refusals WHERE holder = ''")
    suspend fun hashes(): List<String>

    /** The holders passed over for a hash, by their stream or their size answer. */
    @Query("SELECT * FROM photo_refusals WHERE holder != ''")
    suspend fun holderMarks(): List<PhotoRefusalEntity>

    @Query("SELECT COUNT(*) FROM photo_refusals WHERE hash = :hash AND holder != ''")
    suspend fun countMarks(hash: String): Int

    @Query("DELETE FROM photo_refusals WHERE hash = :hash")
    suspend fun delete(hash: String)

    /** Drops every refusal no peer advertises any more: the start-up sweep. */
    @Query("DELETE FROM photo_refusals WHERE hash NOT IN (SELECT advertisedAvatar FROM peers WHERE advertisedAvatar IS NOT NULL)")
    suspend fun deleteUnadvertised(): Int
}
