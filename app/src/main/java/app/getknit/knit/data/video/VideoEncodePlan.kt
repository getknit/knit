package app.getknit.knit.data.video

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a picked video is, as far as the encode needs to know: its coded [width] × [height], the [rotationDegrees] its
 * container asks the player to apply, its [durationMs], its average [frameRate] (null when the container does not say)
 * and whether it carries [hasAudio].
 */
data class VideoSource(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val frameRate: Float?,
    val hasAudio: Boolean,
) {
    /** The shorter of the two sides, whichever way up the video plays. */
    val shortSide: Int get() = minOf(width, height)

    /** The longer of the two sides. */
    val longSide: Int get() = maxOf(width, height)
}

/**
 * One encode of a short video (ADR 2026-10.hmfe): what [VideoTranscoder] asks Media3 Transformer for.
 *
 * [shortSide] is the output's short side, or null to keep the source's (it is already no larger). [clipEndMs] cuts
 * the source at that point, or null to send it whole. [bitrateMode] is the mode [VideoEncoderChoice] chose for the
 * encoders that will run. [attempt] counts the encodes [VideoEncodePolicy.next] has asked for so far, zero for the
 * first.
 */
data class VideoEncodePlan(
    val videoMime: String,
    val shortSide: Int?,
    val maxFrameRate: Float,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val clipEndMs: Long?,
    val bitrateMode: BitrateMode,
    val attempt: Int,
) {
    /** Whether the encode drops the source's tail past [VideoEncodePolicy.MAX_DURATION_MS]. */
    val trimmed: Boolean get() = clipEndMs != null
}

/** What [VideoEncodePolicy.next] makes of an encode's output. */
sealed interface VideoEncodeVerdict {
    /** The output is small enough to send as it is. */
    data object Accept : VideoEncodeVerdict

    /** The output overshot the ceiling: encode once more with [plan]. */
    data class Retry(
        val plan: VideoEncodePlan,
    ) : VideoEncodeVerdict

    /** The retry overshot too: the video is refused, and Direct file send is the way to send it. */
    data object Refuse : VideoEncodeVerdict
}

/**
 * How Knit re-encodes a picked short video before sending it. Size first, not "as much as fits in 8 MiB": a video goes
 * out at about 480p, at most 30 frames a second, at about 400 kbps in all, so a 61 s clip is about 3 MB — under the
 * 5 MB tier of "Ask before large Bluetooth downloads" (ADR 2026-10.js2p), and fetched over Wi-Fi Aware in seconds.
 * Someone who wants the original sends it with Direct file send.
 *
 * HEVC where [VideoEncoderChoice] picks it, otherwise H.264 at [H264_BITRATE_FACTOR] times the rate, which every
 * receiver decodes. A source smaller than [SHORT_SIDE] is never upscaled, and its bitrate scales down with its
 * pixel count (to no less than [MIN_VIDEO_BITRATE]). A source longer than [MAX_DURATION_MS] is cut at it.
 *
 * Encoders do not all hold a requested bitrate at these low rates, so the output is measured, not trusted: past
 * [CEILING_BYTES] it is encoded once more as a smaller picture at the same rate, and past it again it is refused. A
 * lower rate was the first retry, and it changes nothing where the overshoot comes from. In VBR on Android 12 and
 * later the platform's quality floor (`VQApply`) lifts the request to a rate set by the pixel count (1.23 Mbps at
 * 854 × 480 on the emulator). In CBR the Redmi Note 13 Pro+'s HEVC encoder put a busy clip at 663 kbps for 350 asked,
 * and at 656 for 245 (Device Farm, 2026-11-20, #353). An encoder that does hold the rate never comes near the ceiling
 * (350 kbps for 61 s is about 3 MB). Fewer pixels is what spends fewer bits. Pure, JVM-tested in
 * `VideoEncodePolicyTest`.
 */
object VideoEncodePolicy {
    const val MIME_HEVC = "video/hevc"
    const val MIME_H264 = "video/avc"

    /** The longest video sent; a longer one goes out as its first 61 s, once the sender has said yes to that. */
    const val MAX_DURATION_MS = 61_000L

    /** The output's short side, in pixels: portrait 480 × 854, landscape 854 × 480. */
    const val SHORT_SIDE = 480

    /** The highest frame rate sent; a 60 fps source drops every other frame. */
    const val MAX_FRAME_RATE = 30f

    /** The HEVC video bitrate at [SHORT_SIDE] (16:9). */
    const val HEVC_VIDEO_BITRATE = 350_000

    /** What H.264 needs over HEVC for about the same picture. */
    const val H264_BITRATE_FACTOR = 1.4

    /** The video bitrate a small source never goes below, whatever its pixel count. */
    const val MIN_VIDEO_BITRATE = 150_000

    /** The AAC bitrate. */
    const val AUDIO_BITRATE = 48_000

    /** The largest output sent: under the 5 MB download-limit tier, with room for the container. */
    const val CEILING_BYTES = 4_500_000L

    /**
     * What the retry aims its output at, as a share of [CEILING_BYTES]: its picture is scaled on the assumption that
     * the bytes go with the pixel count, which an encoder held to a quality floor or a QP cap makes nearly true, and
     * this is the margin for the rest.
     */
    const val RETRY_TARGET_SHARE = 0.85

    /** The smallest short side the retry shrinks to. */
    const val MIN_SHORT_SIDE = 144

    /** The pixel count [HEVC_VIDEO_BITRATE] is set for: 480 × 854. */
    private const val REFERENCE_PIXELS = SHORT_SIDE * 854

    /**
     * Whether a source [durationMs] long is cut at [MAX_DURATION_MS]: the one rule the composer's "Send the first 61
     * seconds?" and the encode's [VideoEncodePlan.clipEndMs] both read, so the question is asked exactly when the cut
     * will be made.
     */
    fun cuts(durationMs: Long): Boolean = durationMs > MAX_DURATION_MS

    /**
     * The first encode of [source], in HEVC when [hevcEncoder] says [VideoEncoderChoice] chose it, in the
     * [bitrateMode] it chose.
     */
    fun plan(
        source: VideoSource,
        hevcEncoder: Boolean,
        bitrateMode: BitrateMode,
    ): VideoEncodePlan {
        val scaled = source.shortSide > SHORT_SIDE
        val outShort = if (scaled) SHORT_SIDE else source.shortSide
        // A scaled source lands at the reference size (or, narrower than 16:9, a little under it); only a source
        // already smaller spends less.
        val pixelShare = if (scaled) 1.0 else (outShort.toLong() * source.longSide).toDouble() / REFERENCE_PIXELS
        val hevcRate = (HEVC_VIDEO_BITRATE * pixelShare.coerceAtMost(1.0)).roundToInt()
        val rate = if (hevcEncoder) hevcRate else (hevcRate * H264_BITRATE_FACTOR).roundToInt()
        return VideoEncodePlan(
            videoMime = if (hevcEncoder) MIME_HEVC else MIME_H264,
            shortSide = if (scaled) SHORT_SIDE else null,
            maxFrameRate = MAX_FRAME_RATE,
            videoBitrate = rate.coerceAtLeast(MIN_VIDEO_BITRATE),
            audioBitrate = AUDIO_BITRATE,
            clipEndMs = MAX_DURATION_MS.takeIf { cuts(source.durationMs) },
            bitrateMode = bitrateMode,
            attempt = 0,
        )
    }

    /**
     * How big an encode would have come out had it not stopped at [capBytes] after [writtenUs] of the [plannedUs] it was
     * making: the cap scaled to the whole clip, and never under one byte past it. A VBR encode on a phone with the
     * quality floor can outrun the in-memory cap before it ends (the emulator's busy clip passed 8 MiB at 51.5 s of
     * 61, #353), and [next] has to see that as the overshoot it is, at about its size, to shrink the picture enough.
     */
    fun overflowEstimate(
        capBytes: Long,
        writtenUs: Long,
        plannedUs: Long,
    ): Long {
        val scaled = if (writtenUs <= 0 || plannedUs <= writtenUs) 0L else capBytes * plannedUs / writtenUs
        return maxOf(scaled, capBytes + 1)
    }

    /** What to do with an encode of [plan], made from [source], that came out at [outputBytes]. */
    fun next(
        plan: VideoEncodePlan,
        source: VideoSource,
        outputBytes: Long,
    ): VideoEncodeVerdict =
        when {
            outputBytes <= CEILING_BYTES -> {
                VideoEncodeVerdict.Accept
            }

            plan.attempt == 0 -> {
                // Bytes go with pixels, so the short side goes with the square root of the share to shed.
                val short = plan.shortSide ?: source.shortSide
                val scale = sqrt(CEILING_BYTES * RETRY_TARGET_SHARE / outputBytes)
                val smaller = ((short * scale).toInt() and 1.inv()).coerceAtLeast(MIN_SHORT_SIDE)
                VideoEncodeVerdict.Retry(plan.copy(shortSide = minOf(smaller, short), attempt = 1))
            }

            else -> {
                VideoEncodeVerdict.Refuse
            }
        }
}
