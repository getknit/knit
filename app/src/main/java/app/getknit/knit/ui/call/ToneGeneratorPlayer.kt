package app.getknit.knit.ui.call

import android.media.AudioManager
import android.media.ToneGenerator
import app.getknit.knit.call.CallTone
import app.getknit.knit.call.TonePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The caller's tones (C-3.13-14) from the platform's [ToneGenerator] on the voice-call stream, so they follow the route
 * Telecom picked. The supervisory tones take the cadence of the network's country (a US ringback is not a UK one). A
 * generator is held only while a tone sounds: a one-shot tone releases it on [scope] once it has played.
 */
internal class ToneGeneratorPlayer(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : TonePlayer {
    private var generator: ToneGenerator? = null
    private var releaser: Job? = null

    @Synchronized
    override fun play(tone: CallTone) {
        stop()
        val (type, ms) =
            when (tone) {
                CallTone.RINGBACK -> ToneGenerator.TONE_SUP_RINGTONE to -1

                CallTone.BUSY -> ToneGenerator.TONE_SUP_BUSY to ONE_SHOT_MS

                CallTone.CONGESTION -> ToneGenerator.TONE_SUP_CONGESTION to ONE_SHOT_MS

                CallTone.ENDED -> ToneGenerator.TONE_PROP_PROMPT to ENDED_MS

                // Two short beeps, unlike the end's one: the speaker's burst stopped, the call did not.
                CallTone.TALK_CAPPED -> ToneGenerator.TONE_PROP_ACK to CAPPED_MS
            }
        // The generator's constructor throws when the audio server has no track to give.
        val g =
            runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME) }
                .onFailure { log("call tone $tone not played: $it") }
                .getOrNull() ?: return
        generator = g
        if (!g.startTone(type, ms)) log("call tone $tone refused")
        if (ms > 0) {
            releaser =
                scope.launch {
                    delay(ms + RELEASE_SLACK_MS)
                    release(g)
                }
        }
    }

    @Synchronized
    override fun stop() {
        releaser?.cancel()
        releaser = null
        generator?.let { release(it) }
    }

    @Synchronized
    private fun release(g: ToneGenerator) {
        if (generator !== g) return
        generator = null
        g.stopTone()
        g.release()
    }

    private companion object {
        const val VOLUME = 80
        const val ONE_SHOT_MS = 3_000
        const val ENDED_MS = 300
        const val CAPPED_MS = 400
        const val RELEASE_SLACK_MS = 100L
    }
}
