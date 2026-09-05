package app.getknit.knit.data.peer

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Cached profile of a peer learned from the mesh, keyed by its [nodeId]. [avatarHash] is the content
 * hash of the peer's avatar; the bytes live in the encrypted `blobs` table (see
 * [app.getknit.knit.data.blob.BlobEntity]) and are only set once the avatar payload has arrived, so a
 * non-null hash always implies the blob is present.
 *
 * [pubKey] is the peer's pinned end-to-end public-key bundle (base64; see
 * [app.getknit.knit.mesh.crypto.PublicKeyBundle]), learned from their profile frame on a
 * trust-on-first-use basis. [verified] is true once the local user has confirmed that key out of band
 * (safety number / QR). The pinned key is immutable once set — a profile advertising a different key
 * for the same nodeId is refused (it could only arise from a nodeId hash collision), so [verified]
 * stays bound to that key and is never silently inherited by a swapped-in key.
 *
 * [deviceTag] is the peer's key-independent device tag (see [app.getknit.knit.identity.DeviceTag]),
 * used only to keep a block sticky when the peer regenerates its key (and thus its nodeId).
 *
 * [protoVersion]/[capabilities] are the peer's advertised protocol version and feature bits (see
 * [app.getknit.knit.mesh.protocol.Protocol]), learned (authenticated) from their profile frame; null
 * until a profile carrying them arrives. `CAP_RATCHET` is consumed at send time (v2 DM gating); the
 * rest are diagnostics.
 *
 * [prekeyId]/[prekeyPub]/[prekeySig] are the peer's current ratchet signed prekey (verified against
 * the pinned [pubKey] before storing — see `InboundPipeline.handleProfile`), and [prekeyProfileAt] the
 * `sentAt` of the profile frame that carried it (the last-writer-wins clock for prekey updates,
 * including the null-prekey downgrade case). All null until a v2-capable profile arrives. The key
 * bytes are base64 like [pubKey] — a `ByteArray` column would break this data class's equality, which
 * Room's flow dedup relies on.
 *
 * [openToChat] is the peer's declared "open to chat" availability, as their latest profile carried it
 * (`ProfileContent.openToChat` / `ProfilePayload.openToChat`); false when their profile predates the flag.
 * A presentation field: it moves with name/status under the same last-writer-wins watermark ([updatedAt]).
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val name: String = "",
    val status: String = "",
    val avatarHash: String? = null,
    val pubKey: String? = null,
    val verified: Boolean = false,
    val deviceTag: String? = null,
    val protoVersion: Int? = null,
    val capabilities: Long? = null,
    val updatedAt: Long = 0L,
    val prekeyId: Int? = null,
    val prekeyPub: String? = null,
    val prekeySig: String? = null,
    val prekeyProfileAt: Long? = null,
    val openToChat: Boolean = false,
    /**
     * The Meshtastic node number of the board this peer's latest profile says they hold
     * (`ProfileContent.loraNode` / `ProfilePayload.loraNode`), or null when it named none. A presentation
     * field under the same [updatedAt] watermark; a self-asserted claim, so several peers may name one node
     * (a board that changed hands) and ingest picks the newest — see `PeerDao.findByLoraNode`.
     */
    val loraNode: Long? = null,
)
