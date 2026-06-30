package app.getknit.knit.data

import android.util.Log
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.moderation.ImageModerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for content-addressed image blobs (attachments + avatars + group photos) held
 * in the encrypted database. Wraps [BlobDao] and owns the cross-table reference check used to
 * garbage-collect an orphaned blob once nothing points at it.
 *
 * It is also the on-device image-moderation hub: it screens images against [imageModerator] and caches
 * the NSFW verdict by content hash in [verdicts]. Screening always runs; the content-filtering setting
 * only gates receive-side *hiding* (the chat blur and the avatar-adoption decision), not the scan. The
 * send side screens to gate a confirm/block ([isImageExplicit], cached nowhere); the receive side caches
 * the verdict for a stored blob ([screenImage]) so each received image is scanned at most once. For an
 * E2E attachment the stored bytes are ciphertext, so the caller decrypts first and passes the plaintext
 * to [screenImage] under the ciphertext hash (see `MeshManager.screenEncryptedAttachment`).
 */
class BlobRepository(
    private val blobs: BlobDao,
    private val messages: MessageDao,
    private val peers: PeerDao,
    private val settings: SettingsStore,
    private val verdicts: BlobVerdictDao,
    private val imageModerator: ImageModerator,
    private val groups: GroupDao,
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
     * Send-side screen: true if the image in [bytes] is classified explicit. Always runs — this is a
     * send-side "good-citizen" check (block-in-room / confirm-in-DM) and is **not** gated by the
     * content-filtering setting, which only governs receive-side hiding. [bytes] are the exact bytes that
     * will be stored and transmitted, decoded here at the same [SCREEN_MAX_DIM] bound the receive-side
     * screen uses — so the sender and recipient classify byte-identical input and reach the same verdict
     * (the screen reflects what is actually sent, not the sharper, full-resolution pre-JPEG source
     * bitmap). Sending an explicit image is allowed but discouraged, so callers use this to prompt for
     * confirmation (not to block). Fail-open (returns false) when the bytes can't be decoded; no verdict
     * is cached here (the receive side caches by the stored/ciphertext hash, see [screenImage]).
     */
    suspend fun isImageExplicit(bytes: ByteArray): Boolean {
        val bitmap = decodeBoundedFromBytes(bytes, SCREEN_MAX_DIM) ?: return false
        val verdict = imageModerator.classify(bitmap)
        Log.d(
            TAG,
            "outgoing image score=${verdict.score} flagged=${verdict.flagged} " +
                "size=${bitmap.width}x${bitmap.height}",
        )
        return verdict.flagged
    }

    /**
     * Receive-side screening for a stored blob: when no verdict is cached yet for [hash], decode [bytes]
     * (first frame for a GIF), classify, and cache the verdict under [hash]. Always runs (not gated by
     * the content-filtering setting): the cached verdict drives the avatar-adoption decision and the
     * chat's reactive blur/collapse, the latter gated at display time by the setting so toggling it flips
     * already-received content without re-scanning. Idempotent per hash, so the same image arriving via
     * multiple messages/hops is scanned once. No-op when the bytes can't be decoded. For a plaintext
     * image (avatar / broadcast attachment) [bytes] are the stored blob's bytes; for an E2E attachment
     * the caller decrypts the ciphertext blob first and passes the plaintext while still keying by the
     * ciphertext [hash] (see `MeshManager.screenEncryptedAttachment`).
     */
    suspend fun screenImage(hash: String, bytes: ByteArray) {
        if (verdicts.find(hash) != null) return
        val bitmap = decodeBoundedFromBytes(bytes, SCREEN_MAX_DIM) ?: return
        val verdict = imageModerator.classify(bitmap)
        Log.d(
            TAG,
            "incoming image hash=$hash score=${verdict.score} flagged=${verdict.flagged} " +
                "size=${bitmap.width}x${bitmap.height}",
        )
        verdicts.upsert(BlobVerdictEntity(hash, verdict.flagged, verdict.score))
    }

    /** Whether [hash] has a cached verdict marking it explicit (used to refuse adopting a flagged avatar). */
    suspend fun isImageFlagged(hash: String): Boolean = verdicts.find(hash)?.flagged == true

    /**
     * Deletes the blob for [hash] only if nothing references it any more — no message attachment, no
     * peer avatar, no group photo, and not the device's own avatar. Safe to call after deleting a
     * message, swapping an avatar/group photo, or discarding a staged-but-unsent attachment; a no-op
     * (and tolerates a null hash) when the blob is still in use.
     */
    suspend fun deleteIfUnreferenced(hash: String?) {
        if (hash == null) return
        if (hash == settings.ownAvatarHash.first()) return
        if (messages.countByAttachmentHash(hash) > 0) return
        if (peers.countByAvatarHash(hash) > 0) return
        if (groups.countByPhotoHash(hash) > 0) return
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
        const val TAG = "ImageModeration"

        // Screening downsamples to the model's input anyway; bound the decode so a peer-supplied image
        // can't OOM before classification.
        const val SCREEN_MAX_DIM = 512
    }
}
