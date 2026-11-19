package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.legal.repoFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * [decodeWholeOriented], the face review's decode (ADR 2026-10.whkk): a photo cut short decodes under
 * [decodeOrientedBounded] to its top with blank below, and the review would show and send that, so the review's decode
 * refuses it. Native graphics, so the platform's own codecs decide, as on a phone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDecodeWholeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "whole-${System.nanoTime()}").apply { mkdirs() }
    private val crew by lazy { repoFile("app/src/androidTest/assets/redact/crew.jpg").readBytes() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun uriOf(bytes: ByteArray): Uri = Uri.fromFile(File(dir, "pick-${System.nanoTime()}").apply { writeBytes(bytes) })

    private fun ByteArray.cut(): ByteArray = copyOf(size * 3 / 10)

    @Test
    fun aWholeJpegDecodes() {
        assertNotNull(decodeWholeOriented(crew, BOUND))
        assertNotNull(decodeWholeOriented(context, uriOf(crew), BOUND))
    }

    @Test
    fun aJpegCutShortIsUnreadableThoughThePlainDecodeAnswersAPhoto() {
        val cut = crew.cut()
        // The gap this closes: the plain decode answers the top of the photo.
        assertNotNull(decodeOrientedBounded(cut, BOUND))
        assertNull(decodeWholeOriented(cut, BOUND))
        assertNull(decodeWholeOriented(context, uriOf(cut), BOUND))
    }

    @Test
    fun aPngCutShortIsUnreadable() {
        val png =
            ByteArrayOutputStream().use { out ->
                val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
                for (y in 0 until SIDE) for (x in 0 until SIDE) bitmap.setPixel(x, y, Color.rgb(x, y, x xor y))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        assertNotNull(decodeWholeOriented(context, uriOf(png), BOUND))
        assertNotNull(decodeOrientedBounded(context, uriOf(png.cut()), BOUND))
        assertNull(decodeWholeOriented(context, uriOf(png.cut()), BOUND))
    }

    @Test
    fun theCheckSamplesAHugeHeaderDownToTheBound() {
        // A fixed eighth would leave a 65,500-pixel side at 8,188: the sample comes from the header instead.
        assertEquals(64, wholeCheckSample(65_500, 65_500, BOUND))
        assertEquals(64, wholeCheckSample(65_500, 1, BOUND))
        assertEquals(1, wholeCheckSample(BOUND, BOUND, BOUND))
        assertEquals(2, wholeCheckSample(BOUND + 1, 1, BOUND))
    }

    @Test
    fun aSmallFileDeclaringAHugePictureIsCheckedWithoutRefusingIt() {
        val png = blankPng(HUGE)
        assertTrue("the fixture is a small file: ${png.size} bytes", png.size < HUGE * HUGE / 8 / 100)
        assertNotNull(decodeWholeOriented(context, uriOf(png), BOUND))
    }

    @Test
    fun aPickThatWillNotOpenIsUnreadable() {
        assertNull(decodeWholeOriented(context, Uri.fromFile(File(dir, "gone")), BOUND))
    }
}

// A 1-bit grey PNG of [side]², every pixel black: it deflates about a thousand to one.
private fun blankPng(side: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val data = DataOutputStream(out)

    fun chunk(
        type: String,
        body: ByteArray,
    ) {
        val typed = type.toByteArray(Charsets.US_ASCII) + body
        data.writeInt(body.size)
        data.write(typed)
        data.writeInt(CRC32().apply { update(typed) }.value.toInt())
    }
    data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val header = ByteArrayOutputStream()
    DataOutputStream(header).apply {
        writeInt(side)
        writeInt(side)
        write(byteArrayOf(1, 0, 0, 0, 0)) // bit depth 1, greyscale, deflate, no filter method, no interlace
    }
    chunk("IHDR", header.toByteArray())
    val pixels = ByteArrayOutputStream()
    DeflaterOutputStream(pixels).use { z ->
        val row = ByteArray(1 + (side + 7) / 8) // filter byte 0, then the row
        repeat(side) { z.write(row) }
    }
    chunk("IDAT", pixels.toByteArray())
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

private const val BOUND = 1024
private const val HUGE = 20_000
private const val SIDE = 256
