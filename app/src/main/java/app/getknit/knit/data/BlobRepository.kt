package app.getknit.knit.data

import android.graphics.Bitmap
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.moderation.ImageModerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for content-addressed image blobs (attachments + avatars) held in the
 * encrypted database. Wraps [BlobDao] and owns the cross-table reference check used to garbage-collect
 * an orphaned blob once nothing points at it.
 *
 * It is also the on-device image-moderation hub: it screens images against [imageModerator] (gated on
 * the user's content-filtering setting) and caches the NSFW verdict by content hash in [verdicts], so
 * identical bytes are scanned at most once across send and receive (see [screenImage]/[isImageBlocked]).
 */
class BlobRepository(
    private val blobs: BlobDao,
    private val messages: MessageDao,
    private val peers: PeerDao,
    private val settings: SettingsStore,
    private val verdicts: BlobVerdictDao,
    private val imageModerator: ImageModerator,
) {
    suspend fun insert(hash: String, mime: String, bytes: ByteArray) =
        blobs.insert(BlobEntity(hash, mime, bytes))

    suspend fun bytes(hash: String): ByteArray? = blobs.bytes(hash)

    suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    suspend fun exists(hash: String): Boolean = blobs.exists(hash)

    /** Hashes of all stored blobs; the chat list observes this to flip attachments from loading to shown. */
    fun observeHashes(): Flow<List<String>> = blobs.observeHashes()

    /** Hashes flagged as explicit by on-device screening; the chat UI blurs these attachments. */
    fun observeFlaggedHashes(): Flow<List<String>> = verdicts.observeFlaggedHashes()

    /**
     * Send-side screen: true if content filtering is on and [bitmap] is classified explicit. Sending is
     * allowed but discouraged, so callers use this to prompt the user for confirmation (not to block).
     */
    suspend fun isImageExplicit(bitmap: Bitmap): Boolean =
        settings.contentFilteringEnabled.first() && imageModerator.classify(bitmap).flagged

    /**
     * Receive-side screening for a stored blob: when content filtering is on and no verdict is cached yet
     * for [hash], decode [bytes] (first frame for a GIF), classify, and cache the verdict. Idempotent per
     * hash, so the same image arriving via multiple messages/hops is scanned once. No-op when filtering
     * is off or the bytes can't be decoded.
     */
    suspend fun screenImage(hash: String, bytes: ByteArray) {
        if (!settings.contentFilteringEnabled.first()) return
        if (verdicts.find(hash) != null) return
        val bitmap = decodeBoundedFromBytes(bytes, SCREEN_MAX_DIM) ?: return
        val verdict = imageModerator.classify(bitmap)
        verdicts.upsert(BlobVerdictEntity(hash, verdict.flagged, verdict.score))
    }

    /** Whether [hash] has a cached verdict marking it explicit (used to refuse adopting a flagged avatar). */
    suspend fun isImageFlagged(hash: String): Boolean = verdicts.find(hash)?.flagged == true

    /**
     * Deletes the blob for [hash] only if nothing references it any more — no message attachment, no
     * peer avatar, and not the device's own avatar. Safe to call after deleting a message, swapping an
     * avatar, or discarding a staged-but-unsent attachment; a no-op (and tolerates a null hash) when the
     * blob is still in use.
     */
    suspend fun deleteIfUnreferenced(hash: String?) {
        if (hash == null) return
        if (hash == settings.ownAvatarHash.first()) return
        if (messages.countByAttachmentHash(hash) > 0) return
        if (peers.countByAvatarHash(hash) > 0) return
        blobs.delete(hash)
        verdicts.delete(hash)
    }

    /**
     * Deletes every blob no longer referenced by a message, a peer avatar, or the own avatar. Run once
     * on mesh start to reclaim blobs left orphaned by, e.g., an attachment staged but never sent (its
     * compose state doesn't survive a restart, so its blob is safe to drop).
     */
    suspend fun deleteOrphans() {
        val own = settings.ownAvatarHash.first()
        blobs.orphanHashes().filter { it != own }.forEach {
            blobs.delete(it)
            verdicts.delete(it)
        }
    }

    private companion object {
        // Screening downsamples to the model's input anyway; bound the decode so a peer-supplied image
        // can't OOM before classification.
        const val SCREEN_MAX_DIM = 512
    }
}
