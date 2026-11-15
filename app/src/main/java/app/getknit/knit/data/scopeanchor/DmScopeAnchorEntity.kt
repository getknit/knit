package app.getknit.knit.data.scopeanchor

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One DM peer's **anchor** (`docs/SPOOL_PROTOCOL.md` C-3.1-6, ADR 2026-11.dgj2, #324): the id and outer seal keys of
 * the live DM scope of our confirmed session with [peerId]. It never holds the pairwise root or any ratchet state.
 *
 * It exists for a restore. A backup carries no ratchet session, so a restored phone's resets replace no root and get no
 * DM scope, which leaves them unable to reach a contact the relay is the only path to. Carried in a backup
 * (`BackupTables.CARRIED`), the anchor is the scope that contact still reads: `MeshManager.finishRestore` arms it
 * ([armedUntil]), and `ScopeRegistry` keeps it as the retiring scope our own unanswered reset is pushed into.
 *
 * The anchor is valid until the pair's next reset, which is why `DmScopeAnchors` keeps rows only for the peers a restore
 * resets, from a confirmed session, and deletes every unarmed row it did not just write. [armedUntil] is 0 except
 * after a restore, when it is the end of the restore's window (`DmScopeAnchors.ARM_WINDOW_MS`, 30 days). A restore
 * file is hostile input, so `DmScopeAnchors` skips a row of the wrong lengths or armed past that window.
 */
@Entity(tableName = "dm_scope_anchors")
data class DmScopeAnchorEntity(
    @PrimaryKey val peerId: String,
    val scopeId: ByteArray,
    val sealKey: ByteArray,
    val nonceKey: ByteArray,
    val armedUntil: Long,
    val updatedAt: Long,
)
