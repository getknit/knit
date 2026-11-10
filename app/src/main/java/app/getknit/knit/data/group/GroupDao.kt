package app.getknit.knit.data.group

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // the groups table and its member clocks behind one DAO; upsert is two of these
@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE groupId IN (:ids)")
    fun observeByIds(ids: Collection<String>): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun findById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE left = 0")
    suspend fun allActive(): List<GroupEntity>

    /**
     * Writes [group], replacing every column in place when the row is already there. Every caller keeps passing one
     * [GroupEntity]; this plain function (nothing Room processes) unpacks it onto [upsertRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60) and [app.getknit.knit.data.peer.PeerDao.upsert]: Room's `@Upsert` is an INSERT it expects to
     * fail on an existing key, then an UPDATE, and SQLCipher logs every failed statement at `E` before Room's catch
     * runs — and every inbound group frame re-upserts its group, so lab phones logged a "UNIQUE constraint failed:
     * groups.groupId" line per frame. `ON CONFLICT DO UPDATE` never issues a failing statement. The parameters are
     * flattened because Room's `:entity.column` bind syntax is refused by this processor at build time.
     */
    suspend fun upsert(group: GroupEntity) =
        upsertRow(
            groupId = group.groupId,
            name = group.name,
            members = group.members,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            nameUpdatedAt = group.nameUpdatedAt,
            left = group.left,
            departed = group.departed,
            photoHash = group.photoHash,
            photoUpdatedAt = group.photoUpdatedAt,
            photoShownHash = group.photoShownHash,
        )

    /** The statement [upsert] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of GroupEntity; see the doc comment above
    @Query(
        "INSERT INTO groups (groupId, name, members, createdBy, createdAt, nameUpdatedAt, `left`, departed, " +
            "photoHash, photoUpdatedAt, photoShownHash) " +
            "VALUES (:groupId, :name, :members, :createdBy, :createdAt, :nameUpdatedAt, :left, :departed, " +
            ":photoHash, :photoUpdatedAt, :photoShownHash) " +
            "ON CONFLICT(groupId) DO UPDATE SET " +
            "name = excluded.name, members = excluded.members, createdBy = excluded.createdBy, " +
            "createdAt = excluded.createdAt, nameUpdatedAt = excluded.nameUpdatedAt, `left` = excluded.`left`, " +
            "departed = excluded.departed, photoHash = excluded.photoHash, " +
            "photoUpdatedAt = excluded.photoUpdatedAt, photoShownHash = excluded.photoShownHash",
    )
    suspend fun upsertRow(
        groupId: String,
        name: String,
        members: String,
        createdBy: String,
        createdAt: Long,
        nameUpdatedAt: Long,
        left: Boolean,
        departed: String,
        photoHash: String?,
        photoUpdatedAt: Long,
        photoShownHash: String?,
    )

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

    /** [memberId]'s leave and rejoin clocks in [groupId], or null when neither was ever recorded (both read as 0). */
    @Query("SELECT * FROM group_member_clocks WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun clocksOf(
        groupId: String,
        memberId: String,
    ): GroupMemberClockEntity?

    /** Writes one member's clocks whole; the caller read them in the same transaction ([clocksOf]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putClocks(clocks: GroupMemberClockEntity)

    /** Drops every member clock [groupId] holds — with the group, or with the thread on our own leave. */
    @Query("DELETE FROM group_member_clocks WHERE groupId = :groupId")
    suspend fun deleteClocks(groupId: String)
}
