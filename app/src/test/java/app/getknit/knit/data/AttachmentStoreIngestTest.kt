package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore.IngestResult
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
 * seeing it: **a photo leaves the phone with none of its metadata** — no GPS position, no camera make — because
 * the pipeline decodes and re-encodes rather than passing the source bytes on, and it is upright, because the
 * EXIF orientation is applied before the tag is dropped. A JPEG offered as an opaque file takes the same door,
 * since screening skips by MIME. Then the size and emptiness refusals, and the opaque store for a real non-image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentStoreIngestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val store = AttachmentStore(context, blobs, screening)
    private val stored = HashMap<String, Pair<String, ByteArray>>()
    private val dir = File(context.cacheDir, "ingest-${System.nanoTime()}").apply { mkdirs() }

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
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
            val result = success(store.ingestFile(fileUri("blob.bin", geotaggedJpeg())))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }
        }

    @Test
    fun theScreenersVerdictRidesTheResult() =
        runTest {
            coEvery { screening.isImageExplicit(any()) } returns true
            assertTrue(success(store.ingest(geotaggedJpeg(), "image/jpeg")).flagged)
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

    @Test
    fun anEmptyVoiceNoteIsRefused() =
        runTest {
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestVoice(ByteArray(0)))
            assertTrue(stored.isEmpty())
        }

    /** [AttachmentStore]'s private bounds, restated: a change there should be a deliberate change here. */
    private companion object {
        const val MAX_DIMENSION = 1280
        const val MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
