package app.getknit.knit.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached profile of a peer learned from the mesh, keyed by its [nodeId]. [avatarHash] is the content
 * hash of the peer's avatar; the bytes live in the encrypted `blobs` table (see
 * [app.getknit.knit.data.blob.BlobEntity]) and are only set once the avatar payload has arrived, so a
 * non-null hash always implies the blob is present. [pubKey] is reserved for future identity
 * verification / end-to-end encryption.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val name: String = "",
    val status: String = "",
    val avatarHash: String? = null,
    val pubKey: String? = null,
    val updatedAt: Long = 0L,
)
