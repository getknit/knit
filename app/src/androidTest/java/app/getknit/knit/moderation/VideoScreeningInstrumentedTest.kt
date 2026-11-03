package app.getknit.knit.moderation

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.video.ClipFixtures
import app.getknit.knit.data.video.TranscodeResult
import app.getknit.knit.data.video.VideoAttachment
import app.getknit.knit.data.video.VideoFrames
import app.getknit.knit.data.video.VideoTranscoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The short-video NSFW screen (ADR 2026-10.hmfe, `docs/CONTENT_MODERATION.md` §7.2) on real hardware: the real encode,
 * the real frame sampling and the bundled model, where every JVM test of it mocks the classifier.
 *
 * - [twelveSamplesAreTwelveMomentsOfTheSentClip] and [aClipWithOneKeyframeIsStillSampledAcrossIt] check that each of
 *   the 12 frames the screen classifies is the frame at its own instant, from drawn stills, with no asset.
 * - [aClipLongerThanKnitSendsIsFlaggedUnscreened] checks the screen's bound on what a received clip may cost it.
 * - [aPushedCorpusIsScreenedAsItWouldBeSent] screens a labelled folder of real clips pushed to the device, each
 *   re-encoded as a send does, and logs every frame's score (tag `VideoScreenTrial`, and the [REPORT] file). Skipped
 *   without a corpus. The clips never enter the repo: push them to [CORPUS] (`explicit/`, `clean/`, or loose; names
 *   without whitespace), run the one method, read the report back, and delete both.
 *
 * One class on one device with `am instrument`, never `connectedDebugAndroidTest` with lab phones attached:
 *
 *     adb shell am instrument -w -e class app.getknit.knit.moderation.VideoScreeningInstrumentedTest \
 *         app.getknit.knit.test/androidx.test.runner.AndroidJUnitRunner
 */
@OptIn(UnstableApi::class)
class VideoScreeningInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "video-screening-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** What a send screens: our own encode, whose keyframes Media3 spaces about a second apart. */
    @Test
    fun twelveSamplesAreTwelveMomentsOfTheSentClip() {
        val colors = palette()
        val source = ClipFixtures.stillsClip(context, colors.map(::still), STILL_MS, File(dir, "stills.mp4")) { h264() }
        val sent = transcode(source)
        Log.i(TAG, "sent clip: ${sent.bytes.size} B, ${ClipFixtures.syncFrames(sent.bytes)} sync frames")
        assertEachFrameIsItsInstant(sent.bytes, colors)
    }

    /**
     * What a receive screens: the bytes as they arrive, which any sender may have encoded with a single keyframe. A
     * frame read at the keyframe nearest each instant, as the screen once read them, is the first still twelve times
     * over.
     */
    @Test
    fun aClipWithOneKeyframeIsStillSampledAcrossIt() {
        val colors = palette()
        val clip =
            ClipFixtures
                .stillsClip(context, colors.map(::still), STILL_MS, File(dir, "one-gop.mp4")) {
                    h264()
                    setEncoderFactory(
                        DefaultEncoderFactory
                            .Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setiFrameIntervalSeconds(ONE_GOP_S).build(),
                            ).build(),
                    )
                }.readBytes()
        val sync = ClipFixtures.syncFrames(clip)
        Log.i(TAG, "one-keyframe clip: ${clip.size} B, $sync sync frames")
        assumeTrue("this encoder spaced keyframes itself ($sync), so there is no single-keyframe clip to read", sync == 1)
        assertEachFrameIsItsInstant(clip, colors)
    }

    /**
     * A received clip longer than Knit sends is flagged with no frame decoded, since each exact frame decodes on from a
     * keyframe its sender placed; one within the limit has its 12 frames classified.
     */
    @Test
    fun aClipLongerThanKnitSendsIsFlaggedUnscreened() {
        val gray = listOf(still(Color.GRAY))
        val long =
            ClipFixtures.stillsClip(
                context,
                gray,
                VideoAttachment.MAX_SCREENED_DURATION_MS + STILL_MS,
                File(dir, "long.mp4"),
            ) { h264() }
        val short = ClipFixtures.stillsClip(context, gray, STILL_MS * 2, File(dir, "short.mp4")) { h264() }
        val moderator = CountingModerator()
        val verdicts = HeldVerdicts()
        val screening = ImageScreeningService(moderator, verdicts, ScopedTextModerator(AllowAll, AllowAll))
        runBlocking { screening.screenVideo("long", long.readBytes()) }
        assertEquals(true, verdicts.held["long"]?.flagged)
        assertEquals(0, moderator.classified.get())

        runBlocking { screening.screenVideo("short", short.readBytes()) }
        assertEquals(false, verdicts.held["short"]?.flagged)
        assertEquals(ImageScreeningService.VIDEO_SAMPLES, moderator.classified.get())
    }

    /**
     * Every clip under the corpus folder (`-e clipDir`, else [CORPUS]) is re-encoded as a send does and screened as
     * the room's send screen does ([ImageScreeningService.isVideoExplicit], poster included); a receiver screens the
     * same frames. A clip under `explicit/` must be flagged and one under `clean/` must not; a clip directly in the
     * folder is only reported. Each line: the label, the clip, what the encode made, the poster's score, each frame's
     * score in clip order, how many of the 12 frames differ, the verdict.
     */
    @Test
    @Suppress("CyclomaticComplexMethod") // one pass over the corpus, a branch for each verdict it reports
    fun aPushedCorpusIsScreenedAsItWouldBeSent() {
        val root = InstrumentationRegistry.getArguments().getString("clipDir") ?: CORPUS
        val clips =
            LABELS.flatMap { label -> list("$root/$label").map { label to "$root/$label/$it" } } +
                list(root).filter { it !in LABELS }.map { "unlabelled" to "$root/$it" }
        assumeTrue("no clips under $root", clips.isNotEmpty())
        val moderator = NsfwImageModerator(context, scope = scope)
        val screening = ImageScreeningService(moderator, HeldVerdicts(), ScopedTextModerator(AllowAll, AllowAll))
        val wrong = mutableListOf<String>()
        // Logcat's ring drops lines on a long run, so the report is also a file the script reads back ([REPORT]).
        val report = File(checkNotNull(context.getExternalFilesDir(null)), REPORT).apply { delete() }

        fun report(line: String) {
            Log.i(TAG, line)
            report.appendText(line + "\n")
        }
        var screened = 0
        runBlocking {
            for ((label, path) in clips) {
                val name = path.substringAfterLast('/')
                val local = File(dir, name).also { pull(path, it) }
                val result = VideoTranscoder(context).transcode(Uri.fromFile(local)) {}
                local.delete()
                if (result !is TranscodeResult.Success) {
                    // The send is refused before any screen runs: nothing goes out to judge.
                    report("$label $name not sent: $result")
                    continue
                }
                screened++
                val poster =
                    decodeBoundedFromBytes(result.poster, POSTER_SCREEN_SIDE)?.let { moderator.classify(it).score }
                val frames = screening.videoFrameVerdicts(result.bytes)
                val flagged = screening.isVideoExplicit(result.bytes, result.poster)
                val expected =
                    when (label) {
                        EXPLICIT -> true
                        CLEAN -> false
                        else -> null
                    }
                val line =
                    "$label $name sent=${result.bytes.size}B ${result.meta.width}x${result.meta.height} " +
                        "${result.meta.durationMs}ms ${result.plan.videoMime} " +
                        "sync=${ClipFixtures.syncFrames(result.bytes)} poster=${poster?.let(::score)} " +
                        "frames=${frames.size} distinct=${distinctFrames(result.bytes)} " +
                        "max=${frames.maxOfOrNull { it.score }?.let(::score)} " +
                        "scores=${frames.joinToString(",") { score(it.score) }} flagged=$flagged" +
                        when (expected) {
                            null -> ""
                            flagged -> " ok"
                            else -> " WRONG"
                        }
                report(line)
                if (expected != null && expected != flagged) wrong += line
            }
        }
        report("screened $screened of ${clips.size} clips, ${wrong.size} wrong")
        assumeTrue("no clip under $root encoded", screened > 0)
        assertTrue("screened wrongly:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * Each of the screen's frames of [bytes] shows the still that plays at its instant. The draw is seeded, so the
     * instants are known here; one within [NEAR_CUT_MS] of a cut is not judged, since the frame nearest it may be
     * either still.
     */
    private fun assertEachFrameIsItsInstant(
        bytes: ByteArray,
        colors: List<Int>,
    ) {
        val count = ImageScreeningService.VIDEO_SAMPLES
        val times = VideoFrames.screenTimes(checkNotNull(VideoFrames.meta(bytes)).durationMs, count, Random(SEED))
        val started = SystemClock.elapsedRealtime()
        val frames = VideoFrames.sample(bytes, count, PROBE_SIDE, Random(SEED))
        // What the exact frames cost: each is decoded on from the keyframe before it.
        Log.i(TAG, "sampled ${frames.size} frames in ${SystemClock.elapsedRealtime() - started} ms")
        try {
            assertEquals("frames at $times", count, frames.size)
            val seen = frames.map { frame -> colors.indices.minBy { distance(colors[it], frame.meanColor()) } }
            val judged = times.indices.filter { times[it] % STILL_MS in NEAR_CUT_MS..(STILL_MS - NEAR_CUT_MS) }
            assertTrue("too few instants clear of a cut: $times", judged.size >= count - 2)
            assertEquals("stills at $times", judged.map { (times[it] / STILL_MS).toInt() }, judged.map { seen[it] })
        } finally {
            frames.forEach(Bitmap::recycle)
        }
    }

    /** How many of the screen's frames of [bytes] are different pictures, not one keyframe read again. */
    private fun distinctFrames(bytes: ByteArray): Int {
        val frames = VideoFrames.sample(bytes, ImageScreeningService.VIDEO_SAMPLES, PROBE_SIDE)
        return try {
            frames
                .map { frame ->
                    IntArray(frame.width * frame.height).also { frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height) }
                }.distinctBy { it.contentHashCode() }
                .size
        } finally {
            frames.forEach(Bitmap::recycle)
        }
    }

    private fun transcode(source: File): TranscodeResult.Success {
        val result = runBlocking { VideoTranscoder(context).transcode(Uri.fromFile(source)) {} }
        assertTrue("encode result $result", result is TranscodeResult.Success)
        return result as TranscodeResult.Success
    }

    /** The file names in [path] on the device, read as the shell: a corpus under /data/local/tmp outlives `pm clear`. */
    private fun list(path: String): List<String> =
        shell("ls -1 $path").lines().map(String::trim).filter { it.isNotEmpty() && !it.contains(' ') && !it.startsWith("ls:") }

    private fun pull(
        path: String,
        to: File,
    ) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use { input ->
            to.outputStream().use { input.copyTo(it) }
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private fun Transformer.Builder.h264() {
        // H.264: the emulator's software HEVC encoder stops at 512 x 512.
        setVideoMimeType(MimeTypes.VIDEO_H264)
    }

    /** [ImageScreeningService.VIDEO_SAMPLES] hues 30° apart, far enough apart to survive the encode. */
    private fun palette(): List<Int> =
        List(ImageScreeningService.VIDEO_SAMPLES) { i -> Color.HSVToColor(floatArrayOf(i * HUE_STEP, 1f, 0.9f)) }

    private fun still(color: Int): Bitmap = createBitmap(STILL_W, STILL_H).apply { eraseColor(color) }

    private fun Bitmap.meanColor(): Int {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        return Color.rgb(
            pixels.sumOf { Color.red(it) } / pixels.size,
            pixels.sumOf { Color.green(it) } / pixels.size,
            pixels.sumOf { Color.blue(it) } / pixels.size,
        )
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int {
        val r = Color.red(a) - Color.red(b)
        val g = Color.green(a) - Color.green(b)
        val bl = Color.blue(a) - Color.blue(b)
        return r * r + g * g + bl * bl
    }

    private fun score(value: Float): String = "%.3f".format(Locale.ROOT, value)

    /** The verdict cache, in memory: [ImageScreeningService.screenVideo] writes it, and the send screen keeps none. */
    private class HeldVerdicts : BlobVerdictDao {
        val held = mutableMapOf<String, BlobVerdictEntity>()

        override suspend fun upsert(verdict: BlobVerdictEntity) {
            held[verdict.hash] = verdict
        }

        override suspend fun find(hash: String): BlobVerdictEntity? = held[hash]

        override fun observeFlaggedHashes(): Flow<List<String>> = emptyFlow()

        override suspend fun delete(hash: String) {
            held.remove(hash)
        }
    }

    /** Allows every frame and counts them: how many the screen decoded and classified. */
    private class CountingModerator : ImageModerator {
        val classified = AtomicInteger()

        override suspend fun classify(bitmap: Bitmap): ImageVerdict {
            classified.incrementAndGet()
            return ImageVerdict.ALLOWED
        }
    }

    /** A video carries no text to screen. */
    private object AllowAll : TextModerator {
        override suspend fun classify(text: String): TextVerdict = TextVerdict.ALLOWED
    }

    private companion object {
        const val TAG = "VideoScreenTrial"

        /** The corpus run's report, in the app's external files dir, where the script reads it back. */
        const val REPORT = "video-screen-trial.txt"

        /** Where a corpus is pushed: the shell can write it, and `pm clear` cannot reach it. */
        const val CORPUS = "/data/local/tmp/knit-screen-clips"
        const val EXPLICIT = "explicit"
        const val CLEAN = "clean"
        val LABELS = listOf(EXPLICIT, CLEAN)

        /** The bound the service decodes a poster at before the classifier. */
        const val POSTER_SCREEN_SIDE = 512

        /** Small: the frames are only told apart, never classified. */
        const val PROBE_SIDE = 64

        /** Each still's length: the 12 stills make a 60 s clip, one still per sample. */
        const val STILL_MS = 5_000L
        const val STILL_W = 360
        const val STILL_H = 640
        const val HUE_STEP = 30f

        /** The screen's draw of instants, fixed so a test knows them. */
        const val SEED = 7

        /** An instant this near a cut is not judged: the frame nearest it may be either still. */
        const val NEAR_CUT_MS = 100L

        /** Longer than the clip: one keyframe, at its start. */
        const val ONE_GOP_S = 3_600f
    }
}
