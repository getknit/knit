package app.getknit.knit.data

import androidx.room.withTransaction
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for content-addressed image blobs (attachments + avatars + group photos) held
 * in the encrypted database. Wraps [BlobDao] and owns the cross-table reference check used to
 * garbage-collect an orphaned blob once nothing points at it — including dropping the blob's cached NSFW
 * verdict row ([verdicts]) as part of the same GC transaction. The image *screening* itself (invoking the
 * classifier, caching verdicts) lives in [app.getknit.knit.moderation.ImageScreeningService]; this class
 * only owns the [verdicts] DAO for verdict-row GC so it can stay atomic with the blob delete.
 */
class BlobRepository(
    private val blobs: BlobDao,
    private val messages: MessageDao,
    private val peers: PeerDao,
    private val settings: SettingsStore,
    private val verdicts: BlobVerdictDao,
    private val groups: GroupDao,
    private val forward: ForwardDao,
    private val db: KnitDatabase,
) {
    suspend fun insert(
        hash: String,
        mime: String,
        bytes: ByteArray,
    ) = blobs.insert(BlobEntity(hash, mime, bytes))

    suspend fun bytes(hash: String): ByteArray? = blobs.bytes(hash)

    suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    suspend fun exists(hash: String): Boolean = blobs.exists(hash)

    /** Hashes of all stored blobs; the chat list observes this to flip attachments from loading to shown. */
    fun observeHashes(): Flow<List<String>> = blobs.observeHashes()

    /**
     * Deletes the blob for [hash] only if nothing references it any more — no message attachment, no
     * peer avatar, no group photo, no carried store-and-forward frame, and not the device's own avatar.
     * Safe to call after deleting a message, swapping an avatar/group photo, or discarding a staged-but-
     * unsent attachment; a no-op (and tolerates a null hash) when the blob is still in use. The forward
     * check keeps a carrier's custodied image alive until the frame that references it stops being carried.
     *
     * The reference checks + the two deletes run in one transaction so they see a consistent snapshot
     * and commit atomically (the callers span the inbound collector, several ViewModels, and the send
     * path — not one writer). This narrows but does not fully close the check-then-act against an
     * *independent* concurrent inserter (e.g. inbound delivery saving a message that references [hash]
     * just after the counts read zero); a blob reclaimed that way is content-addressed, so it self-heals
     * via a later [app.getknit.knit.mesh.BlobExchange] re-pull. The own-avatar guard reads DataStore,
     * which can't enroll in a Room transaction, so it stays outside (the own avatar isn't churned
     * concurrently the way message/forward refs are).
     */
    suspend fun deleteIfUnreferenced(hash: String?) {
        if (hash == null) return
        if (hash == settings.ownAvatarHash.first()) return
        db.withTransaction {
            if (messages.countByAttachmentHash(hash) > 0) return@withTransaction
            if (peers.countByAvatarHash(hash) > 0) return@withTransaction
            if (groups.countByPhotoHash(hash) > 0) return@withTransaction
            if (forward.countByAttachmentHash(hash) > 0) return@withTransaction
            blobs.delete(hash)
            verdicts.delete(hash)
        }
    }

    /**
     * Bytes held purely for store-and-forward custody (referenced by a carried frame but no local message).
     * The eager carrier-pull ([`MeshManager.onCarriedFrame`]) uses this as a pull-time soft cap so altruistic
     * relay of other peers' images stays bounded; see [BlobDao.carrierOnlyBlobBytes].
     */
    suspend fun carrierOnlyBlobBytes(): Long = blobs.carrierOnlyBlobBytes()

    /**
     * Deletes every blob no longer referenced by a message, a peer avatar, or the own avatar. Run once
     * on mesh start to reclaim blobs left orphaned by, e.g., an attachment staged but never sent (its
     * compose state doesn't survive a restart, so its blob is safe to drop).
     */
    suspend fun deleteOrphans() {
        val own = settings.ownAvatarHash.first()
        db.withTransaction {
            blobs.orphanHashes().filter { it != own }.forEach {
                blobs.delete(it)
                verdicts.delete(it)
            }
        }
    }
}
