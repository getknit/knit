package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.getknit.knit.data.video.TranscodeResult
import app.getknit.knit.data.video.VideoEncoder
import app.getknit.knit.data.video.VideoTranscoder
import app.getknit.knit.data.webp.AnimatedFrames
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.mesh.protocol.AttachmentName
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.VideoInfo
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
 * Photos are decoded, EXIF-rotated, downscaled, and re-encoded to JPEG — unless one is already bounded,
 * upright and no sharper than that re-encode would make it, in which case it goes out as its own bytes with
 * only the metadata taken out ([ImageStrip], ADR 2026-10.unqn), so a picture saved and sent on loses nothing
 * to a second encode. GIFs keep their animation but are re-encoded to a smaller **animated WebP** via
 * [app.getknit.knit.data.webp.WebpTranscode] (VP8 beats GIF's LZW even at the same size, and Coil plays
 * animated WebP like a GIF) so they transmit faster; a GIF that somehow can't be shrunk falls back to its
 * original bytes. An animated WebP or APNG keeps its frames too ([animated], ADR 2026-10.8hmx): a sticker-sized
 * WebP goes out as its own bytes with the metadata stripped, anything larger, and every APNG (which Android cannot
 * play), is composited and resampled into an animated WebP the way a GIF is. Which door a picture takes is read off
 * its bytes whenever they are in memory, not off the type its provider claimed. The bytes never touch disk — they
 * go straight into the encrypted database. That invariant is why the in-app camera hands its capture over as a
 * [ByteArray] rather than staging a plaintext JPEG in `cacheDir`.
 *
 * Before staging, an image bound for the public Nearby room (`screen = true`) is screened for explicit content
 * via [ImageScreeningService]: a flagged image is still ingested, and [ingest] reports the flag so the caller
 * can refuse it. One bound for a DM or a group is never screened on the way out — its receivers screen what
 * arrives and hide it behind their own Content filtering toggle (ADR 2026-10.9rh6).
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
 *
 * A pick whose bytes this phone already holds under an identity — a photo saved from a chat, a file passed along,
 * the same keyboard GIF twice — skips all of the above and goes out as that identity, unchanged
 * ([AttachmentIdentities], ADR 2026-10.mptm): no re-encode, so no generation loss and no GIF flattened to a still,
 * and no second copy for the mesh to carry.
 */
class AttachmentStore(
    private val context: Context,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val identities: AttachmentIdentities,
    private val videos: VideoEncoder = VideoTranscoder(context),
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
     * [name] and [sizeBytes] travel the same way and for the same reason. [name] is set only by [ingestFile];
     * [sizeBytes] by every ingest but a link card's, since a recipient's "Ask before large Bluetooth downloads"
     * judges a large photo's or voice note's declared length too (ADR 2026-10.js2p). It is the **plaintext**
     * length deliberately: it is what the recipient will save, whereas the blob stored under [hash] in a DM or
     * group is that plaintext plus its seal.
     *
     * [link] is the same idea for a link-preview card ([ingestLinkPreview]): what the composer draws for the
     * staged card and the link its dismissal is remembered by, carried on the one staged object rather than
     * kept beside it.
     *
     * [animated] rides along for [voice]'s reason: an image with more than one frame ([AnimatedImage]), read
     * off the exact bytes stored here so the sender's "GIF" label agrees with the one the recipient derives.
     *
     * [reuse] is the identity the bytes already go by when this pick is a file the phone holds (ADR
     * 2026-10.mptm): [hash] is then its plaintext, and the send goes out under [reuse] rather than a fresh seal.
     * [sourceHash] is the hash of the bytes the picker handed over when they are not the ones stored under
     * [hash] — a re-encoded photo, a GIF made a WebP — so picking the same original again finds this send.
     */
    data class Ingested(
        val hash: String,
        val mime: String,
        val voice: VoiceAudio.Description? = null,
        val name: String? = null,
        val sizeBytes: Int = 0,
        val link: LinkCard? = null,
        val animated: Boolean = false,
        val sourceHash: String? = null,
        val reuse: AttachmentIdentities.Held? = null,
        val video: VideoInfo? = null,
        /** A video cut to its first 61 s on the way in: the composer says so, once. */
        val trimmed: Boolean = false,
    )

    /**
     * Outcome of an ingest: stored ([Success], with [Success.flagged] true when a requested screen judged the
     * image explicit so the caller can refuse it), or [Failed] with the [Failed.reason] the caller
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
     * Ingests [uri] into the blob store. GIFs are re-encoded smaller and animated WebPs and APNGs keep their frames
     * ([animated]); other images are downscaled to [MAX_DIMENSION] and re-encoded as JPEG. Returns [IngestResult.Failed]
     * on a decode failure or if the processed image exceeds [MAX_BYTES], else [IngestResult.Success] with
     * [Success.flagged] set when on-device screening, run only when [screen] asks for it, judged the image explicit.
     *
     * This is the *image* door, reached from the photo picker and the keyboard. [ingestFile] is the door for
     * everything else, and hands anything that turns out to be an image straight back here.
     *
     * The source is read whole first (bounded) so a picture this phone already holds is found by its bytes
     * ([ingestPicked]). One past [MAX_BYTES] can be nothing we hold, so it takes the long way, decoded from [uri].
     *
     * [fitBytes], when set, is the most a still photo should take to go up through the relays this conversation
     * uses ([fitted], C-7.6-32): over it, the picture is re-encoded smaller.
     */
    suspend fun ingest(
        uri: Uri,
        screen: Boolean = false,
        fitBytes: Int? = null,
    ): IngestResult =
        withContext(Dispatchers.IO) {
            val sourceMime = context.contentResolver.getType(uri)
            readBounded(uri)?.let { raw -> return@withContext ingestPicked(raw, sourceMime, screen, fitBytes) }
            ingest(
                sourceMime = sourceMime,
                screen = screen,
                held = null,
                readRaw = { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } },
                decodeOriented = { decodeOrientedBounded(context, uri, MAX_DIMENSION) },
                fitBytes = fitBytes,
            )
        }

    /**
     * Ingests a picked image already read into memory: as the identity it goes by when this phone holds those bytes
     * (or their source) already ([reused]), else through the pipeline, remembering the source when the pipeline
     * changed it. The keyboard's GIFs and the photo picker's pictures come through here; the lab feeds it directly.
     */
    internal suspend fun ingestPicked(
        raw: ByteArray,
        sourceMime: String?,
        screen: Boolean = false,
        fitBytes: Int? = null,
    ): IngestResult =
        withContext(Dispatchers.IO) {
            reused(raw, screen = screen)?.let { return@withContext it }
            val result =
                ingest(
                    sourceMime = sourceMime,
                    screen = screen,
                    held = raw,
                    readRaw = { raw },
                    decodeOriented = { decodeOrientedBounded(raw, MAX_DIMENSION) },
                    fitBytes = fitBytes,
                )
            if (result !is IngestResult.Success) return@withContext result
            val source = sha256(raw)
            if (source == result.ingested.hash) result else result.copy(ingested = result.ingested.copy(sourceHash = source))
        }

    /**
     * The pick as the identity this phone already sends [raw] under ([AttachmentIdentities.find]), or null when
     * there is none. The plaintext is staged under its own hash exactly as a fresh ingest would stage it — [raw]
     * itself when it is that plaintext, else opened from the identity (a source match: the original of a photo we
     * re-encoded) — and, when [screen] asks, screened as a fresh image would be, so the room's block behaves as it
     * always did. [name] and the size are kept for a file; an image carries neither, as on [ingestFile]'s image
     * hand-back.
     */
    private suspend fun reused(
        raw: ByteArray,
        name: String? = null,
        screen: Boolean,
    ): IngestResult.Success? {
        if (raw.isEmpty()) return null
        val bytesHash = sha256(raw)
        val held = identities.find(bytesHash) ?: return null
        // A still found by the source's hash for bytes that animate is a send from before #133, flattened: take the
        // fresh ingest, whose animated identity the lookup then prefers (ADR 2026-10.8hmx).
        if (bytesHash != held.plainHash && !held.animated && AnimatedImage.isAnimated(raw)) return null
        val plain = if (bytesHash == held.plainHash) raw else identities.plaintextOf(held) ?: return null
        blobs.insert(held.plainHash, held.mime, plain)
        val image = held.mime.startsWith("image/")
        return IngestResult.Success(
            Ingested(
                hash = held.plainHash,
                mime = held.mime,
                voice = if (VoiceAudio.isVoice(held.mime)) VoiceAudio.describe(plain) else null,
                name = name.takeUnless { image },
                sizeBytes = plain.size,
                animated = held.animated,
                sourceHash = bytesHash.takeIf { it != held.plainHash },
                reuse = held,
            ),
            flagged = screen && image && imageScreening.isImageExplicit(plain),
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
        screen: Boolean = false,
        fitBytes: Int? = null,
    ): IngestResult =
        withContext(Dispatchers.IO) {
            ingest(
                sourceMime = sourceMime,
                screen = screen,
                held = bytes,
                readRaw = { bytes },
                decodeOriented = { decodeOrientedBounded(bytes, MAX_DIMENSION) },
                fitBytes = fitBytes,
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
                Ingested(hash, VoiceAudio.MIME, voice = VoiceAudio.describe(bytes), sizeBytes = bytes.size),
                flagged = false,
            )
        }

    /**
     * Whether a pick from the photo picker or the share sheet is a video, which takes [ingestVideo] rather than the
     * image door: the provider's type when it names one, else the container's own signature
     * ([FileTypes.videoMimeOf]) read off the first bytes.
     */
    suspend fun isVideo(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            when {
                declared?.startsWith("video/") == true -> {
                    true
                }

                declared?.startsWith("image/") == true -> {
                    false
                }

                else -> {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val head = ByteArray(VIDEO_SNIFF_BYTES)
                            val read = stream.read(head)
                            read > 0 && FileTypes.videoMimeOf(head.copyOf(read)) != null
                        }
                    }.getOrNull() == true
                }
            }
        }

    /**
     * Ingests a picked short video (ADR 2026-10.hmfe): re-encoded by [VideoEncoder] to about 480p HEVC, 30 fps and
     * 400 kbps, cut to its first 61 s, with the source's metadata dropped — never sent as picked, unlike a picture
     * that is already small (ADR 2026-10.unqn): no source video is. Stored under `video/mp4` with the name
     * `video.mp4` (what an older build's file bubble shows and saves) and [Ingested.video], the duration, size and
     * poster the bubble draws before the bytes land. [onProgress] reports the encode in 0..1.
     *
     * Bound for the Nearby room ([screen]), the poster and frames sampled across the clip are classified, and a
     * flagged video is reported as an image is. Not found by its source's bytes ([AttachmentIdentities]): hashing a
     * source that may be hundreds of MB to save one encode is the wrong trade, and a re-encode of the same pick lands
     * on new bytes anyway.
     */
    suspend fun ingestVideo(
        uri: Uri,
        screen: Boolean,
        onProgress: (Float) -> Unit = {},
    ): IngestResult {
        val encoded =
            when (val result = videos.transcode(uri, onProgress)) {
                is TranscodeResult.Success -> result
                TranscodeResult.TooLarge -> return IngestResult.Failed(IngestResult.Reason.TooLarge)
                TranscodeResult.Unreadable, is TranscodeResult.Failed -> return IngestResult.Failed(IngestResult.Reason.Unreadable)
            }
        return withContext(Dispatchers.IO) {
            val bytes = encoded.bytes
            failureFor(bytes)?.let { return@withContext it }
            val flagged = screen && imageScreening.isVideoExplicit(bytes, encoded.poster)
            val hash = sha256(bytes)
            blobs.insert(hash, VIDEO_MIME, bytes)
            val video = VideoInfo(encoded.meta.durationMs, encoded.meta.width, encoded.meta.height, encoded.poster)
            IngestResult.Success(
                Ingested(
                    hash,
                    VIDEO_MIME,
                    name = VIDEO_NAME,
                    sizeBytes = bytes.size,
                    video = video,
                    trimmed = encoded.plan.trimmed,
                ),
                flagged,
            )
        }
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
    suspend fun ingestFile(
        uri: Uri,
        screen: Boolean = false,
    ): IngestResult =
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
            reused(bytes, name, screen)?.let { return@withContext it }

            FileTypes.imageMimeOf(bytes)?.let { sniffed ->
                val asImage = ingestPicked(bytes, sniffed, screen)
                if (asImage is IngestResult.Success) return@withContext asImage
            }

            val mime = declared?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME
            val hash = sha256(bytes)
            blobs.insert(hash, mime, bytes)
            // A picked audio file draws as a voice note (VoiceAudio.isVoice), so it gets a note's length and waveform.
            val voice = if (VoiceAudio.isVoice(mime)) VoiceAudio.describe(bytes) else null
            IngestResult.Success(
                Ingested(hash, mime, voice = voice, name = name, sizeBytes = bytes.size),
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
     * and, for the Nearby room, already moderated (`LinkPreviewService` screened the picture and the text on the
     * way in; a DM's or a group's card is screened by its receivers, ADR 2026-10.9rh6), so nothing here decodes
     * or classifies. Never flagged, for that reason; the card rides a DM or group sealed like any
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
     * EXIF-corrected, sub-sampled bitmap (every other format). Both are called at most once. [held] is
     * the source when the caller already has it in memory, which is when its own signature picks the door
     * (a GIF a provider typed as nothing, or as something else, is still a GIF) and when an animation ([animated])
     * or a still ([passThrough]) can skip the decode; null for a source too large to read whole, which goes by
     * [sourceMime] alone and which no pass-through would take.
     */
    @Suppress("LongParameterList") // the two sources a picture can come from, and what it must fit
    private suspend fun ingest(
        sourceMime: String?,
        screen: Boolean,
        held: ByteArray?,
        readRaw: () -> ByteArray?,
        decodeOriented: () -> Bitmap?,
        fitBytes: Int? = null,
    ): IngestResult {
        val kind = held?.let(FileTypes::imageMimeOf) ?: sourceMime
        val (mime, bytes) =
            if (kind == "image/gif") {
                // Re-encode the GIF as a smaller animated WebP (its per-frame VP8 compression beats GIF's
                // 256-colour LZW even at the same dimensions, and Coil's AnimatedImageDecoder plays it like
                // a GIF). Keep the raw GIF only if that somehow isn't smaller, so a GIF is never regressed.
                val raw = readRaw() ?: return IngestResult.Failed(IngestResult.Reason.Unreadable)
                val webp = WebpTranscode.shrink(raw, GIF_MAX_DIMENSION, GIF_MAX_FPS, GIF_WEBP_QUALITY)
                if (webp != null) "image/webp" to webp else "image/gif" to raw
            } else {
                held?.let(::animated)
                    ?: fitted(
                        held?.let(::passThrough)
                            ?: reencoded(decodeOriented() ?: return IngestResult.Failed(IngestResult.Reason.Unreadable)),
                        fitBytes,
                        decodeOriented,
                    )
            }
        failureFor(bytes)?.let { return it }
        // Screen the exact bytes we store and transmit (decoded at the receiver's bound), so the
        // send-side verdict matches what the recipient computes rather than scoring the sharper,
        // pre-JPEG source. Only for the room ([screen]); stored regardless — the caller drops a flagged
        // one — and fail-open when the bytes can't be decoded.
        val flagged = screen && imageScreening.isImageExplicit(bytes)
        val hash = sha256(bytes)
        blobs.insert(hash, mime, bytes)
        return IngestResult.Success(
            Ingested(hash, mime, sizeBytes = bytes.size, animated = AnimatedImage.isAnimated(bytes)),
            flagged,
        )
    }

    /** [bitmap] bounded to [MAX_DIMENSION] and encoded afresh — the long way, and one generation of loss. */
    private fun reencoded(bitmap: Bitmap): Pair<String, ByteArray> {
        val scaled = downscale(bitmap, MAX_DIMENSION)
        return encoded(scaled, if (scaled.hasAlpha()) WEBP_QUALITY else JPEG_QUALITY)
    }

    /**
     * [scaled] encoded at [quality]. JPEG has no alpha channel, so a transparent PNG would flatten its transparent
     * regions to black: when the source carries transparency it is lossy WebP instead, which keeps the alpha channel
     * and still compresses well; opaque photos stay JPEG (smallest).
     */
    private fun encoded(
        scaled: Bitmap,
        quality: Int,
    ): Pair<String, ByteArray> {
        val alpha = scaled.hasAlpha()
        val bytes =
            ByteArrayOutputStream().use { out ->
                scaled.compress(if (alpha) lossyWebpFormat() else Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        return (if (alpha) WEBP else "image/jpeg") to bytes
    }

    /**
     * [plain], or the same picture re-encoded smaller when it is over [fitBytes] — the most a photo may be to go up
     * through the relays its conversation uses, under the per-attachment limit a ticket there carries (`am`,
     * C-7.6-32). Quality first, then size, down [FIT_STEPS] to its floor; the first step that fits is kept. One that
     * never fits keeps [plain]: too large for that relay, it still goes nearby at its best. A still photo only — the
     * GIF and animation doors keep their frames, and a video is never squeezed twice (ADR 2026-10.hmfe).
     */
    private fun fitted(
        plain: Pair<String, ByteArray>,
        fitBytes: Int?,
        decodeOriented: () -> Bitmap?,
    ): Pair<String, ByteArray> {
        if (fitBytes == null || plain.second.size <= fitBytes) return plain
        val bitmap = decodeOriented() ?: return plain
        for ((edge, quality) in FIT_STEPS) {
            val step = encoded(downscale(bitmap, edge), quality)
            if (step.second.size <= fitBytes) {
                Log.i(TAG, "photo fitted to $fitBytes B: ${plain.second.size} → ${step.second.size} B at $edge px, q$quality")
                return step
            }
        }
        Log.i(TAG, "photo (${plain.second.size} B) does not fit $fitBytes B even at the floor; it stays nearby only there")
        return plain
    }

    /**
     * [raw] as its own pixels with its metadata gone ([ImageStrip]), when that is what [reencoded] would have
     * arrived at anyway minus the generation it costs (ADR 2026-10.unqn): a JPEG or still lossy WebP no larger than
     * [MAX_DIMENSION] on its long edge, upright (an orientation tag other than normal needs pixels moved), and no
     * sharper than the re-encode — a JPEG whose tables say at most [JPEG_QUALITY], a WebP within
     * [WEBP_MAX_BYTES_PER_PIXEL] — so a camera original still takes one re-encode and our own output never takes
     * another. Then it must decode at the size it declares, so nothing goes out untouched that the long way would
     * have refused. Null sends it the long way.
     */
    private fun passThrough(raw: ByteArray): Pair<String, ByteArray>? {
        // The header and the tag first: a camera original fails here without being copied.
        val declared = declaredSize(raw) ?: return null
        if (maxOf(declared.first, declared.second) > MAX_DIMENSION || exifOrientation(raw) !in UPRIGHT) return null
        val stripped = ImageStrip.of(raw) ?: return null
        val (width, height) = stripped.width to stripped.height
        val sharp =
            when (val quality = stripped.quality) {
                null -> stripped.bytes.size > width.toLong() * height * WEBP_MAX_BYTES_PER_PIXEL
                else -> quality > JPEG_QUALITY + QUALITY_SLACK
            }
        val clean = maxOf(width, height) <= MAX_DIMENSION && !sharp && decodesAt(stripped.bytes, width, height)
        return if (clean) stripped.mime to stripped.bytes else null
    }

    /**
     * [raw] with its frames kept, when it is an animated WebP or APNG (ADR 2026-10.8hmx); null for a still, and for
     * an animation neither route below will take, which then goes the still way and keeps its first frame.
     *
     * An animated WebP within [ANIMATED_PASS_MAX_DIMENSION], [ANIMATED_PASS_MAX_FRAMES] and [ANIMATED_PASS_MAX_BYTES]
     * — the common sticker — goes out as its own bytes, its metadata stripped ([ImageStrip.animatedWebp]). Anything
     * past those, and an APNG always, is composited and resampled into an animated WebP at the GIF door's bounds; a
     * stripped WebP that the resample would not beat is sent stripped, so a WebP is never regressed.
     */
    private fun animated(raw: ByteArray): Pair<String, ByteArray>? {
        if (!AnimatedImage.isAnimated(raw)) return null
        val stripped = ImageStrip.animatedWebp(raw)
        if (stripped != null && isSticker(stripped)) return WEBP to stripped.bytes
        val webp =
            AnimatedFrames.of(raw)?.let { WebpTranscode.shrinkAnimated(it, raw.size, GIF_MAX_DIMENSION, GIF_MAX_FPS, GIF_WEBP_QUALITY) }
        return when {
            webp != null && (stripped == null || webp.size < stripped.bytes.size) -> WEBP to webp
            stripped != null -> WEBP to stripped.bytes
            else -> null.also { Log.i(TAG, "animation (${raw.size} B) keeps one frame: neither the strip nor a resample took it") }
        }
    }

    /** Whether [stripped] is within the bounds an animated WebP goes out untouched within. */
    private fun isSticker(stripped: ImageStrip.Animated): Boolean =
        maxOf(stripped.width, stripped.height) <= ANIMATED_PASS_MAX_DIMENSION &&
            stripped.frames <= ANIMATED_PASS_MAX_FRAMES &&
            stripped.bytes.size <= ANIMATED_PASS_MAX_BYTES

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AttachmentStore"
        const val WEBP = "image/webp"
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 85

        /**
         * The re-encode ladder [fitted] walks, (long edge, quality): quality first at full size, then smaller. Measured
         * 2026-10-17 with libjpeg on a desktop over five Pixel camera originals (4080×3072), whose 1280 px q85 encodes
         * ran 219–361 KB against the 245,732 B a five-chunk `am` admits: q75 fit four of them, q65 all five (134–227
         * KB). The 640 px q50 floor came to 32–59 KB, inside any `am` of two chunks; a screenshot is far under at q85.
         */
        val FIT_STEPS = listOf(1280 to 75, 1280 to 65, 1280 to 55, 1024 to 60, 800 to 55, 640 to 50)
        const val WEBP_QUALITY = 85

        /**
         * How far over [JPEG_QUALITY] a JPEG's estimated quality may sit and still pass through untouched: the
         * estimate reads back integer-rounded tables, so our own q85 output can read a point either side.
         */
        const val QUALITY_SLACK = 2

        /**
         * The heaviest still WebP that passes through untouched, per pixel: half a byte (4 bits) sits well above
         * what [WEBP_QUALITY] lossy plus an alpha plane comes to, so our own output always qualifies, and a WebP
         * this heavy is worth the one re-encode.
         */
        const val WEBP_MAX_BYTES_PER_PIXEL = 0.5

        /** The orientation tags a picture can pass through under: none, or one saying it is already upright. */
        val UPRIGHT = setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)

        // GIFs are re-encoded to animated WebP (not re-photographed), so a tighter dimension + frame-rate
        // cap keeps them legibly animated while cutting the bytes that go over BLE; WEBP quality trades
        // size vs. fidelity (q70 shrinks even already-optimized GIFs). See [WebpTranscode].
        const val GIF_MAX_DIMENSION = 480
        const val GIF_MAX_FPS = 15
        const val GIF_WEBP_QUALITY = 70

        /**
         * The bounds an animated WebP passes through untouched within (ADR 2026-10.8hmx). 512 px is the sticker
         * canvas — at [GIF_MAX_DIMENSION] every sticker would be resampled for 32 px — the frame cap is
         * [WebpTranscode]'s own, and the byte cap is what a Bluetooth link pays: a sticker is well under half of it.
         */
        const val ANIMATED_PASS_MAX_DIMENSION = 512
        const val ANIMATED_PASS_MAX_FRAMES = 200
        const val ANIMATED_PASS_MAX_BYTES = 1024 * 1024

        /** What a re-encoded short video is stored and sent as (ADR 2026-10.hmfe). */
        const val VIDEO_MIME = "video/mp4"

        /** The name a video carries in a DM or a group: what an older build's file bubble shows and saves it as. */
        const val VIDEO_NAME = "video.mp4"

        /** Enough of a file's head to read an ISO base-media brand or a Matroska header. */
        const val VIDEO_SNIFF_BYTES = 16

        const val MAX_BYTES = 8 * 1024 * 1024 // 8 MiB cap (a transcoded GIF should land well under this)

        /** What a file is stored as when its provider names no type at all. */
        const val DEFAULT_MIME = "application/octet-stream"

        const val READ_CHUNK = 64 * 1024
    }
}
