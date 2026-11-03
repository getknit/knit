package app.getknit.knit.data.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.transformer.InAppMp4Muxer
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.ByteArrayMediaSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The whole short-video encode on a real codec stack (ADR 2026-10.hmfe): a 70 s 720 × 1280 clip carrying a GPS
 * location goes in, and what comes out is HEVC (or H.264 on a phone with no HEVC encoder), 480 on its short side, cut
 * to 61 s, under the ceiling, unpadded, with no location — and it yields a poster small enough to ride inline.
 *
 * The source is made here, by Transformer, from a drawn still, so the test needs no video asset. Runs on the emulator
 * (the platform's software HEVC encoder) and on hardware, where the encode time it logs is the number that matters.
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.data.video.VideoTranscoderInstrumentedTest
 */
@OptIn(UnstableApi::class)
class VideoTranscoderInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "video-transcoder-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aLongGeotaggedClipComesOutSmallUprightTrimmedAndWithoutItsLocation() {
        val source = geotaggedClip(durationMs = 70_000)
        val probe = VideoTranscoder(context).probe(Uri.fromFile(source))
        assertNotNull("the fixture probes", probe)
        android.util.Log.i("VideoTranscoderTest", "fixture $probe")
        assertTrue("the fixture carries its location", source.readBytes().containsAscii("©xyz", latin1 = true))

        val result = runBlocking { VideoTranscoder(context).transcode(Uri.fromFile(source)) {} }
        assertTrue("encode result $result", result is TranscodeResult.Success)
        result as TranscodeResult.Success
        val bytes = result.bytes
        android.util.Log.i(
            "VideoTranscoderTest",
            "encoded ${bytes.size} B in ${result.elapsedMs} ms, ${result.plan.videoMime} @ ${result.plan.videoBitrate}",
        )

        assertTrue("${bytes.size} B", bytes.size <= VideoEncodePolicy.CEILING_BYTES)
        assertTrue("trimmed", result.plan.trimmed)
        val meta = result.meta
        assertEquals(480, minOf(meta.width, meta.height))
        assertTrue("duration ${meta.durationMs}", meta.durationMs in 60_000..61_500)
        assertTrue(videoMimeOf(bytes) in setOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC))
        assertTrue("no location atom", !bytes.containsAscii("©xyz", latin1 = true))
        assertTrue("no padding", bytes.indexOfAscii("free") < 0)

        val poster = result.poster
        assertTrue("a poster", poster.isNotEmpty())
        assertTrue("poster ${poster.size} B", poster.size <= VideoFrames.POSTER_MAX_BYTES)
    }

    /**
     * A real clip, for the device spike: `-e videoPath <file>` names a video already pushed to the app's external files
     * dir, and its encode is written beside it as `<name>.knit.mp4` for inspection. Skipped without the argument.
     */
    @Test
    fun aPushedClipEncodesUnderTheCeilingWithoutItsMetadata() {
        val path = InstrumentationRegistry.getArguments().getString("videoPath")
        assumeTrue("no -e videoPath", path != null)
        val source = File(checkNotNull(path))
        val result = runBlocking { VideoTranscoder(context).transcode(Uri.fromFile(source)) {} }
        assertTrue("encode result $result", result is TranscodeResult.Success)
        result as TranscodeResult.Success
        File(source.parentFile, source.nameWithoutExtension + ".knit.mp4").writeBytes(result.bytes)
        val meta = result.meta
        val poster = result.poster
        android.util.Log.i(
            "VideoTranscoderTest",
            "${source.name}: ${result.source} -> ${result.bytes.size} B ${meta.width}x${meta.height} " +
                "${meta.durationMs} ms in ${result.elapsedMs} ms, ${result.plan.videoMime} @ ${result.plan.videoBitrate} " +
                "pass ${result.plan.attempt}, poster ${poster.size} B",
        )
        assertTrue(result.bytes.size <= VideoEncodePolicy.CEILING_BYTES)
        assertTrue("no location", !result.bytes.containsAscii("\u00A9xyz", latin1 = true))
        assertTrue("no model", !result.bytes.containsAscii("com.android.model"))
    }

    /** A [durationMs] portrait clip made from one drawn still, carrying a location, written to [dir]. */
    private fun geotaggedClip(durationMs: Long): File {
        val bitmap = createBitmap(720, 1280)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(40, 90, 160))
            drawCircle(360f, 640f, 200f, Paint().apply { color = Color.YELLOW })
        }
        val muxers = InAppMp4Muxer.Factory { entries -> entries.add(Mp4LocationData(37.4219f, -122.084f)) }
        return ClipFixtures.stillsClip(context, listOf(bitmap), durationMs, File(dir, "source.mp4")) {
            // H.264: the emulator's software HEVC encoder stops at 512 x 512, which would shrink the fixture.
            setVideoMimeType(MimeTypes.VIDEO_H264)
            setMuxerFactory(muxers)
        }
    }

    private fun videoMimeOf(bytes: ByteArray): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ByteArrayMediaSource(bytes))
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it?.startsWith("video/") == true }
        } finally {
            extractor.release()
        }
    }

    private fun ByteArray.indexOfAscii(
        text: String,
        latin1: Boolean = false,
    ): Int {
        val needle = text.toByteArray(if (latin1) Charsets.ISO_8859_1 else Charsets.US_ASCII)
        return (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1
    }

    private fun ByteArray.containsAscii(
        text: String,
        latin1: Boolean = false,
    ): Boolean = indexOfAscii(text, latin1) >= 0
}
