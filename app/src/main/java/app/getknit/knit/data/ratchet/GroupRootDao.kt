package app.getknit.knit.data.ratchet

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Row-level operations for the spool plane's shared group roots. Thin by rule
 * (`.agents/rules/coding.md`): no `@Transaction` methods — atomicity lives at the repository callers'
 * `db.withWriteTransaction`.
 */
@Dao
interface GroupRootDao {
    @Query("SELECT * FROM group_roots WHERE groupId = :groupId")
    suspend fun find(groupId: String): GroupRootEntity?

    @Query("SELECT * FROM group_roots")
    suspend fun all(): List<GroupRootEntity>

    /**
     * Writes [row], replacing every non-key column in place when the group's row is already there. Callers keep
     * passing one [GroupRootEntity]; this plain function (nothing Room processes) unpacks it onto [upsertRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60), [app.getknit.knit.data.peer.PeerDao.upsert] and [app.getknit.knit.data.group.GroupDao.upsert]:
     * Room's `@Upsert` is an INSERT it expects to fail on an existing key, then an UPDATE, and SQLCipher logs every
     * failed statement at `E` before Room's catch runs — lab phones logged a "UNIQUE constraint failed: group_roots.groupId" line
     * per root write. `ON CONFLICT DO UPDATE` never issues a failing statement, and overwrites every non-key column
     * exactly as `@Upsert` did. The parameters are flattened because Room's `:entity.column` bind syntax is refused
     * by this processor at build time.
     */
    suspend fun upsert(row: GroupRootEntity) =
        upsertRow(
            groupId = row.groupId,
            root = row.root,
            version = row.version,
            minter = row.minter,
            prevRoot = row.prevRoot,
            prevVersion = row.prevVersion,
            prevExpiresAt = row.prevExpiresAt,
            firstEligibleAt = row.firstEligibleAt,
            remintDueAt = row.remintDueAt,
        )

    /** The statement [upsert] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of GroupRootEntity; see upsert
    @Query(
        "INSERT INTO group_roots (groupId, root, version, minter, prevRoot, prevVersion, prevExpiresAt, " +
            "firstEligibleAt, remintDueAt) " +
            "VALUES (:groupId, :root, :version, :minter, :prevRoot, :prevVersion, :prevExpiresAt, " +
            ":firstEligibleAt, :remintDueAt) " +
            "ON CONFLICT(groupId) DO UPDATE SET " +
            "root = excluded.root, version = excluded.version, minter = excluded.minter, " +
            "prevRoot = excluded.prevRoot, prevVersion = excluded.prevVersion, " +
            "prevExpiresAt = excluded.prevExpiresAt, firstEligibleAt = excluded.firstEligibleAt, " +
            "remintDueAt = excluded.remintDueAt",
    )
    suspend fun upsertRow(
        groupId: String,
        root: ByteArray?,
        version: Int,
        minter: String,
        prevRoot: ByteArray?,
        prevVersion: Int,
        prevExpiresAt: Long,
        firstEligibleAt: Long,
        remintDueAt: Long,
    )

    @Query("DELETE FROM group_roots WHERE groupId = :groupId")
    suspend fun deleteFor(groupId: String)

    /** Retires a drained previous lineage in place; the live root is untouched. */
    @Query(
        "UPDATE group_roots SET prevRoot = NULL, prevVersion = 0, prevExpiresAt = 0 " +
            "WHERE prevExpiresAt != 0 AND prevExpiresAt <= :now",
    )
    suspend fun clearDrainedPrevRoots(now: Long)
}
