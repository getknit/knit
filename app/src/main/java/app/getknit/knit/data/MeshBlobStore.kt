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
import java.util.concurrent.ConcurrentHashMap

/**
 * [BlobStore] backed by the encrypted [BlobRepository], bridging the mesh blob-exchange to the
 * database. Mesh file transfers stream as `LinkFraming` file records over the data-path socket, so this materializes
 * short-lived plaintext temp files in [transferDir] for outbound sends and ingests inbound staging
 * files into the database — deleting the decrypted staging copy as soon as the bytes are encrypted.
 *
 * [transferDir] holds only in-flight transfer files (never the canonical copy, which lives encrypted
 * in the DB) and is purged on mesh start via [clearTransfers]. The transient plaintext window is no
 * larger than the mesh file transfer itself — which, since a cut transfer resumes from the prefix the
 * receiver kept (`PartialBlobs`, ADR 2026-10.wtyc), can span several links within one session: those
 * prefixes are purged on mesh start too, and dropped once the blob lands or an hour after their last progress.
 */
class MeshBlobStore(
    private val blobs: BlobRepository,
    private val messages: MessageRepository,
    private val imageScreening: ImageScreeningService,
    private val transferDir: File,
) : BlobStore {
    // Hashes whose bytes are in hand and on their way in — set on entry, cleared in a finally, never a memo.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override suspend fun has(hash: String): Boolean = blobs.exists(hash)

    override fun storing(hash: String): Boolean = hash in inFlight

    override suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    // [fileFor]'s gate, then the row's length straight from the table — no bytes read, no temp file written.
    override suspend fun servable(hashes: List<String>): Map<String, Long> = blobs.sizes(hashes.filter(::isValidBlobHash))

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
                // Written aside and renamed into place: two asks for one blob can race here, and a holder that
                // streamed a file another serve was still writing would send a short or torn copy — which, spliced
                // onto a receiver's kept prefix, would cost it that prefix (#116).
                val staged = runCatching { File.createTempFile("$hash-", ".tmp", ensureDir()) }.getOrElse { return@withContext null }
                val written = runCatching { staged.writeBytes(bytes) }.isSuccess && staged.renameTo(dest)
                if (!written) {
                    staged.delete()
                    if (!dest.exists()) return@withContext null
                }
            }
            dest
        }

    /**
     * Ingests a received file into the encrypted store, deletes the decrypted staging copy, and returns
     * a temp file (re-materialized from the DB) whose path the obtained hook hands on.
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
            whileStoring(hash) {
                val bytes = runCatching { src.readBytes() }.getOrNull() ?: return@withContext null
                if (sha256Hex(bytes) != hash) {
                    Log.w(TAG, "Dropping incoming blob: bytes do not match claimed hash $hash")
                    src.delete()
                    return@withContext null
                }
                ingest(hash, mime, bytes)
                src.delete() // drop the plaintext staging copy now that the bytes are encrypted
                fileFor(hash)
            }
        }

    /**
     * Screens a received blob whose bytes already hash to [hash], then stores it — the one door for a blob
     * that arrived from someone else, on either plane: [saveIncoming] after a radio pull, and
     * `MeshManager.scopeBlobs().save` after a spool fetch (#109, where a bare insert left a pulled group photo
     * or avatar with no verdict). The screen runs **before the insert**, so "held" means "screened": a profile
     * or group frame handled on another coroutine that finds the bytes held also finds their verdict, and so
     * does the caller's obtained hook.
     */
    suspend fun ingest(
        hash: String,
        mime: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) { whileStoring(hash) { store(hash, mime, bytes) } }

    private suspend fun store(
        hash: String,
        mime: String,
        bytes: ByteArray,
    ) {
        // [mime] is the sender's claim — a radio holder's file header, which [BlobExchange.onRequest] lets any
        // neighbour that asks choose, or the spool fetcher's hint — so it decides nothing here. Our own decrypted
        // row is authoritative (ADR 035); the claim is only the fallback when no row names the hash.
        val localMime = messages.attachmentMimeForHash(hash)
        // Screen the received image on-device and cache the verdict by hash (the UI blurs flagged
        // attachments), then store it regardless, so a false positive never drops content. The one skip is a
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
        // room offers neither the mic nor the file picker, and a receiver drops any other kind a room post
        // names (RoomAttachments, ADR 2026-10.79j9), so a room attachment's row says image, card or video,
        // and a card is not skipped but *opened*:
        // `screenAttachment` screens its picture and its text into one verdict, so the mime a room author
        // claims can only route its blob into the stricter screen, never around one. A **key-less** blob —
        // a pulled avatar, a group photo, a relayed blob with no row at all — is always screened, whatever
        // it calls itself; this is the sole screen those get, whichever plane brought them.
        val key = messages.attachmentKeyForHash(hash)
        when {
            localMime == LinkPreviewBlob.MIME && key == null -> {
                imageScreening.screenAttachment(hash, bytes, localMime, isRoom = true)
            }

            isVideo(localMime) && key == null -> {
                // A room video (ADR 2026-10.hmfe): its frames, sampled. Bytes that hold no readable video fall to
                // the image screen, so calling a picture a video routes it to a screen, never around one.
                imageScreening.screenVideo(hash, bytes)
            }

            !isImage(localMime) && key != null -> {
                // A sealed non-image: ciphertext here, screened after decryption in InboundPipeline.onObtained.
            }

            else -> {
                imageScreening.screenImage(hash, bytes)
            }
        }
        blobs.insert(hash, localMime ?: mime, bytes)
    }

    /**
     * Runs [block] with [hash] reported as [storing]. Only the call that marked it clears it, so a nested call
     * ([saveIncoming] into [ingest]) never clears it early. Two copies at once (a radio pull and a spool fetch)
     * share one mark: if the marking one ends first, the other finishes unmarked — the gap as it was before the
     * screen moved ahead of the insert, and only when the first one failed (a success holds the bytes).
     */
    private inline fun <T> whileStoring(
        hash: String,
        block: () -> T,
    ): T {
        val marked = inFlight.add(hash)
        try {
            return block()
        } finally {
            if (marked) inFlight.remove(hash)
        }
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

    private fun isVideo(mime: String?): Boolean = mime != null && mime.startsWith("video/")

    private companion object {
        const val TAG = "MeshBlobStore"
    }
}
