package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.flow.Flow

/** Single source of truth for cached peer profiles. */
class PeerRepository(
    private val dao: PeerDao,
) {
    fun observePeers(): Flow<List<PeerEntity>> = dao.observeAll()

    fun observe(nodeId: String): Flow<PeerEntity?> = dao.observeByNodeId(nodeId)

    suspend fun find(nodeId: String): PeerEntity? = dao.findByNodeId(nodeId)

    suspend fun upsert(peer: PeerEntity) = dao.upsert(peer)

    /** Marks (or clears) the user's out-of-band verification of this peer's pinned key. */
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    ) = dao.setVerified(nodeId, verified)
}
