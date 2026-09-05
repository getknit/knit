package app.getknit.knit.data.peer

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY name ASC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun findByNodeId(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun observeByNodeId(nodeId: String): Flow<PeerEntity?>

    /**
     * The peer whose latest profile claims LoRa board [node] — the newest claim wins, since a board that
     * changed hands is named by two profiles until the old holder's next one drops it.
     */
    @Query("SELECT * FROM peers WHERE loraNode = :node ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByLoraNode(node: Long): PeerEntity?

    /** Every peer's `(nodeId, name)` — the light projection the name-collision index is built from. */
    @Query("SELECT nodeId, name FROM peers")
    suspend fun namesAll(): List<PeerName>

    @Query("UPDATE peers SET verified = :verified WHERE nodeId = :nodeId")
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    )

    /** How many peers reference avatar blob [hash] — part of the orphaned-blob garbage-collection check. */
    @Query("SELECT COUNT(*) FROM peers WHERE avatarHash = :hash")
    suspend fun countByAvatarHash(hash: String): Int

    /** Node ids the user has out-of-band verified — exempt from the cap and the message-request queue. */
    @Query("SELECT nodeId FROM peers WHERE verified = 1")
    suspend fun verifiedNodeIds(): List<String>

    /** Count of unverified peers not in [protected] — the pool [evictOldestCappable] may trim. */
    @Query("SELECT COUNT(*) FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected)")
    suspend fun countCappable(protected: Collection<String>): Int

    /** Evicts the [over] oldest-by-`updatedAt` unverified peers not in [protected]. */
    @Query(
        "DELETE FROM peers WHERE nodeId IN " +
            "(SELECT nodeId FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected) ORDER BY updatedAt ASC LIMIT :over)",
    )
    suspend fun evictOldestCappable(
        protected: Collection<String>,
        over: Int,
    )

    @Upsert
    suspend fun upsert(peer: PeerEntity)
}
