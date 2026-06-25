package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.getknit.knit.ui.util.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Stores the device's own avatar (picked + center-cropped to a 256² JPEG in filesDir) and caches
 * peer avatars received over the mesh (in cacheDir). Avatars travel as Nearby file payloads, not in
 * the database.
 *
 * Both are **content-addressed** by the SHA-256 of the JPEG bytes — the own avatar at
 * `filesDir/avatar-<hash>.jpg`, peer avatars at `cacheDir/avatar-<nodeId>-<hash>.jpg`. The hash in the
 * filename is what makes a *changed* avatar land at a *new* path: Coil keys its image cache by path
 * and a `StateFlow` only re-emits on a changed value, so the old fixed-name scheme made an overwritten
 * avatar render stale forever (the value never changed and Coil kept serving the cached bitmap).
 * Mirrors [AttachmentStore]'s content-addressed approach.
 */
class AvatarStore(private val context: Context) {

    private val ownDir: File get() = context.filesDir

    private fun ownAvatars(): List<File> =
        ownDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }?.toList()
            ?: emptyList()

    /** The current own-avatar JPEG, or null if none has been set. */
    fun ownAvatarFile(): File? {
        migrateLegacyOwnAvatar()
        return ownAvatars().firstOrNull()
    }

    fun ownAvatarPath(): String? = ownAvatarFile()?.absolutePath

    /**
     * SHA-256 content hash of the avatar bytes, so peers (and our own send-dedup) can tell when the
     * avatar actually changed. Read straight from the content-addressed filename — no need to re-hash.
     */
    fun ownAvatarHash(): String? =
        ownAvatarFile()?.name?.removePrefix(PREFIX)?.removeSuffix(SUFFIX)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun peerAvatarPath(nodeId: String): String? =
        context.cacheDir
            .listFiles { f -> f.name.startsWith(peerAvatarPrefix(nodeId)) && f.name.endsWith(SUFFIX) }
            ?.firstOrNull()
            ?.absolutePath

    /**
     * Decodes [uri] (EXIF-corrected and memory-bounded) into a bitmap for the crop UI — large enough
     * for a crisp 256² avatar but capped so a big photo can't OOM the decode. Null if unreadable.
     */
    suspend fun loadForCrop(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        decodeOrientedBounded(context, uri, MAX_CROP_DIMENSION)?.let { downscale(it, MAX_CROP_DIMENSION) }
    }

    /** Decodes [uri] and center-crops to a square (the path used when no interactive crop is supplied). */
    suspend fun saveOwnAvatar(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        loadForCrop(uri)?.let { saveOwnAvatar(it) } ?: false
    }

    /** Extracts the (already-square) [crop] region from [source], then writes it as the own avatar. */
    suspend fun saveOwnAvatar(source: Bitmap, crop: CropRect): Boolean = withContext(Dispatchers.IO) {
        saveOwnAvatar(Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height))
    }

    /** Center-crops [bitmap] to a square, scales to 256², and writes the content-addressed own-avatar JPEG. */
    suspend fun saveOwnAvatar(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val side = min(bitmap.width, bitmap.height)
        val square = if (bitmap.width == side && bitmap.height == side) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        }
        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
        val bytes = ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            .toByteArray()
        // Replace any prior avatar (hashed or legacy) so exactly one avatar-<hash>.jpg remains; the new
        // hash yields a new path, which is what makes the change visible past Coil's path-keyed cache.
        ownAvatars().forEach { it.delete() }
        File(ownDir, LEGACY_OWN_NAME).delete()
        File(ownDir, "$PREFIX${sha256(bytes)}$SUFFIX").writeBytes(bytes)
        true
    }

    /** One-time move of a pre-content-addressing `avatar.jpg` to the hashed scheme so it isn't lost. */
    private fun migrateLegacyOwnAvatar() {
        val legacy = File(ownDir, LEGACY_OWN_NAME)
        if (!legacy.exists() || ownAvatars().isNotEmpty()) return
        legacy.copyTo(File(ownDir, "$PREFIX${sha256(legacy.readBytes())}$SUFFIX"), overwrite = true)
        legacy.delete()
    }

    companion object {
        private const val AVATAR_SIZE = 256
        private const val JPEG_QUALITY = 90
        // Working resolution for the crop source: ample for a 256² avatar, bounded to avoid OOM.
        private const val MAX_CROP_DIMENSION = 1280
        private const val PREFIX = "avatar-"
        private const val SUFFIX = ".jpg"
        private const val LEGACY_OWN_NAME = "avatar.jpg"

        /**
         * Content-addressed cache filename for a peer's avatar, shared with `NearbyTransport`'s
         * incoming-file writer so [peerAvatarPath] finds exactly what the transport wrote.
         */
        fun peerAvatarFileName(nodeId: String, hash: String): String = "$PREFIX$nodeId-$hash$SUFFIX"

        private fun peerAvatarPrefix(nodeId: String): String = "$PREFIX$nodeId-"
    }
}
