package app.getknit.knit.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached profile of a peer learned from the mesh, keyed by its [nodeId]. [avatarPath] points at a
 * locally-cached image file (avatars travel as Nearby file payloads, not in the DB). [pubKey] is
 * reserved for future identity verification / end-to-end encryption.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val name: String = "",
    val status: String = "",
    val avatarPath: String? = null,
    val pubKey: String? = null,
    val updatedAt: Long = 0L,
)
