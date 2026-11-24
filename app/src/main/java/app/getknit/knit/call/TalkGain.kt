package app.getknit.knit.call

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The walkie's gain on what the microphone captured, before the encoder (docs/VOICE_CALLS.md §3.11, C-3.11-5). A live
 * call's capture goes through WebRTC's gain control; the walkie's comes from the phone's flat `VOICE_RECOGNITION`
 * path, which has none, and it played far quieter than a call at the same volume. So speech is brought toward
 * [TARGET_DB] RMS here, by at most [MAX_GAIN_DB] and never below unity.
 *
 * Slow up, fast down: the gain falls at once toward a loud frame's need and rises at most [RISE_DB_PER_FRAME] per frame
 * of speech, so it settles near the loud syllables instead of pumping the quiet ones up. It holds between words, where
 * a frame is not speech: within 12 dB of the quietest frame of the last 2 s ([SpeechFloor], which a steady hum
 * becomes), or under [MIN_SPEECH_DB]. A limiter keeps every frame's peak under [CEILING_DB]. It is kept across bursts,
 * one per microphone, so a session's second burst starts where the first left off.
 *
 * Works on 16-bit little-endian mono PCM in place, one 20 ms frame per [apply]. Not thread-safe: the capture loop owns
 * it.
 */
class TalkGain {
    /** The gain in force, in dB. */
    var gainDb = INITIAL_GAIN_DB
        private set

    /** The loudest speech frame since [newBurst], in dBFS RMS; null when none was heard. */
    var speechDb: Double? = null
        private set

    /** Frames since [newBurst] the limiter held under the target gain. */
    var limited = 0
        private set

    // The latest frames' RMS, for the noise floor: the quietest of them.
    private val floor = SpeechFloor()
    private var applied = dbToGain(INITIAL_GAIN_DB)

    /** Starts a burst's tallies; the gain and the noise floor carry over. */
    fun newBurst() {
        speechDb = null
        limited = 0
    }

    /**
     * Applies the gain to the [size] bytes of [pcm]: one frame. Unless it may [learn] from it, the frame neither steers
     * the gain nor joins the noise floor: a hands-free walkie's microphone hears the peer's burst from the loudspeaker
     * too (C-3.11-11).
     */
    fun apply(
        pcm: ByteArray,
        size: Int,
        learn: Boolean = true,
    ) {
        val samples = size / BYTES
        if (samples == 0) return
        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until samples) {
            val s = sampleAt(pcm, i)
            sumSquares += s.toDouble() * s
            peak = max(peak, abs(s))
        }
        val rmsDb = SpeechFloor.toDb(sqrt(sumSquares / samples) / FULL_SCALE)
        if (learn) track(rmsDb)
        val limit = if (peak == 0) Double.MAX_VALUE else dbToGain(CEILING_DB) * FULL_SCALE / peak
        val target = dbToGain(gainDb)
        val end = min(target, limit)
        if (end < target) limited++
        // A rising gain ramps across the frame so it never clicks; the limit caps every sample of it.
        val start = applied
        for (i in 0 until samples) {
            val ramp = start + (end - start) * (i + 1) / samples
            val g = min(ramp, limit)
            val out = (sampleAt(pcm, i) * g).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i * BYTES] = out.toByte()
            pcm[i * BYTES + 1] = (out shr BYTE_BITS).toByte()
        }
        applied = end
    }

    // A frame well over the noise floor steers the gain; anything else leaves it be.
    private fun track(rmsDb: Double) {
        if (!floor.take(rmsDb, SPEECH_OVER_FLOOR_DB, MIN_SPEECH_DB)) return
        speechDb = max(speechDb ?: rmsDb, rmsDb)
        val wanted = (TARGET_DB - rmsDb).coerceIn(0.0, MAX_GAIN_DB)
        gainDb =
            if (wanted < gainDb) {
                wanted
            } else {
                min(wanted, gainDb + RISE_DB_PER_FRAME)
            }
    }

    private fun sampleAt(
        pcm: ByteArray,
        i: Int,
    ): Int = ((pcm[i * BYTES].toInt() and BYTE) or (pcm[i * BYTES + 1].toInt() shl BYTE_BITS)).toShort().toInt()

    companion object {
        /** Where speech is brought to, in dBFS RMS: peaks then sit a few dB under full scale. */
        const val TARGET_DB = -18.0

        /** The most it adds: a quiet voice at arm's length on a flat path. */
        const val MAX_GAIN_DB = 24.0

        /** Where a new microphone starts: about what a flat path's speech needs. */
        const val INITIAL_GAIN_DB = 12.0

        /** No frame's peak goes over this, in dBFS. */
        const val CEILING_DB = -1.0

        /** At 50 frames a second, 5 dB a second of speech. */
        const val RISE_DB_PER_FRAME = 0.1

        /** Quieter than this is never speech, in dBFS RMS. */
        const val MIN_SPEECH_DB = -55.0

        private const val SPEECH_OVER_FLOOR_DB = 12.0
        private const val FULL_SCALE = 32_768.0
        private const val BYTES = 2
        private const val BYTE = 0xFF
        private const val BYTE_BITS = 8
        private const val DB_PER_DECADE = 20.0

        private fun dbToGain(db: Double): Double = 10.0.pow(db / DB_PER_DECADE)
    }
}
