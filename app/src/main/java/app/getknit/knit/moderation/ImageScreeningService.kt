package app.getknit.knit.moderation

import android.util.Log
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import kotlinx.coroutines.flow.Flow

/**
 * On-device attachment-moderation service: screens images against [imageModerator] and caches the NSFW verdict
 * by content hash in [verdicts]. Extracted from `BlobRepository` so the data layer no longer invokes the
 * classifier (`docs/ARCHITECTURE_REVIEW.md` #16). Screening always runs; the content-filtering setting only
 * gates receive-side *hiding* (the chat blur and the avatar-adoption decision), not the scan.
 *
 * The name predates link-preview cards. A card ([LinkPreviewBlob]) is an attachment whose bytes are a container
 * rather than a picture, so [screenAttachment] opens it and screens what is inside — its picture against
 * [imageModerator], its title and description against [textModeration], the way the message body is — into
 * **one** verdict under the blob's hash. One verdict because the UI has one question ("hide this
 * attachment?") and one place to ask it; a card whose text or picture trips either classifier hides whole.
 *
 * The send side screens to gate a confirm/block ([isImageExplicit], cached nowhere); the receive side caches
 * the verdict for a stored blob ([screenImage] / [screenAttachment]) so each received attachment is scanned at
 * most once. For an E2E attachment the stored bytes are ciphertext, so the caller decrypts first and passes
 * the plaintext under the ciphertext hash (see `InboundPipeline.screenHeldAttachment`).
 */
class ImageScreeningService(
    private val imageModerator: ImageModerator,
    private val verdicts: BlobVerdictDao,
    private val textModeration: ScopedTextModerator,
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
     * Receive-side screening for any held plaintext attachment, routed by what **our own row** says it is:
     * a link-preview card is opened and screened as a card ([screenLinkPreview]); everything else is screened
     * as an image ([screenImage]) — which is also what an attachment with no row at all gets, on the safe side.
     * [isRoom] selects the text classifier for a card: the Nearby room's, or the one DMs and groups use.
     */
    suspend fun screenAttachment(
        hash: String,
        bytes: ByteArray,
        mime: String?,
        isRoom: Boolean,
    ) {
        if (mime == LinkPreviewBlob.MIME) screenLinkPreview(hash, bytes, isRoom) else screenImage(hash, bytes)
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
     * ciphertext [hash] (see `InboundPipeline.screenHeldAttachment`).
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

    /**
     * A card's receive-side screen: its picture (if any) through the image classifier and its title and
     * description through the text classifier for this scope, folded into one verdict under [hash]. A container
     * that does not decode gets no verdict, like an image that does not decode — it renders nothing anyway.
     * Idempotent per hash, like [screenImage].
     */
    private suspend fun screenLinkPreview(
        hash: String,
        bytes: ByteArray,
        isRoom: Boolean,
    ) {
        if (verdicts.find(hash) != null) return
        val card = LinkPreviewBlob.decodeOrNull(bytes) ?: return
        val picture = card.image?.let { decodeBoundedFromBytes(it, SCREEN_MAX_DIM) }?.let { imageModerator.classify(it) }
        val text = textModeration.classify(card.moderationText(), isRoom)
        val flagged = picture?.flagged == true || text.flagged
        Log.d(TAG, "incoming card hash=$hash picture=${picture?.flagged} text=${text.flagged} flagged=$flagged")
        verdicts.upsert(BlobVerdictEntity(hash, flagged, maxOf(picture?.score ?: 0f, text.score)))
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
