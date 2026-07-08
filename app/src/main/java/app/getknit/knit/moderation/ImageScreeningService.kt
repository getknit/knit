package app.getknit.knit.moderation

import android.util.Log
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.decodeBoundedFromBytes
import kotlinx.coroutines.flow.Flow

/**
 * On-device image-moderation service: screens images against [imageModerator] and caches the NSFW verdict
 * by content hash in [verdicts]. Extracted from `BlobRepository` so the data layer no longer invokes the
 * classifier (`docs/ARCHITECTURE_REVIEW.md` #16). Screening always runs; the content-filtering setting only
 * gates receive-side *hiding* (the chat blur and the avatar-adoption decision), not the scan.
 *
 * The send side screens to gate a confirm/block ([isImageExplicit], cached nowhere); the receive side caches
 * the verdict for a stored blob ([screenImage]) so each received image is scanned at most once. For an E2E
 * attachment the stored bytes are ciphertext, so the caller decrypts first and passes the plaintext to
 * [screenImage] under the ciphertext hash (see `InboundPipeline.screenEncryptedAttachment`).
 */
class ImageScreeningService(
    private val imageModerator: ImageModerator,
    private val verdicts: BlobVerdictDao,
) {
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
     * ciphertext [hash] (see `InboundPipeline.screenEncryptedAttachment`).
     */
    suspend fun screenImage(
        hash: String,
        bytes: ByteArray,
    ) {
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

    private companion object {
        const val TAG = "ImageModeration"

        // Screening downsamples to the model's input anyway; bound the decode so a peer-supplied image
        // can't OOM before classification.
        const val SCREEN_MAX_DIM = 512
    }
}
