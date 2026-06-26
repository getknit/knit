package app.getknit.knit.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a content-addressed attachment out of the app's encrypted blob store and into the public
 * gallery at `Pictures/Knit`, so received images can be saved like in Signal. Uses [MediaStore] with
 * scoped storage (`RELATIVE_PATH` + `IS_PENDING`, both API 29+), which needs no runtime storage
 * permission and no FileProvider on minSdk 29.
 *
 * Kept separate from [AttachmentStore] (which owns ingest into the `blobs` table) because exporting to
 * shared storage is a distinct concern.
 */
class GallerySaver(private val context: Context) {

    /**
     * Writes [bytes] (a blob's decrypted image, identified by content [hash] and [mime]) into
     * `Pictures/Knit` as `knit-<hash>.<ext>`. Returns true on success.
     */
    suspend fun saveToPictures(bytes: ByteArray, hash: String, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext false
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "knit-$hash.$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Knit")
                // Hide the row until the bytes are fully written, then flip it visible below.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    ?: error("null output stream for $uri")
            }.onFailure { Log.w(TAG, "Failed writing knit-$hash.$ext to gallery", it) }.isSuccess
            if (!written) {
                runCatching { resolver.delete(uri, null, null) } // drop the orphaned pending row
                return@withContext false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }

    private companion object {
        const val TAG = "GallerySaver"
    }
}
