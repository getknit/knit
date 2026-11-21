package app.getknit.knit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.webp.AnimationFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Pictures through [AttachmentStore]'s picker door on the phone's own decoders and encoders, which the JVM tests replace
 * with Robolectric's: a camera photo stored sideways with its orientation and its position in EXIF, as a JPEG and as the
 * HEIC Samsung and Xiaomi cameras write, a photo already small and upright that passes through with only its metadata
 * cut, a transparent PNG, and a GIF.
 *
 * Every photo is checked for what the recipient will see: upright (a red block the camera stored at its top left is at
 * the top right once turned), bounded, and with no GPS position or camera model left in what is stored to be sent.
 *
 * Runs in the app's own process and graph (`.agents/context/testing.md`, I1): the store it ingests into is the app's
 * database, keyed by the app's `DatabaseKey`, which is settled first.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.data.ImagePipelineInstrumentedTest
 */
class ImagePipelineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: AttachmentStore
    private lateinit var blobs: BlobRepository

    @Before
    fun setUp() {
        val koin = GlobalContext.get()
        koin.get<DatabaseKey>().getOrCreate()
        store = koin.get()
        blobs = koin.get()
        dir = File(context.cacheDir, "image-pipeline-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSidewaysCameraJpegComesOutUprightBoundedAndWithoutItsLocation() {
        val photo = File(dir, "camera.jpg")
        photo.writeBytes(jpeg(cameraFrame(STORED_WIDTH, STORED_HEIGHT), quality = CAMERA_QUALITY))
        tag(photo, ExifInterface.ORIENTATION_ROTATE_90)

        val (mime, sent) = ingested(photo)
        assertEquals("image/jpeg", mime)
        val shown = decode(sent)
        assertEquals("long edge bounded", PHOTO_MAX_DIMENSION, maxOf(shown.width, shown.height))
        assertUpright(shown)
        assertNoMetadata(sent)
    }

    /**
     * A photo already within the bound, upright and no sharper than the store's own encode goes out as its own pixels
     * (ADR 2026-10.unqn): what the recipient decodes is exactly what the sender's file decodes to, with the EXIF cut.
     * A re-encode would cost a generation and change the pixels, so [Bitmap.sameAs] tells the two doors apart.
     */
    @Test
    fun anUprightBoundedJpegPassesThroughWithItsPixelsAndWithoutItsLocation() {
        val original = jpeg(cameraFrame(PASS_WIDTH, PASS_HEIGHT), quality = PHOTO_JPEG_QUALITY)
        val photo = File(dir, "saved.jpg").apply { writeBytes(original) }
        tag(photo, ExifInterface.ORIENTATION_NORMAL)
        val tagged = photo.readBytes()

        val (mime, sent) = ingested(photo)
        assertEquals("image/jpeg", mime)
        assertNotEquals("the metadata was cut", tagged.size, sent.size)
        assertTrue("the same pixels", decode(sent).sameAs(decode(original)))
        assertNoMetadata(sent)
    }

    /**
     * A HEIC with its turn in EXIF and no `irot` box, as a Samsung or Xiaomi camera writes it (scripts/gen-heic-fixture.py):
     * the platform's HEVC decoder reads it, ExifInterface reads its orientation through the HEIF container, and what
     * goes out is an upright JPEG without the position.
     */
    @Test
    fun aSidewaysHeicFromACameraComesOutAnUprightJpegWithoutItsLocation() {
        val heic = File(dir, "camera.heic")
        InstrumentationRegistry.getInstrumentation().context.assets.open(HEIC_ASSET).use { input ->
            heic.outputStream().use { input.copyTo(it) }
        }
        assertEquals("image/heif", FileTypes.imageMimeOf(heic.readBytes()))

        val (mime, sent) = ingested(heic)
        assertEquals("image/jpeg", mime)
        val shown = decode(sent)
        assertEquals("turned to portrait", HEIC_HEIGHT to HEIC_WIDTH, shown.width to shown.height)
        assertUpright(shown)
        assertNoMetadata(sent)
    }

    /** JPEG has no alpha, so a transparent PNG goes out as lossy WebP, whose encoder keeps the transparency. */
    @Test
    fun aTransparentPngGoesOutAsAWebpThatKeepsItsTransparency() {
        val sticker =
            createBitmap(TRANSPARENT_WIDTH, TRANSPARENT_HEIGHT).apply {
                eraseColor(Color.TRANSPARENT)
                Canvas(this).drawRect(
                    TRANSPARENT_WIDTH / 2f,
                    0f,
                    TRANSPARENT_WIDTH.toFloat(),
                    TRANSPARENT_HEIGHT.toFloat(),
                    Paint().apply { color = Color.BLUE },
                )
            }
        val png = File(dir, "sticker.png")
        png.outputStream().use { sticker.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val (mime, sent) = ingested(png)
        assertEquals("image/webp", mime)
        val shown = decode(sent)
        val clear = shown.getPixel(shown.width / 4, shown.height / 2)
        val solid = shown.getPixel(shown.width * 3 / 4, shown.height / 2)
        assertTrue("transparent half: alpha ${Color.alpha(clear)}", Color.alpha(clear) < ALPHA_SLACK)
        assertTrue("opaque half: alpha ${Color.alpha(solid)}", Color.alpha(solid) > 255 - ALPHA_SLACK)
        assertTrue("opaque half is blue: ${AnimationFixtures.describe(solid)}", AnimationFixtures.near(solid, Color.BLUE))
    }

    /** The GIF door, end to end: a picked GIF is stored as the smaller animated WebP and marked as animated. */
    @Test
    fun aPickedGifIsStoredAsASmallerAnimatedWebp() {
        val gif = AnimationFixtures.gif()
        val file = File(dir, "reaction.gif").apply { writeBytes(gif) }
        val result = runBlocking { store.ingest(Uri.fromFile(file)) }
        assertTrue("ingest $result", result is AttachmentStore.IngestResult.Success)
        val ingested = (result as AttachmentStore.IngestResult.Success).ingested
        assertEquals("image/webp", ingested.mime)
        assertTrue("marked animated", ingested.animated)
        val sent = checkNotNull(runBlocking { blobs.bytes(ingested.hash) })
        assertTrue("${sent.size} B of a ${gif.size} B GIF", sent.size < gif.size)
    }

    /** [file] through the picker door (`file://`, so no provider type), and the bytes stored to be sent. */
    private fun ingested(file: File): Pair<String, ByteArray> {
        val result = runBlocking { store.ingest(Uri.fromFile(file)) }
        assertTrue("ingest $result", result is AttachmentStore.IngestResult.Success)
        val ingested = (result as AttachmentStore.IngestResult.Success).ingested
        val bytes = checkNotNull(runBlocking { blobs.bytes(ingested.hash) }) { "nothing stored under ${ingested.hash}" }
        return ingested.mime to bytes
    }

    /**
     * A frame as a camera sensor reads it, landscape: noise (so the encoder works as hard as on a photo), a red block in
     * the top-left corner and a green one in the bottom-right.
     */
    private fun cameraFrame(
        width: Int,
        height: Int,
    ): Bitmap {
        val random = Random(SEED)
        val pixels = IntArray(width * height) { Color.rgb(64 + random.nextInt(64), 96 + random.nextInt(64), 128 + random.nextInt(64)) }
        val bitmap = createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        val block = minOf(width, height) / 4
        Canvas(bitmap).apply {
            drawRect(0f, 0f, block.toFloat(), block.toFloat(), Paint().apply { color = RED })
            val right = width.toFloat()
            val bottom = height.toFloat()
            drawRect(right - block, bottom - block, right, bottom, Paint().apply { color = GREEN })
        }
        return bitmap
    }

    private fun jpeg(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Writes what a phone camera writes into [file]'s EXIF: [orientation], a GPS position, the make and the model. */
    private fun tag(
        file: File,
        orientation: Int,
    ) {
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setLatLong(LATITUDE, LONGITUDE)
            setAttribute(ExifInterface.TAG_MAKE, "samsung")
            setAttribute(ExifInterface.TAG_MODEL, "SM-S918B")
            saveAttributes()
        }
        val written = ExifInterface(file.path)
        assertTrue("the fixture carries a position", written.latLong != null)
    }

    /** The camera stored red at its top left; turned upright that corner is at the top right, and green at the bottom left. */
    private fun assertUpright(shown: Bitmap) {
        val inset = minOf(shown.width, shown.height) / 16
        val topRight = shown.getPixel(shown.width - inset, inset)
        val bottomLeft = shown.getPixel(inset, shown.height - inset)
        assertTrue("top right is red: ${AnimationFixtures.describe(topRight)}", AnimationFixtures.near(topRight, RED))
        assertTrue("bottom left is green: ${AnimationFixtures.describe(bottomLeft)}", AnimationFixtures.near(bottomLeft, GREEN))
    }

    private fun assertNoMetadata(sent: ByteArray) {
        val exif = ExifInterface(ByteArrayInputStream(sent))
        assertNull("no position", exif.latLong)
        assertNull("no camera make", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull("no camera model", exif.getAttribute(ExifInterface.TAG_MODEL))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        assertTrue("no turn left to apply: $orientation", orientation in UPRIGHT)
    }

    /** [bytes] decoded whole: the bound is above every picture here, so nothing is sampled down. */
    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(decodeBoundedFromBytes(bytes, UNBOUNDED)) { "does not decode" }

    private companion object {
        const val SEED = 353L

        // AttachmentStore's private MAX_DIMENSION and JPEG_QUALITY on the 2.12 line (main moved them to ImageEncode).
        const val PHOTO_MAX_DIMENSION = 1_280
        const val PHOTO_JPEG_QUALITY = 85
        const val STORED_WIDTH = 2_000
        const val STORED_HEIGHT = 1_500
        const val CAMERA_QUALITY = 95
        const val PASS_WIDTH = 1_000
        const val PASS_HEIGHT = 750
        const val HEIC_ASSET = "codec/camera-sideways.heic"
        const val HEIC_WIDTH = 640
        const val HEIC_HEIGHT = 480
        const val TRANSPARENT_WIDTH = 600
        const val TRANSPARENT_HEIGHT = 400
        const val ALPHA_SLACK = 16
        const val UNBOUNDED = 4_096
        const val LATITUDE = 37.4219
        const val LONGITUDE = -122.084
        val RED = Color.rgb(220, 30, 30)
        val GREEN = Color.rgb(30, 200, 60)
        val UPRIGHT = setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
    }
}
