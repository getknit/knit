package app.getknit.knit.mesh

import java.io.File

/**
 * Content-addressed image-blob storage, abstracted so [BlobExchange] stays free of Android/Room
 * dependencies (and unit-testable). Implemented for the app by an adapter over `AttachmentStore`.
 */
interface BlobStore {
    fun has(hash: String): Boolean

    /** The local file for [hash], or null if not held. */
    fun fileFor(hash: String): File?

    /** The mime type of the held blob [hash], or null if not held. */
    fun mimeFor(hash: String): String?

    /** Persists a received blob (copying from [srcPath]) and returns the stored file, or null on failure. */
    suspend fun saveIncoming(hash: String, mime: String, srcPath: String): File?
}
