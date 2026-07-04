package app.getknit.knit.data.peer

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY name ASC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun findByNodeId(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun observeByNodeId(nodeId: String): Flow<PeerEntity?>

    @Query("UPDATE peers SET verified = :verified WHERE nodeId = :nodeId")
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    )

    /** How many peers reference avatar blob [hash] — part of the orphaned-blob garbage-collection check. */
    @Query("SELECT COUNT(*) FROM peers WHERE avatarHash = :hash")
    suspend fun countByAvatarHash(hash: String): Int

    @Upsert
    suspend fun upsert(peer: PeerEntity)
}
