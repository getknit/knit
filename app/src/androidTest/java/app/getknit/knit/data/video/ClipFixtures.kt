package app.getknit.knit.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.getknit.knit.data.ByteArrayMediaSource
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Source clips made on the device by Transformer from drawn stills, so a video test needs no asset. */
@OptIn(UnstableApi::class)
object ClipFixtures {
    /**
     * A clip of [stills] in turn, each [stillMs] long at 30 fps, written to [out]. [configure] sets the encode (the
     * codec, the muxer, the encoder's settings) on the builder before it is built.
     */
    fun stillsClip(
        context: Context,
        stills: List<Bitmap>,
        stillMs: Long,
        out: File,
        configure: Transformer.Builder.() -> Unit = {},
    ): File {
        val items =
            stills.mapIndexed { i, still ->
                val png = File(out.parentFile, "${out.nameWithoutExtension}-still-$i.png")
                png.outputStream().use { still.compress(Bitmap.CompressFormat.PNG, 100, it) }
                EditedMediaItem
                    .Builder(
                        MediaItem
                            .Builder()
                            .setUri(Uri.fromFile(png))
                            .setImageDurationMs(stillMs)
                            .build(),
                    ).setFrameRate(FRAME_RATE)
                    .build()
            }
        export(context, Composition.Builder(EditedMediaItemSequence.withVideoFrom(items)).build(), out, configure)
        return out
    }

    /** How many of [bytes]' video samples are sync frames: the keyframes `OPTION_CLOSEST_SYNC` can land on. */
    fun syncFrames(bytes: ByteArray): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ByteArrayMediaSource(bytes))
            val track =
                (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
            extractor.selectTrack(track)
            var sync = 0
            do {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync++
            } while (extractor.advance())
            sync
        } finally {
            extractor.release()
        }
    }

    private fun export(
        context: Context,
        composition: Composition,
        out: File,
        configure: Transformer.Builder.() -> Unit,
    ) {
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        val thread = HandlerThread("fixture").apply { start() }
        Handler(thread.looper).post {
            Transformer
                .Builder(context)
                .setLooper(thread.looper)
                .apply(configure)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            exported: Composition,
                            exportResult: ExportResult,
                        ) = done.countDown()

                        override fun onError(
                            exported: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            failure = exportException
                            done.countDown()
                        }
                    },
                ).build()
                .start(composition, out.path)
        }
        assertTrue("fixture timed out", done.await(10, TimeUnit.MINUTES))
        thread.quitSafely()
        failure?.let { throw it }
    }

    private const val FRAME_RATE = 30
}
