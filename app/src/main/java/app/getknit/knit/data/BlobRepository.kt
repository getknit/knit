package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.blob.AttachmentHolderDao
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.PhotoRefusalDao
import app.getknit.knit.data.blob.PhotoRefusalEntity
import app.getknit.knit.data.blob.SavedFileDao
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.message.DeliveryPlane
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
 * when it does (ADR 2026-09.7ad3) — as is the [holders] proof that a DM photo's recipient holds it (ADR
 * 2026-10.vy27).
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
    private val holders: AttachmentHolderDao,
    // The photos refused over their ceiling (#234), kept beside the verdicts (ADR 2026-11.4ckq).
    private val refusals: PhotoRefusalDao = db.photoRefusalDao(),
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
     * Records proof, learnt over [plane] at [at] (our clock), that [holderId] holds the bytes of [hash] (ADR
     * 2026-10.vy27). Only a plane that [DeliveryPlane.provesHold] is proof — a short-range radio, or a contact's direct
     * link (ADR 2026-10.a5yj): the recipient holds the bytes whichever of them carried them. A board carries no blob,
     * and the spool is what the proof saves, so either writes nothing. The first proof stands.
     */
    suspend fun recordHolder(
        hash: String,
        holderId: String,
        plane: DeliveryPlane,
        at: Long,
    ) {
        if (plane.provesHold) holders.record(hash, holderId, plane.code, at)
    }

    /**
     * Whether the DM between [me] and [peer] holds proof that its recipient has [hash]'s bytes, from whichever end
     * this node is (`AttachmentHolderDao.heldInPair`): the spool never needs the photo then.
     */
    suspend fun heldInPair(
        hash: String,
        me: String,
        peer: String,
    ): Boolean = holders.heldInPair(hash, me, peer, DeliveryPlane.provesHoldCodes)

    /**
     * Hash → who a contact's direct link proved holds it, for the attachments among [hashes] (ADR 2026-10.a5yj): on the
     * recipient, itself once the bytes arrived over a link; on the sender, the peer once it answered our holder query
     * over one. The chat marks such a file as the link's. A hash with no such proof is absent, and an empty set is
     * answered without a query, as [observeSizes] is.
     */
    fun observeLinkHolders(hashes: Set<String>): Flow<Map<String, Set<String>>> =
        if (hashes.isEmpty()) {
            flowOf(emptyMap())
        } else {
            holders.observeOnPlane(hashes.toList(), DeliveryPlane.DirectLink.code).map { rows ->
                rows.groupBy({ it.hash }, { it.holderId }).mapValues { (_, ids) -> ids.toSet() }
            }
        }

    /**
     * Hash → holder → the plane its proof came over (a `DeliveryPlane` code), for the attachments among [hashes] with
     * proof that someone holds the bytes (`DeliveryPlane.provesHold`, ADR 2026-10.vy27). The chat reads it once for
     * both of its uses: the link mark on a file a contact's direct link proved ([observeLinkHolders]'s rows), and the
     * sender's relay marker, which a DM peer proved to hold the file no longer needs. A hash with no proof is absent,
     * and an empty set is answered without a query, as [observeSizes] is.
     */
    fun observeHolders(hashes: Set<String>): Flow<Map<String, Map<String, Int>>> =
        if (hashes.isEmpty()) {
            flowOf(emptyMap())
        } else {
            holders.observeOnPlanes(hashes.toList(), DeliveryPlane.provesHoldCodes).map { rows ->
                rows.groupBy({ it.hash }, { it.holderId to it.plane }).mapValues { (_, held) -> held.toMap() }
            }
        }

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

    /** Hash → byte length for the stored blobs among [hashes], read once; a hash not held is absent. */
    suspend fun sizes(hashes: Collection<String>): Map<String, Long> =
        if (hashes.isEmpty()) emptyMap() else blobs.sizes(hashes.toList()).associate { it.hash to it.size.toLong() }

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
            // A peer that still advertises the photo keeps its verdict and its refusal, never its bytes (ADR
            // 2026-11.4ckq): the database re-arm wants an advertised photo whose bytes are gone, and a refused one must
            // stay refused. The bytes go, so a block, a refusal and the ceiling's release still free them.
            if (peers.countByAdvertisedAvatar(hash) == 0) {
                verdicts.delete(hash)
                refusals.delete(hash)
            }
            savedFiles.delete(hash)
            holders.delete(hash)
        }
    }

    /**
     * Drops the bytes of a peer's photo screening refused while content filtering is on — unless a message, a face, a
     * group photo, a carried frame or the own avatar still needs them — and **keeps its verdict**, as
     * [dropRefusedGroupPhoto] does for a group's (ADR 2026-11.4ckq). The peer goes on advertising the photo, so its row
     * still names [hash] ([deleteIfUnreferenced] keeps the verdict while it does), and the kept verdict is what tells
     * the re-pull paths not to fetch it again: without it every database re-arm would fetch, refuse and delete it.
     */
    suspend fun dropRefusedFace(hash: String) {
        if (hash == settings.ownAvatarHash.first()) return
        db.withWriteTransaction {
            if (messages.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            if (peers.countByAvatarHash(hash) > 0) return@withWriteTransaction
            if (groups.countByPhotoHash(hash) > 0) return@withWriteTransaction
            if (forward.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            blobs.delete(hash)
        }
    }

    /**
     * Records that a held copy of [hash], [size] bytes, was over the profile photo ceiling (#234), so the refusal outlives
     * the session (ADR 2026-11.4ckq). Kept while a peer advertises the hash.
     */
    suspend fun refuseAsPhoto(
        hash: String,
        size: Long,
    ) = refusals.insert(PhotoRefusalEntity(hash, size = size))

    /** Every photo a held copy refused over its ceiling: what each mesh start seeds the session's refusals from. */
    suspend fun photoRefusals(): List<String> = refusals.hashes()

    /**
     * Records that [holder]'s stream of [hash], or its size answer, passed the photo ceiling (#234, ADR 2026-11.4ckq): it
     * is passed over for that hash past the session too. Evidence about that holder alone, never a refusal of the hash.
     * At most [MAX_HOLDER_MARKS] holders a hash: one more is that holder's one more copy, never a want lost.
     */
    suspend fun markOverCeiling(
        hash: String,
        holder: String,
    ) {
        if (holder.isEmpty() || refusals.countMarks(hash) >= MAX_HOLDER_MARKS) return
        refusals.insert(PhotoRefusalEntity(hash, holder, size = 0L))
    }

    /** `(hash, holder)` of every holder passed over past its session: what each mesh start seeds the session's marks from. */
    suspend fun holderMarks(): List<Pair<String, String>> = refusals.holderMarks().map { it.hash to it.holder }

    /**
     * Drops the bytes of a group photo screening refused while content filtering is on — unless a message,
     * a peer avatar, a carried frame or the own avatar still needs them — and **keeps its verdict**. The group
     * goes on advertising the photo it decided on (ADR 2026-09.nxcq), so the row still names [hash]; the kept
     * verdict is what tells the re-pull paths (`InboundPipeline.groupPhotoDecision`,
     * `MeshManager.rewantMissingBlobs`) not to fetch it again. The verdict goes with the hash's last group
     * reference, through [deleteIfUnreferenced], once the group moves on to another photo.
     */
    suspend fun dropRefusedGroupPhoto(hash: String) {
        if (hash == settings.ownAvatarHash.first()) return
        db.withWriteTransaction {
            if (messages.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            if (peers.countByAvatarHash(hash) > 0) return@withWriteTransaction
            if (forward.countByAttachmentHash(hash) > 0) return@withWriteTransaction
            blobs.delete(hash)
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
                // A photo a peer still advertises keeps its verdict, as in [deleteIfUnreferenced] (ADR 2026-11.4ckq).
                if (peers.countByAdvertisedAvatar(it) == 0) verdicts.delete(it)
                savedFiles.delete(it)
                holders.delete(it)
            }
            // A refusal outlives the photo's bytes only while a peer still advertises it (ADR 2026-11.4ckq).
            refusals.deleteUnadvertised()
        }
    }

    private companion object {
        // As `PartialBlobs` keeps per hash in a session.
        const val MAX_HOLDER_MARKS = 16
    }
}
