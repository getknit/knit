package app.getknit.knit.data.forward

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A DM or group chat frame this device is carrying for store-and-forward delivery. The mesh floods a
 * frame once and forgets it; this table lets a node keep an addressed message and re-offer it to a
 * neighbor that joins later (the DM recipient / a relay toward them, or a group member) — see
 * `app.getknit.knit.mesh.ForwardSync`.
 *
 * [id] is the wire frame id (== the dedup key and, for our own sends, the `messages` row id). [bytes]
 * is the canonical CBOR of the frame with its routing reset (hops 0 / default ttl, signature intact),
 * so it can be re-served verbatim and re-floods with a full hop budget. Exactly one of [recipientId]
 * (the DM's cleartext target) / [groupId] (the group's id) is set; the broadcast room is never carried.
 * [recipientId] lets a carrier push a DM toward that peer and require a delivery receipt to come *from*
 * that peer before purging it; a group has no single recipient (TTL/caps purge only), and the member
 * roster to push toward is read from the decoded frame ([bytes]). [groupId] bounds the per-group quota.
 * [origin] distinguishes a frame we relayed for others (`ORIGIN_RELAY`) from one we authored
 * (`ORIGIN_SELF`) so cap eviction sheds carried traffic before our own outbox. [receivedAt] orders
 * eviction; [expiresAt] drives the TTL sweep.
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
    val bytes: ByteArray,
    val receivedAt: Long,
    val expiresAt: Long,
) {
    // Identity is the frame id. The default data-class equals/hashCode would compare the ByteArray by
    // reference (and Room/detekt flag a ByteArray in a data class); the id is the only field that
    // matters for equality (mirrors BlobEntity).
    override fun equals(other: Any?): Boolean =
        this === other || (other is ForwardEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
