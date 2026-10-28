package app.getknit.knit.call

import android.media.MediaRecorder

/**
 * Where a walkie burst is recorded from (docs/VOICE_CALLS.md C-3.11-5, C-3.13-7; ADR 2026-10.79yb's thirteenth
 * amendment): the sources a burst tries in order, whether Knit adds the platform's noise suppressor, and when a burst
 * says the phone's call path hands only zeros. Pure.
 *
 * The phone's own microphone follows the live call's [CaptureProfile] (ADR 2026-10.kw29): on [CaptureProfile.STANDARD]
 * it records from `VOICE_COMMUNICATION`, the phone's call-tuned path with its own suppression and gain; on
 * [CaptureProfile.FALLBACK] (a generic image, or a phone where a call captured only zeros) from `VOICE_RECOGNITION`,
 * which has no processing of its own. A headset records from `VOICE_COMMUNICATION` either way, the one source sure to
 * reach its microphone.
 */
object TalkCapture {
    /** A run of exact zeros this long on the call path is a dead route, not a quiet room: a real microphone's floor is louder. */
    const val DEAD_AFTER_MS = 1_000L

    /**
     * The sources a burst tries, the first that records winning. [first] is the debug bridge's source A/B, tried ahead of
     * the phone's own microphone's list; a headset ignores it.
     */
    fun sources(
        headset: Boolean,
        profile: CaptureProfile,
        first: Int? = null,
    ): List<Int> {
        val usual =
            when {
                headset -> listOf(VOICE_COMMUNICATION, MIC)
                profile == CaptureProfile.STANDARD -> listOf(VOICE_COMMUNICATION, VOICE_RECOGNITION, MIC)
                else -> listOf(VOICE_RECOGNITION, MIC)
            }
        return if (first == null || headset) usual else (listOf(first) + usual).distinct()
    }

    /**
     * Whether Knit attaches the platform's noise suppressor to a burst recorded from [source]: only where the source runs
     * none of its own. `VOICE_COMMUNICATION` already carries the phone's call processing, and a second pass over it
     * costs the transcript.
     */
    fun suppress(source: Int): Boolean = source != VOICE_COMMUNICATION

    /**
     * Whether a finished burst says the phone's call path is dead (C-3.13-13's rule, a burst's length): the phone's own
     * microphone on `VOICE_COMMUNICATION`, at least [DEAD_AFTER_MS] of audio, and not one sample above zero. The caller
     * excuses a muted or system-silenced microphone, as a live call's watch does.
     */
    fun deadMicrophone(
        source: Int?,
        headset: Boolean,
        samples: Long,
        peak: Int,
        sampleRate: Int = WalkiePolicy.SAMPLE_RATE,
    ): Boolean = source == VOICE_COMMUNICATION && !headset && peak == 0 && samples * MS_PER_S >= DEAD_AFTER_MS * sampleRate

    private const val VOICE_COMMUNICATION = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private const val VOICE_RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private const val MIC = MediaRecorder.AudioSource.MIC
    private const val MS_PER_S = 1_000L
}
