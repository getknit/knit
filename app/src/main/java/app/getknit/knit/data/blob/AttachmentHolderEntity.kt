package app.getknit.knit.data.blob

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Query

/**
 * Proof that [holderId] holds the bytes of the attachment [hash], learnt over a short-range radio [plane] (a
 * `DeliveryPlane` code) at [provedAt] (our clock) — the evidence `docs/SPOOL_PROTOCOL.md` C-9.5-8 names for skipping
 * a DM photo's spool upload for good (ADR 2026-10.vy27, amending ADR 021). Two writers, one per end of the DM:
 *
 * - **The recipient** records itself once the bytes arrived here over BLE or Wi-Fi Aware and the store took them —
 *   hash-checked, after `AttachmentDeferPolicy.noteRadioArrival` and the store write.
 * - **The sender** records the peer once the peer, asked over a live short-range link with the holder query
 *   (`blobhave`, ADR 2026-10.6e5t), answered that it holds the hash: its responder says so only for screened,
 *   hash-checked bytes it would serve.
 *
 * A delivery ack is never written here: it says the frame arrived, not the bytes (ADR 021). Permanent on purpose — a
 * fact, not a sighting — so a fresh process does not upload what it already proved. Keyed per (hash, holder), so a
 * picture forwarded under the same sealed identity to someone else (ADR 2026-10.mptm) still needs that person's own
 * proof. The row goes with its blob ([app.getknit.knit.data.BlobRepository]'s GC deletes both) and rides a backup
 * like `blobs`: a restore is a move, and the peer still holds what it held.
 */
@Entity(tableName = "attachment_holders", primaryKeys = ["hash", "holderId"])
data class AttachmentHolderEntity(
    val hash: String,
    val holderId: String,
    val plane: Int,
    val provedAt: Long,
)

@Dao
interface AttachmentHolderDao {
    /** Records that [holderId] holds [hash]. The first proof stands: a later one changes nothing. */
    @Query(
        "INSERT INTO attachment_holders (hash, holderId, plane, provedAt) VALUES (:hash, :holderId, :plane, :provedAt) " +
            "ON CONFLICT(hash, holderId) DO NOTHING",
    )
    suspend fun record(
        hash: String,
        holderId: String,
        plane: Int,
        provedAt: Long,
    )

    /**
     * Whether the DM between [me] and [peer] holds proof that its recipient has the bytes of [hash], read from
     * whichever end this node is: a message of ours naming it that [peer] proved it holds, or a message of [peer]'s
     * naming it whose bytes reached us over a radio. Requiring the message keeps avatars and group pictures (no
     * message row between the pair) uploading as they always did. Only a short-range [planes] code counts.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM attachment_holders h JOIN messages m ON m.attachmentHash = h.hash " +
            "WHERE h.hash = :hash AND h.plane IN (:planes) AND (" +
            "(h.holderId = :peer AND m.senderId = :me) OR (h.holderId = :me AND m.senderId = :peer)))",
    )
    suspend fun heldInPair(
        hash: String,
        me: String,
        peer: String,
        planes: List<Int>,
    ): Boolean

    /** Every proof held for [hash], oldest first: the developer view's holder rows (ADR 2026-10.s7wg). Read only. */
    @Query("SELECT * FROM attachment_holders WHERE hash = :hash ORDER BY provedAt ASC, holderId ASC")
    suspend fun holdersOf(hash: String): List<AttachmentHolderEntity>

    @Query("DELETE FROM attachment_holders WHERE hash = :hash")
    suspend fun delete(hash: String)
}
