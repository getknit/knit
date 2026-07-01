package app.getknit.knit.data.forward

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A DM, group, or broadcast-room chat frame this device is carrying for store-and-forward delivery. The
 * mesh floods a frame once and forgets it; this table lets a node keep a message and re-offer it to a
 * neighbor that joins later (the DM recipient / a relay toward them, a group member, or anyone for a
 * broadcast) — see `app.getknit.knit.mesh.ForwardSync`.
 *
 * [id] is the wire frame id (== the dedup key and, for our own sends, the `messages` row id). [signed]
 * is the canonical CBOR of the routing envelope and [sig] its Ed25519 signature — the immutable,
 * re-floodable core. The throwaway routing counters (ttl/hops) live only in the wrapper a carrier stamps
 * fresh on re-serve, so the frame re-floods with a full hop budget. At most one of [recipientId] (the
 * DM's cleartext target) / [groupId] (the group's id) is set; **both null identifies a broadcast-room
 * frame** (offered to every newcomer, TTL/cap-bounded only). [recipientId] lets a carrier push a DM
 * toward that peer and require a delivery receipt to come *from* that peer before purging it; a group
 * has no single recipient (TTL/caps purge only), and the member roster to push toward is read from the
 * decoded envelope ([signed]). [groupId] bounds the per-group quota. [origin] distinguishes a frame we
 * relayed for others (`ORIGIN_RELAY`) from one we authored (`ORIGIN_SELF`) so cap eviction sheds carried
 * traffic before our own outbox. [receivedAt] orders eviction; [expiresAt] drives the TTL sweep.
 */
@Entity(
    tableName = "forward_store",
    indices = [Index("recipientId"), Index("groupId"), Index("expiresAt")],
)
data class ForwardEntity(
    @PrimaryKey val id: String,
    val recipientId: String?,
    val groupId: String?,
    val senderId: String,
    val origin: Int,
    val signed: ByteArray,
    val sig: ByteArray,
    val receivedAt: Long,
    val expiresAt: Long,
) {
    // Identity is the frame id. The default data-class equals/hashCode would compare the ByteArrays by
    // reference (and Room/detekt flag a ByteArray in a data class); the id is the only field that
    // matters for equality (mirrors BlobEntity).
    override fun equals(other: Any?): Boolean =
        this === other || (other is ForwardEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
