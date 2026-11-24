package app.getknit.knit.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a content-addressed attachment out of the app's encrypted blob store and into public shared storage:
 * pictures to `Pictures/Knit`, videos to `Movies/Knit`, voice notes to `Recordings/Knit` (`Music/Knit` before Android
 * 12). Uses [MediaStore] with scoped storage (`RELATIVE_PATH` + `IS_PENDING`, both API 29+), which needs no runtime
 * storage permission and no FileProvider on minSdk 29.
 *
 * Kept separate from [AttachmentStore] (which owns ingest into the `blobs` table) because exporting to
 * shared storage is a distinct concern.
 */
class GallerySaver(
    private val context: Context,
) {
    /** What a voice-note save did: [VoiceSave.AlreadySaved] wrote nothing, because the copy is already there. */
    enum class VoiceSave { Saved, AlreadySaved, Failed }

    /**
     * Writes [bytes] (a blob's decrypted image, identified by content [hash] and [mime]) into
     * `Pictures/Knit` as `knit-<hash>.<ext>`. Returns true on success.
     *
     * Images only: [MediaStore.Images] is the sole collection this writes to, so a non-image [mime] is
     * refused rather than filed in the user's gallery under a type the gallery can't read.
     */
    suspend fun saveToPictures(
        bytes: ByteArray,
        hash: String,
        mime: String,
    ): Boolean {
        if (bytes.isEmpty() || !mime.startsWith("image/")) return false
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        return insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            bytes,
            "knit-$hash.$ext",
            mime,
            "${Environment.DIRECTORY_PICTURES}/Knit",
        )
    }

    /**
     * Writes a decrypted short video (ADR 2026-10.hmfe) into `Movies/Knit` as `knit-<hash>.mp4`, the
     * [MediaStore.Video] sibling of [saveToPictures]. Videos only, for the same reason that one takes images only.
     */
    suspend fun saveToMovies(
        bytes: ByteArray,
        hash: String,
        mime: String,
    ): Boolean {
        if (bytes.isEmpty() || !mime.startsWith("video/")) return false
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"
        return insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            bytes,
            "knit-$hash.$ext",
            mime,
            "${Environment.DIRECTORY_MOVIES}/Knit",
        )
    }

    /**
     * Writes a decrypted voice note (ADR 034's 2026-11-23 amendment) into [voiceFolder] as [displayName], the
     * [MediaStore.Audio] sibling of [saveToPictures]. Audio only, for the same reason that one takes images only.
     *
     * A copy already there under the same name is [VoiceSave.AlreadySaved] and nothing is written: the name is built
     * from the note alone, so a second tap would otherwise make a second file. An app always sees the rows it put in
     * MediaStore, without a permission, which is all this query asks. A copy the user deleted or renamed is not
     * there, and a new one is written.
     */
    suspend fun saveVoice(
        bytes: ByteArray,
        displayName: String,
        mime: String,
    ): VoiceSave {
        if (bytes.isEmpty() || !mime.startsWith("audio/")) return VoiceSave.Failed
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        if (held(collection, displayName, voiceFolder)) return VoiceSave.AlreadySaved
        return if (insert(collection, bytes, displayName, mime, voiceFolder)) VoiceSave.Saved else VoiceSave.Failed
    }

    /** Whether a row of ours named [displayName] already sits in [folder] of [collection]. */
    private suspend fun held(
        collection: Uri,
        displayName: String,
        folder: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        // MediaStore keeps a relative path with its trailing slash.
                        arrayOf(displayName, "$folder/"),
                        null,
                    )?.use { it.count > 0 } ?: false
            }.onFailure { Log.w(TAG, "Couldn't look for $displayName in $folder", it) }.getOrDefault(false)
        }

    /**
     * Inserts [bytes] into [collection] as [displayName] under [relativePath], hidden (`IS_PENDING`) until they are
     * fully written, then flipped visible. A failed write drops the orphaned pending row. Returns true on success.
     */
    private suspend fun insert(
        collection: Uri,
        bytes: ByteArray,
        displayName: String,
        mime: String,
        relativePath: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    // Hide the row until the bytes are fully written, then flip it visible below.
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val resolver = context.contentResolver
            val uri =
                runCatching { resolver.insert(collection, values) }
                    .onFailure { Log.w(TAG, "Couldn't add $displayName to $relativePath", it) }
                    .getOrNull() ?: return@withContext false
            val written =
                runCatching {
                    resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                        ?: error("null output stream for $uri")
                }.onFailure { Log.w(TAG, "Failed writing $displayName to $relativePath", it) }.isSuccess
            if (!written) {
                runCatching { resolver.delete(uri, null, null) } // drop the orphaned pending row
                return@withContext false
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }

    companion object {
        private const val TAG = "GallerySaver"

        /**
         * Whether a saved voice note goes to `Recordings/Knit`: from Android 12, the first release whose audio
         * collection takes a Recordings folder (music players skip it). Before it, `Music/Knit`.
         */
        val voiceInRecordings: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** Where a saved voice note goes ([voiceInRecordings]). */
        val voiceFolder: String
            get() =
                if (voiceInRecordings) "${Environment.DIRECTORY_RECORDINGS}/Knit" else "${Environment.DIRECTORY_MUSIC}/Knit"
    }
}
