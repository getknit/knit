package app.getknit.knit.redact

import app.getknit.knit.mesh.protocol.AttachmentName
import java.io.File
import java.util.UUID

/**
 * Covered photos kept on disk for a direct Wi-Fi transfer (ADR 2026-10.whkk). A chat photo is covered in memory and
 * handed to the attachment store, but a transfer reads its source by URI when the link comes up, minutes after the
 * offer, and again on a Retry, so the covered bytes need a file of their own. Only the covered copy is written; the
 * original is never copied.
 *
 * Each copy sits in its own folder under the app's cache, so two photos with one name do not collide and the file's
 * own name is the one the peer is shown. A copy is deleted once its transfer can no longer be retried ([discard]: it
 * arrived, or the peer declined it), and any copy older than [KEEP_MS] when the next is written or a chat opens
 * ([prune]): a Retry after that finds the source gone and says so, as it does for a document deleted since the pick.
 * The system may clear the cache sooner, with the same outcome. This is the one plaintext picture Knit keeps in its
 * cache, and it holds only covered pixels.
 */
object CoveredCopies {
    /** The folder under the cache dir. */
    const val DIR = "covered-transfers"

    /** How long a copy is kept for a Retry: a week. */
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    private const val FALLBACK_STEM = "photo"

    /** The longest file name, in UTF-8 bytes, a Linux filesystem takes (NAME_MAX). */
    private const val NAME_MAX_BYTES = 255

    /**
     * The name the peer is shown for a covered copy of [original]: its stem, with the extension of the type the copy
     * was encoded as, since a covered photo is re-encoded whatever it came as.
     */
    fun nameFor(
        original: String?,
        mime: String,
    ): String {
        val stem = AttachmentName.sanitize(original)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: FALLBACK_STEM
        val extension = if (mime == "image/webp") ".webp" else ".jpg"
        // A sanitized name is bounded in characters, not bytes: 128 four-byte characters is past what a file can be named.
        var fitted = stem
        while ((fitted + extension).toByteArray().size > NAME_MAX_BYTES) fitted = fitted.dropLast(1)
        if (fitted.lastOrNull()?.isHighSurrogate() == true) fitted = fitted.dropLast(1)
        return AttachmentName.sanitize(fitted + extension) ?: (FALLBACK_STEM + extension)
    }

    /** Writes [bytes] as [name] in a folder of its own under [cacheDir], first deleting copies older than [KEEP_MS]. */
    fun write(
        cacheDir: File,
        name: String,
        bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): File {
        val root = File(cacheDir, DIR)
        pruneRoot(root, now)
        val folder = File(root, UUID.randomUUID().toString())
        check(folder.mkdirs()) { "could not create $folder" }
        return File(folder, name).apply { writeBytes(bytes) }
    }

    /** Deletes every copy under [cacheDir] older than [KEEP_MS]. */
    fun prune(
        cacheDir: File,
        now: Long = System.currentTimeMillis(),
    ) = pruneRoot(File(cacheDir, DIR), now)

    /**
     * Deletes [file] with its folder when it is a copy written here under [cacheDir]; anything else is left alone.
     * Whether it was one is the answer.
     */
    fun discard(
        cacheDir: File,
        file: File,
    ): Boolean {
        val folder = file.canonicalFile.parentFile ?: return false
        return folder.parentFile == File(cacheDir, DIR).canonicalFile && folder.deleteRecursively()
    }

    private fun pruneRoot(
        root: File,
        now: Long,
    ) {
        root.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.deleteRecursively() }
    }
}
