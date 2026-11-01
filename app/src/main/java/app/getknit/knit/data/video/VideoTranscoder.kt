package app.getknit.knit.data.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** The seam `AttachmentStore` encodes a picked video through: [VideoTranscoder] on a phone, a fake in a JVM test. */
interface VideoEncoder {
    /** What [uri] is, read off its container without decoding a frame, or null if it has no readable video track. */
    fun probe(uri: Uri): VideoSource?

    /** Re-encodes [uri], reporting progress in 0..1 through [onProgress]. */
    suspend fun transcode(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): TranscodeResult
}

/** What [VideoTranscoder.transcode] made of a picked video. */
sealed interface TranscodeResult {
    /**
     * The re-encoded MP4, what its own bytes say about it ([meta]), its [poster] (empty when no frame decoded), the
     * [plan] it was made with, the [source] it was made from, and how long the encode took.
     */
    class Success(
        val bytes: ByteArray,
        val meta: VideoMeta,
        val poster: ByteArray,
        val plan: VideoEncodePlan,
        val source: VideoSource,
        val elapsedMs: Long,
    ) : TranscodeResult

    /** No video track, or a container the platform cannot read. */
    data object Unreadable : TranscodeResult

    /** Over [VideoEncodePolicy.CEILING_BYTES] even after the retry. */
    data object TooLarge : TranscodeResult

    /** The encode itself failed: no usable encoder, a decoder that gave up, a frame the GL pipeline refused. */
    data class Failed(
        val errorCode: Int,
    ) : TranscodeResult
}

/**
 * Re-encodes a picked video to what [VideoEncodePolicy] asks for, with Media3 Transformer, into memory.
 *
 * The decisions are the policy's; this is the wiring. It reads the source's dimensions, rotation, length, frame rate
 * and audio with `MediaMetadataRetriever`, asks the platform whether it has an HEVC encoder, runs the export on a
 * looper of its own and writes it through [MemoryMp4Muxer], so the plaintext never touches the filesystem and the
 * source's location and capture metadata never leave the phone. The output is measured, and an overshoot is encoded
 * once more at the policy's lower rate. HDR is tone-mapped to SDR: every phone records HDR now, and an HDR stream at
 * this bitrate is mostly lost contrast.
 *
 * [transcode] is cancellable: cancelling the coroutine cancels the export and frees its codecs. `ExperimentalApi` is
 * for the last HDR mode only, Media3's read-as-SDR, kept as the final fallback.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class VideoTranscoder(
    private val context: Context,
) : VideoEncoder {
    override fun probe(uri: Uri): VideoSource? =
        runCatching {
            MediaMetadataRetriever().useRetriever { retriever ->
                retriever.setDataSource(context, uri)
                if (retriever.extract(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@useRetriever null
                val width = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return@useRetriever null
                val height = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return@useRetriever null
                val durationMs = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L
                val frames = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                VideoSource(
                    width = width,
                    height = height,
                    rotationDegrees = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0,
                    durationMs = durationMs,
                    frameRate = frames?.takeIf { durationMs > 0 }?.let { it * MS_PER_S / durationMs.toFloat() },
                    hasAudio = retriever.extract(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                )
            }
        }.onFailure { Log.w(TAG, "video probe failed: ${it.javaClass.simpleName}") }.getOrNull()

    /** Re-encodes [uri], reporting progress in 0..1 through [onProgress] (on the encode's own thread). */
    override suspend fun transcode(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): TranscodeResult {
        val source = probe(uri) ?: return TranscodeResult.Unreadable
        val started = SystemClock.elapsedRealtime()
        var plan = VideoEncodePolicy.plan(source, hevcEncoder = hasHevcEncoder())
        var hdrModes = HDR_MODES
        while (true) {
            val bytes =
                when (val pass = export(uri, plan, hdrModes.first(), source.hasAudio, onProgress)) {
                    is ExportOutcome.Done -> {
                        pass.bytes
                    }

                    is ExportOutcome.Error -> {
                        if (pass.errorCode !in HDR_FALLBACK_ERRORS || hdrModes.size == 1) {
                            return TranscodeResult.Failed(pass.errorCode)
                        }
                        hdrModes = hdrModes.drop(1)
                        Log.d(TAG, "video encode: retrying with HDR mode ${hdrModes.first()}")
                        continue
                    }
                }
            Log.d(TAG, "video encode pass ${plan.attempt}: ${plan.videoMime} ${plan.videoBitrate} b/s -> ${bytes.size} B")
            when (val verdict = VideoEncodePolicy.next(plan, bytes.size.toLong())) {
                VideoEncodeVerdict.Accept -> {
                    val meta = VideoFrames.meta(bytes) ?: return TranscodeResult.Unreadable
                    val poster = VideoFrames.poster(bytes) ?: ByteArray(0)
                    return TranscodeResult.Success(bytes, meta, poster, plan, source, SystemClock.elapsedRealtime() - started)
                }

                VideoEncodeVerdict.Refuse -> {
                    return TranscodeResult.TooLarge
                }

                is VideoEncodeVerdict.Retry -> {
                    plan = verdict.plan
                }
            }
        }
    }

    private sealed interface ExportOutcome {
        class Done(
            val bytes: ByteArray,
        ) : ExportOutcome

        class Error(
            val errorCode: Int,
        ) : ExportOutcome
    }

    private suspend fun export(
        uri: Uri,
        plan: VideoEncodePlan,
        hdrMode: Int,
        hasAudio: Boolean,
        onProgress: (Float) -> Unit,
    ): ExportOutcome {
        val thread = HandlerThread("knit-video-encode").apply { start() }
        val handler = Handler(thread.looper)
        val muxers = MemoryMp4Muxer.Factory(OUTPUT_MAX_BYTES)
        return try {
            suspendCancellableCoroutine { cont ->
                var transformer: Transformer? = null
                val holder = ProgressHolder()
                val poll =
                    object : Runnable {
                        override fun run() {
                            val t = transformer ?: return
                            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(holder.progress / PERCENT)
                            }
                            handler.postDelayed(this, PROGRESS_POLL_MS)
                        }
                    }
                val listener =
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            handler.removeCallbacks(poll)
                            Log.d(
                                TAG,
                                "video export: ${exportResult.videoEncoderName} ${exportResult.width}x${exportResult.height} " +
                                    "${exportResult.averageVideoBitrate} b/s, ${exportResult.audioEncoderName} " +
                                    "${exportResult.averageAudioBitrate} b/s, ${exportResult.videoFrameCount} frames",
                            )
                            val bytes = muxers.bytes()
                            cont.resume(if (bytes == null) ExportOutcome.Error(NO_OUTPUT) else ExportOutcome.Done(bytes))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            handler.removeCallbacks(poll)
                            Log.w(TAG, "video encode failed: ${exportException.errorCodeName}")
                            cont.resume(ExportOutcome.Error(exportException.errorCode))
                        }
                    }
                handler.post {
                    if (!cont.isActive) return@post
                    val built = buildTransformer(plan, muxers, listener, thread)
                    transformer = built
                    built.start(composition(uri, plan, hdrMode, hasAudio), UNUSED_OUTPUT_PATH)
                    handler.post(poll)
                }
                cont.invokeOnCancellation { handler.post { transformer?.cancel() } }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun buildTransformer(
        plan: VideoEncodePlan,
        muxers: MemoryMp4Muxer.Factory,
        listener: Transformer.Listener,
        thread: HandlerThread,
    ): Transformer {
        val encoders =
            DefaultEncoderFactory
                .Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings
                        .Builder()
                        .setBitrate(plan.videoBitrate)
                        .setBitrateMode(bitrateModeFor(plan.videoMime))
                        .build(),
                ).setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate).build())
                .setEnableFallback(true)
                .build()
        return Transformer
            .Builder(context)
            .setLooper(thread.looper)
            .setVideoMimeType(plan.videoMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .setMuxerFactory(muxers)
            .addListener(listener)
            .build()
    }

    private fun composition(
        uri: Uri,
        plan: VideoEncodePlan,
        hdrMode: Int,
        hasAudio: Boolean,
    ): Composition {
        val item =
            MediaItem
                .Builder()
                .setUri(uri)
                .apply {
                    plan.clipEndMs?.let {
                        setClippingConfiguration(
                            MediaItem.ClippingConfiguration
                                .Builder()
                                .setEndPositionMs(it)
                                .build(),
                        )
                    }
                }.build()
        val effects =
            listOfNotNull(
                FrameDropEffect.createDefaultFrameDropEffect(plan.maxFrameRate),
                plan.shortSide?.let { Presentation.createForShortSide(it) },
            )
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
        return Composition
            .Builder(
                if (hasAudio) {
                    EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
                } else {
                    EditedMediaItemSequence.withVideoFrom(listOf(edited))
                },
            ).setHdrMode(hdrMode)
            .build()
    }

    /**
     * Whether an HEVC encoder here can take the output size at the output rate, either way up. Having one is not
     * enough: AOSP's software HEVC encoder, the one a phone without a hardware HEVC encoder falls back to, stops at
     * 512 × 512, and Transformer's fallback would then shrink a 480p plan to 288p rather than change codec.
     */
    private fun hasHevcEncoder(): Boolean {
        // 16:9 rounded up to even: an odd 853 fails every encoder's 2 x 2 alignment (found on the Pixel 3).
        val long = ((VideoEncodePolicy.SHORT_SIDE * LONG_SIDE_16_9 / SHORT_SIDE_16_9.toDouble()).roundToInt() + 1) and 1.inv()
        val short = VideoEncodePolicy.SHORT_SIDE
        val rate = VideoEncodePolicy.MAX_FRAME_RATE.toDouble()
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            val type = info.supportedTypes.firstOrNull { it.equals(VideoEncodePolicy.MIME_HEVC, ignoreCase = true) }
            val video = type?.takeIf { info.isEncoder }?.let { info.getCapabilitiesForType(it).videoCapabilities }
            video != null &&
                (video.areSizeAndRateSupported(long, short, rate) || video.areSizeAndRateSupported(short, long, rate))
        }
    }

    /**
     * CBR wherever the encoder offers it, else VBR. Under VBR the platform's quality floor (`VQApply`, Android 12+)
     * raises a low requested bitrate to its own minimum on the codecs that carry its tuning: the emulator's AOSP AVC
     * encoder took a 490 kbps request to 1.23 Mbps at 480p, and a 28 s clip came out at 4.7 MB on both passes, whatever
     * was asked. CBR is the mode that floor leaves alone, and it makes the size what the plan says it is.
     */
    private fun bitrateModeFor(mime: String): Int {
        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val anyCbr =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
                type != null && info.isEncoder && info.getCapabilitiesForType(type).encoderCapabilities?.isBitrateModeSupported(cbr) == true
            }
        return if (anyCbr) cbr else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    }

    private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }

    private fun MediaMetadataRetriever.extract(key: Int): String? = extractMetadata(key)

    private fun MediaMetadataRetriever.extractInt(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

    private fun MediaMetadataRetriever.extractLong(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

    private companion object {
        const val TAG = "VideoTranscoder"

        /** Transformer wants an output path; [MemoryMp4Muxer.Factory] ignores it, and nothing is written there. */
        const val UNUSED_OUTPUT_PATH = "/dev/null"

        /** A hard bound on what one encode may buffer: the 8 MiB attachment cap. */
        const val OUTPUT_MAX_BYTES = 8 * 1024 * 1024

        const val PROGRESS_POLL_MS = 250L
        const val PERCENT = 100f
        const val MS_PER_S = 1000f
        const val LONG_SIDE_16_9 = 16
        const val SHORT_SIDE_16_9 = 9

        /**
         * How an HDR source is brought to SDR, tried in turn: the OpenGL tone-map (the best picture, and it fails where
         * the GL stack lacks what it needs — the emulator's SwiftShader, and so may some phones), the decoder's own
         * tone-map, and last the stream read as if it were SDR (washed out, but sent). An SDR source passes through all
         * three unchanged, so the order costs it nothing. The last is Media3's experimental mode, and only a last resort.
         */
        val HDR_MODES =
            listOf(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
                Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
            )

        /** The failures a different HDR mode can get past: the frame pipeline, and a codec refusing the format. */
        val HDR_FALLBACK_ERRORS =
            setOf(
                ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
                ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            )

        /** An export that completed without ever making a muxer: nothing to send. */
        const val NO_OUTPUT = -1
    }
}
