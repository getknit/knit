package app.getknit.knit.data.video

/**
 * One encoder the platform lists for a MIME type, as far as [VideoEncoderChoice] needs to know: its [name], whether it
 * is [hardware], whether it [takesOutputSize] (854 × 480 at 30 fps, either way up), and whether it offers [cbr] and
 * [vbr].
 */
data class EncoderCandidate(
    val name: String,
    val hardware: Boolean,
    val takesOutputSize: Boolean,
    val cbr: Boolean,
    val vbr: Boolean,
)

/** The bitrate mode an encode asks for. */
enum class BitrateMode { CBR, VBR }

/** An encode's codec ([mime]), the [encoders] Media3 may pick from (by name, in the platform's order) and their [mode]. */
data class EncoderChoice(
    val mime: String,
    val encoders: List<String>,
    val mode: BitrateMode,
)

/**
 * Which codec, encoders and bitrate mode a video encode asks Media3 for (ADR 2026-10.hmfe, the 2026-11-20 amendment,
 * #353).
 *
 * The three are chosen together, from the encoders that will actually run. Media3's encoder factory keeps only the
 * hardware encoders when there are any, then drops each one that lacks the requested mode. Before this, the mode was
 * CBR when any encoder offered it, software ones included. On the Galaxy A51 and the Redmi Note 13 Pro+ only a
 * software encoder did, so the factory was left with nothing and every export failed with
 * `ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED`.
 *
 * Every encoder offered takes the output size and supports the mode asked for, so Media3's filters cannot empty the
 * list. The order is:
 * 1. CBR before VBR. CBR makes the size the plan's whatever the picture: under VBR, on Android 12 and later, the
 *    platform's quality floor raised a 490 kbps request to 1.23 Mbps, which puts a 61 s clip at about 9 MB, twice the
 *    ceiling, on both passes.
 * 2. Hardware before software within a mode. A software CBR encoder is preferred to a hardware VBR one, at the cost
 *    of a slower encode on the phones that have no hardware CBR encoder.
 * 3. HEVC before H.264 within those. AOSP's software HEVC encoder stops at 512 × 512, so in practice a phone with no
 *    hardware CBR encoder gets software H.264 at the H.264 rate.
 *
 * The choice is null when no encoder takes the output size. The encode then falls back to H.264 on Media3's default
 * list and lets Media3 fit the size. Pure, JVM-tested in `VideoEncoderChoiceTest`.
 */
object VideoEncoderChoice {
    /** The choice over the phone's [hevc] and [h264] encoders, or null when none takes the output size. */
    fun choose(
        hevc: List<EncoderCandidate>,
        h264: List<EncoderCandidate>,
    ): EncoderChoice? {
        val byMime = listOf(VideoEncodePolicy.MIME_HEVC to hevc, VideoEncodePolicy.MIME_H264 to h264)
        for (mode in BitrateMode.entries) {
            for (hardware in listOf(true, false)) {
                for ((mime, candidates) in byMime) {
                    val pool =
                        candidates.filter { it.takesOutputSize && it.hardware == hardware && it.offers(mode) }
                    if (pool.isNotEmpty()) return EncoderChoice(mime, pool.map { it.name }, mode)
                }
            }
        }
        return null
    }

    private fun EncoderCandidate.offers(mode: BitrateMode): Boolean =
        when (mode) {
            BitrateMode.CBR -> cbr
            BitrateMode.VBR -> vbr
        }
}
