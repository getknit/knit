package app.getknit.knit.moderation

import android.graphics.Bitmap

/**
 * Classifies an image as allowed or explicit (NSFW). Implementations run entirely on-device against a
 * bundled model (the app has no network); see the MediaPipe-backed `NsfwImageModerator`. Callers should
 * cache verdicts by the image's content hash, since the same bytes are scanned on both send and receive.
 */
interface ImageModerator {
    suspend fun classify(bitmap: Bitmap): ImageVerdict
}
