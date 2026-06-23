package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Stores the device's own avatar (picked + center-cropped to a 256² JPEG in filesDir) and caches
 * peer avatars received over the mesh (cacheDir/<nodeId>.jpg). Mirrors the legacy app's approach;
 * avatars travel as Nearby file payloads, not in the database.
 */
class AvatarStore(private val context: Context) {

    val ownAvatarFile: File get() = File(context.filesDir, "avatar.jpg")

    fun ownAvatarPath(): String? = ownAvatarFile.takeIf(File::exists)?.absolutePath

    /** Cheap content fingerprint so peers can tell when an avatar changed. */
    fun ownAvatarHash(): String? =
        ownAvatarFile.takeIf(File::exists)?.let { "${it.length()}-${it.lastModified()}" }

    fun peerAvatarFile(nodeId: String): File = File(context.cacheDir, "$nodeId.jpg")

    fun peerAvatarPath(nodeId: String): String? =
        peerAvatarFile(nodeId).takeIf(File::exists)?.absolutePath

    /** Decodes [uri], center-crops to a square, scales to 256², and writes the own-avatar JPEG. */
    suspend fun saveOwnAvatar(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return@withContext false

        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
        ownAvatarFile.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        true
    }

    private companion object {
        const val AVATAR_SIZE = 256
        const val JPEG_QUALITY = 90
    }
}
