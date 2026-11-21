package app.getknit.knit.data.webp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import androidx.core.graphics.createBitmap
import app.getknit.knit.data.webp.AnimationFixtures.ApngFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The animation doors on the phone's own codecs, which the JVM tests replace with Robolectric's: a GIF through
 * `android.graphics.Movie`, an APNG's frames through `BitmapFactory`, each sample through `Bitmap.compress(WEBP_LOSSY)`,
 * and the animated WebP [AnimatedWebpMuxer] writes from those encodes read back by `ImageDecoder`, the decoder Coil plays
 * it with. The per-frame encoder's output is the platform's (its `VP8X`, its `ICCP`), which is what the muxer has to
 * take apart, so a phone whose encoder writes something new is caught here and not in a chat.
 *
 * Every source frame carries a colour of its own. `ImageDecoder` reads the output's size, its play count and its first
 * frame; every later frame, decoded by `BitmapFactory` out of the app's own demux ([AnimatedFrames]), is checked for the
 * colour of the source frame on screen at its time. The bounds are [app.getknit.knit.data.AttachmentStore]'s GIF door:
 * 480 px, 15 fps, q70.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.data.webp.WebpTranscodeInstrumentedTest
 */
class WebpTranscodeInstrumentedTest {
    @Test
    fun aGifBecomesASmallerLoopingAnimatedWebpThatShowsEachFrameInTurn() {
        val gif = AnimationFixtures.gif(delayMs = GIF_DELAY_MS)
        val webp = WebpTranscode.shrink(gif, MAX_DIMENSION, MAX_FPS, QUALITY)
        assertNotNull("the GIF was transcoded", webp)
        Log.i(TAG, "gif ${gif.size} B -> webp ${webp!!.size} B")
        assertTrue("${webp.size} B of a ${gif.size} B GIF", webp.size < gif.size)

        val drawable = playable(webp)
        assertEquals(320, drawable.intrinsicWidth)
        assertEquals(240, drawable.intrinsicHeight)
        assertEquals("a GIF that loops for ever still does", AnimatedImageDrawable.REPEAT_INFINITE, drawable.repeatCount)
        val first = firstFrame(drawable)
        val centre = first.pixel(0.5f, 0.5f)
        assertTrue(
            "ImageDecoder's first frame, centre: ${AnimationFixtures.describe(centre)}",
            AnimationFixtures.near(centre, AnimationFixtures.GIF_MARKS[0]),
        )

        val frames = AnimationFixtures.frames(webp)
        val lengthMs = AnimationFixtures.GIF_MARKS.size * GIF_DELAY_MS
        assertEquals("one frame per sample", (lengthMs + SAMPLE_MS - 1) / SAMPLE_MS, frames.size)
        assertShows(frames, GIF_DELAY_MS) { source, bitmap ->
            listOf("centre" to (bitmap.pixel(0.5f, 0.5f) to AnimationFixtures.GIF_MARKS[source]))
        }
    }

    /**
     * An APNG, which Android cannot play, becomes an animated WebP it can, keeping its play count; a frame smaller than
     * the canvas lands over the one before it (blend over, dispose none), so its corners still show the last frame.
     */
    @Test
    fun anApngBecomesAnAnimatedWebpComposedFrameByFrameWithItsPlayCount() {
        val apng =
            AnimationFixtures.apng(
                width = APNG_SIDE,
                height = APNG_SIDE,
                frames =
                    listOf(
                        ApngFrame(0, 0, APNG_SIDE, APNG_SIDE, APNG_CENTRES[0]),
                        ApngFrame(0, 0, APNG_SIDE, APNG_SIDE, APNG_CENTRES[1]),
                        ApngFrame(APNG_SIDE / 4, APNG_SIDE / 4, APNG_SIDE / 2, APNG_SIDE / 2, APNG_CENTRES[2], blendOver = true),
                        ApngFrame(0, 0, APNG_SIDE, APNG_SIDE, APNG_CENTRES[3]),
                    ),
                delayMs = APNG_DELAY_MS,
                plays = APNG_PLAYS,
            )
        val source = AnimatedFrames.of(apng)
        assertNotNull("the APNG demuxes", source)
        val webp = WebpTranscode.shrinkAnimated(source!!, apng.size, MAX_DIMENSION, MAX_FPS, QUALITY)
        assertNotNull("the APNG was transcoded", webp)

        val drawable = playable(webp!!)
        assertEquals(APNG_SIDE, drawable.intrinsicWidth)
        // The platform counts repeats after the first play: an animation played three times repeats twice.
        assertEquals("played $APNG_PLAYS times", APNG_PLAYS - 1, drawable.repeatCount)
        assertEquals(APNG_PLAYS, AnimatedFrames.of(webp)?.loopCount)
        val first = firstFrame(drawable)
        assertTrue(
            "ImageDecoder's first frame, centre: ${AnimationFixtures.describe(first.pixel(0.5f, 0.5f))}",
            AnimationFixtures.near(first.pixel(0.5f, 0.5f), APNG_CENTRES[0]),
        )

        assertShows(AnimationFixtures.frames(webp), APNG_DELAY_MS) { frame, bitmap ->
            listOf(
                "centre" to (bitmap.pixel(0.5f, 0.5f) to APNG_CENTRES[frame]),
                "corner" to (bitmap.pixel(0.05f, 0.05f) to APNG_CORNERS[frame]),
            )
        }
    }

    /** The frame [drawable] shows before it is started, drawn by the platform onto a bitmap of its own size. */
    private fun firstFrame(drawable: AnimatedImageDrawable): Bitmap =
        createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(Canvas(it))
        }

    /** [webp] read by the platform's `ImageDecoder` as an animation, as Coil reads it. */
    private fun playable(webp: ByteArray): AnimatedImageDrawable {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(webp)))
        assertTrue("the platform plays it: ${drawable.javaClass.simpleName}", drawable is AnimatedImageDrawable)
        return drawable as AnimatedImageDrawable
    }

    /**
     * Each output frame shows the source frame on screen at its start time, by the colours [expect] lists for that
     * source frame (`name to (actual to expected)`). Frames starting within [EDGE_MS] of a source frame's change are not
     * judged: where a sample lands on the boundary is the sampler's rounding, not what this checks.
     */
    private fun assertShows(
        frames: List<Pair<Int, Bitmap>>,
        sourceMs: Int,
        expect: (source: Int, bitmap: Bitmap) -> List<Pair<String, Pair<Int, Int>>>,
    ) {
        var judged = 0
        for ((startMs, bitmap) in frames) {
            val intoSource = startMs % sourceMs
            if (intoSource < EDGE_MS || sourceMs - intoSource < EDGE_MS) continue
            for ((name, colours) in expect(startMs / sourceMs, bitmap)) {
                val (actual, expected) = colours
                assertTrue(
                    "frame at $startMs ms, $name: ${AnimationFixtures.describe(actual)}, wanted ${AnimationFixtures.describe(expected)}",
                    AnimationFixtures.near(actual, expected),
                )
            }
            judged++
        }
        assertTrue("$judged frames judged of ${frames.size}", judged >= frames.size / 2)
    }

    private fun Bitmap.pixel(
        x: Float,
        y: Float,
    ): Int = getPixel((width * x).toInt().coerceAtMost(width - 1), (height * y).toInt().coerceAtMost(height - 1))

    private companion object {
        const val TAG = "WebpTranscodeTest"
        const val MAX_DIMENSION = 480
        const val MAX_FPS = 15
        const val QUALITY = 70
        const val SAMPLE_MS = 1000 / MAX_FPS
        const val GIF_DELAY_MS = 200
        const val EDGE_MS = 20
        const val APNG_SIDE = 200
        const val APNG_DELAY_MS = 250
        const val APNG_PLAYS = 3
        val APNG_CENTRES = intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        val APNG_CORNERS = intArrayOf(Color.RED, Color.BLUE, Color.BLUE, Color.YELLOW)
    }
}
