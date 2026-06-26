package app.getknit.knit.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exports a content-addressed attachment out of the app's private store and into the public gallery
 * at `Pictures/Knit`, so received images can be saved like in Signal. Uses [MediaStore] with scoped
 * storage (`RELATIVE_PATH` + `IS_PENDING`, both API 29+), which needs no runtime storage permission
 * and no FileProvider on minSdk 29.
 *
 * Kept separate from [AttachmentStore] (which owns the private `filesDir/attachments` store) because
 * exporting to shared storage is a distinct concern.
 */
class GallerySaver(private val context: Context) {

    /**
     * Copies [source] (a `<hash>.<ext>` file from [AttachmentStore]) into `Pictures/Knit`. The MIME
     * type is derived from the file extension, so JPEGs land as `.jpg` and GIFs stay animated.
     * Returns true on success.
     */
    suspend fun saveToPictures(source: File): Boolean = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext false
        val ext = source.extension.lowercase().ifBlank { "jpg" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "knit-${source.name}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Knit")
            // Hide the row until the bytes are fully written, then flip it visible below.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("null output stream for $uri")
        }.onFailure { Log.w(TAG, "Failed writing ${source.name} to gallery", it) }.isSuccess
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
