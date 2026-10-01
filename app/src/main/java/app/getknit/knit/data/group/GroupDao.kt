package app.getknit.knit.data.group

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun findById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE left = 0")
    suspend fun allActive(): List<GroupEntity>

    @Upsert
    suspend fun upsert(group: GroupEntity)

    /**
     * How many groups reference [hash] as their photo, decided or shown — guards blob GC (a shared photo stays
     * referenced). The decided half is what keeps a refused photo's screening verdict while the group still
     * advertises it, so nothing pulls it again (ADR 2026-09.nxcq).
     */
    @Query("SELECT COUNT(*) FROM groups WHERE photoHash = :hash OR photoShownHash = :hash")
    suspend fun countByPhotoHash(hash: String): Int

    /** The groups whose decided photo is [hash] but which do not show it yet — what a landing blob settles. */
    @Query(
        "SELECT * FROM groups WHERE left = 0 AND photoHash = :hash AND " +
            "(photoShownHash IS NULL OR photoShownHash != photoHash)",
    )
    suspend fun awaitingPhoto(hash: String): List<GroupEntity>

    /**
     * Decided photos not shown yet whose bytes are not local — the database half of a group photo pull, re-armed
     * on every point a holder may have appeared (`MeshManager.rewantMissingBlobs`, ADR 2026-09.ptv8).
     */
    @Query(
        "SELECT DISTINCT photoHash FROM groups WHERE left = 0 AND photoHash IS NOT NULL AND " +
            "(photoShownHash IS NULL OR photoShownHash != photoHash) AND " +
            "photoHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun photoHashesNeedingFetch(): List<String>

    /** Marks the group left so inbound frames are dropped and it's hidden from the list. */
    @Query("UPDATE groups SET left = 1 WHERE groupId = :groupId")
    suspend fun markLeft(groupId: String)

    /** Hard-deletes the group row (no tombstone), so a future inbound group frame can re-create it. */
    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteById(groupId: String)
}
