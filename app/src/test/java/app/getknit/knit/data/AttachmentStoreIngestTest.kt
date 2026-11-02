package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore.IngestResult
import app.getknit.knit.data.webp.AnimatedWebpMuxer
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The image and file doors, with real codecs (native graphics). The property a user relies on without ever
 * seeing it: **a photo leaves the phone with none of its metadata** — no GPS position, no camera make — whether
 * the pipeline re-encodes it or, for a picture already bounded, upright and no sharper than the re-encode, strips
 * its metadata and keeps its pixels (ADR 2026-10.unqn), and it is upright, because a picture tagged otherwise is
 * re-encoded with the EXIF orientation applied. A JPEG offered as an opaque file takes the same door, since
 * screening skips by MIME. Then the size and emptiness refusals, and the opaque store for a real non-image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentStoreIngestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val identities = mockk<AttachmentIdentities>(relaxed = true)
    private val store = AttachmentStore(context, blobs, screening, identities)
    private val stored = HashMap<String, Pair<String, ByteArray>>()
    private val dir = File(context.cacheDir, "ingest-${System.nanoTime()}").apply { mkdirs() }

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
        // Nothing is held under an identity unless a test says so: a relaxed mock would hand back a mock identity.
        coEvery { identities.find(any()) } returns null
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A 2000×1000 landscape JPEG that says it was shot rotated 90°, somewhere specific, on a named camera. */
    private fun geotaggedJpeg(): ByteArray {
        val file = File(dir, "photo.jpg")
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setLatLong(37.4219983, -122.084)
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 8")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes().also {
            // The fixture itself must carry what the pipeline is meant to drop, or the test proves nothing.
            assertTrue(ExifInterface(ByteArrayInputStream(it)).latLong != null)
        }
    }

    private fun fileUri(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    private fun assertNoMetadata(bytes: ByteArray) {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull("GPS survived the re-encode", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
    }

    private fun success(result: IngestResult): IngestResult.Success {
        assertTrue("expected a stored attachment, got $result", result is IngestResult.Success)
        return result as IngestResult.Success
    }

    /**
     * A photo-like JPEG: a gradient under per-pixel noise, which compresses about as a camera frame does, so its 1280 px
     * q85 encode is too large for a five-chunk `am` and a lower step is not.
     */
    private fun noisyPhoto(): ByteArray {
        val random = java.util.Random(7)
        val width = 2000
        val height = 1500
        val pixels =
            IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val n = random.nextInt(48) - 24
                Color.rgb((x * 255 / width + n).coerceIn(0, 255), (y * 255 / height + n).coerceIn(0, 255), (128 + n).coerceIn(0, 255))
            }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            it.toByteArray()
        }
    }

    private val fiveChunks = 5 * 49_152 - 28

    @Test
    fun aPhotoOverTheRelaysLimitIsReencodedUnderIt() =
        runTest {
            val plain = success(store.ingest(noisyPhoto(), "image/jpeg")).ingested
            assertTrue("the fixture must be too large at q85: ${plain.sizeBytes}", plain.sizeBytes > fiveChunks)
            val fitted = success(store.ingest(noisyPhoto(), "image/jpeg", fitBytes = fiveChunks)).ingested
            assertTrue("fitted: ${fitted.sizeBytes}", fitted.sizeBytes <= fiveChunks)
            val (mime, bytes) = stored.getValue(fitted.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            assertTrue(checkNotNull(decodeOrientedBounded(bytes, MAX_DIMENSION)).width > 0)
        }

    @Test
    fun aPhotoUnderTheLimitOrWithNoneIsLeftAsItIs() =
        runTest {
            val plain = success(store.ingest(geotaggedJpeg(), "image/jpeg")).ingested
            assertEquals(plain.hash, success(store.ingest(geotaggedJpeg(), "image/jpeg", fitBytes = fiveChunks)).ingested.hash)
            assertEquals(plain.hash, success(store.ingest(geotaggedJpeg(), "image/jpeg", fitBytes = null)).ingested.hash)
        }

    @Test
    fun aPhotoNoStepCanFitKeepsItsBestForNearby() =
        runTest {
            // Under the floor's reach: too large for that relay, it still goes nearby at its full quality.
            val plain = success(store.ingest(noisyPhoto(), "image/jpeg")).ingested
            assertEquals(plain.hash, success(store.ingest(noisyPhoto(), "image/jpeg", fitBytes = 1_000)).ingested.hash)
        }

    @Test
    fun aCapturedPhotoIsStoredUprightBoundedAndWithoutItsMetadata() =
        runTest {
            val result = success(store.ingest(geotaggedJpeg(), "image/jpeg"))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertEquals(sha256Hex(bytes), result.ingested.hash)
            assertNoMetadata(bytes)
            // Decoded with no orientation left to apply, so what comes back is the stored pixels' own shape.
            val stored = checkNotNull(decodeOrientedBounded(bytes, MAX_DIMENSION * 2))
            assertTrue("rotated upright: ${stored.width}x${stored.height}", stored.height > stored.width)
            assertTrue(maxOf(stored.width, stored.height) <= MAX_DIMENSION)
            assertFalse(result.flagged)
        }

    @Test
    fun aJpegOfferedAsAnOpaqueFileTakesTheImageDoor() =
        runTest {
            val result = success(store.ingestFile(fileUri("blob.bin", geotaggedJpeg()), screen = true))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }
        }

    @Test
    fun theScreenersVerdictRidesTheResult() =
        runTest {
            coEvery { screening.isImageExplicit(any()) } returns true
            assertTrue(success(store.ingest(geotaggedJpeg(), "image/jpeg", screen = true)).flagged)
        }

    @Test
    fun aPickForAPrivateChatIsNeverScreened() =
        runTest {
            // Only the Nearby room screens what is sent (ADR 2026-10.9rh6): every door, a reused identity
            // included, leaves the screener alone unless the caller asks for it.
            coEvery { screening.isImageExplicit(any()) } returns true
            val photo = geotaggedJpeg()

            assertFalse(success(store.ingest(photo, "image/jpeg")).flagged)
            assertFalse(success(store.ingest(fileUri("photo.jpg", encoded(picture(900, 700))))).flagged)
            assertFalse(success(store.ingestFile(fileUri("blob.bin", encoded(picture(800, 600))))).flagged)
            coEvery { identities.find(sha256Hex(photo)) } returns held(plainHash = sha256Hex(photo))
            assertFalse(success(store.ingest(fileUri("saved.jpg", photo))).flagged)

            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aTransparentImageKeepsItsAlphaAsWebp() =
        runTest {
            val png =
                ByteArrayOutputStream().use { out ->
                    Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        out,
                    )
                    out.toByteArray()
                }
            val result = success(store.ingest(png, "image/png"))
            assertEquals("image/webp", result.ingested.mime)
        }

    @Test
    fun bytesThatDoNotDecodeAreUnreadable() =
        runTest {
            val result = store.ingest(byteArrayOf(1, 2, 3, 4, 5), "image/jpeg")
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), result)
            assertTrue(stored.isEmpty())
        }

    @Test
    fun aNonImageFileIsStoredAsItIsAndNeverScreened() =
        runTest {
            val pdf = "%PDF-1.4\n%âãÏÓ\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()
            val result = success(store.ingestFile(fileUri("report.pdf", pdf)))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals(DEFAULT_MIME, mime)
            assertArrayEquals(pdf, bytes)
            assertEquals(pdf.size, result.ingested.sizeBytes)
            assertFalse(result.flagged)
            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aFilePastTheCapIsRefusedOnTheStreamItself() =
        runTest {
            // A file:// Uri advertises no size, so only the bounded read can catch this one.
            val result = store.ingestFile(fileUri("big.bin", ByteArray(MAX_BYTES + 1)))
            assertEquals(IngestResult.Failed(IngestResult.Reason.TooLarge), result)
            assertTrue(stored.isEmpty())
        }

    @Test
    fun anEmptyFileIsUnreadable() =
        runTest {
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestFile(fileUri("empty.txt", ByteArray(0))))
        }

    // --- A file Knit wrote itself, handed over as bytes (#287) ---

    /** Diagnostics sends a field log into a chat as bytes; it keeps its own name and type. */
    @Test
    fun aFileKnitWroteIsStoredUnderItsOwnNameAndType() =
        runTest {
            val log = """{"t":1,"k":"tick"}""".toByteArray() + "\n".toByteArray()
            val result = success(store.ingestFileBytes(log, "knit-fieldlog-2.10.2-20261102-1432.jsonl", "text/plain"))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("text/plain", mime)
            assertArrayEquals(log, bytes)
            assertEquals("knit-fieldlog-2.10.2-20261102-1432.jsonl", result.ingested.name)
            assertEquals(log.size, result.ingested.sizeBytes)
            assertFalse(result.flagged)
        }

    /** The bytes door is the URI door past the read: the same name rules and the same refusals. */
    @Test
    fun bytesMeetTheSameNameRulesAndRefusals() =
        runTest {
            val sanitized = success(store.ingestFileBytes("x".toByteArray(), "../logs/report.txt", "text/plain"))
            assertEquals("..logsreport.txt", sanitized.ingested.name)

            // The name alone and the type alone each refuse a package: either can be the honest one.
            assertEquals(
                IngestResult.Failed(IngestResult.Reason.Installable),
                store.ingestFileBytes("PK".toByteArray(), "Knit.apk", "application/octet-stream"),
            )
            assertEquals(
                IngestResult.Failed(IngestResult.Reason.Installable),
                store.ingestFileBytes("PK".toByteArray(), "notes.bin", "application/vnd.android.package-archive"),
            )
            assertEquals(
                IngestResult.Failed(IngestResult.Reason.TooLarge),
                store.ingestFileBytes(ByteArray(MAX_BYTES + 1), "big.txt", null),
            )
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestFileBytes(ByteArray(0), "empty.txt", null))
        }

    /** Bytes that are a picture take the image door here too, so nothing reaches a room unscreened by a name. */
    @Test
    fun aJpegHandedOverAsBytesTakesTheImageDoor() =
        runTest {
            val result = success(store.ingestFileBytes(geotaggedJpeg(), "report.txt", "text/plain", screen = true))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }
        }

    @Test
    fun anEmptyVoiceNoteIsRefused() =
        runTest {
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestVoice(ByteArray(0)))
            assertTrue(stored.isEmpty())
        }

    // --- A pick this phone already holds (ADR 2026-10.mptm) ---

    /** An animated WebP as the ingest stores a GIF: the VP8X chunk with its animation flag. Nothing decodes it. */
    private val animatedWebp =
        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 0x02, 0, 0, 0)

    private fun held(
        plainHash: String,
        mime: String = "image/jpeg",
        key: String? = "a2V5",
        animated: Boolean = false,
    ) = AttachmentIdentities.Held(hash = "c".repeat(64), key = key, mime = mime, animated = animated, plainHash = plainHash)

    @Test
    fun aPictureThePhoneHoldsGoesOutAsItsIdentityWithItsBytesUnchanged() =
        runTest {
            val photo = geotaggedJpeg()
            val identity = held(plainHash = sha256Hex(photo))
            coEvery { identities.find(sha256Hex(photo)) } returns identity

            val result = success(store.ingest(fileUri("saved.jpg", photo), screen = true))

            assertEquals(identity, result.ingested.reuse)
            assertEquals(sha256Hex(photo), result.ingested.hash)
            assertNull("the bytes are the plaintext: nothing was re-encoded", result.ingested.sourceHash)
            // Byte for byte what was picked: no second JPEG generation, and no new identity for the mesh to carry.
            assertArrayEquals(photo, stored.getValue(sha256Hex(photo)).second)
            coVerify(exactly = 0) { identities.plaintextOf(any()) }
            // Screened as a fresh pick would be, so the room's block is unchanged.
            coVerify(exactly = 1) { screening.isImageExplicit(photo) }
        }

    @Test
    fun aForwardedGifStaysAnimatedBecauseItIsNeverDecoded() =
        runTest {
            val identity = held(plainHash = sha256Hex(animatedWebp), mime = "image/webp", key = null, animated = true)
            coEvery { identities.find(sha256Hex(animatedWebp)) } returns identity

            val result = success(store.ingest(fileUri("saved.webp", animatedWebp)))

            assertTrue(result.ingested.animated)
            assertEquals("image/webp", result.ingested.mime)
            assertArrayEquals(animatedWebp, stored.getValue(result.ingested.hash).second)
        }

    @Test
    fun theOriginalOfAPhotoWeSentFindsTheIdentityItWentOutUnder() =
        runTest {
            val original = geotaggedJpeg()
            val sent = "the re-encoded plaintext".toByteArray()
            val identity = held(plainHash = sha256Hex(sent))
            coEvery { identities.find(sha256Hex(original)) } returns identity
            coEvery { identities.plaintextOf(identity) } returns sent

            val result = success(store.ingest(fileUri("original.jpg", original)))

            assertEquals(sha256Hex(sent), result.ingested.hash)
            assertEquals(sha256Hex(original), result.ingested.sourceHash)
            assertArrayEquals(sent, stored.getValue(sha256Hex(sent)).second)
        }

    @Test
    fun anIdentityWhoseBlobWillNotOpenFallsBackToAFreshIngest() =
        runTest {
            val original = geotaggedJpeg()
            coEvery { identities.find(sha256Hex(original)) } returns held(plainHash = "f".repeat(64))
            coEvery { identities.plaintextOf(any()) } returns null

            val result = success(store.ingest(fileUri("original.jpg", original)))

            assertNull(result.ingested.reuse)
            assertNoMetadata(stored.getValue(result.ingested.hash).second)
        }

    @Test
    fun aFilePassedAlongKeepsItsIdentityAndThePicksOwnNameAndSize() =
        runTest {
            val pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".toByteArray()
            val identity = held(plainHash = sha256Hex(pdf), mime = "application/pdf")
            coEvery { identities.find(sha256Hex(pdf)) } returns identity

            val result = success(store.ingestFile(fileUri("evacuation_procedure.pdf", pdf)))

            assertEquals(identity, result.ingested.reuse)
            assertEquals("application/pdf", result.ingested.mime)
            // The name is the pick's own, as on a fresh ingest (a file:// Uri offers none under Robolectric).
            assertEquals(success(store.ingestFile(fileUri("other.pdf", "%PDF-other".toByteArray()))).ingested.name, result.ingested.name)
            assertEquals(pdf.size, result.ingested.sizeBytes)
            assertFalse(result.flagged)
            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aPictureFoundThroughTheFileDoorCarriesNoNameAsAnyImageFromThere() =
        runTest {
            val photo = geotaggedJpeg()
            coEvery { identities.find(sha256Hex(photo)) } returns held(plainHash = sha256Hex(photo))

            val result = success(store.ingestFile(fileUri("IMG_0001.jpg", photo)))

            assertNull(result.ingested.name)
            // Its length still rides along, for the recipient's large-download gate (ADR 2026-10.js2p).
            assertEquals(photo.size, result.ingested.sizeBytes)
        }

    @Test
    fun aFreshPickRemembersTheBytesItCameFromWhenTheIngestChangedThem() =
        runTest {
            val photo = geotaggedJpeg()

            val result = success(store.ingest(fileUri("photo.jpg", photo)))

            assertNull(result.ingested.reuse)
            assertEquals(sha256Hex(photo), result.ingested.sourceHash)
            assertTrue(result.ingested.hash != sha256Hex(photo))
        }

    // --- A picture already clean and bounded keeps its pixels (ADR 2026-10.unqn) ---

    /** A photo-like [width]×[height] picture: a gradient, so the entropy-coded data is not trivial. */
    private fun picture(
        width: Int,
        height: Int,
        alpha: Boolean = false,
    ): Bitmap {
        val pixels =
            IntArray(width * height) {
                val x =
                    it % width
                val y = it / width
                Color.argb(if (alpha) (x + y) % 256 else 255, x % 256, y % 256, (x * y) % 256)
            }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** [bitmap] written as [format] at [quality], then — unless [tag] is false — given a GPS position, a camera and [orientation]. */
    private fun encoded(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 85,
        tag: Boolean = true,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    ): ByteArray {
        val file = File(dir, "picture-${System.nanoTime()}")
        file.outputStream().use { bitmap.compress(format, quality, it) }
        if (tag) {
            ExifInterface(file.absolutePath).apply {
                setLatLong(37.4219983, -122.084)
                setAttribute(ExifInterface.TAG_MAKE, "Google")
                setAttribute(ExifInterface.TAG_MODEL, "Pixel 8")
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file.readBytes().also { if (tag) assertTrue(ExifInterface(ByteArrayInputStream(it)).latLong != null) }
    }

    /** A JPEG's marker segments ahead of its first scan, and where that scan starts. */
    private fun headerOf(jpeg: ByteArray): Pair<List<Int>, Int> {
        val markers = ArrayList<Int>()
        var at = 2
        while (true) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA) return markers to at
            markers += marker
            at += 2 + (((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF))
        }
    }

    /** From the first scan to the end: the entropy-coded pixels, which a strip must not touch. */
    private fun scanOf(jpeg: ByteArray): ByteArray = jpeg.copyOfRange(headerOf(jpeg).second, jpeg.size)

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int>? = declaredSize(bytes)

    @Test
    fun aCleanBoundedJpegKeepsItsPixelsByteForByteAndLosesItsMetadata() =
        runTest {
            val photo = encoded(picture(1000, 800))

            val result = success(store.ingest(fileUri("photo.jpg", photo), screen = true))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            assertFalse("APP1 survived", 0xE1 in headerOf(bytes).first)
            assertArrayEquals("the pixels were re-encoded", scanOf(photo).copyOf(scanOf(bytes).size), scanOf(bytes))
            assertEquals(1000 to 800, sizeOf(bytes))
            assertEquals(sha256Hex(photo), result.ingested.sourceHash)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }
        }

    @Test
    fun aJpegWithNothingToStripGoesOutAsItIs() =
        runTest {
            val photo = encoded(picture(1000, 800), tag = false)

            val result = success(store.ingest(photo, "image/jpeg"))

            assertArrayEquals(photo, stored.getValue(result.ingested.hash).second)
            assertEquals(sha256Hex(photo), result.ingested.hash)
        }

    @Test
    fun aBoundedJpegTaggedAsRotatedIsStillReencodedUpright() =
        runTest {
            val photo = encoded(picture(1000, 800), orientation = ExifInterface.ORIENTATION_ROTATE_90)

            val bytes = stored.getValue(success(store.ingest(photo, "image/jpeg")).ingested.hash).second

            assertNoMetadata(bytes)
            assertEquals(800 to 1000, sizeOf(bytes))
        }

    @Test
    fun anUprightJpegOverTheBoundIsStillDownscaled() =
        runTest {
            val photo = encoded(picture(2000, 1000))

            val bytes = stored.getValue(success(store.ingest(photo, "image/jpeg")).ingested.hash).second

            assertNoMetadata(bytes)
            assertEquals(MAX_DIMENSION to 640, sizeOf(bytes))
        }

    @Test
    fun aJpegSharperThanTheReencodeTakesOneGenerationAndNoMore() =
        runTest {
            val original = encoded(picture(1000, 800), quality = 100)

            val first = success(store.ingestPicked(original, "image/jpeg")).ingested
            val firstBytes = stored.getValue(first.hash).second
            assertFalse(
                "a q100 original passed through at its own weight",
                scanOf(firstBytes).contentEquals(scanOf(original).copyOf(scanOf(firstBytes).size)),
            )
            assertTrue(firstBytes.size < original.size)

            // The re-encode saved and picked again, by a phone that holds no identity for it: the chain stops here.
            val again = success(store.ingestPicked(firstBytes, "image/jpeg")).ingested
            assertEquals(first.hash, again.hash)
            assertNull(again.sourceHash)
            assertArrayEquals(firstBytes, stored.getValue(again.hash).second)
        }

    @Test
    fun aTransparentWebpLosesItsMetadataAndKeepsItsHashWhenPickedAgain() =
        runTest {
            val sticker = encoded(picture(400, 300, alpha = true), format = lossyWebpFormat())

            val first = success(store.ingest(fileUri("sticker.webp", sticker)))
            val (mime, bytes) = stored.getValue(first.ingested.hash)
            assertEquals("image/webp", mime)
            assertNoMetadata(bytes)
            assertEquals(400 to 300, sizeOf(bytes))

            val again = success(store.ingest(fileUri("saved.webp", bytes)))
            assertEquals(first.ingested.hash, again.ingested.hash)
        }

    // --- An animation picked from outside Knit keeps its frames (#133, ADR 2026-10.8hmx) ---

    /** One frame of a [width]×[height] animation: [picture]'s gradient, moved along by [index]. */
    private fun frame(
        width: Int,
        height: Int,
        index: Int,
    ): Bitmap {
        val pixels = IntArray(width * height) { Color.argb(255, (it % width + 40 * index) % 256, (it / width) % 256, (60 * index) % 256) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value ushr 24).toByte())

    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private val planted = "MM GPS 37.42 -122.08 Pixel 8"

    private fun ByteArray.holds(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    /** A [width]×[height] animated WebP of [frames] frames, as a sticker app writes one, carrying an EXIF chunk. */
    private fun animatedWebp(
        width: Int,
        height: Int,
        frames: Int = 3,
    ): ByteArray {
        val stills = (0 until frames).map { encoded(frame(width, height, it), format = lossyWebpFormat(), quality = 90, tag = false) }
        val muxed = checkNotNull(AnimatedWebpMuxer.mux(stills, IntArray(frames) { 100 }, width, height))
        val exif = "EXIF".toByteArray() + le32(planted.length) + planted.toByteArray()
        return (muxed + exif).also { webp ->
            le32(webp.size - 8).copyInto(webp, 4)
            webp[20] = (webp[20].toInt() or 0x08).toByte() // the EXIF flag
            assertTrue(AnimatedImage.isAnimated(webp) && webp.holds(planted))
        }
    }

    private fun png(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val crc =
            java.util.zip
                .CRC32()
                .apply { update(type.toByteArray()) }
                .apply { update(data) }
        return be32(data.size) + type.toByteArray() + data + be32(crc.value.toInt())
    }

    /** [png]'s chunks as (type, data). */
    private fun chunksOf(png: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var at = 8
        while (at + 8 <= png.size) {
            val length =
                java.nio.ByteBuffer
                    .wrap(png, at, 4)
                    .int
            chunks += String(png, at + 4, 4, Charsets.US_ASCII) to png.copyOfRange(at + 8, at + 8 + length)
            at += 12 + length
        }
        return chunks
    }

    /** A [width]×[height] APNG of [frames] full-canvas frames, its first the default image, carrying a `tEXt`. */
    private fun apng(
        width: Int,
        height: Int,
        frames: Int = 3,
    ): ByteArray {
        val stills = (0 until frames).map { encoded(frame(width, height, it), format = Bitmap.CompressFormat.PNG, tag = false) }
        val ihdr = chunksOf(stills[0]).first { it.first == "IHDR" }.second
        var sequence = 0
        val out = ByteArrayOutputStream()
        out.write(stills[0].copyOf(8))
        out.write(png("IHDR", ihdr))
        out.write(png("tEXt", "Comment".toByteArray() + byteArrayOf(0) + planted.toByteArray()))
        out.write(png("acTL", be32(frames) + be32(0)))
        stills.forEachIndexed { index, still ->
            out.write(png("fcTL", be32(sequence++) + be32(width) + be32(height) + be32(0) + be32(0) + byteArrayOf(0, 1, 0, 10, 0, 0)))
            for ((type, data) in chunksOf(still)) {
                if (type != "IDAT") continue
                out.write(if (index == 0) png("IDAT", data) else png("fdAT", be32(sequence++) + data))
            }
        }
        out.write(png("IEND", ByteArray(0)))
        return out.toByteArray().also { assertTrue(AnimatedImage.isAnimated(it)) }
    }

    /** An animated WebP's canvas, from its VP8X header. */
    private fun canvasOf(webp: ByteArray): Pair<Int, Int> {
        fun u24(at: Int) =
            (webp[at].toInt() and 0xFF) or ((webp[at + 1].toInt() and 0xFF) shl 8) or ((webp[at + 2].toInt() and 0xFF) shl 16)
        return (1 + u24(24)) to (1 + u24(27))
    }

    @Test
    fun aStickerSizedAnimatedWebpKeepsItsFramesByteForByteAndLosesItsMetadata() =
        runTest {
            val sticker = animatedWebp(512, 512)

            val first = success(store.ingest(fileUri("sticker", sticker), screen = true))
            val (mime, bytes) = stored.getValue(first.ingested.hash)

            assertEquals("image/webp", mime)
            assertTrue(first.ingested.animated)
            assertFalse("the EXIF chunk survived", bytes.holds(planted) || bytes.holds("EXIF"))
            // Its frames are the picked ones: only the metadata went.
            assertArrayEquals(checkNotNull(ImageStrip.animatedWebp(sticker)).bytes, bytes)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }

            val again = success(store.ingest(fileUri("saved", bytes)))
            assertEquals(first.ingested.hash, again.ingested.hash)
        }

    @Test
    fun anAnimatedWebpPastTheStickerBoundIsResampledAndStaysAnimated() =
        runTest {
            val result = success(store.ingest(fileUri("big", animatedWebp(1000, 1000))))
            val (mime, bytes) = stored.getValue(result.ingested.hash)

            assertEquals("image/webp", mime)
            assertTrue(AnimatedImage.isAnimated(bytes))
            assertEquals(480 to 480, canvasOf(bytes))
            assertFalse(bytes.holds(planted))
        }

    @Test
    fun anApngLeavesAsAnAnimatedWebpWithoutItsText() =
        runTest {
            val result = success(store.ingest(fileUri("reaction", apng(200, 150))))
            val (mime, bytes) = stored.getValue(result.ingested.hash)

            assertEquals("image/webp", mime)
            assertTrue(result.ingested.animated)
            assertTrue(AnimatedImage.isAnimated(bytes))
            assertEquals(200 to 150, canvasOf(bytes))
            assertFalse(bytes.holds(planted))
        }

    @Test
    fun aGifWhoseProviderNamesNoTypeStaysAnimated() =
        runTest {
            // Two 1×1 frames; a file:// Uri reports no type, which used to send this down the still door as a JPEG.
            val frameBlock = byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0, 0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
            val gif =
                "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1) + frameBlock + frameBlock +
                    byteArrayOf(0x3B)
            assertTrue(AnimatedImage.isAnimated(gif))

            val result = success(store.ingest(fileUri("sticker", gif)))

            // A GIF too small to shrink stays a GIF, and one that shrinks is an animated WebP: either keeps its frames.
            assertTrue(AnimatedImage.isAnimated(stored.getValue(result.ingested.hash).second))
            assertTrue(result.ingested.mime in setOf("image/gif", "image/webp"))
        }

    @Test
    fun anAnimationSentFlattenedBeforeTheFixIsNotReusedAsTheStill() =
        runTest {
            val sticker = animatedWebp(256, 256)
            coEvery { identities.find(sha256Hex(sticker)) } returns held(plainHash = "f".repeat(64), mime = "image/jpeg", animated = false)

            val result = success(store.ingest(fileUri("sticker", sticker)))

            assertNull(result.ingested.reuse)
            assertTrue(result.ingested.animated)
            assertEquals(sha256Hex(sticker), result.ingested.sourceHash)
            coVerify(exactly = 0) { identities.plaintextOf(any()) }
        }

    /** [AttachmentStore]'s private bounds, restated: a change there should be a deliberate change here. */
    private companion object {
        const val MAX_DIMENSION = 1280
        const val MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
