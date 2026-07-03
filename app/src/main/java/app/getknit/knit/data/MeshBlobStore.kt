package app.getknit.knit.data

import android.util.Log
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [BlobStore] backed by the encrypted [BlobRepository], bridging the mesh blob-exchange to the
 * database. Mesh file transfers stream as `LinkFraming` file records over the data-path socket, so this materializes
 * short-lived plaintext temp files in [transferDir] for outbound sends and ingests inbound staging
 * files into the database — deleting the decrypted staging copy as soon as the bytes are encrypted.
 *
 * [transferDir] holds only in-flight transfer files (never the canonical copy, which lives encrypted
 * in the DB) and is purged on mesh start via [clearTransfers]. The transient plaintext window is no
 * larger than the mesh file transfer itself.
 */
class MeshBlobStore(
    private val blobs: BlobRepository,
    private val transferDir: File,
) : BlobStore {

    override suspend fun has(hash: String): Boolean = blobs.exists(hash)

    override suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    /** Materializes a temp file from the stored bytes so the transport can send it; null if not held. */
    override suspend fun fileFor(hash: String): File? = withContext(Dispatchers.IO) {
        // [hash] is interpolated into the temp filename below; reject anything that isn't a content
        // address so a peer-supplied "../" can't escape [transferDir].
        if (!isValidBlobHash(hash)) return@withContext null
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
            // [hash] is an untrusted, peer-supplied content address. Reject a malformed one before it
            // reaches a filesystem path, and verify the bytes actually hash to it — a holder must not be
            // able to serve arbitrary bytes under another blob's address (content-address poisoning).
            if (!isValidBlobHash(hash)) {
                Log.w(TAG, "Dropping incoming blob with malformed hash")
                src.delete()
                return@withContext null
            }
            val bytes = runCatching { src.readBytes() }.getOrNull() ?: return@withContext null
            if (sha256Hex(bytes) != hash) {
                Log.w(TAG, "Dropping incoming blob: bytes do not match claimed hash $hash")
                src.delete()
                return@withContext null
            }
            blobs.insert(hash, mime, bytes)
            // Screen the received image on-device and cache the verdict by hash (the UI blurs flagged
            // attachments). Stored regardless, so a false positive never drops content.
            blobs.screenImage(hash, bytes)
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

    private companion object {
        const val TAG = "MeshBlobStore"
    }
}
