package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.ui.util.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Produces the device's own avatar (picked + center-cropped to a 256² JPEG) and stores it in the
 * encrypted, content-addressed `blobs` table (see [BlobEntity]) rather than as a plaintext file.
 *
 * Avatars are **content-addressed** by the SHA-256 of the JPEG bytes — the same hash advertised in the
 * profile frame. The hash is what makes a *changed* avatar visible: Coil keys its image cache by the
 * blob hash (see the `BlobImage` fetcher/keyer), so a new hash defeats the cache. Mirrors
 * [AttachmentStore]'s content-addressed approach. The own-avatar hash itself is persisted in
 * [app.getknit.knit.data.settings.SettingsStore.ownAvatarHash].
 */
class AvatarStore(
    private val context: Context,
    private val blobs: BlobDao,
) {
    /**
     * Decodes [uri] (EXIF-corrected and memory-bounded) into a bitmap for the crop UI — large enough
     * for a crisp 256² avatar but capped so a big photo can't OOM the decode. Null if unreadable.
     */
    suspend fun loadForCrop(uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            decodeOrientedBounded(context, uri, MAX_CROP_DIMENSION)?.let { downscale(it, MAX_CROP_DIMENSION) }
        }

    /** Extracts the (already-square) [crop] region from [source], then stores it as the own avatar. */
    suspend fun saveOwnAvatar(
        source: Bitmap,
        crop: CropRect,
    ): String? =
        withContext(Dispatchers.IO) {
            saveOwnAvatar(Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height))
        }

    /**
     * Center-crops [bitmap] to a square, scales to 256², encodes JPEG, and stores it in the `blobs`
     * table. Returns the content hash (the caller persists it as the own-avatar hash), or null.
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
            val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
            val bytes =
                ByteArrayOutputStream()
                    .also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
            val hash = sha256(bytes)
            blobs.insert(BlobEntity(hash, "image/jpeg", bytes))
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
