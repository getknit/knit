package app.getknit.knit.data.peer

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The newest signed `profile` frame this phone pinned a peer from, verbatim — the proof of that peer's key that
 * a carrier hands a newcomer (ADR 2026-09.g64k). [signed] is the canonical CBOR of the routing envelope and [sig]
 * its Ed25519 signature, the same immutable core `forward_store` keeps; [sentAt] is the frame's publish stamp
 * (clamped to the skew window on the way in), the order a later copy must beat to replace this one.
 *
 * Carrying a peer's frame requires their key pinned in `peers`, but the pin alone cannot prove the key to
 * anyone else: only the peer's own signature over a profile can. Custody loses the profile frame to the
 * per-sender quota and to the TTL before it loses the posts behind it, and `KeyExchange`'s cache is
 * in memory, so without this row a restarted carrier serves a stranger a backlog nobody can let them read.
 *
 * A table of its own, not columns on [PeerEntity]: that class's equality (which Room's flow dedup relies on)
 * would break on a `ByteArray`, and every peer flow reads `SELECT *`. Lives and dies with the pin — every
 * deletion from `peers` sweeps the orphans ([PeerDao.deleteOrphanProfiles]).
 */
@Entity(tableName = "peer_profiles")
data class PeerProfileEntity(
    @PrimaryKey val nodeId: String,
    val signed: ByteArray,
    val sig: ByteArray,
    val sentAt: Long,
) {
    // Identity is the node id, as ForwardEntity's is its frame id: the default equals would compare the arrays
    // by reference.
    override fun equals(other: Any?): Boolean = this === other || (other is PeerProfileEntity && nodeId == other.nodeId)

    override fun hashCode(): Int = nodeId.hashCode()
}
