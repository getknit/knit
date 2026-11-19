package app.getknit.knit.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import app.getknit.knit.di.FACE_REDACTION_WIRED
import app.getknit.knit.mesh.protocol.AttachmentName
import app.getknit.knit.redact.CoveredCopies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * [TransferFiles] on Android: a picked `content://` document (or a `file://` path, which the debug bridge
 * uses) as the source, and a pending `MediaStore.Downloads` entry under `Download/Knit` as the sink — the
 * `GallerySaver` idiom (`RELATIVE_PATH` + `IS_PENDING`, no storage permission on minSdk 29). A received file
 * lands where Quick Share would put it, visible to every app; it is deliberately not an attachment and never
 * enters the encrypted blob store.
 */
class AndroidTransferFiles(
    context: Context,
) : TransferFiles {
    private val app = context.applicationContext

    override suspend fun openSource(uri: String): TransferSource? =
        withContext(Dispatchers.IO) {
            val parsed = uri.toUri()
            if (parsed.scheme == ContentResolver.SCHEME_FILE) fileSource(parsed) else contentSource(parsed)
        }

    override suspend fun createSink(
        name: String,
        mime: String?,
        size: Long,
    ): TransferSink? =
        withContext(Dispatchers.IO) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: DEFAULT_MIME)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                    // Hidden until the bytes are all there and verified; commit() flips it.
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val resolver = app.contentResolver
            val target = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            target?.let { DownloadSink(resolver, it) }
        }

    override suspend fun release(uri: String) {
        // Only a covered photo's copy is ever released, and a build without face redaction writes none.
        if (!FACE_REDACTION_WIRED) return
        val parsed = uri.toUri()
        if (parsed.scheme != ContentResolver.SCHEME_FILE) return
        val path = parsed.path ?: return
        withContext(Dispatchers.IO) { runCatching { CoveredCopies.discard(app.cacheDir, File(path)) } }
    }

    override fun freeBytes(): Long =
        runCatching { app.getSystemService(StorageManager::class.java)?.getAllocatableBytes(StorageManager.UUID_DEFAULT) }
            .getOrNull() ?: (app.getExternalFilesDir(null)?.usableSpace ?: 0L)

    private fun fileSource(uri: Uri): TransferSource? {
        val file = File(uri.path ?: return null)
        if (!file.isFile) return null
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        return TransferSource(file.name, file.length(), mime) { FileInputStream(file) }
    }

    private fun contentSource(uri: Uri): TransferSource? {
        val resolver = app.contentResolver
        // The transfer runs minutes after the pick, off the app scope; keep the grant past the activity.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (name, declared) = nameAndSize(resolver, uri)
        val measured = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it > 0L }
        val size = measured ?: declared ?: return null
        val display = AttachmentName.sanitize(name ?: uri.lastPathSegment) ?: return null
        return TransferSource(display, size, resolver.getType(uri)) {
            resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        }
    }

    private fun nameAndSize(
        resolver: ContentResolver,
        uri: Uri,
    ): Pair<String?, Long?> =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) c.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else null
                name to size
            }
        }.getOrNull() ?: (null to null)

    private class DownloadSink(
        private val resolver: ContentResolver,
        private val target: Uri,
    ) : TransferSink {
        override val uri: String = target.toString()
        private var out: OutputStream? = null

        override fun stream(): OutputStream =
            out ?: (resolver.openOutputStream(target) ?: throw IOException("no output stream for $target")).also { out = it }

        override suspend fun commit() {
            withContext(Dispatchers.IO) {
                out?.let { o ->
                    o.flush()
                    (o as? FileOutputStream)?.fd?.sync()
                    o.close()
                }
                out = null
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                runCatching { out?.close() }
                out = null
                runCatching { resolver.delete(target, null, null) }
            }
        }
    }

    private companion object {
        const val FOLDER = "Knit"
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
