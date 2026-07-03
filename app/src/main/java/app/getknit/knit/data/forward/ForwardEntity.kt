package app.getknit.knit.data.forward

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A floodable frame this device is carrying for store-and-forward delivery — a DM/group/broadcast-room chat
 * message, or a metadata frame (reaction/receipt/group-update/group-leave/profile). The
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
 * decoded envelope ([signed]). [groupId] bounds the per-group quota. [type] is the wire frame type, so the
 * store buckets per-type policy — the broadcast quota + shorter TTL apply only to broadcast-room chat, not the
 * reaction/receipt/profile frames that share its null recipient/group shape. [origin] records whether we
 * relayed a frame for others (`ORIGIN_RELAY`) or authored it (`ORIGIN_SELF`) — diagnostic only, now that quota
 * eviction is origin-agnostic. [sentAt] is the originator's envelope clock (identical on every node), so
 * trimming each over-quota bucket to its newest-N *by [sentAt]* keeps every node's carried set — and hence its
 * content digest — convergent; without it a chatty originator (which used to bypass the quota outright) held
 * frames a capped carrier could never accept, so the digests never matched. [receivedAt] records local arrival;
 * [expiresAt] drives the TTL sweep.
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
    val type: String,
    val origin: Int,
    val signed: ByteArray,
    val sig: ByteArray,
    val sentAt: Long,
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
