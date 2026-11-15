package app.getknit.knit.data.scopeanchor

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** The anchors of ADR 2026-11.dgj2 ([DmScopeAnchorEntity]); `DmScopeAnchors` is the one writer. */
@Dao
interface DmScopeAnchorDao {
    @Query("SELECT * FROM dm_scope_anchors")
    suspend fun all(): List<DmScopeAnchorEntity>

    /** Writes one peer's anchor whole. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(anchor: DmScopeAnchorEntity)

    @Query("DELETE FROM dm_scope_anchors WHERE peerId = :peerId")
    suspend fun delete(peerId: String)

    /** Deletes [peerId]'s row unless it is armed past [now]: a restore's anchor outlives the reconcile that misses it. */
    @Query("DELETE FROM dm_scope_anchors WHERE peerId = :peerId AND armedUntil <= :now")
    suspend fun deleteUnarmed(
        peerId: String,
        now: Long,
    )

    /** Disarms every row: a restore file can arrive with any value here, so `finishRestore` starts from none. */
    @Query("UPDATE dm_scope_anchors SET armedUntil = 0")
    suspend fun disarmAll()

    /** Arms [peerId]'s row, if it has one, until [until]. */
    @Query("UPDATE dm_scope_anchors SET armedUntil = :until WHERE peerId = :peerId")
    suspend fun arm(
        peerId: String,
        until: Long,
    )
}
