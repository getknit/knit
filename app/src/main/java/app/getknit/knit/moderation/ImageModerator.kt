package app.getknit.knit.moderation

import android.graphics.Bitmap

/**
 * Classifies an image as allowed or explicit (NSFW). Implementations run entirely on-device against a
 * bundled model (the app has no network); see the MediaPipe-backed `NsfwImageModerator`. The send side
 * screens the picked plaintext to gate a confirm/block; the receive side screens the received (and, for
 * an E2E attachment, decrypted) bytes and caches the verdict by the blob's content hash, so each stored
 * image is scanned at most once (see `BlobRepository.screenImage`).
 */
interface ImageModerator {
    suspend fun classify(bitmap: Bitmap): ImageVerdict
}
