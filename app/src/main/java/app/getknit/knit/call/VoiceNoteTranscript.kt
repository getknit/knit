package app.getknit.knit.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A chat voice note written out as text on the person's tap (ADR 2026-10.am8d), by the walkie's speech model. The phone
 * that shows the text derives it from the bytes it holds, the sender's or the receiver's, exactly as it derives the
 * waveform: nothing is on the wire, and the text is kept on the message row so a long note is decoded once.
 */
object VoiceNotePolicy {
    /**
     * A note this long or shorter is one whole-line stream; a longer one is a continuous stream split every
     * [CaptionPolicy.MAX_RUN_MS] ([step]). Whole reads better (the noisy clip in ADR 2026-10.nz3g read 0 % word error
     * whole and 16.7 % split), but a whole line is cut at 30 s and loses what follows, so the margin below that is
     * deliberate.
     */
    const val WHOLE_MAX_MS = 25_000L

    /** How much audio goes to the stream at a time. */
    const val SLICE_MS = 500L

    /**
     * How much audio comes between looks ([PassGate]). Coarser than the walkie's: a note is fed faster than it plays,
     * and a whole-line look decodes the line again, so a look per half second of audio would cost far more than it shows.
     */
    const val LOOK_MS = 2_000L

    /** Whether a note of [audioMs] is a continuous stream rather than one whole line. */
    fun continuous(audioMs: Long): Boolean = audioMs > WHOLE_MAX_MS

    /**
     * A long note's run after a look: finished only at the live captions' run bound, never at a pause, and dropped when
     * it has heard nothing for [CaptionPolicy.QUIET_RUN_MS]. A pause is where a call's captions settle a line, but a
     * note is looked at every [LOOK_MS] of audio, so by the look that finds the pause the next sentence has begun in
     * the run, and finishing it there loses that sentence's first words (on the P9, 2026-10-21, "Which way to" and
     * "Look for" from a 75 s note that one unsplit stream heard whole).
     */
    fun step(
        heard: Heard,
        runMs: Long,
    ): RunStep =
        when {
            heard.text.isBlank() && runMs >= CaptionPolicy.QUIET_RUN_MS -> RunStep.DROP
            runMs >= CaptionPolicy.MAX_RUN_MS -> RunStep.SETTLE
            else -> RunStep.KEEP
        }
}

/** Where one note's transcription stands, beside the row's own stored text. */
sealed interface NoteStt {
    /** Queued or being transcribed: [heard] is the words so far. */
    data class Running(
        val heard: Heard = Heard(""),
    ) : NoteStt

    /** Finished and saved; the row carries [text] once the database says so. */
    data class Done(
        val text: String,
    ) : NoteStt

    /** A call holds the model: not run, or stopped, with nothing saved. */
    data object Busy : NoteStt

    /** The model would not run, or the bytes would not decode. */
    data object Failed : NoteStt
}

/**
 * Transcribes voice notes one at a time, in the order they were asked for (ADR 2026-10.am8d). [pcm] reads a note's
 * bytes and decodes them to the model's 16 kHz mono ([app.getknit.knit.data.VoiceAudio.speechPcm]); [save] writes the
 * text on every row naming the note. A note at or under [VoiceNotePolicy.WHOLE_MAX_MS] is one whole-line stream; a
 * longer one is continuous, in runs of up to a minute ([VoiceNotePolicy.step]), each run's words joined to the last.
 *
 * A call comes first: no note starts while [callActive] says one is up, and one running stops between slices once a
 * call begins, closing its stream and saving nothing ([NoteStt.Busy]); the model is released when the queue drains only
 * while no call is up, since a call's own streams may be open on it.
 *
 * The text is held here only until the row has it, and never logged. Thread-safe.
 */
class VoiceNoteTranscriber(
    private val speech: SpeechToText,
    private val pcm: suspend (hash: String, key: String?) -> FloatArray?,
    private val save: suspend (hash: String, text: String) -> Unit,
    private val callActive: () -> Boolean,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<Map<String, NoteStt>>(emptyMap())

    /** Each note asked for in this process, by its attachment hash. */
    val state: StateFlow<Map<String, NoteStt>> = _state.asStateFlow()

    private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val serial = Mutex()
    private val lock = Any()

    // Under [lock]: how many notes are queued or running.
    private var pending = 0

    /**
     * Queues the note stored under [hash] (opened with [key] when it is sealed). False when it is already queued or
     * running, or a call is up ([NoteStt.Busy]).
     */
    fun transcribe(
        hash: String,
        key: String?,
    ): Boolean {
        if (callActive()) {
            _state.update { it + (hash to NoteStt.Busy) }
            return false
        }
        synchronized(lock) {
            if (_state.value[hash] is NoteStt.Running) return false
            _state.update { it + (hash to NoteStt.Running()) }
            pending++
        }
        workScope.launch(dispatcher) {
            serial.withLock {
                val outcome =
                    runCatching { run(hash, key) }
                        .onFailure { log("voice note ${hash.take(HASH_LOG)}: ${it.javaClass.simpleName}") }
                        .getOrDefault(NoteStt.Failed)
                if (outcome is NoteStt.Done) {
                    runCatching { save(hash, outcome.text) }
                        .onFailure { log("voice note ${hash.take(HASH_LOG)}: not saved, ${it.javaClass.simpleName}") }
                }
                _state.update { it + (hash to outcome) }
                val drained = synchronized(lock) { --pending == 0 }
                if (drained && !callActive()) speech.release()
            }
        }
        return true
    }

    // Under [serial]: the note through the model, a slice at a time, with a look on the gate's cadence.
    private suspend fun run(
        hash: String,
        key: String?,
    ): NoteStt {
        if (callActive()) return NoteStt.Busy
        val audio = pcm(hash, key) ?: return NoteStt.Failed
        val pass = Pass(hash, audio)
        try {
            var offset = 0
            while (offset < audio.size) {
                val end = minOf(audio.size, offset + SLICE_SAMPLES)
                pass.slice(audio.copyOfRange(offset, end))?.let { return it }
                offset = end
            }
            return pass.finish()
        } finally {
            pass.close()
        }
    }

    /** One note on its way through the model: its open run's stream, and the words its settled runs have said. */
    private inner class Pass(
        private val hash: String,
        audio: FloatArray,
    ) {
        private val audioMs = audio.size * MS_PER_S / SttPolicy.SAMPLE_RATE
        private val continuous = VoiceNotePolicy.continuous(audioMs)
        private val began = clock()

        // The open run's stream, opened as its first audio goes in, so a run that settles at the note's last sample
        // leaves none behind.
        private var stream: SpeechStream? = null
        private var settled = ""
        private var gate = PassGate(VoiceNotePolicy.LOOK_MS)
        private var runMs = 0.0
        private var looks = 0

        /** [samples] to the open run, and a look when one is due: null to go on, else how the note ended. */
        suspend fun slice(samples: FloatArray): NoteStt? {
            if (callActive()) return NoteStt.Busy
            val open = stream ?: speech.open(continuous)?.also { stream = it } ?: return NoteStt.Failed
            if (!open.add(samples)) return NoteStt.Failed
            val ms = samples.size * MS_PER_S.toDouble() / SttPolicy.SAMPLE_RATE
            runMs += ms
            if (!gate.fed(ms)) return null
            val lookAt = clock()
            val heard = open.look() ?: return NoteStt.Failed
            gate.took(clock() - lookAt)
            looks++
            _state.update { it + (hash to NoteStt.Running(Heard(joined(settled, heard.settled), heard.live))) }
            return if (continuous) step(open, heard) else null
        }

        // A long note's run after a look ([VoiceNotePolicy.step]): kept, or settled (its words kept) or dropped, a fresh run
        // taking what follows.
        private suspend fun step(
            open: SpeechStream,
            heard: Heard,
        ): NoteStt? {
            when (VoiceNotePolicy.step(heard, runMs.toLong())) {
                RunStep.KEEP -> return null
                RunStep.SETTLE -> settled = joined(settled, (open.finish() ?: return NoteStt.Failed).text)
                RunStep.DROP -> open.close()
            }
            stream = null
            gate = PassGate(VoiceNotePolicy.LOOK_MS)
            runMs = 0.0
            return null
        }

        /** The note's end: the open run's last look, joined to what the settled runs said. */
        suspend fun finish(): NoteStt {
            val last = stream?.let { it.finish() ?: return NoteStt.Failed }
            val text = joined(settled, last?.text.orEmpty())
            // Timings only: the text itself is never logged.
            log(
                "voice note ${hash.take(HASH_LOG)}: $audioMs ms of audio, ${if (continuous) "split" else "whole"}, " +
                    "$looks look(s), ${clock() - began} ms, ${text.length} chars",
            )
            return NoteStt.Done(text)
        }

        /** Lets go of a stream a call or a failure left open; a no-op once finished. */
        suspend fun close() {
            stream?.close()
        }
    }

    private fun joined(
        before: String,
        after: String,
    ): String = listOf(before, after).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val MS_PER_S = 1_000L
        const val SLICE_SAMPLES = (VoiceNotePolicy.SLICE_MS * SttPolicy.SAMPLE_RATE / MS_PER_S).toInt()
        const val HASH_LOG = 8
    }
}

/**
 * What a chat needs from speech to offer a voice note's text (ADR 2026-10.am8d): the Calls screen's switch, the shared
 * model and its download, and the notes asked for. Built only where speech is (`STT_WIRED`); a chat without it gets
 * null and draws no control.
 */
interface VoiceNoteSpeech {
    /** The Calls screen's "Transcripts and captions" switch. */
    val enabled: Flow<Boolean>

    /** Where the one speech model stands on this phone. */
    val model: StateFlow<SttModelState>

    /** Each note asked for in this process ([VoiceNoteTranscriber.state]). */
    val notes: StateFlow<Map<String, NoteStt>>

    /** What the model's download costs, for the offer to name. */
    val modelBytes: Long

    /** Whether a download now would ride a metered route. */
    fun metered(): Boolean

    /** Queues the note under [hash] ([VoiceNoteTranscriber.transcribe]). */
    fun transcribe(
        hash: String,
        key: String?,
    ): Boolean

    /** Starts the model's download, the same job the Calls screen starts; false when one could not begin. */
    fun download(): Boolean
}
