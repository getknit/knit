package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.scale
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.ui.util.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Produces the device's own avatar (picked + center-cropped to a 256² JPEG) and stores it in the
 * encrypted, content-addressed `blobs` table (see [app.getknit.knit.data.blob.BlobEntity]) rather than as a plaintext file.
 *
 * Avatars are **content-addressed** by the SHA-256 of the JPEG bytes — the same hash advertised in the
 * profile frame. The hash is what makes a *changed* avatar visible: Coil keys its image cache by the
 * blob hash (see the `BlobImage` fetcher/keyer), so a new hash defeats the cache. Mirrors
 * [AttachmentStore]'s content-addressed approach. The own-avatar hash itself is persisted in
 * [app.getknit.knit.data.settings.SettingsStore.ownAvatarHash].
 *
 * The same door stores a group photo, and both are screened for explicit content before they are stored, in
 * every scope and whatever the Content filtering toggle says: a face is shown to everyone who sees the profile or
 * the group, strangers in the Nearby room included (ADR 2026-10.9rh6). Receivers screen it again on arrival.
 */
class AvatarStore(
    private val context: Context,
    private val blobs: BlobRepository,
    private val screening: ImageScreeningService,
) {
    /**
     * Decodes [uri] (EXIF-corrected and memory-bounded) into a bitmap for the crop UI — large enough
     * for a crisp 256² avatar but capped so a big photo can't OOM the decode. Null if unreadable.
     */
    suspend fun loadForCrop(uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            decodeOrientedBounded(context, uri, MAX_CROP_DIMENSION)?.let { downscale(it, MAX_CROP_DIMENSION) }
        }

    /**
     * Extracts the (already-square) [crop] region from [source], then stores it as the own avatar; null when the
     * screen refused it.
     */
    suspend fun saveOwnAvatar(
        source: Bitmap,
        crop: CropRect,
    ): String? =
        withContext(Dispatchers.IO) {
            saveOwnAvatar(Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height))
        }

    /**
     * Center-crops [bitmap] to a square, scales to 256², encodes JPEG, screens those exact bytes, and stores them
     * in the `blobs` table. Returns the content hash, which the caller persists as the own-avatar hash, or null —
     * nothing stored — when the screen judged the picture explicit (fail-open, like every send-side screen, when
     * the model is off). A crop rect the bitmap can't satisfy throws out of `createBitmap` instead.
     */
    suspend fun saveOwnAvatar(bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            val side = min(bitmap.width, bitmap.height)
            val square =
                if (bitmap.width == side && bitmap.height == side) {
                    bitmap
                } else {
                    Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                }
            val scaled = square.scale(AVATAR_SIZE, AVATAR_SIZE)
            val bytes =
                ByteArrayOutputStream()
                    .also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
            if (screening.isImageExplicit(bytes)) return@withContext null
            val hash = sha256(bytes)
            blobs.insert(hash, "image/jpeg", bytes)
            hash
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val AVATAR_SIZE = 256
        const val JPEG_QUALITY = 90

        // Working resolution for the crop source: ample for a 256² avatar, bounded to avoid OOM.
        const val MAX_CROP_DIMENSION = 1280
    }
}
