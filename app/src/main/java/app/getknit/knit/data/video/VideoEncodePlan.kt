package app.getknit.knit.data.video

import kotlin.math.roundToInt

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
 * the source at that point, or null to send it whole. [attempt] counts the encodes [VideoEncodePolicy.next] has asked
 * for so far, zero for the first.
 */
data class VideoEncodePlan(
    val videoMime: String,
    val shortSide: Int?,
    val maxFrameRate: Float,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val clipEndMs: Long?,
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
 * HEVC wherever the phone can encode it; a phone that cannot gets H.264 at [H264_BITRATE_FACTOR] times the rate, which
 * every receiver decodes. A source smaller than [SHORT_SIDE] is never upscaled, and its bitrate scales down with its
 * pixel count (to no less than [MIN_VIDEO_BITRATE]). A source longer than [MAX_DURATION_MS] is cut at it.
 *
 * Hardware encoders overshoot a requested bitrate, some by a lot at these low rates, so the output is measured, not
 * trusted: past [CEILING_BYTES] it is encoded once more at [RETRY_BITRATE_FACTOR] times the rate, and past it again it
 * is refused. Pure, JVM-tested in `VideoEncodePolicyTest`.
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

    /** What the one retry asks for, against the first encode's rate. */
    const val RETRY_BITRATE_FACTOR = 0.7

    /** The pixel count [HEVC_VIDEO_BITRATE] is set for: 480 × 854. */
    private const val REFERENCE_PIXELS = SHORT_SIDE * 854

    /**
     * Whether a source [durationMs] long is cut at [MAX_DURATION_MS]: the one rule the composer's "Send the first 61
     * seconds?" and the encode's [VideoEncodePlan.clipEndMs] both read, so the question is asked exactly when the cut
     * will be made.
     */
    fun cuts(durationMs: Long): Boolean = durationMs > MAX_DURATION_MS

    /** The first encode of [source], in HEVC when [hevcEncoder] says the phone has an HEVC encoder. */
    fun plan(
        source: VideoSource,
        hevcEncoder: Boolean,
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
            attempt = 0,
        )
    }

    /** What to do with an encode of [plan] that came out at [outputBytes]. */
    fun next(
        plan: VideoEncodePlan,
        outputBytes: Long,
    ): VideoEncodeVerdict =
        when {
            outputBytes <= CEILING_BYTES -> {
                VideoEncodeVerdict.Accept
            }

            plan.attempt == 0 -> {
                VideoEncodeVerdict.Retry(
                    plan.copy(
                        videoBitrate = (plan.videoBitrate * RETRY_BITRATE_FACTOR).roundToInt(),
                        attempt = 1,
                    ),
                )
            }

            else -> {
                VideoEncodeVerdict.Refuse
            }
        }
}
