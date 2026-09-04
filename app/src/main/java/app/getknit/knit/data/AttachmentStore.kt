package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.mesh.protocol.AttachmentName
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.moderation.ImageScreeningService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Ingests picked/keyboard/captured images into the encrypted, content-addressed `blobs` table (see
 * [app.getknit.knit.data.blob.BlobEntity]). The content hash both keys the blob and is the key carried
 * in the wire frame, so any holder can serve the bytes and identical images dedupe.
 *
 * Photos are decoded, EXIF-rotated, downscaled, and re-encoded to JPEG. GIFs keep their animation but
 * are re-encoded to a smaller **animated WebP** via [app.getknit.knit.data.webp.WebpTranscode] (VP8
 * beats GIF's LZW even at the same size, and Coil plays animated WebP like a GIF) so they transmit
 * faster; a GIF that somehow can't be shrunk falls back to its original bytes. The bytes never touch
 * disk — they go straight into the encrypted database. That invariant is why the in-app camera hands
 * its capture over as a [ByteArray] rather than staging a plaintext JPEG in `cacheDir`.
 *
 * Before staging, the image is screened for explicit content via [ImageScreeningService]. Sending an
 * explicit image is *allowed but discouraged*: a flagged image is still ingested, and [ingest] reports the
 * flag so the caller can ask the user to confirm before staging/sending it (the receive side blurs it).
 *
 * Arbitrary files ([ingestFile]) and voice notes ([ingestVoice]) share the tail of this pipeline and none
 * of its head — with one deliberate exception: a picked *file* whose bytes turn out to carry an image
 * signature is handed back to [ingest] and treated as the image it is. Screening skips by MIME, so that
 * hand-back is what stops a renamed JPEG walking past the classifier.
 *
 * Voice notes arrive already in their final encoding from `VoiceRecorder`, so there is nothing to decode,
 * downscale or re-compress, and no on-device model can screen speech, so they are never flagged. What both
 * share is the part that matters — the same content-addressed insert into the same encrypted table, which is
 * why every layer below this one (custody, `BlobExchange`, the spool plane) carries either with no changes
 * at all.
 */
class AttachmentStore(
    private val context: Context,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
) {
    /**
     * The result of ingesting a picked/keyboard/captured image or a recorded voice note: its content [hash]
     * and [mime], plus — for a voice note — the [voice] description derived from the audio at ingest.
     *
     * [voice] travels with the staged attachment rather than being written to a row here because at ingest
     * there is no row yet, and by the time there is, the hash has changed: a DM/group attachment is sealed
     * on send and the row records its *ciphertext* hash. So the description rides along and is written
     * where the row is actually created (`MeshManager.sendChat`), against the hash that row will hold.
     *
     * [name] and [sizeBytes] travel the same way and for the same reason, and are set only by [ingestFile].
     * [sizeBytes] is the **plaintext** length deliberately: it is what the recipient will save, whereas the
     * blob stored under [hash] in a DM or group is that plaintext plus its seal.
     *
     * [link] is the same idea for a link-preview card ([ingestLinkPreview]): what the composer draws for the
     * staged card and the link its dismissal is remembered by, carried on the one staged object rather than
     * kept beside it.
     */
    data class Ingested(
        val hash: String,
        val mime: String,
        val voice: VoiceAudio.Description? = null,
        val name: String? = null,
        val sizeBytes: Int = 0,
        val link: LinkCard? = null,
    )

    /**
     * Outcome of an ingest: stored ([Success], with [Success.flagged] true when screening judged the image
     * explicit so the caller can prompt for confirmation), or [Failed] with the [Failed.reason] the caller
     * needs in order to say something useful.
     *
     * The reason exists because [ingestFile] made silence wrong. A failed *image* pick could be swallowed —
     * the picture is still sitting in the picker, so there was nothing to explain (ADR 029) — but a file
     * refused for its size is refused permanently and invisibly, and a file we will not send at all is a
     * decision the user is owed a sentence about.
     */
    sealed interface IngestResult {
        data class Success(
            val ingested: Ingested,
            val flagged: Boolean,
        ) : IngestResult

        data class Failed(
            val reason: Reason,
        ) : IngestResult

        /** Why an ingest produced nothing. */
        enum class Reason {
            /** The bytes could not be read or decoded — a broken image, an unreadable provider. */
            Unreadable,

            /** Past [MAX_BYTES], and unlike a photo there is nothing to shrink. */
            TooLarge,

            /** An installable app package, which Knit does not send (see [FileTypes.isInstallable]). */
            Installable,
        }
    }

    /**
     * Ingests [uri] into the blob store. GIFs are re-encoded smaller (animation preserved); other
     * images are downscaled to [MAX_DIMENSION] and re-encoded as JPEG. Returns [IngestResult.Failed]
     * on a decode failure or if the processed image exceeds [MAX_BYTES], else [IngestResult.Success] with
     * [Success.flagged] set when on-device screening judged the image explicit.
     *
     * This is the *image* door, reached from the photo picker and the keyboard. [ingestFile] is the door for
     * everything else, and hands anything that turns out to be an image straight back here.
     */
    suspend fun ingest(uri: Uri): IngestResult =
        withContext(Dispatchers.IO) {
            ingest(
                sourceMime = context.contentResolver.getType(uri),
                readRaw = { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } },
                decodeOriented = { decodeOrientedBounded(context, uri, MAX_DIMENSION) },
            )
        }

    /**
     * Ingests an image already held in memory — the in-app camera's captured JPEG, which deliberately
     * never reaches disk. Identical to [ingest] in every other respect, screening included, so a
     * captured photo is indistinguishable from a picked one once stored.
     */
    suspend fun ingest(
        bytes: ByteArray,
        sourceMime: String?,
    ): IngestResult =
        withContext(Dispatchers.IO) {
            ingest(
                sourceMime = sourceMime,
                readRaw = { bytes },
                decodeOriented = { decodeOrientedBounded(bytes, MAX_DIMENSION) },
            )
        }

    /**
     * Ingests a recorded voice note — the AAC/ADTS bytes `VoiceRecorder` captured in memory — into the same
     * content-addressed blob store an image goes to, under [VoiceAudio.MIME].
     *
     * Deliberately not routed through the image pipeline above: that one decodes a [Bitmap], downscales and
     * re-encodes, all meaningless here, and it calls the NSFW screener, which cannot classify speech. So a
     * voice note is **never** flagged, and [IngestResult.Success.flagged] is always false — which is exactly
     * what lets `ChatViewModel.stage` handle it with no branch of its own. See `docs/CONTENT_MODERATION.md`
     * for why audio ships unscreened and what protects a recipient instead.
     *
     * Fails on empty bytes or anything past [MAX_BYTES]; the recorder caps duration long before that, so
     * this is a backstop rather than a path a user reaches.
     */
    suspend fun ingestVoice(bytes: ByteArray): IngestResult =
        withContext(Dispatchers.IO) {
            failureFor(bytes)?.let { return@withContext it }
            val hash = sha256(bytes)
            blobs.insert(hash, VoiceAudio.MIME, bytes)
            IngestResult.Success(
                Ingested(hash, VoiceAudio.MIME, voice = VoiceAudio.describe(bytes)),
                flagged = false,
            )
        }

    /**
     * Ingests any picked file — a PDF, a spreadsheet, an archive — into the same content-addressed blob
     * store an image goes to, keeping its own MIME and the name the provider gave it. Offered only in DMs
     * and groups (ADR 2026-09.qq2r); the Nearby room never carries one, which is what keeps
     * `MeshBlobStore`'s screening skip sound.
     *
     * Three refusals, in the order they cost least:
     *
     * 1. An **installable package** is never sent ([FileTypes.isInstallable]).
     * 2. Anything the provider says is past [MAX_BYTES] is refused *before* a byte is read. The column is
     *    advisory, so the read is bounded too and re-checks — but a 2 GB pick should not be streamed into
     *    memory just to be told no.
     * 3. Empty or unreadable bytes.
     *
     * Then the part that is a safety property rather than plumbing: **bytes that are actually an image go
     * through the image pipeline**, whatever the provider called them. Screening skips by MIME, so without
     * this a JPEG offered as `application/octet-stream` would reach a recipient unscreened. A signature we
     * misread costs nothing — [ingest] failing to decode simply falls through to storing the file as-is,
     * and the recipient screens the decrypted plaintext of every keyed attachment regardless
     * (`InboundPipeline.onObtained`).
     *
     * A genuine non-image is stored opaque and is never flagged: nothing on the device can classify a
     * spreadsheet. See `docs/CONTENT_MODERATION.md` §7 for what protects a recipient instead.
     */
    suspend fun ingestFile(uri: Uri): IngestResult =
        withContext(Dispatchers.IO) {
            val name = AttachmentName.sanitize(displayName(uri))
            val declared = context.contentResolver.getType(uri)
            if (FileTypes.isInstallable(declared, name)) {
                return@withContext IngestResult.Failed(IngestResult.Reason.Installable)
            }
            val advertised = advertisedSize(uri)
            if (advertised != null && advertised > MAX_BYTES) {
                return@withContext IngestResult.Failed(IngestResult.Reason.TooLarge)
            }
            val bytes = readBounded(uri) ?: return@withContext IngestResult.Failed(IngestResult.Reason.TooLarge)
            failureFor(bytes)?.let { return@withContext it }

            FileTypes.imageMimeOf(bytes)?.let { sniffed ->
                val asImage =
                    ingest(
                        sourceMime = sniffed,
                        readRaw = { bytes },
                        decodeOriented = { decodeOrientedBounded(bytes, MAX_DIMENSION) },
                    )
                if (asImage is IngestResult.Success) return@withContext asImage
            }

            val mime = declared?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME
            val hash = sha256(bytes)
            blobs.insert(hash, mime, bytes)
            IngestResult.Success(
                Ingested(hash, mime, name = name, sizeBytes = bytes.size),
                flagged = false,
            )
        }

    /** The provider's display name for [uri], or null when it offers none. */
    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()

    /** The size [uri]'s provider claims, or null when it claims none. Advisory — [readBounded] still bounds. */
    private fun advertisedSize(uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()

    /**
     * [uri]'s bytes, or null if there are more than [MAX_BYTES] of them. Reads one byte past the cap and
     * stops: a provider is free to under-report its size (or report none), so the limit has to hold on the
     * stream itself rather than on what the query said. Never touches disk — the bytes go from the provider
     * into memory and from there into the encrypted blob store.
     */
    private fun readBounded(uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> readBounded(stream) }
        }.getOrNull()

    private fun readBounded(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    /**
     * Stores a link-preview card the sender fetched for a link in its draft, as the blob its message will
     * reference — the third door, beside images and files, and the narrowest: the container is already built
     * and already moderated (`LinkPreviewService` screened the picture and the text on the way in), so nothing
     * here decodes or classifies. Never flagged, for that reason; the card rides a DM or group sealed like any
     * other attachment, and the Nearby room in the clear.
     */
    suspend fun ingestLinkPreview(blob: LinkPreviewBlob): IngestResult =
        withContext(Dispatchers.IO) {
            val bytes = blob.encode()
            if (bytes.isEmpty() || bytes.size > LinkPreviewBlob.MAX_BYTES) {
                return@withContext IngestResult.Failed(IngestResult.Reason.TooLarge)
            }
            val hash = sha256(bytes)
            blobs.insert(hash, LinkPreviewBlob.MIME, bytes)
            IngestResult.Success(Ingested(hash, LinkPreviewBlob.MIME, link = blob.toCard()), flagged = false)
        }

    /** The failure [bytes] earn on the shared size/emptiness gate, or null when they are fine to store. */
    private fun failureFor(bytes: ByteArray): IngestResult.Failed? =
        when {
            bytes.isEmpty() -> IngestResult.Failed(IngestResult.Reason.Unreadable)
            bytes.size > MAX_BYTES -> IngestResult.Failed(IngestResult.Reason.TooLarge)
            else -> null
        }

    /**
     * The one ingest pipeline, over whichever source the caller has: [readRaw] yields the untouched
     * source bytes (the GIF path re-encodes those directly) and [decodeOriented] yields an
     * EXIF-corrected, sub-sampled bitmap (every other format). Both are called at most once.
     */
    private suspend fun ingest(
        sourceMime: String?,
        readRaw: () -> ByteArray?,
        decodeOriented: () -> Bitmap?,
    ): IngestResult {
        val (mime, bytes) =
            if (sourceMime == "image/gif") {
                // Re-encode the GIF as a smaller animated WebP (its per-frame VP8 compression beats GIF's
                // 256-colour LZW even at the same dimensions, and Coil's AnimatedImageDecoder plays it like
                // a GIF). Keep the raw GIF only if that somehow isn't smaller, so a GIF is never regressed.
                val raw = readRaw() ?: return IngestResult.Failed(IngestResult.Reason.Unreadable)
                val webp = WebpTranscode.shrink(raw, GIF_MAX_DIMENSION, GIF_MAX_FPS, GIF_WEBP_QUALITY)
                if (webp != null) "image/webp" to webp else "image/gif" to raw
            } else {
                val bitmap = decodeOriented() ?: return IngestResult.Failed(IngestResult.Reason.Unreadable)
                val scaled = downscale(bitmap, MAX_DIMENSION)
                // JPEG has no alpha channel, so a transparent PNG would flatten its transparent regions to
                // black. When the source carries transparency, re-encode as lossy WebP instead — it keeps
                // the alpha channel and still compresses well; opaque photos stay JPEG (smallest).
                if (scaled.hasAlpha()) {
                    val webp =
                        ByteArrayOutputStream().use { out ->
                            scaled.compress(lossyWebpFormat(), WEBP_QUALITY, out)
                            out.toByteArray()
                        }
                    "image/webp" to webp
                } else {
                    val jpeg =
                        ByteArrayOutputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            out.toByteArray()
                        }
                    "image/jpeg" to jpeg
                }
            }
        failureFor(bytes)?.let { return it }
        // Screen the exact bytes we store and transmit (decoded at the receiver's bound), so the
        // send-side verdict matches what the recipient computes rather than scoring the sharper,
        // pre-JPEG source. Stored regardless — an explicit image is allowed but the caller confirms
        // before sending; fail-open when the bytes can't be decoded.
        val flagged = imageScreening.isImageExplicit(bytes)
        val hash = sha256(bytes)
        blobs.insert(hash, mime, bytes)
        return IngestResult.Success(Ingested(hash, mime), flagged)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 85
        const val WEBP_QUALITY = 85

        // GIFs are re-encoded to animated WebP (not re-photographed), so a tighter dimension + frame-rate
        // cap keeps them legibly animated while cutting the bytes that go over BLE; WEBP quality trades
        // size vs. fidelity (q70 shrinks even already-optimized GIFs). See [WebpTranscode].
        const val GIF_MAX_DIMENSION = 480
        const val GIF_MAX_FPS = 15
        const val GIF_WEBP_QUALITY = 70

        const val MAX_BYTES = 8 * 1024 * 1024 // 8 MiB cap (a transcoded GIF should land well under this)

        /** What a file is stored as when its provider names no type at all. */
        const val DEFAULT_MIME = "application/octet-stream"

        const val READ_CHUNK = 64 * 1024
    }
}
