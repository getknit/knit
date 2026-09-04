package app.getknit.knit.data

import android.util.Log
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.mesh.transferExtForMime
import app.getknit.knit.moderation.ImageScreeningService
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
    private val messages: MessageRepository,
    private val imageScreening: ImageScreeningService,
    private val transferDir: File,
) : BlobStore {
    override suspend fun has(hash: String): Boolean = blobs.exists(hash)

    override suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    /** Materializes a temp file from the stored bytes so the transport can send it; null if not held. */
    override suspend fun fileFor(hash: String): File? =
        withContext(Dispatchers.IO) {
            // [hash] is interpolated into the temp filename below; reject anything that isn't a content
            // address so a peer-supplied "../" can't escape [transferDir].
            if (!isValidBlobHash(hash)) return@withContext null
            val bytes = blobs.bytes(hash) ?: return@withContext null
            val mime = blobs.mimeFor(hash) ?: "image/jpeg"
            val dest = File(ensureDir(), "$hash.${transferExtForMime(mime)}")
            if (!dest.exists()) {
                runCatching { dest.writeBytes(bytes) }.getOrElse { return@withContext null }
            }
            dest
        }

    /**
     * Ingests a received file into the encrypted store, deletes the decrypted staging copy, and returns
     * a temp file (re-materialized from the DB) the transport can forward on to any other wanters.
     */
    override suspend fun saveIncoming(
        hash: String,
        mime: String,
        srcPath: String,
    ): File? =
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
            // [mime] is the serving peer's claim, and [BlobExchange.onRequest] serves a blob to *any*
            // neighbour that asks — so it decides nothing here. Our own decrypted row is authoritative and a
            // peer's header is not (the rule ADR 035 already applies on the spool plane at
            // `MeshManager.scopeBlobs().save`); the header is only the fallback when no row names the hash.
            val localMime = messages.attachmentMimeForHash(hash)
            blobs.insert(hash, localMime ?: mime, bytes)
            // Screen the received image on-device and cache the verdict by hash (the UI blurs flagged
            // attachments). Stored regardless, so a false positive never drops content. The one skip is a
            // **sealed non-image** — a voice note, or an arbitrary file (ADR 2026-09.qq2r): its stored bytes
            // here are ciphertext the image decoder cannot read at all, so handing them over buys a failed
            // decode and a meaningless cached verdict, and nothing else (docs/CONTENT_MODERATION.md §7).
            // Skipping is not a decision to leave those unscreened — `InboundPipeline.onObtained` decrypts
            // every keyed attachment and screens the plaintext, MIME-blind, which is what catches an image
            // mislabelled as a file.
            //
            // Both halves of the test matter, and neither is redundant (knit/knit-next#30). The mime is read
            // from **our own row**, never the serving peer's `FileHeaderWire` — `BlobExchange.onRequest`
            // serves a blob to any neighbour that asks, so that header is the asker's choice. Requiring a
            // key on top of it is what keeps the row's own mime trustworthy: a Nearby-room attachment is not
            // re-sealed, so *its* mime rides in the clear and lands in the row verbatim, which would move
            // the spoof from any neighbour to the message's author. That costs nothing legitimate because the
            // room offers neither the mic nor the file picker, so a room attachment is an image or — the one
            // other kind the room originates — a link-preview card, and a card is not skipped but *opened*:
            // `screenAttachment` screens its picture and its text into one verdict, so the mime a room author
            // claims can only route its blob into the stricter screen, never around one. A **key-less** blob —
            // a pulled avatar, a group photo, a relayed blob with no row at all — is always screened, whatever
            // it calls itself; this is the sole screen those get.
            val key = messages.attachmentKeyForHash(hash)
            when {
                localMime == LinkPreviewBlob.MIME && key == null -> {
                    imageScreening.screenAttachment(hash, bytes, localMime, isRoom = true)
                }

                !isImage(localMime) && key != null -> {
                    // A sealed non-image: ciphertext here, screened after decryption in InboundPipeline.onObtained.
                }

                else -> {
                    imageScreening.screenImage(hash, bytes)
                }
            }
            src.delete() // drop the plaintext staging copy now that the bytes are encrypted
            fileFor(hash)
        }

    /** Drops all materialized transfer temp files; called on mesh start to clear last session's leftovers. */
    fun clearTransfers() {
        transferDir.listFiles()?.forEach { it.delete() }
    }

    private fun ensureDir(): File = transferDir.apply { if (!exists()) mkdirs() }

    /**
     * Whether [mime] names something the NSFW classifier could plausibly decode. A **null** mime — no row
     * names this hash — is deliberately not an image and yet is still screened: the caller pairs this with
     * the key test, and a row-less blob has no key either, so it falls to the safe side.
     */
    private fun isImage(mime: String?): Boolean = mime != null && mime.startsWith("image/")

    private companion object {
        const val TAG = "MeshBlobStore"
    }
}
