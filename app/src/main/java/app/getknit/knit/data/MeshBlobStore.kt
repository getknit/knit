package app.getknit.knit.data

import app.getknit.knit.mesh.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [BlobStore] backed by the encrypted [BlobRepository], bridging the mesh blob-exchange to the
 * database. Nearby file transfers are inherently file-based (`Payload.fromFile`), so this materializes
 * short-lived plaintext temp files in [transferDir] for outbound sends and ingests inbound staging
 * files into the database — deleting the decrypted staging copy as soon as the bytes are encrypted.
 *
 * [transferDir] holds only in-flight transfer files (never the canonical copy, which lives encrypted
 * in the DB) and is purged on mesh start via [clearTransfers]. The transient plaintext window is no
 * larger than the (currently unencrypted) Nearby transfer itself.
 */
class MeshBlobStore(
    private val blobs: BlobRepository,
    private val transferDir: File,
) : BlobStore {

    override suspend fun has(hash: String): Boolean = blobs.exists(hash)

    override suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    /** Materializes a temp file from the stored bytes so the transport can send it; null if not held. */
    override suspend fun fileFor(hash: String): File? = withContext(Dispatchers.IO) {
        val bytes = blobs.bytes(hash) ?: return@withContext null
        val mime = blobs.mimeFor(hash) ?: "image/jpeg"
        val dest = File(ensureDir(), "$hash.${extForMime(mime)}")
        if (!dest.exists()) {
            runCatching { dest.writeBytes(bytes) }.getOrElse { return@withContext null }
        }
        dest
    }

    /**
     * Ingests a received file into the encrypted store, deletes the decrypted staging copy, and returns
     * a temp file (re-materialized from the DB) the transport can forward on to any other wanters.
     */
    override suspend fun saveIncoming(hash: String, mime: String, srcPath: String): File? =
        withContext(Dispatchers.IO) {
            val src = File(srcPath)
            val bytes = runCatching { src.readBytes() }.getOrNull() ?: return@withContext null
            blobs.insert(hash, mime, bytes)
            src.delete() // drop the plaintext staging copy now that the bytes are encrypted
            fileFor(hash)
        }

    /** Drops all materialized transfer temp files; called on mesh start to clear last session's leftovers. */
    fun clearTransfers() {
        transferDir.listFiles()?.forEach { it.delete() }
    }

    private fun ensureDir(): File = transferDir.apply { if (!exists()) mkdirs() }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
