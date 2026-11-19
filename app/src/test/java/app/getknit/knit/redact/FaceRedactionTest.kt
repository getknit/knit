package app.getknit.knit.redact

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.decodeBoundedFromBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The fill a covered face gets, on real Skia pixels (Robolectric's native graphics): one opaque colour, the average of
 * what the cover hides, over the whole cover bar its anti-aliased rim; nothing changed outside it; the photo it was drawn
 * from untouched. Under the cover the test photo is red on the left half and blue on the right, so the fill is
 * (127.5, 0, 127.5) rounded half up, whatever the sampling, as long as it is spread evenly across the cover.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceRedactionTest {
    @Test
    fun aCoveredAreaIsFilledWithTheAverageOfWhatItHidesAndNothingOutsideItChanges() {
        val photo = halves(patterned(200, 150))
        val redaction = Redaction(200, 150).draw(40f, 30f, 120f, 110f)
        val cover = redaction.covers.single()

        val out = FaceRedaction.covered(photo, redaction)

        for (y in 0 until 150) {
            for (x in 0 until 200) {
                when {
                    inside(cover, x, y, inset = cover.cornerRadius + 1f) -> {
                        assertEquals("($x, $y) under the cover", PURPLE, out.getPixel(x, y))
                    }

                    !inside(cover, x, y, inset = -1f) -> {
                        assertEquals("($x, $y) outside the cover", photo.getPixel(x, y), out.getPixel(x, y))
                    }
                }
            }
        }
    }

    @Test
    fun anOverlappingCoverAveragesThePhotoNotTheFillAlreadyDrawn() {
        // The second cover takes the first's blue right half and as much white beside it: (0, 0, 255) and (255, 255,
        // 255) average to (128, 128, 255). Averaged over the first cover's purple fill instead, it would be (192, 128, 192).
        val photo = halves(Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        val redaction = Redaction(200, 150).draw(40f, 30f, 120f, 110f).draw(80f, 30f, 160f, 110f)

        val out = FaceRedaction.covered(photo, redaction)

        assertEquals(Color.rgb(128, 128, 255), out.getPixel(100, 70))
    }

    @Test
    fun anUncoveredFaceIsNotFilledAndThePhotoDrawnFromIsUntouched() {
        val photo = patterned(200, 150)
        val before = IntArray(200 * 150).also { photo.getPixels(it, 0, 200, 0, 0, 200, 150) }
        val found = Redaction.of(200, 150, listOf(FaceBox(50f, 40f, 40f, 50f, 0.9f)))

        val out = FaceRedaction.covered(photo, found.toggle(found.covers.single().id))

        val after = IntArray(200 * 150).also { photo.getPixels(it, 0, 200, 0, 0, 200, 150) }
        assertTrue("the source photo changed", before.contentEquals(after))
        val sent = IntArray(200 * 150).also { out.getPixels(it, 0, 200, 0, 0, 200, 150) }
        assertTrue("an uncovered face was filled", before.contentEquals(sent))
    }

    @Test
    fun aCoveredPhotoIsSentAsAJpegThatDecodesToTheFillUnderTheCover() {
        val photo = halves(patterned(200, 150))
        val redaction = Redaction(200, 150).draw(40f, 30f, 120f, 110f)
        val cover = redaction.covers.single()

        val (mime, bytes) = FaceRedaction.encode(FaceRedaction.covered(photo, redaction))

        assertEquals("image/jpeg", mime)
        val decoded = checkNotNull(decodeBoundedFromBytes(bytes, 4096))
        assertEquals(200 to 150, decoded.width to decoded.height)
        // JPEG's 8 x 8 blocks ring at the cover's edge, so the check stays a block clear of it.
        for (y in 0 until 150) {
            for (x in 0 until 200) {
                if (!inside(cover, x, y, inset = cover.cornerRadius + JPEG_BLOCK)) continue
                assertNear("($x, $y) under the cover", PURPLE, decoded.getPixel(x, y))
            }
        }
    }

    @Test
    fun aPhotoWithTransparencyIsSentAsWebpWithItsClearAreasStillClear() {
        // The top 20 rows are clear; the cover sits below them.
        val photo =
            halves(patterned(200, 150)).apply {
                setHasAlpha(true)
                for (y in 0 until 20) {
                    for (x in 0 until 200) setPixel(x, y, Color.TRANSPARENT)
                }
            }

        val (mime, bytes) = FaceRedaction.encode(FaceRedaction.covered(photo, Redaction(200, 150).draw(40f, 30f, 120f, 110f)))

        assertEquals("image/webp", mime)
        val decoded = checkNotNull(decodeBoundedFromBytes(bytes, 4096))
        assertEquals("a clear pixel's alpha", 0, Color.alpha(decoded.getPixel(100, 10)))
        val covered = decoded.getPixel(80, 70)
        assertEquals("a covered pixel's alpha", 255, Color.alpha(covered))
        assertNear("a covered pixel", PURPLE, covered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRedactionForAnotherSizeOfPhotoIsRefused() {
        FaceRedaction.covered(patterned(200, 150), Redaction(150, 200))
    }

    /** Every pixel a different, bright colour, so a fill or a shift anywhere shows; opaque, as a decoded photo is. */
    private fun patterned(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            for (y in 0 until height) {
                for (x in 0 until width) setPixel(x, y, Color.rgb(64 + x % 192, 64 + y % 192, 64 + (x * 7 + y * 13) % 192))
            }
        }

    /** [photo] with the cover's area, (40, 30)–(120, 110), red on its left half and blue on its right. */
    private fun halves(photo: Bitmap): Bitmap =
        photo.apply {
            for (y in 30 until 110) {
                for (x in 40 until 120) setPixel(x, y, if (x < 80) Color.RED else Color.BLUE)
            }
        }

    private fun assertNear(
        what: String,
        want: Int,
        got: Int,
    ) {
        val off =
            maxOf(
                abs(Color.red(want) - Color.red(got)),
                abs(Color.green(want) - Color.green(got)),
                abs(Color.blue(want) - Color.blue(got)),
            )
        assertTrue("$what decodes to ${Integer.toHexString(got)}, the fill is ${Integer.toHexString(want)}", off <= LOSSY)
    }

    /** Whether pixel ([x], [y]) lies within [cover] shrunk by [inset] on every side (grown, for a negative one). */
    private fun inside(
        cover: Cover,
        x: Int,
        y: Int,
        inset: Float,
    ): Boolean =
        x >= ceil(cover.left + inset) && x + 1 <= floor(cover.right - inset) &&
            y >= ceil(cover.top + inset) && y + 1 <= floor(cover.bottom - inset)

    private companion object {
        const val JPEG_BLOCK = 8f

        /** How far a lossy encode may move a channel of a flat area. */
        const val LOSSY = 12

        /** Half red, half blue, averaged and rounded half up. */
        val PURPLE = Color.rgb(128, 0, 128)
    }
}
