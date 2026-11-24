package app.getknit.knit.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GallerySaver.VoiceSave
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a held attachment's bytes may do once they leave the bubble: play in memory, go to the gallery, go to the
 * voice-note folder, or go to a document the user picked. The one copy of those rules, for every screen that shows
 * attachments: the chat and "Media, links and files" (ADR 2026-11.dgvf) both call it.
 *
 * Every door decrypts on the way out and writes nowhere of Knit's own (ADR 029: an attachment's plaintext lives in
 * the encrypted blob store and nowhere else). A file opens from the copy the user saved it to, and a risky one is
 * only ever saved (ADR 2026-09.7ad3). A sealed attachment opened here also teaches its identity its plaintext, so a
 * forward of the saved copy reuses it (ADR 2026-10.mptm).
 */
class AttachmentExport(
    private val blobs: BlobRepository,
    private val identities: AttachmentIdentities,
    private val gallerySaver: GallerySaver,
    @field:SuppressLint("StaticFieldLeak") private val context: Context,
) {
    /** A file written to a picked document: the toast to show, and the copy to open (null when none should be). */
    data class FileWrite(
        @param:StringRes val message: Int,
        val opened: SavedFile?,
    )

    /**
     * A held attachment's plaintext, for the video player (ADR 2026-10.hmfe): read and decrypted into memory like a
     * voice note's, never staged as a file. Null when the blob is gone or does not open.
     */
    suspend fun plaintext(
        hash: String,
        key: String?,
    ): ByteArray? {
        val raw = blobs.bytes(hash) ?: return null
        if (key == null) return raw
        return withContext(Dispatchers.Default) { AttachmentCrypto.open(raw, b64d(key)) }
    }

    /** Saves a held video to `Movies/Knit` (ADR 2026-10.hmfe); answers the toast to show, a string resource. */
    suspend fun saveVideo(
        hash: String,
        key: String?,
        mime: String?,
    ): Int {
        val bytes = plaintext(hash, key)
        val ok = bytes != null && gallerySaver.saveToMovies(bytes, hash, mime ?: "video/mp4")
        return if (ok) R.string.chat_video_saved else R.string.chat_video_save_failed
    }

    /**
     * Saves a held voice note to [GallerySaver.voiceFolder] as its own bytes, never transcoded (ADR 034's 2026-11-23
     * amendment); answers the toast to show, a string resource. [sentAt] names the file with [hash]
     * ([voiceFileName]), so a second save of the same note finds the first and writes nothing. [key] and [mime] are
     * the tapped row's, as in [savePicture]; the type written is [voiceSaveType]'s, never the sender's own string.
     */
    suspend fun saveVoice(
        hash: String,
        key: String?,
        mime: String?,
        sentAt: Long,
    ): Int {
        val type = voiceSaveType(mime) ?: return R.string.chat_voice_save_failed
        val bytes = plaintext(hash, key) ?: return R.string.chat_voice_save_failed
        if (key != null) identities.notePlain(hash, bytes)
        val name = voiceFileName(hash, sentAt, ZoneId.systemDefault(), type.ext)
        // The toast names the folder, as the picture save's does.
        val saved = if (GallerySaver.voiceInRecordings) R.string.chat_voice_saved_recordings else R.string.chat_voice_saved_music
        return when (gallerySaver.saveVoice(bytes, name, type.mime)) {
            VoiceSave.Saved -> saved
            VoiceSave.AlreadySaved -> R.string.chat_voice_already_saved
            VoiceSave.Failed -> R.string.chat_voice_save_failed
        }
    }

    /**
     * Exports the picture blob [hash] to the public `Pictures/Knit` folder; answers the toast to show, a string resource.
     *
     * [key] and [mime] come from the message row the user tapped, which is exactly what
     * [app.getknit.knit.ui.image.BlobFetcher] takes to render it — so what gets saved is what is on screen, by
     * construction. Both matter:
     *
     * - A DM/group attachment's stored blob is `iv || ciphertext` ([AttachmentCrypto]), content-addressed by the
     *   *ciphertext* hash, so it has to be opened before it leaves the app. Without [key] this wrote 300 KB of
     *   ciphertext into the gallery under an image mime and reported success.
     * - `blobs.mime` describes those stored (ciphertext) bytes and is only ever as good as whatever named the blob
     *   when it landed — since ADR 035 a fetcher default on the spool path rather than the frame. The row's mime is
     *   the plaintext's own type; the blob row is just the fallback.
     */
    suspend fun savePicture(
        hash: String,
        key: String?,
        mime: String?,
    ): Int {
        val raw = blobs.bytes(hash)
        val bytes = if (key != null && raw != null) AttachmentCrypto.open(raw, b64d(key)) else raw
        if (key != null && bytes != null) identities.notePlain(hash, bytes)
        val type = mime ?: blobs.mimeFor(hash)
        val ok = bytes != null && type != null && gallerySaver.saveToPictures(bytes, hash, type)
        return if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed
    }

    /**
     * The copy a received file was saved to before, when it is still there to open (ADR 2026-09.7ad3). A copy that
     * has been moved or deleted, or whose grant is gone, is forgotten, and the answer is null: the caller sends the
     * file through the storage picker. A risky file ([FileTypes.isRisky]) never comes here — the screen asks first
     * and saves it, and nothing opens it.
     */
    suspend fun savedCopy(pending: PendingSave): SavedFile? {
        val copy = blobs.savedCopy(pending.hash)?.toUri() ?: return null
        if (withContext(Dispatchers.IO) { context.contentResolver.documentExists(copy) }) {
            return SavedFile(copy, pending.mime ?: withContext(Dispatchers.IO) { context.contentResolver.typeOf(copy) })
        }
        blobs.forgetSavedCopy(pending.hash)
        return null
    }

    /**
     * Writes a received **file** attachment to the document [dest] the user just picked, decrypting it on the way
     * exactly as [savePicture] does, then remembers the copy and answers it to be opened (ADR 2026-09.7ad3).
     *
     * Knit never hands another app a file *from the blob store*: that would need a content provider serving
     * decrypted bytes, and ADR 029's invariant is worth more than the convenience. Through the storage picker the
     * bytes go straight from the blob into the stream the user chose, still never landing in our own storage. What
     * gets opened, now and on every later tap, is that copy, the user's own, served by the provider that holds it
     * under a read grant Knit persists — the same hand-off the direct-transfer card makes. A risky file
     * ([FileTypes.isRisky]) is saved and left there: nothing on the device can look inside it, and an app package
     * must never reach an installer through us.
     */
    suspend fun writeTo(
        pending: PendingSave,
        dest: Uri,
    ): FileWrite {
        val ok =
            withContext(Dispatchers.IO) {
                val raw = blobs.bytes(pending.hash)
                val bytes =
                    if (pending.key != null && raw != null) AttachmentCrypto.open(raw, b64d(pending.key)) else raw
                if (pending.key != null && bytes != null) identities.notePlain(pending.hash, bytes)
                bytes != null &&
                    runCatching {
                        context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
            }
        if (!ok) return FileWrite(R.string.chat_file_save_failed, null)
        if (FileTypes.isRisky(pending.mime, pending.name)) return FileWrite(R.string.chat_file_saved, null)
        // Without the persisted grant the URI works until this process dies; the next tap then finds it
        // unreadable, forgets it and asks again, so a refused take costs one prompt and nothing else.
        runCatching { context.contentResolver.takePersistableUriPermission(dest, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        blobs.rememberSavedCopy(pending.hash, dest.toString(), System.currentTimeMillis())
        val type = pending.mime ?: withContext(Dispatchers.IO) { context.contentResolver.typeOf(dest) }
        return FileWrite(R.string.chat_file_saved, SavedFile(dest, type))
    }

    /** A type a voice note is saved under: the MIME MediaStore is told and the file's extension, both Knit's own. */
    internal data class VoiceType(
        val mime: String,
        val ext: String,
    )

    companion object {
        private val AAC = VoiceType(VoiceAudio.MIME, "aac")
        private val M4A = VoiceType("audio/mp4", "m4a")
        private val MP3 = VoiceType("audio/mpeg", "mp3")
        private val OGG = VoiceType("audio/ogg", "ogg")
        private val WAV = VoiceType("audio/x-wav", "wav")
        private val FLAC = VoiceType("audio/flac", "flac")
        private val AMR = VoiceType("audio/amr", "amr")

        /**
         * The audio a save writes, by the row's MIME without its parameters, lower-cased: a recorded note's ADTS AAC and
         * the common formats a picked file sent as a note comes in. Opus is an Ogg stream and is saved as one.
         */
        private val VOICE_TYPES =
            mapOf(
                "audio/aac" to AAC,
                "audio/aac-adts" to AAC,
                "audio/mp4" to M4A,
                "audio/m4a" to M4A,
                "audio/x-m4a" to M4A,
                "audio/mpeg" to MP3,
                "audio/mp3" to MP3,
                "audio/ogg" to OGG,
                "audio/opus" to OGG,
                "audio/wav" to WAV,
                "audio/x-wav" to WAV,
                "audio/wave" to WAV,
                "audio/flac" to FLAC,
                "audio/x-flac" to FLAC,
                "audio/amr" to AMR,
            )

        /**
         * The type and extension a voice note under [mime] is saved as, or null to refuse the save. The MIME is the
         * sender's, and whatever MIME and extension a save writes decide how MediaStore scans the file and which app
         * opens it: `audio/x-mpegurl` would land as an `.m3u` playlist a music player follows to the sender's server,
         * `audio/midi` as a `.mid` for a parser the voice path never needed. So the platform's MIME map is never
         * asked: only [VOICE_TYPES] is. A row naming no type is a recorded note. Pure.
         */
        internal fun voiceSaveType(mime: String?): VoiceType? {
            if (mime == null) return AAC
            return VOICE_TYPES[mime.substringBefore(';').trim().lowercase(Locale.ROOT)]
        }

        private const val HASH_CHARS = 8
        private const val FIRST_YEAR = 2000
        private const val LAST_YEAR = 9999
        private val HEX8 = Regex("[0-9a-f]{$HASH_CHARS}")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /**
         * A saved voice note's file name: `knit-voice-<yyyyMMdd-HHmmss>-<8 hex>.<ext>`, the note's time in [zone] and
         * its hash's first eight characters. The same note always gets the same name, which is how
         * [GallerySaver.saveVoice] spots a second save; it never names the sender, since shared storage is readable by
         * other apps.
         *
         * Both inputs came off a peer's frame, so the name holds only digits and hex: a [hash] whose first eight
         * characters are not lowercase hex is named by its own SHA-256 instead, and a [sentAt] outside the years
         * [FIRST_YEAR]..[LAST_YEAR] (the sender's clock, unchecked) drops the time rather than write a six-digit year.
         * [ext] is [voiceSaveType]'s, never the sender's. Pure.
         */
        internal fun voiceFileName(
            hash: String,
            sentAt: Long,
            zone: ZoneId,
            ext: String,
        ): String {
            val head = hash.take(HASH_CHARS)
            val short = if (HEX8.matches(head)) head else sha256Hex(hash.encodeToByteArray()).take(HASH_CHARS)
            val time = Instant.ofEpochMilli(sentAt).atZone(zone)
            val stamp = if (time.year in FIRST_YEAR..LAST_YEAR) "${STAMP.format(time)}-" else ""
            return "knit-voice-$stamp$short.$ext"
        }
    }
}
