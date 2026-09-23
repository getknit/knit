package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.SavedFileDao
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for content-addressed image blobs (attachments + avatars + group photos) held
 * in the encrypted database. Wraps [BlobDao] and owns the cross-table reference check used to
 * garbage-collect an orphaned blob once nothing points at it — including dropping the blob's cached NSFW
 * verdict row ([verdicts]) as part of the same GC transaction. The image *screening* itself (invoking the
 * classifier, caching verdicts) lives in [app.getknit.knit.moderation.ImageScreeningService]; this class
 * only owns the [verdicts] DAO for verdict-row GC so it can stay atomic with the blob delete, and the
 * [savedFiles] DAO for the same reason: where a received file was saved is a fact about the blob, and goes
 * when it does (ADR 2026-09.7ad3).
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
    private val savedFiles: SavedFileDao,
) {
    suspend fun insert(
        hash: String,
        mime: String,
        bytes: ByteArray,
    ) = blobs.insert(BlobEntity(hash, mime, bytes))

    suspend fun bytes(hash: String): ByteArray? = blobs.bytes(hash)

    suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    suspend fun exists(hash: String): Boolean = blobs.exists(hash)

    /** The document URI the file attachment [hash] was last saved to, or null if it never was. */
    suspend fun savedCopy(hash: String): String? = savedFiles.uriFor(hash)

    /** Records that the file attachment [hash] now has a copy at [uri], saved at [at] (our clock). */
    suspend fun rememberSavedCopy(
        hash: String,
        uri: String,
        at: Long,
    ) = savedFiles.upsert(hash, uri, at)

    /** Forgets [hash]'s saved copy — it was moved or deleted, or the grant to it is gone. */
    suspend fun forgetSavedCopy(hash: String) = savedFiles.delete(hash)

    /**
     * Hash → byte length for the stored blobs among [hashes]; a hash not held is absent. The chat observes
     * this for the attachments in its window plus the one staged in its composer, to flip an attachment
     * from loading to shown (a hash being present at all) and to decide whether one can cross an Internet
     * relay (its size against the relays' advertised budget).
     *
     * An empty set is answered without a query, so a thread with nothing to size holds no subscription to
     * the blobs table at all — the common case, and the one Room would otherwise re-run on every blob write.
     */
    fun observeSizes(hashes: Set<String>): Flow<Map<String, Int>> =
        if (hashes.isEmpty()) {
            flowOf(emptyMap())
        } else {
            blobs.observeSizes(hashes.toList()).map { rows -> rows.associate { it.hash to it.size } }
        }

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
        db.withWriteTransaction {
            if (messages.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            if (peers.countByAvatarHash(hash) > 0) return@withWriteTransaction
            if (groups.countByPhotoHash(hash) > 0) return@withWriteTransaction
            if (forward.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            blobs.delete(hash)
            verdicts.delete(hash)
            savedFiles.delete(hash)
        }
    }

    /**
     * Bytes held purely for store-and-forward custody (referenced by a carried frame but no local message).
     * The eager carrier-pull ([app.getknit.knit.mesh.InboundPipeline.onCarriedFrame]) uses this as a pull-time
     * soft cap so altruistic relay of other peers' images stays bounded; see [BlobDao.carrierOnlyBlobBytes].
     */
    suspend fun carrierOnlyBlobBytes(): Long = blobs.carrierOnlyBlobBytes()

    /**
     * Deletes every blob no longer referenced by a message, a peer avatar, or the own avatar. Run once
     * on mesh start to reclaim blobs left orphaned by, e.g., an attachment staged but never sent (its
     * compose state doesn't survive a restart, so its blob is safe to drop).
     */
    suspend fun deleteOrphans() {
        val own = settings.ownAvatarHash.first()
        db.withWriteTransaction {
            blobs.orphanHashes().filter { it != own }.forEach {
                blobs.delete(it)
                verdicts.delete(it)
                savedFiles.delete(it)
            }
        }
    }
}
